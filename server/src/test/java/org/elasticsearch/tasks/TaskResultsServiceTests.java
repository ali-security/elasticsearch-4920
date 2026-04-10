/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public License
 * v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.tasks;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.UnavailableShardsException;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.RemoteTransportException;

public class TaskResultsServiceTests extends ESTestCase {

    public void testIsRetryableTaskStoreFailureWithEsRejectedExecutionException() {
        assertTrue(TaskResultsService.isRetryableTaskStoreFailure(new EsRejectedExecutionException()));
    }

    public void testIsRetryableTaskStoreFailureWithUnavailableShardsException() {
        assertTrue(
            TaskResultsService.isRetryableTaskStoreFailure(new UnavailableShardsException(".tasks", 0, "primary not active Timeout: [1m]"))
        );
    }

    public void testIsRetryableTaskStoreFailureWithWrappedUnavailableShardsException() {
        UnavailableShardsException root = new UnavailableShardsException(".tasks", 0, "primary not active");
        assertTrue(TaskResultsService.isRetryableTaskStoreFailure(new ElasticsearchException("wrapper", root)));
    }

    public void testIsRetryableTaskStoreFailureWithRemoteTransportException() {
        UnavailableShardsException root = new UnavailableShardsException(".tasks", 0, "primary not active");
        assertTrue(
            TaskResultsService.isRetryableTaskStoreFailure(new RemoteTransportException("remote", new ElasticsearchException("step", root)))
        );
    }

    public void testIsRetryableTaskStoreFailureWithNonRetryableExceptions() {
        assertFalse(TaskResultsService.isRetryableTaskStoreFailure(new IllegalStateException("mapping failure")));
        assertFalse(TaskResultsService.isRetryableTaskStoreFailure(new ElasticsearchException("other")));
    }
}
