package io.integrationplatform.e2e.release1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** T174b: the names the runner passes as -D properties, and the on-main names it swapped in. No Docker. */
class E2eNamesRuntimeTest {

    @AfterEach
    void clear() {
        for (String key : new String[] {"e2e.interfaceCode", "e2e.tenantBCode", "e2e.wiremockUrl", "e2e.artemisContainer"}) {
            System.clearProperty(key);
        }
    }

    @Test
    void theInterfaceDefaultsToTheReferenceExampleAndTakesTheSeededCode() {
        assertThat(E2eNames.interfaceCode()).isEqualTo("REF-ORDER");
        System.setProperty("e2e.interfaceCode", " flow-abc ");
        assertThat(E2eNames.interfaceCode()).isEqualTo("flow-abc");
    }

    @Test
    void tenantBIsNeverGuessed() {
        assertThatThrownBy(E2eNames::tenantBCode).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("e2e.tenantBCode");
        System.setProperty("e2e.tenantBCode", "lane-d-1a2b3c-bs-b");
        assertThat(E2eNames.tenantBCode()).isEqualTo("lane-d-1a2b3c-bs-b");
    }

    @Test
    void externalContainersAreOffUnlessTheRunnerNamesThem() {
        assertThat(E2eNames.externalWiremock()).isNull();
        assertThat(E2eNames.externalArtemisContainer()).isNull();
        System.setProperty("e2e.wiremockUrl", "http://127.0.0.1:8089/");
        System.setProperty("e2e.artemisContainer", "t174c-artemis");
        assertThat(E2eNames.externalWiremock()).isEqualTo("http://127.0.0.1:8089");
        assertThat(E2eNames.externalArtemisContainer()).isEqualTo("t174c-artemis");
    }

    @Test
    void replayIsByDeliveryIdAndTheMqQueueIsTheSeededOne() {
        assertThat(E2eNames.nameOf("replayApi")).contains("{deliveryId}").doesNotContain("{trackingId}");
        assertThat(E2eNames.nameOf("mqTargetQueue")).isEqualTo("r1.orders.mq");
        assertThat(E2eNames.nameOf("bulkIntakeApi")).contains("{interfaceCode}").endsWith(":bulk");
        assertThat(E2eNames.nameOf("dailyReportApi")).contains("{day}");
    }
}
