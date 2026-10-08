package io.integrationplatform.config.rule;

import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON Decision Model samples the E6 lane B suites author: one decision table per kind, in the open ZEN format
 * jdm-editor emits (input node -> decision table -> output node). Every sample is returned COMPACT, in Jackson's
 * order-keeping serialization, and its keys are deliberately not in jsonb's order ("id","type","name","position",
 * "content"; "nodes" before "edges"), so a store that re-orders keys cannot pass the byte-for-byte round trip.
 */
public final class Jdm {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The decision table node id every sample uses (the refused cell names it). */
    public static final String TABLE_NODE = "checks";

    private Jdm() {
    }

    /**
     * VALIDATION, hitPolicy collect, four rows: for {@code {"amount":150,"currency":"XXX","reference":""}} rows 1-3
     * match (in that table order, codes deliberately not alphabetical) and row 4 does not.
     */
    public static String validationTable() {
        return compact(graph("""
                "hitPolicy":"collect",
                "inputs":[{"id":"c1","name":"Amount","field":"amount"},
                          {"id":"c2","name":"Currency","field":"currency"},
                          {"id":"c3","name":"Reference","field":"reference"}],
                "outputs":[{"id":"o1","name":"Field","field":"violation.field"},
                           {"id":"o2","name":"Code","field":"violation.code"},
                           {"id":"o3","name":"Message","field":"violation.message"}],
                "rules":[
                  {"_id":"r1","c1":"> 100","c2":"","c3":"","o1":"\\"amount\\"","o2":"\\"Z_AMOUNT_TOO_HIGH\\"","o3":"\\"amount must not exceed 100\\""},
                  {"_id":"r2","c1":"","c2":"\\"XXX\\"","c3":"","o1":"\\"currency\\"","o2":"\\"A_CURRENCY_UNKNOWN\\"","o3":"\\"currency XXX is not accepted\\""},
                  {"_id":"r3","c1":"","c2":"","c3":"\\"\\"","o1":"\\"reference\\"","o2":"\\"M_REFERENCE_REQUIRED\\"","o3":"\\"reference is required\\""},
                  {"_id":"r4","c1":"< 0","c2":"","c3":"","o1":"\\"amount\\"","o2":"\\"NEGATIVE\\"","o3":"\\"amount must not be negative\\""}]
                """));
    }

    /** The payload that matches rows 1, 2 and 3 of {@link #validationTable()}. */
    public static String validationInput() {
        return "{\"amount\":150,\"currency\":\"XXX\",\"reference\":\"\"}";
    }

    /** ROUTING, hitPolicy first: NL goes to dst-eu, anything else to dst-default. */
    public static String routingTable() {
        return compact(graph("""
                "hitPolicy":"first",
                "inputs":[{"id":"c1","name":"Country","field":"country"}],
                "outputs":[{"id":"o1","name":"Target","field":"target"}],
                "rules":[
                  {"_id":"r1","c1":"\\"NL\\"","o1":"\\"dst-eu\\""},
                  {"_id":"r2","c1":"","o1":"\\"dst-default\\""}]
                """));
    }

    /** LOOKUP, hitPolicy first: a country code to its name. */
    public static String lookupTable() {
        return compact(graph("""
                "hitPolicy":"first",
                "inputs":[{"id":"c1","name":"Code","field":"code"}],
                "outputs":[{"id":"o1","name":"Name","field":"name"}],
                "rules":[
                  {"_id":"r1","c1":"\\"NL\\"","o1":"\\"Netherlands\\""},
                  {"_id":"r2","c1":"\\"DE\\"","o1":"\\"Germany\\""}]
                """));
    }

    /** The validation table with row index 1's Amount cell unparsable ({@code > > 100}). */
    public static String unparsableExpression() {
        return validationTable().replace("\"c1\":\"\",\"c2\":\"\\\"XXX\\\"\"", "\"c1\":\"> > 100\",\"c2\":\"\\\"XXX\\\"\"");
    }

    /** The validation table plus a condition column c9 that names no field and holds no condition in any row. */
    public static String emptyConditionColumn() {
        return validationTable().replace("{\"id\":\"c3\",\"name\":\"Reference\",\"field\":\"reference\"}",
                "{\"id\":\"c3\",\"name\":\"Reference\",\"field\":\"reference\"},{\"id\":\"c9\",\"name\":\"\",\"field\":\"\"}");
    }

    private static String graph(String tableContent) {
        return """
                {"nodes":[
                  {"id":"request","type":"inputNode","name":"Request","position":{"x":0,"y":0}},
                  {"id":"%s","type":"decisionTableNode","name":"Checks","position":{"x":240,"y":0},
                   "content":{%s}},
                  {"id":"response","type":"outputNode","name":"Response","position":{"x":480,"y":0}}],
                 "edges":[{"id":"e1","sourceId":"request","targetId":"%s","type":"edge"},
                          {"id":"e2","sourceId":"%s","targetId":"response","type":"edge"}]}
                """.formatted(TABLE_NODE, tableContent, TABLE_NODE, TABLE_NODE);
    }

    private static String compact(String json) {
        return JSON.readTree(json).toString();
    }
}
