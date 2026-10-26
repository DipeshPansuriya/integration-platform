package io.integrationplatform.routing.adapter.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.AllowList;
import io.integrationplatform.config.destination.DestinationGuard;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * E16 security F1 (CWE-918) and F2 (CWE-74) on the send path, no containers, a fake resolver: the DB host is
 * resolved ONCE, EVERY resolved address is checked against the refused ranges and the platform hosts, the sender is
 * handed the checked address to dial, and a port outside the operator's list is refused before any DNS. Tenant
 * text with URL syntax never reaches the sender (and so never the driver).
 */
class DbTargetSsrfTest {

    static final InetAddress PUBLIC = address(93, 184, 216, 34);
    static final InetAddress OTHER_PUBLIC = address(93, 184, 216, 35);
    static final InetAddress METADATA = address(169, 254, 169, 254);

    final DbSender sender = mock(DbSender.class);
    final AtomicInteger lookups = new AtomicInteger();
    final Stage2Send send = new Stage2Send(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), false);

    static InetAddress address(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String settings(String host, int port, String database) {
        return "{\"host\":\"" + host + "\",\"port\":" + port + ",\"database\":\"" + database
                + "\",\"user\":\"restricted\",\"table\":\"orders_in\"}";
    }

    DbTargetCall call(String settingsJson, List<InetAddress> resolves) {
        DbSettings settings = DbSettings.fromJson(settingsJson);
        DbTargets targets = id -> Optional.of(new DbTargets.DbTarget(settings, "pw".toCharArray()));
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("*.partner.example", "198.51.100.7")),
                name -> {
                    lookups.incrementAndGet();
                    return resolves;
                }, List.of(), List.of(), List.of("10.9.9.9", "platform-db.internal"));
        return new DbTargetCall(ApprovedTablesFixtures.ALL, new DbTargetOutbound(targets, s -> Optional.of(List.of(Map.of("awb", "A1")))),
                sender, guard, List.of(5432, 5433), CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(),
                ObservationRegistry.create());
    }

    private void assertRefusedAndNothingSent(DbTargetCall call) throws Exception {
        assertThatThrownBy(() -> call.call(send)).isInstanceOfSatisfying(TargetFaultException.class,
                fault -> assertThat(fault.outcomeClass()).isEqualTo(OutcomeClass.PERMANENT));
        verify(sender, never()).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));
    }

    @Test
    void theHostIsResolvedOnceAndTheSenderIsHandedTheCheckedAddress() throws Exception {
        call(settings("db.partner.example", 5432, "orders"), List.of(PUBLIC, OTHER_PUBLIC)).call(send);

        ArgumentCaptor<InetAddress> pinned = ArgumentCaptor.forClass(InetAddress.class);
        verify(sender).insert(any(DbMessage.class), any(UUID.class), pinned.capture());
        assertThat(pinned.getValue()).isEqualTo(PUBLIC);
        assertThat(lookups).as("resolved once, never again at connect time").hasValue(1);
    }

    @Test
    void anAllowListedAddressLiteralIsPinnedWithoutALookup() throws Exception {
        call(settings("198.51.100.7", 5433, "orders"), List.of()).call(send);

        ArgumentCaptor<InetAddress> pinned = ArgumentCaptor.forClass(InetAddress.class);
        verify(sender).insert(any(DbMessage.class), any(UUID.class), pinned.capture());
        assertThat(pinned.getValue().getHostAddress()).isEqualTo("198.51.100.7");
        assertThat(lookups).hasValue(0);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void oneRefusedAddressAmongSeveralRefusesTheWholeDestination() throws Exception {
        assertRefusedAndNothingSent(call(settings("db.partner.example", 5432, "orders"), List.of(PUBLIC, METADATA)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "169.254.169.254", "10.1.2.3", "192.168.1.5", "172.16.0.9", "100.64.0.1",
        "10.9.9.9"})
    @Tag("negative")
    @Tag("security")
    void aNameThatResolvesToALoopbackLinkLocalPrivateOrPlatformAddressIsRefused(String resolved) throws Exception {
        InetAddress target = InetAddress.getByName(resolved);
        assertRefusedAndNothingSent(call(settings("db.partner.example", 5432, "orders"), List.of(target)));
        assertThat(lookups).hasValue(1);
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void aPlatformHostIsRefusedWhateverThePort() throws Exception {
        assertRefusedAndNothingSent(call(settings("10.9.9.9", 5432, "orders"), List.of()));
        assertRefusedAndNothingSent(call(settings("platform-db.internal", 5433, "orders"), List.of(PUBLIC)));
    }

    @ParameterizedTest
    @ValueSource(ints = {22, 25, 6379, 9092, 5431, 8080, 61616})
    @Tag("negative")
    @Tag("security")
    void aPortOutsideTheOperatorsListIsRefusedBeforeAnyLookup(int port) throws Exception {
        assertRefusedAndNothingSent(call(settings("db.partner.example", port, "orders"), List.of(PUBLIC)));
        assertThat(lookups).as("a refused port costs no DNS query").hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x?socketFactory=evil", "x?sslmode=disable", "orders&user=postgres", "orders;DROP",
        "a/b", "a#b", "a,b", "a%2fb", "a b", "a`b"})
    @Tag("negative")
    @Tag("security")
    void aDatabaseValueWithUrlSyntaxNeverReachesTheSender(String database) throws Exception {
        // the stored row is read at send time: the settings constructor refuses it, the delivery is PERMANENT
        assertThatThrownBy(() -> call(settings("db.partner.example", 5432, database), List.of(PUBLIC)))
                .isInstanceOf(IllegalStateException.class);
        verify(sender, never()).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"db.partner.example?socketFactory=evil", "db.partner.example/x", "db.partner.example#x",
        "db.partner.example,evil.example", "db.partner.example;x", "db.partner.example&x", "user@db.partner.example"})
    @Tag("negative")
    @Tag("security")
    void aHostValueWithUrlSyntaxNeverReachesTheSender(String host) throws Exception {
        assertThatThrownBy(() -> call(settings(host, 5432, "orders"), List.of(PUBLIC)))
                .isInstanceOf(IllegalStateException.class);
        verify(sender, never()).insert(any(DbMessage.class), any(UUID.class), any(InetAddress.class));
    }

    @Test
    @Tag("negative")
    @Tag("security")
    void aRowStoredBeforeTheFixWithUrlSyntaxIsAPermanentFaultAtSend() throws Exception {
        DbTargets legacy = id -> {
            throw new IllegalStateException("the DB database must be 1 to 63 letters, digits, dot, hyphen or underscore");
        };
        DestinationGuard guard = new DestinationGuard(AllowList.of(List.of("*.partner.example")), n -> List.of(PUBLIC));
        DbTargetCall call = new DbTargetCall(ApprovedTablesFixtures.ALL, new DbTargetOutbound(legacy, s -> Optional.of(List.of(Map.of("awb", "A1")))),
                sender, guard, List.of(5432), CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry(),
                ObservationRegistry.create());

        assertRefusedAndNothingSent(call);
    }
}
