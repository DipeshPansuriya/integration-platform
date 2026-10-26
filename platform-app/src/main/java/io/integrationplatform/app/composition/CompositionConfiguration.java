package io.integrationplatform.app.composition;

import io.integrationplatform.config.system.application.DbAccountWidthCheck;
import io.integrationplatform.ingest.port.StageTimings;
import io.integrationplatform.routing.adapter.api.ApiTargetBody;
import io.integrationplatform.routing.adapter.db.DbAccountWidth;
import io.integrationplatform.routing.adapter.db.DbRows;
import io.integrationplatform.routing.adapter.file.FileTargetContent;
import io.integrationplatform.routing.adapter.file.JdbcFileTargets;
import io.integrationplatform.routing.adapter.kafkatopic.KafkaTargetBody;
import io.integrationplatform.routing.adapter.mq.MqCsvRows;
import io.integrationplatform.routing.adapter.mq.MqTargetBody;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.transform.api.LookupSource;
import io.integrationplatform.transform.api.TransformPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The composition root's own part of the running pipeline (E11 T-049, row 278 T106): the transform -> API adapter
 * link (the {@link ApiTargetBody} the adapter configuration's placeholder named "replaced by the transform
 * integration") and, when background work runs, the application's one {@link TenantContext}.
 */
@Configuration(proxyBeanMethods = false)
class CompositionConfiguration {

    @Bean
    ApiTargetBody transformedApiBody(JdbcClient jdbcClient, ObjectProvider<TenantContext> tenantContexts,
            TransformPort transformPort, LookupSource lookupSource, ObjectProvider<StageTimings> timings) {
        TenantContext boundAtCall =
                () -> Optional.ofNullable(tenantContexts.getIfAvailable()).flatMap(TenantContext::currentTenantId);
        return new TransformedApiBody(jdbcClient, boundAtCall, transformPort, lookupSource,
                timings.getIfAvailable());
    }

    /** Row 327: the MQ message is the transformed API body's bytes - one transform lookup, inside the same tenant transaction. */
    @Bean
    MqTargetBody transformedMqBody(ApiTargetBody transformedApiBody) {
        return send -> transformedApiBody.bodyFor(send).map(ApiTargetBody.ApiWireBody::bytes);
    }

    /**
     * E18 finding (E15 F15 MQ-CSV): the CSV outbound's rows are the same transformed body - a JSON object is one row,
     * a JSON array of objects is several, anything else (or no body) is no rows, so the send is refused PERMANENT.
     */
    @Bean
    MqCsvRows transformedMqCsvRows(ApiTargetBody transformedApiBody) {
        JsonMapper json = JsonMapper.builder().build();
        return send -> transformedApiBody.bodyFor(send).flatMap(body -> rows(json, body.bytes()));
    }

    private static Optional<List<Map<String, ?>>> rows(JsonMapper json, byte[] bytes) {
        try {
            Object parsed = json.readValue(new String(bytes, StandardCharsets.UTF_8), Object.class);
            List<Map<String, ?>> rows = new java.util.ArrayList<>();
            if (parsed instanceof Map<?, ?> row) {
                rows.add(stringKeyed(row));
            } else if (parsed instanceof List<?> list && !list.isEmpty()) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> row)) {
                        return Optional.empty();
                    }
                    rows.add(stringKeyed(row));
                }
            }
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
        } catch (JacksonException notJson) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> row) {
        Map<String, Object> keyed = new java.util.LinkedHashMap<>();
        row.forEach((key, value) -> keyed.put(String.valueOf(key), value));
        return keyed;
    }

    /** Row 330: the KAFKA message uses the same transformed API body bytes. */
    @Bean
    KafkaTargetBody transformedKafkaBody(ApiTargetBody transformedApiBody) {
        return send -> transformedApiBody.bodyFor(send).map(ApiTargetBody.ApiWireBody::bytes);
    }

    /** E16: the DB rows are the transformed payload (object = one row, array of objects = rows); see {@link TransformedDbRows}. */
    @Bean
    DbRows transformedDbRows(ApiTargetBody transformedApiBody) {
        return new TransformedDbRows(transformedApiBody);
    }

    /**
     * T178a / B30: config's approval asks "is the DB account wider than INSERT and SELECT?"; routing answers through
     * its own {@link DbAccountWidth} (routing uses no config application type). Without routing's DB slice the bean
     * is a check that refuses every approval (fail closed; the same words as config's own default, which this bean
     * replaces - a null bean made that default depend on the container treating a NullBean as absent).
     */
    @Bean
    DbAccountWidthCheck dbAccountWidthCheck(ObjectProvider<DbAccountWidth> routingWidth) {
        DbAccountWidth width = routingWidth.getIfAvailable();
        if (width != null) {
            return width::refusalFor;
        }
        return (systemId, settings) -> Optional.of("DB_ACCOUNT_UNVERIFIED: no privilege probe is composed; an approval"
                + " is refused when the account's privileges cannot be read");
    }

    /** E16: the file body is the transformed payload, named by the target's pattern or the delivery id; see {@link TransformedFileContent}. */
    @Bean
    FileTargetContent transformedFileContent(ApiTargetBody transformedApiBody,
            ObjectProvider<JdbcFileTargets> fileTargets) {
        return TransformedFileContent.of(transformedApiBody,
                endpointId -> Optional.ofNullable(fileTargets.getIfAvailable()).flatMap(t -> t.settingsOf(endpointId)));
    }

    /**
     * With a Kafka backbone the relay and the stage consumers bind tenants on their own threads; this composite is
     * then the TenantContext every injection point and the stamping transaction manager resolve.
     */
    @Bean
    @Primary
    @ConditionalOnProperty("IPASS_KAFKA_BOOTSTRAP_SERVERS")
    TenantContext workerAwareTenantContext(ObjectProvider<TenantContext> contexts) {
        return new WorkerAwareTenantContext(contexts);
    }
}
