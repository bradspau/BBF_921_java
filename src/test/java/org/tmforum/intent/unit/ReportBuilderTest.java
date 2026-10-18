package org.tmforum.intent.unit;

import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tmforum.intent.engine.ResultGraphWriter;
import org.tmforum.intent.handler.ReportBuilder;
import org.tmforum.intent.handler.TurtleEvaluator;
import org.tmforum.intent.model.EvaluationResult;

import java.io.StringReader;
import java.time.Instant;

import static org.tmforum.intent.unit.TestInputs.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TR290 §4 reports built from the real seed intent and PON inventory, over both builder inputs: the evaluator's
 * in-memory tree (TREE) and the expression plus the shared result graph of the same evaluation (GRAPH).
 */
class ReportBuilderTest {

    enum Path { TREE, GRAPH }

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
        intent = TestInputs.seedIntent();
        inventory = TestInputs.inventory();
    }


    private Model report(Path path, String intentTtl, String... obs) {
        String obsTtl = obs.length == 0 ? null : observations(obs);
        EvaluationResult r = evaluator.evaluate(intentTtl, obsTtl, inventory);
        return build(path, r, intentTtl, obsTtl, new ReportBuilder.ReportContext(REPORT, r.intentHandlingState(), 3,
                Instant.parse("2026-06-11T10:00:00Z")));
    }

    private Model build(Path path, EvaluationResult r, String intentTtl, String obsTtl, ReportBuilder.ReportContext ctx) {
        if (path == Path.TREE) return builder.build(r.report(), ctx);
        Model resultGraph = ResultGraphWriter.write(r.report(), obsTtl == null ? null : parse(obsTtl));
        return builder.build(parse(intentTtl), resultGraph, ctx);
    }

    private static Model parse(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(ttl), "http://tmforum.org/", "TURTLE");
        return m;
    }

    private boolean ask(Model m, String where) {
        return QueryExecutionFactory.create(PFX + "ASK { " + where + " }", m).execAsk();
    }

    private int count(Model m, String where) {
        var rs = QueryExecutionFactory.create(PFX + "SELECT (COUNT(*) AS ?n) WHERE { " + where + " }", m).execSelect();
        return rs.next().getLiteral("n").getInt();
    }

    // ── Structure and results ─────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Path.class)
    void fulfilled_everyReportedElementIsTrue_andCarriesTheReportHeader(Path path) {
        Model m = report(path, intent, ALL_OK);

        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:about bbf:HSIIntent ; icm:result true ; "
                + "imo:handlingState imo:StateCompliant ; icm:reportNumber 3"));
        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:reportGenerated [ <http://www.w3.org/2006/time#inXSDDateTimeStamp> "
                + "\"2026-06-11T10:00:00Z\"^^xsd:dateTime ]"));
        assertEquals(0, count(m, "?r icm:about ?x ; icm:result false"), "nothing is false when Fulfilled");
        assertEquals(5, count(m, "?r a icm:ConditionReport"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void partialObservations_showWhichConditionsAreNotMet_andWhy(Path path) {
        Model m = report(path, intent, BANDWIDTH_ONLY);

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

    @ParameterizedTest
    @EnumSource(Path.class)
    void aViolatedCondition_isExplainedByItsValues(Path path) {
        Model m = report(path, intent, "DownstreamBandwidthMetric", "150", "Mbps", "UpstreamBandwidthMetric", "30", "Mbps",
                "LatencyMetric", "40", "ms", "JitterMetric", "1.2", "ms", "PacketLossMetric", "0.02", "percent");

        assertTrue(ask(m, "?c icm:about bbf:HSI_LatencyCondition ; icm:result false ; "
                + "icm:reason [ rdfs:label \"40 ms < 25 ms is false\" ]"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void resultFrom_followsTheIntentHierarchy(Path path) {
        Model m = report(path, intent, ALL_OK);

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

    @ParameterizedTest
    @EnumSource(Path.class)
    void deliveryReports_listTheResourcesChosen_inTargetReports(Path path) {
        Model m = report(path, intent, ALL_OK);

        assertTrue(ask(m, "?e icm:about bbf:HSIServiceProvisioningExpectation ; icm:targetReport ?t . "
                + "?t a icm:TargetReport ; icm:about bbf:SelectedUNIInterface ; icm:targetCount 1 ; rdfs:member pon:UNI-001-1"));
        assertTrue(ask(m, "?e icm:about bbf:HSISTagProvisioningExpectation ; icm:targetReport ?t . "
                + "?t icm:targetCount 1 ; rdfs:member pon:SVLAN-N-HSI"));
        assertTrue(ask(m, "?e icm:about bbf:HSICTAGProvisioningExpectation ; icm:targetReport ?t . "
                + "?t icm:targetCount 1 ; rdfs:member pon:CTAG-N-001"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void aFailedDeliveryHasAReasonAndNoChosenResource(Path path) {
        String noFreeUni = inventory;
        inventory = noFreeUni.replace("pon:inUse         false ;", "pon:inUse         true ;");
        Model m = report(path, intent, ALL_OK);

        assertTrue(ask(m, "?e a icm:DeliveryExpectationReport ; icm:about bbf:HSIServiceProvisioningExpectation ; "
                + "icm:result false ; icm:reason [ rdfs:label ?why ] . FILTER(CONTAINS(?why, \"no candidate of type UNIPort\"))"));
        assertFalse(ask(m, "?e icm:about bbf:HSIServiceProvisioningExpectation ; icm:targetReport ?t . ?t rdfs:member ?x"));
    }

    // ── Scope (TR290 §4.2) and observations (§4.6) ────────────────────────────

    @ParameterizedTest
    @EnumSource(Path.class)
    void onlyElementsInTheReportingTargetAreReported_andTheIntentAlwaysIs(Path path) {
        // Keep just the intent and the five conditions in the reporting target
        String narrowed = intent.replaceFirst("(?s)bbf:HSIMonitoringTarget a icm:Target ;.*?bbf:HSIServiceTarget \\.",
                "bbf:HSIMonitoringTarget a icm:Target ; rdfs:member bbf:HSI_DownstreamBandwidthCondition , "
                        + "bbf:HSI_LatencyCondition .");
        Model m = report(path, narrowed, ALL_OK);

        assertTrue(ask(m, "?ir a icm:IntentReport"), "the intent is always reported");
        assertEquals(2, count(m, "?c a icm:ConditionReport"));
        assertEquals(0, count(m, "?e a ?t . FILTER(?t IN (icm:DeliveryExpectationReport, icm:PropertyExpectationReport))"));
        // contributors are the nearest reported descendants
        assertEquals(2, count(m, "?ir a icm:IntentReport ; icm:resultFrom ?c . ?c a icm:ConditionReport"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void withoutAReportingExpectation_onlyTheIntentReportExists(Path path) {
        String noReporting = intent.replaceAll("(?s)bbf:HSIMonitoringExpectation a icm:ReportingExpectation ;.*?bbf:Periodic15m \\] \\.", "")
                .replace("bbf:HSIMonitoringExpectation )", ")");
        Model m = report(path, noReporting, ALL_OK);

        assertEquals(1, count(m, "?r icm:about ?x"));
        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:result true"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void observedValues_areOnlyIncludedForAnObservationReportingExpectation(Path path) {
        Model plain = report(path, intent, ALL_OK);
        assertEquals(0, count(plain, "?c met:observed ?o"), "a basic reporting expectation omits observations");

        String observed = intent.replace("a icm:ReportingExpectation", "a icm:ObservationReportingExpectation");
        Model m = report(path, observed, ALL_OK);
        assertEquals(5, count(m, "?c a icm:ConditionReport ; met:observed ?o"));
        assertTrue(ask(m, "?c icm:about bbf:HSI_LatencyCondition ; met:observed [ a met:Observation ; "
                + "met:observedMetric bbf:LatencyMetric ; rdf:value 8 ; quan:unit \"ms\" ]"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void observationReportingIncludesUnits(Path path) {
        String observed = intent.replace("a icm:ReportingExpectation", "a icm:ObservationReportingExpectation");
        Model m = report(path, observed, ALL_OK);

        assertEquals(5, count(m, "?c a icm:ConditionReport ; met:observed [ quan:unit ?u ]"), "every observation has its unit");
        assertTrue(ask(m, "?c icm:about bbf:HSI_DownstreamBandwidthCondition ; met:observed [ met:observedMetric "
                + "bbf:DownstreamBandwidthMetric ; rdf:value 150 ; quan:unit \"Mbps\" ]"));
        assertTrue(ask(m, "?c icm:about bbf:HSI_PacketLossCondition ; met:observed [ rdf:value 0.02 ; quan:unit \"percent\" ]"));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void blankNodeConditionIsNotReportedButAffectsItsParent(Path path) {
        // A blank-node latency condition (< 5 ms, observed 8 ms) inside a blank-node log:allOf that also holds an
        // IRI condition: the blank nodes get no report (TR290 §4.2) but make the performance expectation false.
        String withBlank = intent.replace("bbf:HSI_PacketLossCondition ) .",
                "[ log:allOf ( bbf:HSI_PacketLossCondition\n"
                        + "  [ a icm:Condition ; quan:smaller ( bbf:LatencyMetric [ rdf:value \"5\"^^xsd:decimal ; "
                        + "quan:unit \"ms\"^^xsd:string ] ) ] ) ] ) .");
        assertNotEquals(intent, withBlank);
        Model m = report(path, withBlank, ALL_OK);

        assertEquals(0, count(m, "?r icm:about ?x . FILTER(isBlank(?x))"), "blank-node elements are not reported");
        assertEquals(5, count(m, "?c a icm:ConditionReport ; icm:result true"), "every IRI condition holds");
        assertEquals(5, count(m, "?perf icm:about bbf:HSIPerformanceExpectation ; icm:resultFrom ?c . "
                + "?c a icm:ConditionReport"), "the IRI condition under the blank node still contributes");
        assertTrue(ask(m, "?perf a icm:PropertyExpectationReport ; icm:about bbf:HSIPerformanceExpectation ; icm:result false"));
        assertTrue(ask(m, "?ir a icm:IntentReport ; icm:result false"));
    }

    @org.junit.jupiter.api.Test
    void bothPathsBuildTheSameReport() {
        String observed = intent.replace("a icm:ReportingExpectation", "a icm:ObservationReportingExpectation");
        for (String ttl : new String[]{intent, observed}) {
            for (String[] obs : new String[][]{ALL_OK, BANDWIDTH_ONLY}) {
                assertTrue(report(Path.TREE, ttl, obs).isIsomorphicWith(report(Path.GRAPH, ttl, obs)));
            }
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(Path.class)
    void theReportIsLinkedToItsTmfIntentReport_andSerialisesToTurtleThatParsesBack(Path path) {
        Model m = report(path, intent, ALL_OK);

        assertTrue(ask(m, "<" + REPORT + "> tmf:icmReport ?ir . ?ir a icm:IntentReport"));
        String ttl = ReportBuilder.toTurtle(m);
        Model back = ModelFactory.createDefaultModel();
        back.read(new StringReader(ttl), null, "TURTLE");
        assertTrue(back.isIsomorphicWith(m));
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void anExpressionWithoutAnIntentHasNothingToReportOn(Path path) {
        String metricOnly = """
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
                _:c a quan:quansmaller ; rdf:first <http://example.org/m> ; rdf:rest [ rdf:first _:b ] .
                _:b rdf:value "10"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(metricOnly, null, null);
        assertNull(build(path, r, metricOnly, null,
                new ReportBuilder.ReportContext(REPORT, r.intentHandlingState(), 1, Instant.now())));
    }
}
