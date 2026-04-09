/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Physical plan node corresponding to {@link org.elasticsearch.xpack.esql.plan.logical.TimeSeriesCollapse}.
 * Produces one multi-valued row per series from tsid-contiguous expanded input.
 */
public class TimeSeriesCollapseExec extends UnaryExec {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        PhysicalPlan.class,
        "TimeSeriesCollapseExec",
        TimeSeriesCollapseExec::new
    );

    private final List<Attribute> collapseAttributes;

    public TimeSeriesCollapseExec(Source source, PhysicalPlan child, List<Attribute> collapseAttributes) {
        super(source, child);
        this.collapseAttributes = collapseAttributes;
    }

    private TimeSeriesCollapseExec(StreamInput in) throws IOException {
        this(
            Source.readFrom((PlanStreamInput) in),
            in.readNamedWriteable(PhysicalPlan.class),
            in.readNamedWriteableCollectionAsList(Attribute.class)
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        Source.EMPTY.writeTo(out);
        out.writeNamedWriteable(child());
        out.writeNamedWriteableCollection(collapseAttributes);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    public List<Attribute> collapseAttributes() {
        return collapseAttributes;
    }

    @Override
    public List<Attribute> output() {
        return child().output();
    }

    @Override
    protected NodeInfo<TimeSeriesCollapseExec> info() {
        return NodeInfo.create(this, TimeSeriesCollapseExec::new, child(), collapseAttributes);
    }

    @Override
    public TimeSeriesCollapseExec replaceChild(PhysicalPlan newChild) {
        return new TimeSeriesCollapseExec(source(), newChild, collapseAttributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(child(), collapseAttributes);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        TimeSeriesCollapseExec other = (TimeSeriesCollapseExec) obj;
        return Objects.equals(child(), other.child()) && Objects.equals(collapseAttributes, other.collapseAttributes);
    }
}
