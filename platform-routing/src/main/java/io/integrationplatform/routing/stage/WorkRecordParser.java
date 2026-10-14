package io.integrationplatform.routing.stage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/**
 * The one reader of record values for the stage base (T-111, ADR-011 Decision 7): the value must be
 * {@code {"v","type","id"}} with {@code v} fixed at 1, {@code type} one of the two work types and {@code id} a
 * uuid; {@code traceparent} is the only header kept. Extra value fields (a forged tenant id, a payload) and extra
 * headers are ignored - they can never reach a handler, so a record cannot redirect work to another tenant
 * (E8-C4, backlog.md:2039-2049). Anything that does not read against the contract is an
 * {@link UnreadableRecordException} - poison, once, never a retry (T-113).
 */
public final class WorkRecordParser {

    private static final String TRACEPARENT = "traceparent";

    private final ObjectMapper json = new ObjectMapper();

    /** Parses a record value and headers into the ids-only contract. */
    public WorkRecord parse(byte[] value, Headers headers) {
        if (value == null) {
            throw new UnreadableRecordException("the record value is null");
        }
        JsonNode node;
        try {
            node = json.readTree(value);
        } catch (IOException e) {
            throw new UnreadableRecordException("the record value is not JSON: " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new UnreadableRecordException("the record value is not a JSON object");
        }
        int v = intField(node, "v");
        if (v != 1) {
            throw new UnreadableRecordException("v is fixed at 1 by the record contract");
        }
        String type = textField(node, "type");
        if (!type.equals("message.accepted") && !type.equals("delivery.due")) {
            throw new UnreadableRecordException("type is not one of the two work types: " + type);
        }
        UUID id = idField(node);
        String traceparent = null;
        for (Header header : headers) {
            if (TRACEPARENT.equals(header.key())) {
                traceparent = header.value() == null ? null : new String(header.value());
            }
        }
        return new WorkRecord(v, type, id, traceparent);
    }

    private int intField(JsonNode node, String name) {
        JsonNode field = required(node, name);
        if (!field.isIntegralNumber()) {
            throw new UnreadableRecordException(name + " is not an integer");
        }
        return field.intValue();
    }

    private String textField(JsonNode node, String name) {
        JsonNode field = required(node, name);
        if (!field.isTextual()) {
            throw new UnreadableRecordException(name + " is not text");
        }
        return field.textValue();
    }

    private UUID idField(JsonNode node) {
        try {
            return UUID.fromString(textField(node, "id"));
        } catch (IllegalArgumentException e) {
            throw new UnreadableRecordException("id is not a uuid");
        }
    }

    private JsonNode required(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || field.isMissingNode() || field.isNull()) {
            throw new UnreadableRecordException(name + " is missing");
        }
        return field;
    }
}