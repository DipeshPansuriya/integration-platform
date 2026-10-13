package io.integrationplatform.config.rule;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.rule.RuleSuiteFixture.Response;
import io.integrationplatform.config.rule.RuleSuiteFixture.Tenant;
import io.integrationplatform.domain.audit.AuditEvent;
import io.integrationplatform.e6laneb.E6LaneBSuiteApp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * T-163 (E7-F3-S1): lookup-table edits take effect immediately and publish one audit event, against a real
 * PostgreSQL under RLS and the real identity chain. The lookup a mapping names ("country codes") is its LOOKUP rule
 * set; an entry is one row of that table. The edit endpoint rewrites that entry in place, so the next evaluation -
 * through the existing {@code RuleEvaluationPort} - sees it with no publish and no restart.
 *
 * <pre>
 * Scenario: Immediate effect
 *   Given a mapping that uses lookup table "country codes"
 *   When a configurator changes one entry
 *   Then the next transform uses the new entry, with no publish and no restart
 *
 * Scenario: The edit is audited
 *   Given a lookup entry change
 *   When it is saved
 *   Then one audit event (who, what, before, after, when) is published, and from E12 it lands in the append-only audit log
 *
 * &#64;negative &#64;security
 * Scenario: A read-only user cannot edit lookups
 *   Given a read-only user
 *   When they change a lookup entry through the API
 *   Then the server answers 403 and nothing changes
 * </pre>
 */
@Tag("container")
@SpringBootTest(classes = E6LaneBSuiteApp.class, webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(LookupEntryChangeContainerTest.AuditCapture.class)
class LookupEntryChangeContainerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuditCapture audit;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        RuleSuiteFixture.wire(registry);
    }

    @Test
    @DisplayName("Scenario: Immediate effect - the next evaluation uses the changed entry, no publish no restart")
    void immediateEffect() {
        Tenant a = RuleSuiteFixture.tenantA();
        String lookupId = createLookup(a, "country codes " + UUID.randomUUID());

        assertThat(lookupValue(a, lookupId, "NL")).as("the seeded entry").isEqualTo("Netherlands");

        Response changed = call("PUT", "/api/v1/admin/lookups/" + lookupId + "/entries/r1", a,
                "{\"key\":\"NL\",\"value\":\"Kingdom of the Netherlands\"}");
        assertThat(changed.status()).as("PUT, body: %s", changed.body()).isEqualTo(200);
        assertThat(changed.json().path("lookupId").asString()).isEqualTo(lookupId);
        assertThat(changed.json().path("entryId").asString()).isEqualTo("r1");
        assertThat(changed.json().path("key").asString()).isEqualTo("NL");
        assertThat(changed.json().path("value").asString()).isEqualTo("Kingdom of the Netherlands");

        assertThat(lookupValue(a, lookupId, "NL"))
                .as("the next evaluation sees the new entry - no publish, no restart")
                .isEqualTo("Kingdom of the Netherlands");
        assertThat(lookupValue(a, lookupId, "DE")).as("the other entry is untouched").isEqualTo("Germany");
        assertThat(RuleSuiteFixture.ownerText("SELECT (content_text LIKE '%Kingdom of the Netherlands%')::text"
                + " FROM public.rule_set WHERE id = ?::uuid", lookupId))
                .as("stored as one configuration row").isEqualTo("true");
    }

    @Test
    @DisplayName("Scenario: The edit is audited - one event carries who, what, before, after, when")
    void theEditIsAudited() {
        Tenant a = RuleSuiteFixture.tenantA();
        String lookupId = createLookup(a, "audited codes " + UUID.randomUUID());
        audit.seen.clear();

        Response changed = call("PUT", "/api/v1/admin/lookups/" + lookupId + "/entries/r1", a,
                "{\"key\":\"NL\",\"value\":\"Kingdom of the Netherlands\"}");
        assertThat(changed.status()).as("PUT, body: %s", changed.body()).isEqualTo(200);

        var published = audit.seen.stream().filter(event -> event.targetIds().contains(lookupId)).toList();
        assertThat(published).as("exactly one audit event for the change").hasSize(1);
        AuditEvent event = published.getFirst();
        assertThat(event.who()).as("the acting configurator").isEqualTo(a.userId().toString());
        assertThat(event.action()).as("what happened").isEqualTo("LOOKUP_ENTRY_CHANGED");
        assertThat(event.targetIds()).as("lookup and entry").containsExactly(lookupId, "r1");
        assertThat(event.before()).as("the value before").isEqualTo("{\"key\":\"NL\",\"value\":\"Netherlands\"}");
        assertThat(event.after()).as("the value after")
                .isEqualTo("{\"key\":\"NL\",\"value\":\"Kingdom of the Netherlands\"}");
        assertThat(event.at()).as("when").isNotNull();
        assertThat(event.tenantId()).as("the tenant of the lookup").isEqualTo(a.id());
    }

    @Test
    @Tag("negative")
    @Tag("security")
    @DisplayName("Scenario: A read-only user cannot edit lookups - 403 and nothing changes")
    void readOnlyUserCannotEditLookups() {
        Tenant a = RuleSuiteFixture.tenantA();
        Tenant readOnly = seedReadOnlyUser(a.id());
        String lookupId = createLookup(a, "locked codes " + UUID.randomUUID());
        String before = RuleSuiteFixture.ownerText("SELECT content_text FROM public.rule_set WHERE id = ?::uuid",
                lookupId);

        Response refused = call("PUT", "/api/v1/admin/lookups/" + lookupId + "/entries/r1", readOnly,
                "{\"key\":\"NL\",\"value\":\"forged\"}");

        assertThat(refused.status()).as("the role does not allow editing: body: %s", refused.body()).isEqualTo(403);
        assertThat(refused.json().path("refusal").asString()).isEqualTo("ROLE_NOT_GRANTED");
        assertThat(RuleSuiteFixture.ownerText("SELECT content_text FROM public.rule_set WHERE id = ?::uuid",
                lookupId)).as("nothing changes").isEqualTo(before);
        assertThat(audit.seen.stream().filter(event -> event.targetIds().contains(lookupId)).count())
                .as("no audit event for a refused change").isZero();
        assertThat(lookupValue(a, lookupId, "NL")).as("the next evaluation still sees the old entry")
                .isEqualTo("Netherlands");
    }

    @Test
    @Tag("negative")
    @DisplayName("An unknown entry, a foreign lookup id and a non-lookup rule set answer 404 and write nothing")
    void unknownOrForeignIdsAreNotFound() {
        Tenant a = RuleSuiteFixture.tenantA();
        Tenant b = RuleSuiteFixture.tenantB();
        String lookupId = createLookup(a, "scoped codes " + UUID.randomUUID());
        String validationId = RuleSuiteFixture.class.getName().isEmpty() ? "" : createValidation(a);
        String before = RuleSuiteFixture.ownerText("SELECT content_text FROM public.rule_set WHERE id = ?::uuid",
                lookupId);

        assertThat(call("PUT", "/api/v1/admin/lookups/" + lookupId + "/entries/no-such-entry", a,
                "{\"key\":\"NL\",\"value\":\"x\"}").status()).as("unknown entry id").isEqualTo(404);
        assertThat(call("PUT", "/api/v1/admin/lookups/" + validationId + "/entries/r1", a,
                "{\"key\":\"NL\",\"value\":\"x\"}").status()).as("a rule set that is not a lookup").isEqualTo(404);
        assertThat(call("PUT", "/api/v1/admin/lookups/" + createLookup(b, "bravo lookup " + UUID.randomUUID())
                + "/entries/r1", a, "{\"key\":\"NL\",\"value\":\"x\"}").status())
                .as("tenant B's lookup is not visible").isEqualTo(404);
        assertThat(call("PUT", "/api/v1/admin/lookups/not-a-uuid/entries/r1", a,
                "{\"key\":\"NL\",\"value\":\"x\"}").status()).as("a malformed id is the same 404, never a 500")
                .isEqualTo(404);
        assertThat(RuleSuiteFixture.ownerText("SELECT content_text FROM public.rule_set WHERE id = ?::uuid",
                lookupId)).as("nothing stored").isEqualTo(before);
    }

    /** The capture takes the audit event the way E12's writer will: as a subscriber of the publisher. */
    static class AuditCapture implements ApplicationListener<ApplicationEvent> {

        final List<AuditEvent> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            if (event instanceof PayloadApplicationEvent payload && payload.getPayload() instanceof AuditEvent audited) {
                seen.add(audited);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Creates one LOOKUP rule set (the lookup table "name" of the scenario) through the authoring API. */
    private String createLookup(Tenant caller, String name) {
        Response created = call("POST", "/api/v1/admin/flows/" + caller.flowId() + "/rule-sets", caller,
                "{\"kind\":\"LOOKUP\",\"name\":\"" + name + "\",\"content\":" + Jdm.lookupTable() + "}");
        assertThat(created.status()).as("POST rule set, body: %s", created.body()).isEqualTo(201);
        return created.json().path("id").asString();
    }

    private String createValidation(Tenant caller) {
        Response created = call("POST", "/api/v1/admin/flows/" + caller.flowId() + "/rule-sets", caller,
                "{\"kind\":\"VALIDATION\",\"name\":\"not a lookup " + UUID.randomUUID()
                        + "\",\"content\":" + Jdm.validationTable() + "}");
        assertThat(created.status()).as("POST rule set, body: %s", created.body()).isEqualTo(201);
        return created.json().path("id").asString();
    }

    /** The output value one evaluate call of the LOOKUP table answers with (the value entry r1 or r2 decodes to). */
    private String lookupValue(Tenant caller, String lookupId, String code) {
        Response evaluated = call("POST", "/api/v1/admin/rule-sets/" + lookupId + "/evaluate", caller,
                "{\"input\":{\"code\":\"" + code + "\"}}");
        assertThat(evaluated.status()).as("evaluate, body: %s", evaluated.body()).isEqualTo(200);
        JsonNode output = evaluated.json().path("output");
        JsonNode row = output.isArray() ? output.get(0) : output;
        return row.path("name").asString();
    }

    private Response call(String method, String path, Tenant caller, String body) {
        return RuleSuiteFixture.call(port, method, path, caller, body);
    }

    /** A second tenant-A user whose READ_ONLY grant cannot edit configuration (RolePolicy EDIT_CONFIGURATION). */
    private static Tenant seedReadOnlyUser(UUID tenantId) {
        UUID userId = UUID.randomUUID();
        String subject = "sub-readonly-" + UUID.randomUUID();
        try (Connection owner = RuleSuiteFixture.ownerConnection()) {
            insert(owner, "INSERT INTO app_user (id, tenant_id, issuer, subject, status)"
                    + " VALUES (?::uuid, ?::uuid, ?, ?, 'ACTIVE')", userId.toString(), tenantId.toString(),
                    RuleSuiteFixture.OIDC_ISSUER.toString(), subject);
            insert(owner, "INSERT INTO role_grant (tenant_id, user_id, role) VALUES (?::uuid, ?::uuid, 'READ_ONLY')",
                    tenantId.toString(), userId.toString());
        } catch (Exception e) {
            throw new IllegalStateException("the read-only user seed failed", e);
        }
        return new Tenant(tenantId, userId, subject, null);
    }

    private static void insert(Connection owner, String sql, String... values) throws Exception {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }
}