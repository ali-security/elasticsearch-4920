/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.common.blobstore.support;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.DeleteResult;
import org.elasticsearch.common.blobstore.OperationPurpose;
import org.elasticsearch.common.blobstore.OptionalBytesReference;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.core.CheckedConsumer;
import org.elasticsearch.core.CheckedRunnable;
import org.elasticsearch.core.CheckedSupplier;
import org.elasticsearch.core.TimeValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * Cloud storage services use an eventual consistency model for identity, access control, and metadata.
 * As a result, they may occasionally return transient 403 Forbidden errors even when permissions are correctly configured.
 * This class wraps the blobContainer to provide a dedicated retry mechanism initially for handling these 403 errors.
 *
 */
public abstract class TenaciousRetryBlobContainer extends FilterBlobContainer {

    private final int maxRetries;
    private final TimeValue delayIncrement;

    public TenaciousRetryBlobContainer(BlobContainer delegate, int maxRetries, TimeValue delayIncrement) {
        super(delegate);
        this.maxRetries = maxRetries;
        this.delayIncrement = delayIncrement;
    }

    protected abstract boolean isExceptionRetryable(Exception e);

    @Override
    public BlobPath path() {
        return execute(super::path);
    }

    @Override
    public boolean blobExists(OperationPurpose purpose, String blobName) throws IOException {
        return execute(() -> super.blobExists(purpose, blobName));
    }

    @Override
    public InputStream readBlob(OperationPurpose purpose, String blobName) throws IOException {
        return execute(() -> super.readBlob(purpose, blobName));
    }

    @Override
    public InputStream readBlob(OperationPurpose purpose, String blobName, long position, long length) throws IOException {
        return execute(() -> super.readBlob(purpose, blobName, position, length));
    }

    @Override
    public long readBlobPreferredLength() {
        return execute(super::readBlobPreferredLength);
    }

    @Override
    public void writeBlob(OperationPurpose purpose, String blobName, InputStream inputStream, long blobSize, boolean failIfAlreadyExists)
        throws IOException {
        executeRunnable(() -> super.writeBlob(purpose, blobName, inputStream, blobSize, failIfAlreadyExists));
    }

    @Override
    public void writeMetadataBlob(
        OperationPurpose purpose,
        String blobName,
        boolean failIfAlreadyExists,
        boolean atomic,
        CheckedConsumer<OutputStream, IOException> writer
    ) throws IOException {
        executeRunnable(() -> super.writeMetadataBlob(purpose, blobName, failIfAlreadyExists, atomic, writer));
    }

    @Override
    public boolean supportsConcurrentMultipartUploads() {
        return execute(super::supportsConcurrentMultipartUploads);
    }

    @Override
    public void writeBlobAtomic(
        OperationPurpose purpose,
        String blobName,
        long blobSize,
        BlobMultiPartInputStreamProvider provider,
        boolean failIfAlreadyExists
    ) throws IOException {
        executeRunnable(() -> super.writeBlobAtomic(purpose, blobName, blobSize, provider, failIfAlreadyExists));
    }

    @Override
    public void writeBlobAtomic(
        OperationPurpose purpose,
        String blobName,
        InputStream inputStream,
        long blobSize,
        boolean failIfAlreadyExists
    ) throws IOException {
        executeRunnable(() -> super.writeBlobAtomic(purpose, blobName, inputStream, blobSize, failIfAlreadyExists));
    }

    @Override
    public void writeBlobAtomic(OperationPurpose purpose, String blobName, BytesReference bytes, boolean failIfAlreadyExists)
        throws IOException {
        executeRunnable(() -> super.writeBlobAtomic(purpose, blobName, bytes, failIfAlreadyExists));
    }

    @Override
    public void writeBlob(OperationPurpose purpose, String blobName, BytesReference bytes, boolean failIfAlreadyExists) throws IOException {
        executeRunnable(() -> super.writeBlobAtomic(purpose, blobName, bytes, failIfAlreadyExists));
    }

    @Override
    public void copyBlob(OperationPurpose purpose, BlobContainer sourceBlobContainer, String sourceBlobName, String blobName, long blobSize)
        throws IOException {
        executeRunnable(() -> super.copyBlob(purpose, sourceBlobContainer, sourceBlobName, blobName, blobSize));
    }

    @Override
    public DeleteResult delete(OperationPurpose purpose) throws IOException {
        return execute(() -> super.delete(purpose));
    }

    @Override
    public void deleteBlobsIgnoringIfNotExists(OperationPurpose purpose, Iterator<String> blobNames) throws IOException {
        executeRunnable(() -> super.deleteBlobsIgnoringIfNotExists(purpose, blobNames));
    }

    @Override
    public Map<String, BlobMetadata> listBlobs(OperationPurpose purpose) throws IOException {
        return execute(() -> super.listBlobs(purpose));
    }

    @Override
    public Map<String, BlobMetadata> listBlobsByPrefix(OperationPurpose purpose, String blobNamePrefix) throws IOException {
        return execute(() -> super.listBlobsByPrefix(purpose, blobNamePrefix));
    }

    @Override
    public void compareAndExchangeRegister(
        OperationPurpose purpose,
        String key,
        BytesReference expected,
        BytesReference updated,
        ActionListener<OptionalBytesReference> listener
    ) {
        executeRunnable(() -> super.compareAndExchangeRegister(purpose, key, expected, updated, listener));
    }

    @Override
    public void compareAndSetRegister(
        OperationPurpose purpose,
        String key,
        BytesReference expected,
        BytesReference updated,
        ActionListener<Boolean> listener
    ) {
        executeRunnable(() -> super.compareAndSetRegister(purpose, key, expected, updated, listener));
    }

    @Override
    public void getRegister(OperationPurpose purpose, String key, ActionListener<OptionalBytesReference> listener) {
        executeRunnable(() -> super.getRegister(purpose, key, listener));
    }

    private <E extends Exception> void executeRunnable(CheckedRunnable<E> runnable) throws E {
        execute(() -> {
            runnable.run();
            return null;
        });
    }

    private <T, E extends Exception> T execute(CheckedSupplier<T, E> operation) throws E {
        int attempts = 0;
        while (true) {
            try {
                return operation.get();
            } catch (Exception e) {
                if (isExceptionRetryable(e) && attempts < maxRetries) {
                    attempts++;
                    try {
                        Thread.sleep(delayIncrement.millis() * attempts);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    throw e;
                }
            }
        }
    }
}
