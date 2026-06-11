package org.tmforum.intent.integration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full intent lifecycle integration test: create → activate → observe → Fulfilled → terminate.
 * Also covers IntentSpec, Hub, fields-filter, pagination, and error paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "intent.base-url=http://localhost:8000/tmf-api/intentManagement/v5")
class IntentLifecycleIT {

    @TempDir
    static Path tdb2Dir;

    @DynamicPropertySource
    static void configureDataset(DynamicPropertyRegistry registry) {
        registry.add("intent.tdb2-path", () -> tdb2Dir.toString());
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String base;

    // Turtle expression: single atLeast condition on a bandwidth metric
    private static final String BANDWIDTH_EXPRESSION = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .

            _:cond a quan:quanatLeast ;
                rdf:first <http://example.org/metrics/bandwidth> ;
                rdf:rest [ rdf:first _:bound ] .
            _:bound rdf:value "50.0"^^xsd:decimal .
            """;

    @BeforeEach
    void setBase() {
        base = "http://localhost:" + port + "/tmf-api/intentManagement/v5";
    }

    // ── Intent lifecycle ──────────────────────────────────────────────────────

    @Test
    void fullLifecycle_createActivateObserveFulfillTerminate() {
        // 1. Create intent
        Map<String, Object> body = Map.of(
                "name", "BandwidthIntent",
                "@type", "Intent",
                "expression", Map.of("@type", "TurtleExpression", "expressionValue", BANDWIDTH_EXPRESSION));
        ResponseEntity<Map> created = rest.postForEntity(base + "/intent", body, Map.class);

        assertEquals(201, created.getStatusCode().value());
        String id = (String) created.getBody().get("id");
        assertNotNull(id);
        assertEquals("ACKNOWLEDGED", created.getBody().get("lifecycleStatus"));

        // 2. Activate
        ResponseEntity<Map> activated = patchIntent(id, Map.of("lifecycleStatus", "ACTIVE"));
        assertEquals(200, activated.getStatusCode().value());
        assertEquals("ACTIVE", activated.getBody().get("lifecycleStatus"));

        // 3. Inject passing observation (bandwidth = 80 > 50)
        Map<String, Object> obs = Map.of(
                "metricUri", "http://example.org/metrics/bandwidth",
                "value", 80.0);
        ResponseEntity<Map> obsResp = rest.postForEntity(base + "/intent/" + id + "/observation", obs, Map.class);
        assertEquals(201, obsResp.getStatusCode().value());

        // 4. Wait for Fulfilled report (async evaluation)
        Map<?, ?> report = pollForReport(id, "Fulfilled", 10);
        assertNotNull(report);
        assertEquals("Fulfilled", report.get("intentHandlingState"));

        // 5. Terminate
        ResponseEntity<Map> terminated = patchIntent(id, Map.of("lifecycleStatus", "TERMINATED"));
        assertEquals(200, terminated.getStatusCode().value());
        assertEquals("TERMINATED", terminated.getBody().get("lifecycleStatus"));

        // 6. Attempt invalid transition from TERMINATED → 400 (IllegalArgumentException)
        ResponseEntity<Map> badPatch = patchIntent(id, Map.of("lifecycleStatus", "ACTIVE"));
        assertTrue(badPatch.getStatusCode().is4xxClientError());

        // 7. Delete
        ResponseEntity<Void> del = rest.exchange(base + "/intent/" + id, HttpMethod.DELETE, null, Void.class);
        assertEquals(204, del.getStatusCode().value());

        // 8. GET after delete → 404
        ResponseEntity<Map> gone = rest.getForEntity(base + "/intent/" + id, Map.class);
        assertEquals(404, gone.getStatusCode().value());
        assertEquals("Error", gone.getBody().get("@type"));
    }

    @Test
    void degradedThenFulfilled_afterObservation() {
        // Create + activate → no observations → Degraded
        String id = createActiveIntent(BANDWIDTH_EXPRESSION);

        // Initial evaluation should degrade (no observations)
        Map<?, ?> degraded = pollForReport(id, "Degraded", 8);
        assertNotNull(degraded);
        assertEquals("Degraded", degraded.get("intentHandlingState"));

        // Post passing observation → Fulfilled
        Map<String, Object> obs = Map.of(
                "metricUri", "http://example.org/metrics/bandwidth",
                "value", 75.0);
        rest.postForEntity(base + "/intent/" + id + "/observation", obs, Map.class);

        Map<?, ?> fulfilled = pollForReport(id, "Fulfilled", 10);
        assertNotNull(fulfilled, "Expected Fulfilled report after passing observation");
    }

    // ── Fields filter ─────────────────────────────────────────────────────────

    @Test
    void fieldsFilter_projectsRequestedFields() {
        String id = createIntent("FieldsIntent");

        ResponseEntity<Map> resp = rest.getForEntity(
                base + "/intent/" + id + "?fields=lifecycleStatus,name", Map.class);
        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = resp.getBody();
        assertNotNull(body.get("id"));       // always preserved
        assertNotNull(body.get("href"));     // always preserved
        assertNotNull(body.get("lifecycleStatus"));
        assertNotNull(body.get("name"));
        assertNull(body.get("@type"));       // not requested, should be absent
    }

    // ── Pagination + list ─────────────────────────────────────────────────────

    @Test
    void list_xTotalCount_andPagination() {
        // Create 3 intents
        for (int i = 0; i < 3; i++) createIntent("ListIntent-" + i);

        ResponseEntity<List> resp = rest.exchange(
                base + "/intent?limit=2&offset=0", HttpMethod.GET, null, List.class);
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(Integer.parseInt(resp.getHeaders().getFirst("X-Total-Count")) >= 3);
        assertTrue(resp.getBody().size() <= 2);
    }

    // ── IntentReport ─────────────────────────────────────────────────────────

    @Test
    void intentReports_listAndGet() {
        String id = createActiveIntent(BANDWIDTH_EXPRESSION);
        // Post passing observation
        Map<String, Object> obs = Map.of(
                "metricUri", "http://example.org/metrics/bandwidth",
                "value", 90.0);
        rest.postForEntity(base + "/intent/" + id + "/observation", obs, Map.class);

        // Wait for at least one report
        pollForReport(id, "Fulfilled", 10);

        // GET report list
        ResponseEntity<List> list = rest.exchange(
                base + "/intent/" + id + "/intentReport", HttpMethod.GET, null, List.class);
        assertEquals(200, list.getStatusCode().value());
        assertFalse(list.getBody().isEmpty());

        // GET specific report
        Map<?, ?> firstReport = (Map<?, ?>) list.getBody().get(0);
        String reportId = (String) firstReport.get("id");
        ResponseEntity<Map> single = rest.getForEntity(
                base + "/intent/" + id + "/intentReport/" + reportId, Map.class);
        assertEquals(200, single.getStatusCode().value());
        assertEquals(reportId, single.getBody().get("id"));
    }

    @Test
    void intentReport_parentNotFound_returns404() {
        ResponseEntity<Map> resp = rest.exchange(
                base + "/intent/00000000-0000-0000-0000-000000000000/intentReport",
                HttpMethod.GET, null, Map.class);
        assertEquals(404, resp.getStatusCode().value());
    }

    // ── IntentSpecification ───────────────────────────────────────────────────

    @Test
    void intentSpec_fullCrud() {
        // Create
        Map<String, Object> body = Map.of("name", "TestSpec", "@type", "IntentSpecification");
        ResponseEntity<Map> created = rest.postForEntity(base + "/intentSpecification", body, Map.class);
        assertEquals(201, created.getStatusCode().value());
        String specId = (String) created.getBody().get("id");
        assertNotNull(specId);

        // Get
        ResponseEntity<Map> found = rest.getForEntity(base + "/intentSpecification/" + specId, Map.class);
        assertEquals(200, found.getStatusCode().value());
        assertEquals("TestSpec", found.getBody().get("name"));

        // Patch
        ResponseEntity<Map> patched = patchSpec(specId, Map.of("name", "RenamedSpec"));
        assertEquals(200, patched.getStatusCode().value());
        assertEquals("RenamedSpec", patched.getBody().get("name"));

        // List
        ResponseEntity<List> list = rest.exchange(
                base + "/intentSpecification", HttpMethod.GET, null, List.class);
        assertEquals(200, list.getStatusCode().value());
        assertTrue(list.getHeaders().containsKey("X-Total-Count"));

        // Delete
        ResponseEntity<Void> del = rest.exchange(
                base + "/intentSpecification/" + specId, HttpMethod.DELETE, null, Void.class);
        assertEquals(204, del.getStatusCode().value());

        // Get after delete → 404
        ResponseEntity<Map> gone = rest.getForEntity(base + "/intentSpecification/" + specId, Map.class);
        assertEquals(404, gone.getStatusCode().value());
    }

    // ── Hub ───────────────────────────────────────────────────────────────────

    @Test
    void hub_registerAndUnregister() {
        // Register
        Map<String, Object> hubBody = Map.of("callback", "http://test.example.com/notify");
        ResponseEntity<Map> reg = rest.postForEntity(base + "/hub", hubBody, Map.class);
        assertEquals(201, reg.getStatusCode().value());
        String hubId = (String) reg.getBody().get("id");
        assertNotNull(hubId);
        assertEquals("http://test.example.com/notify", reg.getBody().get("callback"));

        // List
        ResponseEntity<List> list = rest.exchange(base + "/hub", HttpMethod.GET, null, List.class);
        assertEquals(200, list.getStatusCode().value());
        assertFalse(list.getBody().isEmpty());

        // Get by id
        ResponseEntity<Map> got = rest.getForEntity(base + "/hub/" + hubId, Map.class);
        assertEquals(200, got.getStatusCode().value());

        // Unregister
        ResponseEntity<Void> del = rest.exchange(base + "/hub/" + hubId, HttpMethod.DELETE, null, Void.class);
        assertEquals(204, del.getStatusCode().value());

        // Verify gone
        ResponseEntity<Map> gone = rest.getForEntity(base + "/hub/" + hubId, Map.class);
        assertEquals(404, gone.getStatusCode().value());
    }

    @Test
    void hub_missingCallback_returns400() {
        ResponseEntity<Map> resp = rest.postForEntity(base + "/hub", Map.of(), Map.class);
        assertEquals(400, resp.getStatusCode().value());
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    @Test
    void getIntent_notFound_returns404_withTmfErrorShape() {
        ResponseEntity<Map> resp = rest.getForEntity(
                base + "/intent/ffffffff-ffff-ffff-ffff-ffffffffffff", Map.class);
        assertEquals(404, resp.getStatusCode().value());
        assertEquals("Error", resp.getBody().get("@type"));
        assertNotNull(resp.getBody().get("code"));
        assertNotNull(resp.getBody().get("reason"));
    }

    @Test
    void patchIntent_unknownState_returns422() {
        String id = createIntent("StateTest");
        // PATCH to non-existent state
        ResponseEntity<Map> resp = patchIntent(id, Map.of("lifecycleStatus", "LIMBO"));
        assertEquals(422, resp.getStatusCode().value());
    }

    @Test
    void observation_forNonExistentIntent_returns404() {
        Map<String, Object> obs = Map.of("metricUri", "http://ex.org/m", "value", 10);
        ResponseEntity<Map> resp = rest.postForEntity(
                base + "/intent/eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee/observation",
                obs, Map.class);
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void observation_missingMetricUri_returns400() {
        String id = createIntent("ObsTest");
        ResponseEntity<Map> resp = rest.postForEntity(
                base + "/intent/" + id + "/observation",
                Map.of("value", 42), Map.class);
        assertEquals(400, resp.getStatusCode().value());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createIntent(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("@type", "Intent");
        body.put("expression", Map.of("@type", "TurtleExpression", "expressionValue", BANDWIDTH_EXPRESSION));
        ResponseEntity<Map> resp = rest.postForEntity(base + "/intent", body, Map.class);
        assertEquals(201, resp.getStatusCode().value());
        return (String) resp.getBody().get("id");
    }

    private String createActiveIntent(String expressionTurtle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ActiveIntent-" + System.nanoTime());
        body.put("@type", "Intent");
        body.put("expression", Map.of("@type", "TurtleExpression", "expressionValue", expressionTurtle));
        ResponseEntity<Map> resp = rest.postForEntity(base + "/intent", body, Map.class);
        String id = (String) resp.getBody().get("id");
        patchIntent(id, Map.of("lifecycleStatus", "ACTIVE"));
        return id;
    }

    private ResponseEntity<Map> patchIntent(String id, Map<String, Object> patch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base + "/intent/" + id, HttpMethod.PATCH,
                new HttpEntity<>(patch, headers), Map.class);
    }

    private ResponseEntity<Map> patchSpec(String id, Map<String, Object> patch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base + "/intentSpecification/" + id, HttpMethod.PATCH,
                new HttpEntity<>(patch, headers), Map.class);
    }

    /**
     * Poll GET /intent/{id}/intentReport until a report with the expected state appears,
     * or until maxSeconds elapses. Returns the matching report or null on timeout.
     */
    @SuppressWarnings("unchecked")
    private Map<?, ?> pollForReport(String intentId, String expectedState, int maxSeconds) {
        String url = base + "/intent/" + intentId + "/intentReport";
        var ref = new Object() { Map<?, ?> found = null; };
        try {
            Awaitility.await()
                    .atMost(maxSeconds, TimeUnit.SECONDS)
                    .pollInterval(250, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        ResponseEntity<List> resp = rest.exchange(url, HttpMethod.GET, null, List.class);
                        if (resp.getBody() == null || resp.getBody().isEmpty()) return false;
                        for (Object item : resp.getBody()) {
                            Map<?, ?> report = (Map<?, ?>) item;
                            if (expectedState.equals(report.get("intentHandlingState"))) {
                                ref.found = report;
                                return true;
                            }
                        }
                        return false;
                    });
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            return null;
        }
        return ref.found;
    }
}
