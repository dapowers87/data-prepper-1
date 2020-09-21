package com.amazon.situp.plugins.sink.elasticsearch;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class BulkRetryStrategy {
    private static final Set<Integer> NON_RETRY_STATUS = new HashSet<>(
            Arrays.asList(
                    RestStatus.BAD_REQUEST.getStatus(),
                    RestStatus.NOT_FOUND.getStatus(),
                    RestStatus.CONFLICT.getStatus()
            ));

    private final RequestFunction<BulkRequest, BulkResponse> requestFunction;
    private final BiConsumer<DocWriteRequest<?>, Throwable> logFailure;
    private final Supplier<BulkRequest> bulkRequestSupplier;

    public BulkRetryStrategy(final RequestFunction<BulkRequest, BulkResponse> requestFunction,
                             final BiConsumer<DocWriteRequest<?>, Throwable> logFailure,
                             final Supplier<BulkRequest> bulkRequestSupplier) {
        this.requestFunction = requestFunction;
        this.logFailure = logFailure;
        this.bulkRequestSupplier = bulkRequestSupplier;
    }

    public void execute(final BulkRequest bulkRequest) throws InterruptedException {
        // Exponential backoff run forever
        // TODO: replace with custom backoff policy setting including maximum interval between retries
        final BackOffUtils backOffUtils = new BackOffUtils(
                BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(50), Integer.MAX_VALUE).iterator());
        handleRetry(bulkRequest, null, true, backOffUtils);
    }

    public boolean canRetry(final BulkResponse response) {
        for (final BulkItemResponse bulkItemResponse : response) {
            if (bulkItemResponse.isFailed() && !NON_RETRY_STATUS.contains(bulkItemResponse.status().getStatus())) {
                return true;
            }
        }
        return false;
    }

    public boolean canRetry(final Exception e) {
        return (e instanceof IOException ||
                (e instanceof ElasticsearchException &&
                        !NON_RETRY_STATUS.contains(((ElasticsearchException) e).status().getStatus())));
    }

    private Tuple<BulkRequest, BulkResponse> retry(final BulkRequest request) throws Exception {
        final BulkResponse response = requestFunction.apply(request);
        return Tuple.tuple(request, response);
    }

    private void handleRetry(
            final BulkRequest request, final BulkResponse response, final boolean initialAttempt,
            final BackOffUtils backOffUtils) throws InterruptedException {
        final BulkRequest bulkRequestForRetry = createBulkRequestForRetry(request, response);
        if (initialAttempt || backOffUtils.hasNext()) {
            if (!initialAttempt) {
                // Wait for backOff duration
                backOffUtils.next();
            }
            final Tuple<BulkRequest, BulkResponse> tuple;
            try {
                tuple = retry(bulkRequestForRetry);
            } catch (final Exception e) {
                if (canRetry(e) && backOffUtils.hasNext()) {
                    // FIXME: maximum recursion depth?
                    handleRetry(bulkRequestForRetry, null, false, backOffUtils);
                } else {
                    handleFailures(bulkRequestForRetry.requests(), e);
                }

                return;
            }
            if (tuple.v2().hasFailures()) {
                if (canRetry(tuple.v2()) && backOffUtils.hasNext()) {
                    // FIXME: maximum recursion depth?
                    handleRetry(tuple.v1(), tuple.v2(), false, backOffUtils);
                } else {
                    handleFailures(tuple.v1().requests(), tuple.v2().getItems());
                }
            }
        }
    }

    private BulkRequest createBulkRequestForRetry(final BulkRequest request, final BulkResponse response) {
        if (response == null) {
            // retry due to Exception
            return request;
        } else {
            final BulkRequest requestToReissue = bulkRequestSupplier.get();
            int index = 0;
            for (final BulkItemResponse bulkItemResponse : response.getItems()) {
                if (bulkItemResponse.isFailed()) {
                    if (!NON_RETRY_STATUS.contains(bulkItemResponse.status().getStatus())) {
                        requestToReissue.add(request.requests().get(index));
                    } else {
                        // log non-retryable failed request
                        logFailure.accept(request.requests().get(index), bulkItemResponse.getFailure().getCause());
                    }
                }
                index++;
            }
            return requestToReissue;
        }
    }

    private void handleFailures(final List<DocWriteRequest<?>> docWriteRequests, final BulkItemResponse[] itemResponses) {
        assert docWriteRequests.size() == itemResponses.length;
        for (int i = 0; i < itemResponses.length; i++) {
            final BulkItemResponse bulkItemResponse = itemResponses[i];
            final DocWriteRequest<?> docWriteRequest = docWriteRequests.get(i);
            if (bulkItemResponse.isFailed()) {
                final BulkItemResponse.Failure failure = bulkItemResponse.getFailure();
                logFailure.accept(docWriteRequest, failure.getCause());
            }
        }
    }

    private void handleFailures(final List<DocWriteRequest<?>> docWriteRequests, final Throwable failure) {
        for (final DocWriteRequest<?> docWriteRequest: docWriteRequests) {
            logFailure.accept(docWriteRequest, failure);
        }
    }
}
