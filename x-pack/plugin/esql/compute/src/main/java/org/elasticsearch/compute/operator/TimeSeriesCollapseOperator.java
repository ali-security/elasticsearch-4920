/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BlockUtils;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Releasables;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stream-collapses tsid-contiguous expanded rows into one multi-valued row per series.
 * <p>
 * Input rows are produced by {@link TimeSeriesAggregationOperator} with {@code collapsed=true}:
 * all rows for a given series are contiguous, and null-fill rows (missing steps) have NULL in
 * their aggregation value channels while the step (timestamp) channel is always populated.
 * This operator:
 * <ol>
 *   <li>Tracks the current series key via {@code keyChannels} (one or more key columns).</li>
 *   <li>Skips rows where any collapse channel is null (missing-step rows).</li>
 *   <li>Accumulates non-null values for {@code collapseChannels} (step + aggregated value).</li>
 *   <li>On series key change (or finish), emits one row with single-valued key columns and
 *       multi-valued collapse columns.</li>
 * </ol>
 */
public class TimeSeriesCollapseOperator implements Operator {

    /**
     * Estimated heap bytes per value stored in collapse lists:
     * object reference in ArrayList backing array + boxed primitive overhead.
     */
    private static final long BYTES_PER_COLLAPSE_VALUE = RamUsageEstimator.NUM_BYTES_OBJECT_REF + 24;

    public record Factory(int[] keyChannels, int[] collapseChannels, int timestampChannel) implements OperatorFactory {
        @Override
        public Operator get(DriverContext driverContext) {
            return new TimeSeriesCollapseOperator(keyChannels, collapseChannels, timestampChannel, driverContext);
        }

        @Override
        public String describe() {
            return "TimeSeriesCollapseOperator[keyChannels="
                + Arrays.toString(keyChannels)
                + ", collapseChannels="
                + Arrays.toString(collapseChannels)
                + ", timestampChannel="
                + timestampChannel
                + "]";
        }
    }

    private final int[] keyChannels;
    private final int[] collapseChannels;
    /** Used only for assertion: verifies timestamps are strictly ascending within each series. */
    private final int timestampChannel;
    private final int totalChannels;
    private final DriverContext driverContext;
    private final CircuitBreaker breaker;

    /** Element types per channel, lazily initialized from the first page. */
    private ElementType[] elementTypes;

    /** Key values for the current in-flight series (null if no series started yet). */
    private Object[] currentKey;

    /** Accumulated non-null collapse values per collapse-channel index. */
    private final List<Object>[] collapseValues;

    private final ArrayDeque<Page> outputQueue = new ArrayDeque<>();
    private boolean finished = false;

    /** Heap bytes currently tracked via the circuit breaker for accumulated collapse values. */
    private long trackedBytes;

    /** Last non-null timestamp seen for the current series; used to assert ascending order. */
    private long lastTimestamp = Long.MIN_VALUE;

    /**
     * Set of series keys already emitted; populated only when assertions are enabled.
     * Used to assert that each series key appears in exactly one contiguous run.
     */
    private Set<List<Object>> seenKeys;

    @SuppressWarnings("unchecked")
    public TimeSeriesCollapseOperator(int[] keyChannels, int[] collapseChannels, int timestampChannel, DriverContext driverContext) {
        this.keyChannels = keyChannels;
        this.collapseChannels = collapseChannels;
        this.timestampChannel = timestampChannel;
        this.totalChannels = keyChannels.length + collapseChannels.length;
        this.driverContext = driverContext;
        this.breaker = driverContext.breaker();
        this.collapseValues = new List[collapseChannels.length];
        for (int c = 0; c < collapseChannels.length; c++) {
            collapseValues[c] = new ArrayList<>();
        }
        assert (seenKeys = new HashSet<>()) != null; // initialized only when assertions are enabled
    }

    @Override
    public boolean canProduceMoreDataWithoutExtraInput() {
        return outputQueue.isEmpty() == false;
    }

    @Override
    public boolean needsInput() {
        return finished == false;
    }

    @Override
    public void addInput(Page page) {
        if (elementTypes == null) {
            elementTypes = new ElementType[totalChannels];
            for (int i = 0; i < totalChannels; i++) {
                elementTypes[i] = page.getBlock(i).elementType();
            }
        }

        int positionCount = page.getPositionCount();
        try {
            for (int p = 0; p < positionCount; p++) {
                Object[] rowKey = extractKey(page, p);

                if (currentKey == null || keysEqual(currentKey, rowKey) == false) {
                    if (currentKey != null) {
                        outputQueue.add(buildOutputPage());
                        resetCollapseValues();
                        lastTimestamp = Long.MIN_VALUE;
                        assert seenKeys.add(Arrays.asList(currentKey)) : "internal error: key already in seen set before completion";
                    }
                    assert seenKeys.contains(Arrays.asList(rowKey)) == false
                        : "series key revisited after its run completed: " + Arrays.toString(rowKey);
                    currentKey = rowKey;
                }

                // Skip rows where any collapse channel is null (missing-step null-fill rows)
                boolean hasData = true;
                for (int c = 0; c < collapseChannels.length; c++) {
                    if (page.getBlock(collapseChannels[c]).isNull(p)) {
                        hasData = false;
                        break;
                    }
                }

                if (hasData) {
                    long ts = (long) BlockUtils.toJavaObject(page.getBlock(timestampChannel), p);
                    assert ts > lastTimestamp
                        : "timestamps must be strictly ascending within a tsid, got " + ts + " after " + lastTimestamp;
                    lastTimestamp = ts;
                    for (int c = 0; c < collapseChannels.length; c++) {
                        Object val = BlockUtils.toJavaObject(page.getBlock(collapseChannels[c]), p);
                        trackValue(val);
                        collapseValues[c].add(val);
                    }
                }
            }
        } finally {
            page.releaseBlocks();
        }
    }

    @Override
    public Page getOutput() {
        return outputQueue.poll();
    }

    @Override
    public void finish() {
        finished = true;
        if (currentKey != null) {
            outputQueue.add(buildOutputPage());
            resetCollapseValues();
            currentKey = null;
        }
    }

    @Override
    public boolean isFinished() {
        return finished && outputQueue.isEmpty();
    }

    @Override
    public void close() {
        for (Page p : outputQueue) {
            p.releaseBlocks();
        }
        outputQueue.clear();
        releaseTrackedBytes();
    }

    private void trackValue(Object value) {
        long bytes = BYTES_PER_COLLAPSE_VALUE;
        if (value instanceof BytesRef br) {
            bytes += br.length;
        }
        breaker.addEstimateBytesAndMaybeBreak(bytes, "ts_collapse");
        trackedBytes += bytes;
    }

    private void releaseTrackedBytes() {
        breaker.addWithoutBreaking(-trackedBytes);
        trackedBytes = 0;
    }

    private Object[] extractKey(Page page, int position) {
        Object[] key = new Object[keyChannels.length];
        for (int i = 0; i < keyChannels.length; i++) {
            key[i] = BlockUtils.toJavaObject(page.getBlock(keyChannels[i]), position);
        }
        return key;
    }

    private static boolean keysEqual(Object[] a, Object[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Objects.equals(a[i], b[i]) == false) {
                return false;
            }
        }
        return true;
    }

    private void resetCollapseValues() {
        for (int c = 0; c < collapseChannels.length; c++) {
            collapseValues[c].clear();
        }
        releaseTrackedBytes();
    }

    @Override
    public String toString() {
        return "TimeSeriesCollapseOperator[keyChannels="
            + Arrays.toString(keyChannels)
            + ", collapseChannels="
            + Arrays.toString(collapseChannels)
            + "]";
    }

    private Page buildOutputPage() {
        BlockFactory blockFactory = driverContext.blockFactory();
        Block[] blocks = new Block[totalChannels];
        boolean success = false;
        try {
            // Build key blocks (single-valued)
            for (int i = 0; i < keyChannels.length; i++) {
                int ch = keyChannels[i];
                try (Block.Builder builder = elementTypes[ch].newBlockBuilder(1, blockFactory)) {
                    BlockUtils.appendValue(builder, currentKey[i], elementTypes[ch]);
                    blocks[ch] = builder.build();
                }
            }
            // Build collapse blocks (multi-valued)
            for (int c = 0; c < collapseChannels.length; c++) {
                int ch = collapseChannels[c];
                List<Object> vals = collapseValues[c];
                try (Block.Builder builder = elementTypes[ch].newBlockBuilder(1, blockFactory)) {
                    if (vals.isEmpty()) {
                        builder.appendNull();
                    } else if (vals.size() == 1) {
                        BlockUtils.appendValue(builder, vals.get(0), elementTypes[ch]);
                    } else {
                        builder.beginPositionEntry();
                        for (Object val : vals) {
                            BlockUtils.appendValue(builder, val, elementTypes[ch]);
                        }
                        builder.endPositionEntry();
                    }
                    blocks[ch] = builder.build();
                }
            }
            success = true;
            return new Page(blocks);
        } finally {
            if (success == false) {
                Releasables.close(blocks);
            }
        }
    }

}
