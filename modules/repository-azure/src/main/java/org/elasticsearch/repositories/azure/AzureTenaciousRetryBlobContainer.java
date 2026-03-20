/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.repositories.azure;

import com.azure.storage.blob.models.BlobStorageException;

import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.support.TenaciousRetryBlobContainer;
import org.elasticsearch.core.TimeValue;

import static com.azure.storage.blob.models.BlobErrorCode.INSUFFICIENT_ACCOUNT_PERMISSIONS;

public class AzureTenaciousRetryBlobContainer extends TenaciousRetryBlobContainer {
    private final int maxRetries;
    private final TimeValue delayIncrement;

    public AzureTenaciousRetryBlobContainer(BlobContainer delegate, int maxRetries, TimeValue delayIncrement) {
        super(delegate, maxRetries, delayIncrement);
        this.delayIncrement = delayIncrement;
        this.maxRetries = maxRetries;
    }

    @Override
    protected boolean isExceptionRetryable(Exception e) {
        return e instanceof BlobStorageException && ((BlobStorageException) e).getErrorCode() == INSUFFICIENT_ACCOUNT_PERMISSIONS;
    }

    @Override
    protected BlobContainer wrapChild(BlobContainer child) {
        return new AzureTenaciousRetryBlobContainer(child, maxRetries, delayIncrement);
    }
}
