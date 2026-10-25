package io.integrationplatform.transform.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.integrationplatform.transform.api.TransformPort;
import io.integrationplatform.transform.internal.DashjoinJsonataTransformPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minidev.json.JSONValue;
import org.junit.jupiter.api.Test;

/**
 * T214 - the showcase "nested appointment" flow: one source document with two array levels
 * (VehicleList[] -> ContainerList[]) is flattened to ONE record per container, each carrying the appointment and
 * vehicle fields. The expression and the sample are the seed's own files (e2e-ui/scripts/release1/nested-appointment/),
 * run here on the real engine through the real port - no product code changes: the engine navigates both levels.
 */
class NestedAppointmentFlattenTest {

    private static final Path FIXTURES = fixtures();

    /** The seed's directory, found by walking up from the module directory (works from any module cwd). */
    private static Path fixtures() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("e2e-ui/scripts/release1/nested-appointment");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("e2e-ui/scripts/release1/nested-appointment not found above the module");
    }

    private static String read(String name) throws IOException {
        return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8).strip();
    }

    private final TransformPort port = new DashjoinJsonataTransformPort(5_000);

    private TransformPort.Result run(String inputJson) throws IOException {
        return port.transform(read("expression.jsonata"), inputJson, (table, key) -> null);
    }

    @Test
    void theSampleBecomesOneFlatRecordWithExactValuesAndTypes() throws IOException {
        TransformPort.Result result = run(read("sample.json"));
        assertEquals(TransformPort.Status.OK, result.status(), result.error());
        // exact text: ISO-8601 dates unchanged, numbers stay numbers (3, 90726015, 13, the three zeros),
        // strings stay strings ("68", "26", "40", "1232", "907261"), a single record is still an ARRAY
        assertEquals("[{\"terminalOpsId\":3,\"terminalId\":2,\"appointmentId\":90726015,"
                + "\"appointmentDate\":\"2026-07-09T11:11:05.834Z\",\"appointmentStatus\":\"F\",\"noOfVehicles\":1,"
                + "\"berthNo\":\"68\",\"slotDate\":\"2026-07-09T11:11:05.834Z\","
                + "\"slotStartTime\":\"2026-07-09T14:00:00.834Z\",\"slotEndTime\":\"2026-07-09T15:00:00.834Z\","
                + "\"driverPortId\":\"907261\",\"bookingType\":\"FO\",\"transactionId\":13,\"containerNo\":\"26\","
                + "\"size\":\"40\",\"type\":\"GB\",\"isoCode\":\"1232\",\"noOfPackages\":0,\"grossWeight\":0,"
                + "\"actualWeight\":0}]", result.outputJson());
    }

    private static String vehicle(String driverPort, String... containerNos) {
        StringBuilder containers = new StringBuilder();
        for (String no : containerNos) {
            containers.append(containers.length() == 0 ? "" : ",")
                    .append("{\"BookingType\":\"FO\",\"TransactionID\":1,\"ContainerNo\":\"").append(no)
                    .append("\",\"Size\":\"20\",\"Type\":\"GP\",\"ISOCode\":\"22G1\",\"NoOfPackages\":0,"
                            + "\"GrossWeight\":0,\"ActualWeight\":0}");
        }
        return "{\"SlotDate\":\"2026-07-09T11:11:05.834Z\",\"SlotStartTime\":\"2026-07-09T14:00:00.834Z\","
                + "\"SlotEndTime\":\"2026-07-09T15:00:00.834Z\",\"DriverPortId\":\"" + driverPort
                + "\",\"ContainerList\":[" + containers + "]}";
    }

    private static String appointment(String... vehicles) {
        return "{\"TerminalOpsId\":3,\"TerminalId\":2,\"AppointmentId\":7,\"AppointmentDate\":\"2026-07-09T11:11:05.834Z\","
                + "\"AppointmentStatus\":\"F\",\"NoofVehicle\":" + vehicles.length + ",\"BerthNo\":\"68\","
                + "\"VehicleList\":[" + String.join(",", vehicles) + "]}";
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoVehiclesByTwoContainersGiveFourRecordsEachCarryingItsOwnVehicle() throws IOException {
        TransformPort.Result result = run(appointment(vehicle("P1", "C1", "C2"), vehicle("P2", "C3", "C4")));
        assertEquals(TransformPort.Status.OK, result.status(), result.error());
        List<Map<String, Object>> records = (List<Map<String, Object>>) JSONValue.parse(result.outputJson());
        assertEquals(List.of("C1", "C2", "C3", "C4"), records.stream().map(r -> r.get("containerNo")).toList());
        assertEquals(List.of("P1", "P1", "P2", "P2"), records.stream().map(r -> r.get("driverPortId")).toList());
        assertTrue(records.stream().allMatch(r -> Integer.valueOf(7).equals(r.get("appointmentId"))
                && "68".equals(r.get("berthNo")) && Integer.valueOf(2).equals(r.get("noOfVehicles"))));
        assertEquals(4, records.size());
    }

    @Test
    void anEmptyContainerListGivesZeroRecordsWithoutError() throws IOException {
        TransformPort.Result result = run(appointment(vehicle("P1")));
        assertEquals(TransformPort.Status.OK, result.status(), result.error());
        assertEquals("[]", result.outputJson());
    }

    @Test
    void aVehicleWithoutContainersNextToOneWithContainersOnlyCountsTheContainers() throws IOException {
        TransformPort.Result result = run(appointment(vehicle("P1"), vehicle("P2", "C9")));
        assertEquals(TransformPort.Status.OK, result.status(), result.error());
        assertTrue(result.outputJson().startsWith("[{") && result.outputJson().contains("\"containerNo\":\"C9\""));
        assertEquals(1, ((List<?>) JSONValue.parse(result.outputJson())).size());
    }

    @Test
    void noVehiclesOrNoVehicleListGivesZeroRecordsWithoutError() throws IOException {
        assertEquals("[]", run(appointment()).outputJson());
        TransformPort.Result bare = run("{\"AppointmentId\":7}");
        assertEquals(TransformPort.Status.OK, bare.status(), bare.error());
        assertEquals("[]", bare.outputJson());
    }
}
