package io.integrationplatform.routing.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

/**
 * The record contract of ADR-011 Decision 7 (backlog.md:2035-2038, "A record carries ids only"): the value is
 * {@code {"v","type","id"}} and the only header the base reads is {@code traceparent} - no payload, no tenant id
 * anywhere in what the stage consumer reads. A forged tenant (header or value field) is ignored: the contract
 * below never carries it into {@link WorkRecord}, so nothing in the base can act on it (E8-C4).
 */
class WorkRecordParserTest {

    private static final UUID WORK_ID = UUID.fromString("33333333-0000-4000-8000-000000000003");

    private final WorkRecordParser parser = new WorkRecordParser();

    @Test
    void anIdsOnlyRecordParsesToItsVTypeAndIdWithTheTraceparentHeader() {
        byte[] value = ("{\"v\":1,\"type\":\"message.accepted\",\"id\":\"" + WORK_ID + "\"}").getBytes();

        WorkRecord record = parser.parse(value, headers("traceparent", "00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01"));

        assertThat(record.v()).isEqualTo(1);
        assertThat(record.type()).isEqualTo("message.accepted");
        assertThat(record.id()).isEqualTo(WORK_ID);
        assertThat(record.traceparent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d84e974e6-00f067aa0ba902b7-01");
    }

    @Test
    void aForgedTenantInValueOrHeaderIsIgnoredTheContractNeverCarriesIt() {
        byte[] value = ("{\"v\":1,\"type\":\"message.accepted\",\"id\":\"" + WORK_ID + "\","
                + "\"tenant_id\":\"0000000b-0000-4000-8000-00000000000b\",\"payload\":{\"amount\":1}}").getBytes();

        WorkRecord record = parser.parse(value,
                headers("tenant_id", "0000000b-0000-4000-8000-00000000000b"));

        assertThat(record.id()).isEqualTo(WORK_ID);
        // The parsed value carries exactly the ids-only contract - no tenant reaches the consumer through it.
        assertThat(record).isEqualTo(new WorkRecord(1, "message.accepted", WORK_ID, null));
    }

    @Test
    void aDeliveryDueRecordParsesTheSameWay() {
        byte[] value = ("{\"v\":1,\"type\":\"delivery.due\",\"id\":\"" + WORK_ID + "\"}").getBytes();

        WorkRecord record = parser.parse(value, new RecordHeaders());

        assertThat(record.type()).isEqualTo("delivery.due");
    }

    @Test
    void anUnparsableRecordIsRejectedForPoison() {
        assertThatThrownBy(() -> parser.parse("not json at all".getBytes(), new RecordHeaders()))
                .isInstanceOf(UnreadableRecordException.class);
    }

    @Test
    void aRecordOutsideTheIdsOnlyContractIsUnreadable() {
        assertThatThrownBy(() -> parser.parse(("{\"v\":2,\"type\":\"message.accepted\",\"id\":\""
                + WORK_ID + "\"}").getBytes(), new RecordHeaders()))
                .as("v is fixed at 1 by the record contract").isInstanceOf(UnreadableRecordException.class);
        assertThatThrownBy(() -> parser.parse(("{\"type\":\"message.accepted\",\"id\":\""
                + WORK_ID + "\"}").getBytes(), new RecordHeaders()))
                .as("v is mandatory").isInstanceOf(UnreadableRecordException.class);
        assertThatThrownBy(() -> parser.parse(("{\"v\":1,\"type\":\"\",\"id\":\"" + WORK_ID + "\"}").getBytes(),
                new RecordHeaders()))
                .as("type is mandatory and only the two ADR-010 work types exist")
                .isInstanceOf(UnreadableRecordException.class);
        assertThatThrownBy(() -> parser.parse(("{\"v\":1,\"type\":\"topic.unknown\",\"id\":\"" + WORK_ID + "\"}").getBytes(), new RecordHeaders()))
                .as("only message.accepted and delivery.due are work records")
                .isInstanceOf(UnreadableRecordException.class);
        assertThatThrownBy(() -> parser.parse("{\"v\":1,\"type\":\"message.accepted\",\"id\":\"nope\"}".getBytes(),
                new RecordHeaders()))
                .as("id is a uuid").isInstanceOf(UnreadableRecordException.class);
    }

    private static Headers headers(String key, String value) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(key, value.getBytes());
        return headers;
    }
}