package io.integrationplatform.routing.callback;

import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.WorkerTenantBinding;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Wires the IB08 dual-mode callback engine: the store, the dispatcher, and the IMMEDIATE notifier.
 * The SCHEDULED path is driven by the {@code CALLBACK_SEND} db-scheduler job in platform-app; the
 * IMMEDIATE path fires asynchronously from the response capture adapter.
 */
@Configuration(proxyBeanMethods = false)
public class CallbackConfiguration {

    @Bean
    JdbcCallbackStore jdbcCallbackStore(JdbcClient jdbcClient, TenantContext tenantContext) {
        return new JdbcCallbackStore(jdbcClient, tenantContext);
    }

    @Bean
    CallbackDispatcher callbackDispatcher(JdbcCallbackStore store, TransactionOperations tx) {
        return new CallbackDispatcher(store, tx);
    }

    @Bean
    CallbackImmediateNotifier callbackImmediateNotifier(CallbackDispatcher dispatcher,
            ObjectProvider<WorkerTenantBinding> binding) {
        WorkerTenantBinding workerBinding = binding.getIfAvailable();
        if (workerBinding == null) {
            return null;
        }
        ExecutorService executor = Executors.newFixedThreadPool(2,
                r -> { Thread t = new Thread(r, "callback-immediate"); t.setDaemon(true); return t; });
        return new CallbackImmediateNotifier(dispatcher, workerBinding, executor);
    }
}
