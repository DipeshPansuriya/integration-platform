package io.integrationplatform.config.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

final class JdbcOperatorAllowedHostRepository {

    private final JdbcClient jdbcClient;

    JdbcOperatorAllowedHostRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<String> findAll() {
        return jdbcClient.sql("SELECT pattern FROM public.operator_allowed_host ORDER BY created_at")
                .query(String.class)
                .list();
    }

    void add(String pattern) {
        jdbcClient.sql("INSERT INTO public.operator_allowed_host (pattern) VALUES (:pattern) ON CONFLICT (pattern) DO NOTHING")
                .param("pattern", pattern.strip().toLowerCase(java.util.Locale.ROOT))
                .update();
    }

    int remove(String pattern) {
        return jdbcClient.sql("DELETE FROM public.operator_allowed_host WHERE pattern = :pattern")
                .param("pattern", pattern.strip().toLowerCase(java.util.Locale.ROOT))
                .update();
    }
}
