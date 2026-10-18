package org.tmforum.intent.unit;

import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.handler.ReportBuilder;
import org.tmforum.intent.handler.TurtleEvaluator;
import org.tmforum.intent.model.EvaluationResult;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** TR290 §4 reports built from the real seed intent and PON inventory. */
class ReportBuilderTest {

    private static final String REPORT = "http://tmforum.org/api/v5/reports/r1";
    private static final String BBF = "http://broadband-forum.org/Intent#";

    private static final String PFX = """
            PREFIX icm: <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/>
            PREFIX imo: <http://tio.models.tmforum.org/tio/v3.6.0/IntentManagementOntology/>
            PREFIX met: <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/>
            PREFIX quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/>
            PREFIX bbf: <http://broadband-forum.org/Intent#>
            PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            PREFIX tmf: <http://tmforum.org/api/v5/>
            """;

    private TurtleEvaluator evaluator;
    private ReportBuilder builder;
    private String intent;
    private String inventory;

    @BeforeEach
    void setUp() throws Exception {
        evaluator = new TurtleEvaluator();
        builder = new ReportBuilder();
        intent = Files.readString(Path.of("seed_data/hsionlyintent_v0.5.ttl"));
        inventory = Files.readString(Path.of("BBF_access/pon_resource_onto.ttl"))
                + "\n" + Files.readString(Path.of("BBF_access/pon_resource_data.ttl"));
    }

    /** Observations as (metric, value, unit) triples; an empty unit means a value without unit. */
    private static String observations(String... metricValueUnit) {
        StringBuilder sb = new StringBuilder("""
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix met: <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
                """);
        for (int i = 0; i < metricValueUnit.length; i += 3) {
            sb.append("<http://example.org/obs/").append(i).append("> a met:Observation ;\n")
              .append("  met:observedMetric <").append(BBF).append(metricValueUnit[i]).append("> ;\n")
              .append("  rdf:value \"").append(metricValueUnit[i + 1]).append("\"^^xsd:decimal ;\n");
            if (!metricValueUnit[i + 2].isEmpty()) {
                sb.append("  quan:unit \"").append(metricValueUnit[i + 2]).append("\" ;\n");
            }
            sb.append("  met:obtainedAt \"2026-06-11T10:00:0").append(i / 3).append("Z\"^^xsd:dateTime .\n");
        }
        return sb.toString();
    }

    private static final String[] ALL_OK = {
            "DownstreamBandwidthMetric", "150", "Mbps", "UpstreamBandwidthMetric", "30", "Mbps",
            "LatencyMetric", "8", "ms", "JitterMetric", "1.2", "ms", "PacketLossMetric", "0.02", "percent"};
    private static final String[] BANDWIDTH_ONLY = {
            "DownstreamBandwidthMetric", "150", "Mbps", "UpstreamBandwidthMetric", "30", "Mbps"};

    private Model report(String intentTtl, String... obs) {
        EvaluationResult r = evaluator.evaluate(intentTtl, obs.length == 0 ? null : observations(obs), inventory);
        return builder.build(r.report(), new ReportBuilder.ReportContext(REPORT, r.intentHandlingState(), 3,
                Instant.parse("2026-06-11T10:00:00Z")));
    }

    private boolean ask(Model m, String where) {
        return QueryExecutionFactory.create(PFX + "ASK { " + where + " }", m).execAsk();
    }

    private int count(Model m, String where) {
        var rs = QueryExecutionFactory.create(PFX + "SELECT (COUNT(*) AS ?n) WHERE { " + where + " }", m).execSelect();
        return rs.next().getLiteral("n").getInt();
    }

    // ── Structure and results ─────────────────────────────────────────────────

    @Test
    void fulfilled_everyReportedElementIsTrue_andCarriesTheReportHeader() {
        Model m = report(intent, ALL_OK);

        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:about bbf:HSIIntent ; icm:result true ; "
                + "imo:handlingState imo:StateCompliant ; icm:reportNumber 3"));
        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:reportGenerated [ <http://www.w3.org/2006/time#inXSDDateTimeStamp> "
                + "\"2026-06-11T10:00:00Z\"^^xsd:dateTime ]"));
        assertEquals(0, count(m, "?r icm:about ?x ; icm:result false"), "nothing is false when Fulfilled");
        assertEquals(5, count(m, "?r a icm:ConditionReport"));
    }

    @Test
    void partialObservations_showWhichConditionsAreNotMet_andWhy() {
        Model m = report(intent, BANDWIDTH_ONLY);

        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:result false ; imo:handlingState imo:StateDegraded"));
        // bandwidth conditions true, latency/jitter/loss false with a reason
        assertTrue(ask(m, "?c a icm:ConditionReport ; icm:about bbf:HSI_DownstreamBandwidthCondition ; icm:result true"));
        assertTrue(ask(m, "?c a icm:ConditionReport ; icm:about bbf:HSI_UpstreamBandwidthCondition ; icm:result true"));
        assertTrue(ask(m, "?c icm:about bbf:HSI_LatencyCondition ; icm:result false ; "
                + "icm:reason [ a icm:Reason ; rdfs:label \"no observation: LatencyMetric\" ]"));
        assertTrue(ask(m, "?c icm:about bbf:HSI_JitterCondition ; icm:result false"));
        assertTrue(ask(m, "?c icm:about bbf:HSI_PacketLossCondition ; icm:result false"));
        // the three delivery expectations still comply
        assertEquals(3, count(m, "?e a icm:DeliveryExpectationReport ; icm:result true"));
    }

    @Test
    void aViolatedCondition_isExplainedByItsValues() {
        Model m = report(intent, "DownstreamBandwidthMetric", "150", "Mbps", "UpstreamBandwidthMetric", "30", "Mbps",
                "LatencyMetric", "40", "ms", "JitterMetric", "1.2", "ms", "PacketLossMetric", "0.02", "percent");

        assertTrue(ask(m, "?c icm:about bbf:HSI_LatencyCondition ; icm:result false ; "
                + "icm:reason [ rdfs:label \"40 ms < 25 ms is false\" ]"));
    }

    @Test
    void resultFrom_followsTheIntentHierarchy() {
        Model m = report(intent, ALL_OK);

        // intent <- 5 expectations (UNI, S-tag, C-tag, performance, monitoring) ; performance <- 5 conditions
        assertEquals(5, count(m, "?ir a icm:IntentReport ; icm:resultFrom ?e . ?e icm:about ?x"));
        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:resultFrom ?e . ?e icm:about bbf:HSIPerformanceExpectation"));
        assertEquals(5, count(m, "?perf icm:about bbf:HSIPerformanceExpectation ; icm:resultFrom ?c . "
                + "?c a icm:ConditionReport"));
        assertTrue(ask(m, "?e a icm:DeliveryExpectationReport ; icm:about bbf:HSIServiceProvisioningExpectation"));
        assertTrue(ask(m, "?e a icm:PropertyExpectationReport ; icm:about bbf:HSIPerformanceExpectation"));
        assertTrue(ask(m, "?e a icm:ReportingExpectationReport ; icm:about bbf:HSIMonitoringExpectation ; icm:result true"));
        assertEquals(0, count(m, "?e a icm:ExpectationReport"), "there is no composite expectation any more");
    }

    @Test
    void deliveryReports_listTheResourcesChosen_inTargetReports() {
        Model m = report(intent, ALL_OK);

        assertTrue(ask(m, "?e icm:about bbf:HSIServiceProvisioningExpectation ; icm:targetReport ?t . "
                + "?t a icm:TargetReport ; icm:about bbf:SelectedUNIInterface ; icm:targetCount 1 ; rdfs:member pon:UNI-001-1"));
        assertTrue(ask(m, "?e icm:about bbf:HSISTagProvisioningExpectation ; icm:targetReport ?t . "
                + "?t icm:targetCount 1 ; rdfs:member pon:SVLAN-N-HSI"));
        assertTrue(ask(m, "?e icm:about bbf:HSICTAGProvisioningExpectation ; icm:targetReport ?t . "
                + "?t icm:targetCount 1 ; rdfs:member pon:CTAG-N-001"));
    }

    @Test
    void aFailedDeliveryHasAReasonAndNoChosenResource() {
        String noFreeUni = inventory;
        inventory = noFreeUni.replace("pon:inUse         false ;", "pon:inUse         true ;");
        Model m = report(intent, ALL_OK);

        assertTrue(ask(m, "?e a icm:DeliveryExpectationReport ; icm:about bbf:HSIServiceProvisioningExpectation ; "
                + "icm:result false ; icm:reason [ rdfs:label ?why ] . FILTER(CONTAINS(?why, \"no candidate of type UNIPort\"))"));
        assertFalse(ask(m, "?e icm:about bbf:HSIServiceProvisioningExpectation ; icm:targetReport ?t . ?t rdfs:member ?x"));
    }

    // ── Scope (TR290 §4.2) and observations (§4.6) ────────────────────────────

    @Test
    void onlyElementsInTheReportingTargetAreReported_andTheIntentAlwaysIs() {
        // Keep just the intent and the five conditions in the reporting target
        String narrowed = intent.replaceFirst("(?s)bbf:HSIMonitoringTarget a icm:Target ;.*?bbf:HSIServiceTarget \\.",
                "bbf:HSIMonitoringTarget a icm:Target ; rdfs:member bbf:HSI_DownstreamBandwidthCondition , "
                        + "bbf:HSI_LatencyCondition .");
        Model m = report(narrowed, ALL_OK);

        assertTrue(ask(m, "?ir a icm:IntentReport"), "the intent is always reported");
        assertEquals(2, count(m, "?c a icm:ConditionReport"));
        assertEquals(0, count(m, "?e a ?t . FILTER(?t IN (icm:DeliveryExpectationReport, icm:PropertyExpectationReport))"));
        // contributors are the nearest reported descendants
        assertEquals(2, count(m, "?ir a icm:IntentReport ; icm:resultFrom ?c . ?c a icm:ConditionReport"));
    }

    @Test
    void withoutAReportingExpectation_onlyTheIntentReportExists() {
        String noReporting = intent.replaceAll("(?s)bbf:HSIMonitoringExpectation a icm:ReportingExpectation ;.*?bbf:Periodic15m \\] \\.", "")
                .replace("bbf:HSIMonitoringExpectation )", ")");
        Model m = report(noReporting, ALL_OK);

        assertEquals(1, count(m, "?r icm:about ?x"));
        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:result true"));
    }

    @Test
    void observedValues_areOnlyIncludedForAnObservationReportingExpectation() {
        Model plain = report(intent, ALL_OK);
        assertEquals(0, count(plain, "?c met:observed ?o"), "a basic reporting expectation omits observations");

        String observed = intent.replace("a icm:ReportingExpectation", "a icm:ObservationReportingExpectation");
        Model m = report(observed, ALL_OK);
        assertEquals(5, count(m, "?c a icm:ConditionReport ; met:observed ?o"));
        assertTrue(ask(m, "?c icm:about bbf:HSI_LatencyCondition ; met:observed [ a met:Observation ; "
                + "met:observedMetric bbf:LatencyMetric ; rdf:value 8 ; quan:unit \"ms\" ]"));
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    @Test
    void theReportIsLinkedToItsTmfIntentReport_andSerialisesToTurtleThatParsesBack() {
        Model m = report(intent, ALL_OK);

        assertTrue(ask(m, "<" + REPORT + "> tmf:icmReport ?ir . ?ir a icm:IntentReport"));
        String ttl = ReportBuilder.toTurtle(m);
        Model back = ModelFactory.createDefaultModel();
        back.read(new StringReader(ttl), null, "TURTLE");
        assertTrue(back.isIsomorphicWith(m));
    }

    @Test
    void anExpressionWithoutAnIntentHasNothingToReportOn() {
        String metricOnly = """
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
                _:c a quan:quansmaller ; rdf:first <http://example.org/m> ; rdf:rest [ rdf:first _:b ] .
                _:b rdf:value "10"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(metricOnly, null, null);
        assertNull(builder.build(r.report(), new ReportBuilder.ReportContext(REPORT, r.intentHandlingState(), 1, Instant.now())));
    }
}
