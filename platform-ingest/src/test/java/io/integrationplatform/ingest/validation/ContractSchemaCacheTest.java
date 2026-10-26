package io.integrationplatform.ingest.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B37 / E11 intake figures: the contract is compiled once per contract version, never per record, and the cached
 * form gives the same answer as the uncached one. The measured cost per record is printed (and bounded loosely).
 */
class ContractSchemaCacheTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CONTRACT = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
             "required":["customerId","orderId","amount","cardNumber"],
             "properties":{"customerId":{"type":"string"},"orderId":{"type":"string"},
                           "amount":{"type":"number"},"cardNumber":{"type":"string","minLength":4}}}
            """;
    private static final String GOOD =
            "{\"customerId\":\"c\",\"orderId\":\"o\",\"amount\":1.5,\"cardNumber\":\"1234\"}";

    @Test
    @DisplayName("the schema source is read once per key: a bulk of 500 records compiles the contract once")
    void compilesOncePerContractKey() {
        ContractSchemaCheck check = new ContractSchemaCheck();
        AtomicInteger reads = new AtomicInteger();
        Object key = "tenant-a/" + UUID.randomUUID();
        JsonNode good = JSON.readTree(GOOD);
        for (int i = 0; i < 500; i++) {
            assertThat(check.check(key, () -> {
                reads.incrementAndGet();
                return JSON.readTree(CONTRACT);
            }, good).violations()).isEmpty();
        }
        assertThat(reads).as("schema source reads").hasValue(1);
    }

    @Test
    @DisplayName("two contract keys never share a compiled schema (tenant and version are part of the key)")
    void keysAreIsolated() {
        ContractSchemaCheck check = new ContractSchemaCheck();
        JsonNode record = JSON.readTree("{\"amount\":\"not-a-number\"}");
        JsonNode strict = JSON.readTree("{\"type\":\"object\",\"properties\":{\"amount\":{\"type\":\"number\"}}}");
        JsonNode lenient = JSON.readTree("{}");
        assertThat(check.check("tenant-a/v1", () -> strict, record).violations()).hasSize(1);
        assertThat(check.check("tenant-b/v1", () -> lenient, record).violations()).isEmpty();
        assertThat(check.check("tenant-a/v1", () -> lenient, record).violations())
                .as("tenant-a/v1 keeps its own compiled schema").hasSize(1);
    }

    @Test
    @DisplayName("cached and uncached checks give the same violations")
    void cachedEqualsUncached() {
        ContractSchemaCheck check = new ContractSchemaCheck();
        JsonNode schema = JSON.readTree(CONTRACT);
        JsonNode bad = JSON.readTree("{\"customerId\":1,\"amount\":\"x\",\"cardNumber\":\"12\"}");
        assertThat(check.check("k", () -> schema, bad).violations()).isEqualTo(check.check(schema, bad).violations());
        assertThat(check.check("k", () -> schema, bad).violations()).hasSize(4);
    }

    @Test
    @DisplayName("the cache is bounded: past the limit it is dropped and refills")
    void cacheIsBounded() {
        ContractSchemaCheck check = new ContractSchemaCheck();
        JsonNode schema = JSON.readTree("{}");
        JsonNode record = JSON.readTree("{}");
        AtomicInteger reads = new AtomicInteger();
        int total = ContractSchemaCheck.CACHE_LIMIT + 10;
        for (int i = 0; i < total; i++) {
            check.check("k" + i, () -> {
                reads.incrementAndGet();
                return schema;
            }, record);
        }
        assertThat(reads).hasValue(total);
        check.check("k" + (total - 1), () -> schema, record);
        assertThat(reads).as("a recent key is still cached after the drop").hasValue(total);
    }

    @Test
    @DisplayName("measured cost per record: cached contract vs compiled per record (printed; loose ceiling)")
    void measuredCostPerRecord() {
        ContractSchemaCheck check = new ContractSchemaCheck();
        PayloadValidationService service = new PayloadValidationService(check, (id, input) -> {
            throw new AssertionError("no rule set on this interface");
        });
        JsonNode schema = JSON.readTree(CONTRACT);
        JsonNode good = JSON.readTree(GOOD);
        int warm = 300;
        int n = 3000;
        for (int i = 0; i < warm; i++) {
            service.validate("m", () -> schema, List.of(), good);
            service.validate(schema, List.of(), good);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            assertThat(service.validate("m", () -> schema, List.of(), good).rejected()).isFalse();
        }
        double cachedUs = (System.nanoTime() - t0) / 1000.0 / n;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            service.validate(schema, List.of(), good);
        }
        double uncachedUs = (System.nanoTime() - t0) / 1000.0 / n;
        System.out.printf("B37 MEASURED validation cost per valid 4-field record: cached %.1f us, compile-per-record %.1f us (%.1fx)%n",
                cachedUs, uncachedUs, uncachedUs / cachedUs);
        assertThat(cachedUs).as("cached cost per record (us), loose ceiling").isLessThan(2000.0);
    }
}
