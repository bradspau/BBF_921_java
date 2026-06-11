package org.tmforum.intent.integration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the TMF921 negotiation flows:
 *
 * Flow 1 — ProbeIntent auto-transition:
 *   ACKNOWLEDGED → ACTIVE (when Fulfilled) or TERMINATED (when Degraded)
 *
 * Flow 3 — Best/Propose bound substitution:
 *   When an intent is Degraded, failed condition bounds are patched with observed values,
 *   and the intent fires IntentAttributeValueChangeEvent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "intent.base-url=http://localhost:8000/tmf-api/intentManagement/v5")
class NegotiationIT {

    @TempDir
    static Path tdb2Dir;

    @DynamicPropertySource
    static void configureDataset(DynamicPropertyRegistry registry) {
        registry.add("intent.tdb2-path", () -> tdb2Dir.toString());
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    private String base;

    @BeforeEach
    void setBase() {
        base = "http://localhost:" + port + "/tmf-api/intentManagement/v5";
    }

    // ── Flow 1: ProbeIntent ───────────────────────────────────────────────────

    @Test
    void probeIntent_passingExpression_autoTransitions_toActive() {
        // Expression with direct values that always pass (no metric ref)
        String turtle = """
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .

                _:cond a quan:quanatLeast ;
                    rdf:first _:val ;
                    rdf:rest [ rdf:first _:bound ] .
                _:val   rdf:value "80"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                """;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ProbePassing");
        body.put("@type", "ProbeIntent");
        body.put("expression", Map.of("@type", "TurtleExpression", "expressionValue", turtle));

        ResponseEntity<Map> resp = rest.postForEntity(base + "/intent", body, Map.class);
        assertEquals(201, resp.getStatusCode().value());
        String id = (String) resp.getBody().get("id");
        assertEquals("ACKNOWLEDGED", resp.getBody().get("lifecycleStatus"));

        // Flow 1: after Fulfilled evaluation → ACKNOWLEDGED → ACTIVE
        pollUntilStatus(id, "ACTIVE", 10);

        ResponseEntity<Map> intent = rest.getForEntity(base + "/intent/" + id, Map.class);
        assertEquals("ACTIVE", intent.getBody().get("lifecycleStatus"));
    }

    @Test
    void probeIntent_failingExpression_autoTransitions_toTerminated() {
        // Expression that always fails (value < bound, no metric to inject)
        String turtle = """
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .

                _:cond a quan:quanatLeast ;
                    rdf:first _:val ;
                    rdf:rest [ rdf:first _:bound ] .
                _:val   rdf:value "20"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                """;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ProbeFailing");
        body.put("@type", "ProbeIntent");
        body.put("expression", Map.of("@type", "TurtleExpression", "expressionValue", turtle));

        ResponseEntity<Map> resp = rest.postForEntity(base + "/intent", body, Map.class);
        assertEquals(201, resp.getStatusCode().value());
        String id = (String) resp.getBody().get("id");

        // Flow 1: after Degraded evaluation → ACKNOWLEDGED → TERMINATED
        pollUntilStatus(id, "TERMINATED", 10);

        ResponseEntity<Map> intent = rest.getForEntity(base + "/intent/" + id, Map.class);
        assertEquals("TERMINATED", intent.getBody().get("lifecycleStatus"));
    }

    @Test
    void probeIntent_noExpression_remainsAcknowledged() {
        // ProbeIntent with empty expression — evaluation skips, no transition
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ProbeNoExpr");
        body.put("@type", "ProbeIntent");

        ResponseEntity<Map> resp = rest.postForEntity(base + "/intent", body, Map.class);
        assertEquals(201, resp.getStatusCode().value());
        String id = (String) resp.getBody().get("id");

        // Wait briefly, then confirm ACKNOWLEDGED unchanged
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        ResponseEntity<Map> intent = rest.getForEntity(base + "/intent/" + id, Map.class);
        assertEquals("ACKNOWLEDGED", intent.getBody().get("lifecycleStatus"));
    }

    // ── Flow 3: Best/Propose bound substitution ───────────────────────────────

    @Test
    void bestPropose_failedCondition_patchesExpressionWithObservedValue() {
        // Expression: bound=100, metric ref. Inject observation=30 (fails). Flow 3 should
        // patch the expression so the new bound becomes 30.
        String metricUri = "http://example.org/metrics/latency";
        String turtle = """
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .

                _:cond a quan:quansmaller ;
                    rdf:first <http://example.org/metrics/latency> ;
                    rdf:rest [ rdf:first _:bound ] .
                _:bound rdf:value "10"^^xsd:decimal .
                """;
        // "smaller" condition: observed < bound. With observed=50 and bound=10, this FAILS.
        // Flow 3 should substitute bound with observed=50.

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "BestProposeIntent");
        body.put("@type", "Intent");
        body.put("expression", Map.of("@type", "TurtleExpression", "expressionValue", turtle));

        ResponseEntity<Map> created = rest.postForEntity(base + "/intent", body, Map.class);
        String id = (String) created.getBody().get("id");

        // Activate
        patchIntent(id, Map.of("lifecycleStatus", "ACTIVE"));

        // Inject failing observation (50 > bound=10, so "smaller" fails)
        Map<String, Object> obs = Map.of("metricUri", metricUri, "value", 50.0);
        ResponseEntity<Map> obsResp = rest.postForEntity(
                base + "/intent/" + id + "/observation", obs, Map.class);
        assertEquals(201, obsResp.getStatusCode().value());

        // Wait for Degraded report (evaluation triggers Flow 3 bound substitution)
        Map<?, ?> report = pollForReport(id, "Degraded", 10);
        assertNotNull(report, "Expected Degraded report after failing observation");

        // Allow time for Flow 3 expression patch to be written
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        // Check that expression was patched (bound should now be around 50, not 10)
        ResponseEntity<Map> intent = rest.getForEntity(base + "/intent/" + id, Map.class);
        Map<?, ?> expr = (Map<?, ?>) intent.getBody().get("expression");
        assertNotNull(expr);
        String newTurtle = (String) expr.get("expressionValue");
        assertNotNull(newTurtle);
        // The original bound "10" should no longer be the bound (was replaced with observed=50)
        // We check the expression changed (contains "50" as the new bound)
        assertTrue(newTurtle.contains("50"), "Expected bound to be substituted with observed value 50");
    }

    @Test
    void bestPropose_onlyForIntentType_notForProbeIntent() {
        // ProbeIntent should NOT have its expression patched by Flow 3
        String turtle = """
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .

                _:cond a quan:quanatLeast ;
                    rdf:first _:val ; rdf:rest [ rdf:first _:bound ] .
                _:val   rdf:value "10"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                """;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "ProbeNoFlow3");
        body.put("@type", "ProbeIntent");
        body.put("expression", Map.of("@type", "TurtleExpression", "expressionValue", turtle));

        ResponseEntity<Map> resp = rest.postForEntity(base + "/intent", body, Map.class);
        String id = (String) resp.getBody().get("id");

        // ProbeIntent auto-transitions to TERMINATED (Degraded)
        pollUntilStatus(id, "TERMINATED", 8);

        // Expression should NOT have changed (Flow 3 skips ProbeIntent)
        ResponseEntity<Map> intent = rest.getForEntity(base + "/intent/" + id, Map.class);
        Map<?, ?> expr = (Map<?, ?>) intent.getBody().get("expression");
        String expressionValue = (String) expr.get("expressionValue");
        // Original turtle has bound "50" — should still be 50 (not patched to 10)
        assertTrue(expressionValue.contains("50"), "ProbeIntent expression should not be patched");
    }

    @Test
    void degradedToActive_flowTwo_afterFulfilledReEvaluation() {
        // Metric-based expression: initially Degraded (no observation), then Fulfilled after POST
        String metricUri = "http://example.org/metrics/rtt";
        String turtle = """
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .

                _:cond a quan:quansmaller ;
                    rdf:first <http://example.org/metrics/rtt> ;
                    rdf:rest [ rdf:first _:bound ] .
                _:bound rdf:value "100"^^xsd:decimal .
                """;

        // Create + activate (metric-based → Degraded immediately, no observations)
        String id = createActiveIntent(turtle);

        // Wait for initial Degraded report
        Map<?, ?> degraded = pollForReport(id, "Degraded", 8);
        assertNotNull(degraded);

        // Manually set to DEGRADED status (simulating FSM state after degraded evaluation)
        // In the real flow, the evaluator doesn't auto-set status to DEGRADED unless specifically
        // triggered by Flow 2 logic. We explicitly PATCH to DEGRADED here.
        patchIntent(id, Map.of("lifecycleStatus", "DEGRADED"));

        // Now inject a passing observation (rtt=30 < 100)
        Map<String, Object> obs = Map.of("metricUri", metricUri, "value", 30.0);
        rest.postForEntity(base + "/intent/" + id + "/observation", obs, Map.class);

        // Flow 2: DEGRADED → ACTIVE after Fulfilled evaluation
        pollUntilStatus(id, "ACTIVE", 10);

        ResponseEntity<Map> intent = rest.getForEntity(base + "/intent/" + id, Map.class);
        assertEquals("ACTIVE", intent.getBody().get("lifecycleStatus"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createActiveIntent(String turtle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "NegotiationIntent-" + System.nanoTime());
        body.put("@type", "Intent");
        body.put("expression", Map.of("@type", "TurtleExpression", "expressionValue", turtle));
        ResponseEntity<Map> resp = rest.postForEntity(base + "/intent", body, Map.class);
        String id = (String) resp.getBody().get("id");
        patchIntent(id, Map.of("lifecycleStatus", "ACTIVE"));
        return id;
    }

    private ResponseEntity<Map> patchIntent(String id, Map<String, Object> patch) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(base + "/intent/" + id, HttpMethod.PATCH,
                new HttpEntity<>(patch, h), Map.class);
    }

    private void pollUntilStatus(String intentId, String expectedStatus, int maxSeconds) {
        Awaitility.await()
                .atMost(maxSeconds, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .until(() -> {
                    ResponseEntity<Map> r = rest.getForEntity(base + "/intent/" + intentId, Map.class);
                    return expectedStatus.equals(r.getBody() != null ? r.getBody().get("lifecycleStatus") : null);
                });
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> pollForReport(String intentId, String expectedState, int maxSeconds) {
        String url = base + "/intent/" + intentId + "/intentReport";
        var ref = new Object() { Map<?, ?> found = null; };
        try {
            Awaitility.await()
                    .atMost(maxSeconds, TimeUnit.SECONDS)
                    .pollInterval(250, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        ResponseEntity<List> r = rest.exchange(url, HttpMethod.GET, null, List.class);
                        if (r.getBody() == null || r.getBody().isEmpty()) return false;
                        for (Object item : r.getBody()) {
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
