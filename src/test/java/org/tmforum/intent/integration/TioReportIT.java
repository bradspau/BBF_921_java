package org.tmforum.intent.integration;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ReadWrite;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TR290 intent reports through the running application: the seed HSI intent against the PON inventory,
 * with the metric observations posted back to back as a client would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "intent.base-url=http://localhost:8000/tmf-api/intentManagement/v5",
        "intent.resource-inventory=BBF_access/pon_resource_data.ttl",
        "intent.resource-ontology=BBF_access/pon_resource_onto.ttl"
})
class TioReportIT {

    private static final String BBF = "http://broadband-forum.org/Intent#";
    private static final String PFX = """
            PREFIX icm: <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/>
            PREFIX tmf: <http://tmforum.org/api/v5/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;

    @TempDir
    static Path tdb2Dir;

    @DynamicPropertySource
    static void configureDataset(DynamicPropertyRegistry registry) {
        registry.add("intent.tdb2-path", () -> tdb2Dir.toString());
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired Dataset dataset;

    private String base() { return "http://localhost:" + port + "/tmf-api/intentManagement/v5"; }

    private String createActiveSeedIntent() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "TR290 report test", "@type", "Intent",
                "expression", Map.of("@type", "TurtleExpression",
                        "expressionValue", Files.readString(Path.of("seed_data/hsionlyintent_v0.5.ttl"))));
        String id = (String) rest.postForEntity(base() + "/intent", body, Map.class).getBody().get("id");
        rest.exchange(base() + "/intent/" + id, HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(Map.of("lifecycleStatus", "ACTIVE")), Map.class);
        return id;
    }

    private void post(String id, String metric, double value) {
        rest.postForEntity(base() + "/intent/" + id + "/observation",
                Map.of("metricUri", BBF + metric, "value", value), Map.class);
    }

    private List<Map<String, String>> select(String body) {
        dataset.begin(ReadWrite.READ);
        try {
            var rs = QueryExecutionFactory.create(PFX + body, dataset).execSelect();
            List<Map<String, String>> rows = new ArrayList<>();
            while (rs.hasNext()) {
                var qs = rs.next();
                Map<String, String> row = new HashMap<>();
                rs.getResultVars().forEach(v -> { if (qs.get(v) != null) row.put(v, qs.get(v).toString()); });
                rows.add(row);
            }
            return rows;
        } finally {
            dataset.end();
        }
    }

    @Test
    void reportsAreNumberedInOrder_andTheLatestOneShowsEveryResultTrueOnceFulfilled() throws Exception {
        String id = createActiveSeedIntent();
        // Post all five metrics back to back: several evaluations are triggered at once
        post(id, "DownstreamBandwidthMetric", 150.0);
        post(id, "UpstreamBandwidthMetric", 30.0);
        post(id, "LatencyMetric", 8.0);
        post(id, "JitterMetric", 1.2);
        post(id, "PacketLossMetric", 0.02);

        String intentNode = "http://tmforum.org/api/v5/intents/" + id;
        // Wait until the report with the highest number says the intent is compliant
        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS).until(() -> {
            ResponseEntity<List> r = rest.exchange(base() + "/intent/" + id + "/intentReport", HttpMethod.GET, null, List.class);
            return r.getBody() != null && r.getBody().stream()
                    .anyMatch(x -> "Fulfilled".equals(((Map<?, ?>) x).get("intentHandlingState")));
        });
        Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS).until(() -> {
            List<Map<String, String>> latest = select("SELECT ?r WHERE { GRAPH ?g { ?rep tmf:parentIntent <" + intentNode + "> ; "
                    + "tmf:icmReport ?ir . ?ir icm:result ?r ; icm:reportNumber ?n } } ORDER BY DESC(?n) LIMIT 1");
            return !latest.isEmpty() && latest.get(0).get("r").startsWith("true");
        });

        // Report numbers are unique and run 1..N with no gaps
        List<Map<String, String>> numbers = select("SELECT ?n WHERE { GRAPH ?g { ?rep tmf:parentIntent <" + intentNode + "> ; "
                + "tmf:icmReport ?ir . ?ir icm:reportNumber ?n } }");
        List<Integer> sorted = numbers.stream().map(m -> Integer.parseInt(m.get("n").split("\\^\\^")[0]))
                .sorted().toList();
        assertFalse(sorted.isEmpty());
        for (int i = 0; i < sorted.size(); i++) assertEquals(i + 1, sorted.get(i), "report numbers: " + sorted);

        // The newest report is compliant all the way down (compared by value: numbers are xsd:positiveInteger)
        int newest = sorted.get(sorted.size() - 1);
        String inNewest = "SELECT ?x ?res ?type WHERE { GRAPH ?g { ?rep tmf:parentIntent <" + intentNode + "> ; "
                + "tmf:icmReport ?ir . ?ir icm:reportNumber ?n . FILTER (?n = " + newest + ") "
                + "?ir icm:resultFrom* ?r . ?r a ?type ; icm:about ?x ; icm:result ?res } }";
        List<Map<String, String>> elements = select(inNewest);
        assertEquals(12, elements.size(), "intent + composite + 5 expectations + 5 conditions: " + elements);
        List<Map<String, String>> falseOnes = elements.stream().filter(e -> e.get("res").startsWith("false")).toList();
        assertTrue(falseOnes.isEmpty(), "false elements in newest report: " + falseOnes);
        assertEquals(5, elements.stream().filter(e -> e.get("type").endsWith("ConditionReport")).count());
    }

    @Test
    void theReportResourceCarriesTheTr290ReportAsItsTurtleExpression() throws Exception {
        String id = createActiveSeedIntent();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS).until(() ->
                rest.getForEntity(base() + "/intent/" + id + "/intentReport", List.class).getBody() != null
                        && !rest.getForEntity(base() + "/intent/" + id + "/intentReport", List.class).getBody().isEmpty());

        Map<?, ?> report = (Map<?, ?>) rest.getForEntity(base() + "/intent/" + id + "/intentReport", List.class).getBody().get(0);
        Map<?, ?> expr = (Map<?, ?>) report.get("expression");
        assertEquals("TurtleExpression", expr.get("@type"));
        String ttl = (String) expr.get("expressionValue");
        assertTrue(ttl.contains("IntentReport") && ttl.contains("icm:result"), ttl);
    }
}
