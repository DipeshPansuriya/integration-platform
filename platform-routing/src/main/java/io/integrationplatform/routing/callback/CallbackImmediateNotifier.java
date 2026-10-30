package io.integrationplatform.routing.callback;

import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fires an asynchronous callback dispatch for IMMEDIATE-mode routes right after the response is
 * captured. The dispatch runs on a bounded thread pool so the delivery pipeline thread returns
 * immediately. The tenant is rebound on the pool thread (the delivery thread's binding is
 * thread-confined and would not carry over).
 */
public class CallbackImmediateNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(CallbackImmediateNotifier.class);

    private final CallbackDispatcher dispatcher;
    private final WorkerTenantBinding binding;
    private final ExecutorService executor;

    public CallbackImmediateNotifier(CallbackDispatcher dispatcher, WorkerTenantBinding binding,
            ExecutorService executor) {
        this.dispatcher = dispatcher;
        this.binding = binding;
        this.executor = executor;
    }

    public void fire(UUID deliveryId, UUID tenantId) {
        executor.submit(() -> {
            try {
                binding.runAs(tenantId, () -> dispatcher.dispatch("IMMEDIATE", 10));
            } catch (Exception e) {
                LOG.atWarn().addKeyValue("event", "callback.immediate.failed")
                        .addKeyValue("deliveryId", deliveryId)
                        .addKeyValue("tenantId", tenantId)
                        .setCause(e)
                        .log("async IMMEDIATE callback dispatch failed for delivery {}", deliveryId);
            }
        });
    }
}
