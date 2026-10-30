package io.integrationplatform.routing.callback;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Sends captured target responses back to the route's callback URL. Used by both the IMMEDIATE
 * Kafka consumer and the SCHEDULED db-scheduler job. Each call claims a batch of PENDING/RETRY
 * rows ({@code FOR UPDATE SKIP LOCKED}), POSTs the body to the callback URL, and marks the row
 * SENT or RETRY/DEAD.
 */
public class CallbackDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackDispatcher.class);
    private static final Duration CALLBACK_TIMEOUT = Duration.ofSeconds(30);

    private final JdbcCallbackStore store;
    private final TransactionOperations tx;
    private final HttpClient httpClient;

    public CallbackDispatcher(JdbcCallbackStore store, TransactionOperations tx) {
        this(store, tx, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    CallbackDispatcher(JdbcCallbackStore store, TransactionOperations tx, HttpClient httpClient) {
        this.store = store;
        this.tx = tx;
        this.httpClient = httpClient;
    }

    public int dispatch(String mode, int batchSize) {
        List<CallbackRecord> batch = tx.execute(status -> store.claimPending(mode, batchSize));
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (CallbackRecord record : batch) {
            boolean ok = sendCallback(record);
            tx.executeWithoutResult(status -> {
                if (ok) {
                    store.markSent(record.responseId());
                } else {
                    store.markFailed(record.responseId());
                }
            });
            if (ok) sent++;
        }
        return sent;
    }

    private boolean sendCallback(CallbackRecord record) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(record.callbackUrl()))
                    .timeout(CALLBACK_TIMEOUT)
                    .header("X-Delivery-Id", record.deliveryId().toString())
                    .header("X-Original-Status", String.valueOf(record.status()));

            byte[] body = record.body() != null ? record.body() : new byte[0];
            String contentType = record.contentType() != null ? record.contentType() : "application/octet-stream";
            builder.header("Content-Type", contentType);
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));

            HttpResponse<Void> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOG.atInfo().addKeyValue("event", "callback.sent")
                        .addKeyValue("deliveryId", record.deliveryId())
                        .addKeyValue("callbackUrl", record.callbackUrl())
                        .addKeyValue("responseStatus", response.statusCode())
                        .log("callback sent for delivery {}", record.deliveryId());
                return true;
            } else {
                LOG.atWarn().addKeyValue("event", "callback.rejected")
                        .addKeyValue("deliveryId", record.deliveryId())
                        .addKeyValue("callbackUrl", record.callbackUrl())
                        .addKeyValue("httpStatus", response.statusCode())
                        .log("callback rejected with HTTP {} for delivery {}", response.statusCode(),
                                record.deliveryId());
                return false;
            }
        } catch (Exception e) {
            LOG.atWarn().addKeyValue("event", "callback.failed")
                    .addKeyValue("deliveryId", record.deliveryId())
                    .addKeyValue("callbackUrl", record.callbackUrl())
                    .setCause(e)
                    .log("callback failed for delivery {}", record.deliveryId());
            return false;
        }
    }
}
