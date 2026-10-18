package org.tmforum.intent.unit;

import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.ResultContract;
import org.tmforum.intent.engine.ResultGraphWriter;
import org.tmforum.intent.handler.TurtleEvaluator;
import org.tmforum.intent.model.EvaluationResult;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;
import static org.tmforum.intent.unit.TestInputs.*;

/** The shared result graph (spec 5.2) written from the Java evaluator's element tree. */
class ResultGraphWriterTest {

    private static final String PFX = """
            PREFIX icm: <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/>
            PREFIX met: <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/>
            PREFIX quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/>
            PREFIX bbf: <http://broadband-forum.org/Intent#>
            PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
            PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            """;

    private TurtleEvaluator evaluator;
    private String intent;
    private String inventory;

    @BeforeEach
    void setUp() {
        evaluator = new TurtleEvaluator();
        intent = seedIntent();
        inventory = inventory();
    }

    private Model graph(String intentTtl, String inv, String... obs) {
        return graphHolding(intentTtl, inv, java.util.List.of(), obs);
    }

    private Model graphHolding(String intentTtl, String inv, java.util.Collection<String> held, String... obs) {
        String obsTtl = obs.length == 0 ? null : observations(obs);
        EvaluationResult r = evaluator.evaluate(intentTtl, obsTtl, inv, held);
        Model o = ModelFactory.createDefaultModel();
        if (obsTtl != null) o.read(new StringReader(obsTtl), "http://tmforum.org/", "TURTLE");
        return ResultGraphWriter.write(r.report(), o);
    }

    private boolean ask(Model m, String where) {
        return QueryExecutionFactory.create(PFX + "ASK { " + where + " }", m).execAsk();
    }

    private int count(Model m, String where) {
        var rs = QueryExecutionFactory.create(PFX + "SELECT (COUNT(*) AS ?n) WHERE { " + where + " }", m).execSelect();
        return rs.next().getLiteral("n").getInt();
    }

    @Test
    void fulfilled_everyElementHasExactlyOneBooleanValue() {
        Model m = graph(intent, inventory, ALL_OK);
        for (String e : new String[]{"HSIIntent", "HSIServiceProvisioningExpectation",
                "HSISTagProvisioningExpectation", "HSICTAGProvisioningExpectation", "HSIPerformanceExpectation",
                "HSIMonitoringExpectation", "HSI_DownstreamBandwidthCondition", "HSI_UpstreamBandwidthCondition",
                "HSI_LatencyCondition", "HSI_JitterCondition", "HSI_PacketLossCondition"}) {
            assertEquals(1, count(m, "bbf:" + e + " rdf:value ?v"), e);
            assertTrue(ask(m, "bbf:" + e + " rdf:value \"true\"^^xsd:boolean"), e);
        }
        // 1 intent + 5 expectations + 5 conditions
        assertEquals(11, count(m, "?e rdf:value ?v . FILTER(datatype(?v) = xsd:boolean)"));
        assertEquals(0, count(m, "?e rdf:value ?v . FILTER(datatype(?v) = xsd:boolean && ?v = false)"));
    }

    @Test
    void partial_hasExplicitFalseAndReasons() {
        Model m = graph(intent, inventory, BANDWIDTH_ONLY);
        for (String metric : new String[]{"Latency", "Jitter", "PacketLoss"}) {
            String c = "bbf:HSI_" + metric + "Condition";
            assertTrue(ask(m, c + " rdf:value false"), c);
            assertTrue(ask(m, c + " icm:reason ?r . ?r a icm:Reason ; rdfs:label \"no observation: "
                    + metric + "Metric\""), c);
        }
        assertTrue(ask(m, "bbf:HSIIntent rdf:value false"));
    }

    @Test
    void deliveryChoicesAreMembersOfTheirTargets() {
        Model m = graph(intent, inventory, ALL_OK);
        assertTrue(ask(m, "bbf:SelectedUNIInterface rdfs:member pon:UNI-001-1"));
        assertTrue(ask(m, "bbf:SelectedSVLANAllocation rdfs:member pon:SVLAN-N-HSI"));
        assertTrue(ask(m, "bbf:SelectedCTAGAllocation rdfs:member pon:CTAG-N-001"));
        assertEquals(3, count(m, "?t rdfs:member ?r"));
    }

    @Test
    void observationsAreLinkedAndCopied() {
        Model m = graph(intent, inventory, ALL_OK);
        assertTrue(ask(m, "bbf:HSI_LatencyCondition met:observed ?o . ?o a met:Observation ; "
                + "met:observedMetric bbf:LatencyMetric ; rdf:value 8 ; quan:unit \"ms\""));
    }

    @Test
    void noIcmResultAndNoHelperTriples() {
        Model m = graph(intent, inventory, BANDWIDTH_ONLY);
        assertFalse(ask(m, "?s icm:result ?o"));
        assertTrue(m.isIsomorphicWith(ResultContract.filter(m)));
        assertTrue(ResultContract.sameResults(m, ResultContract.filter(m)));
    }

    @Test
    void unitMismatchGivesFalseWithReason() {
        Model m = graph(intent, inventory, "DownstreamBandwidthMetric", "150", "Mbps",
                "UpstreamBandwidthMetric", "30", "Mbps", "LatencyMetric", "8", "",
                "JitterMetric", "1.2", "ms", "PacketLossMetric", "0.02", "percent");
        assertTrue(ask(m, "bbf:HSI_LatencyCondition rdf:value false"));
        assertTrue(ask(m, "bbf:HSI_LatencyCondition icm:reason ?r . ?r rdfs:label ?l . FILTER(contains(?l, \"unit mismatch\"))"));
    }

    @Test
    void metricOnlyExpression_yieldsAnEmptyGraph() {
        String metricOnly = """
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
                @prefix bbf: <http://broadband-forum.org/Intent#> .
                bbf:C a quan:quanatMost ; <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> bbf:LatencyMetric ;
                  <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> ( 10 ) .
                """;
        Model m = assertDoesNotThrow(() -> graph(metricOnly, inventory, "LatencyMetric", "8", "ms"));
        assertTrue(m.isEmpty());
    }

    @Test
    void seedWithoutReportingExpectation_stillHasExplicitFalseAndReason() {
        String noReporting = intent.replaceAll("(?s)bbf:HSIMonitoringExpectation a icm:ReportingExpectation ;.*?bbf:Periodic15m \\] \\.", "")
                .replace("bbf:HSIMonitoringExpectation )", ")");
        assertNotEquals(intent, noReporting);
        Model m = graph(noReporting, inventory, BANDWIDTH_ONLY);
        assertTrue(ask(m, "bbf:HSI_LatencyCondition rdf:value false"));
        assertTrue(ask(m, "bbf:HSI_LatencyCondition icm:reason ?r . ?r rdfs:label \"no observation: LatencyMetric\""));
    }

    @Test
    void largePool_onlyTheChoiceIsAMember() {
        StringBuilder unis = new StringBuilder();
        for (int i = 0; i < 1000; i++) {   // sort before UNI-001-1, so the choice comes from this pool
            unis.append("pon:UNI-000-").append(String.format("%04d", i)).append("\n    a pon:UNIPort ;\n")
                .append("    pon:name \"b").append(i).append("\" ;\n    pon:inUse false ;\n")
                .append("    pon:provisioningState pon:Ready ;\n    pon:operationalState pon:Up ;\n")
                .append("    pon:region bbf:NorthRegion .\n");
        }
        String big = inventory + "\n" + unis;
        Model m = graph(intent, big, ALL_OK);
        assertEquals(1, count(m, "bbf:SelectedUNIInterface rdfs:member ?r"));
        assertFalse(ask(m, "bbf:SelectedUNIInterface rdfs:member pon:UNI-001-1"), "choice comes from the 1000-port pool");
        assertEquals(3, count(m, "?t rdfs:member ?r"));
    }

    @Test
    void filterStripsEverythingOutsideTheContractFamilies() {
        Model all = ModelFactory.createDefaultModel();
        all.read(new StringReader(intent + "\n" + observations(ALL_OK)), "http://tmforum.org/", "TURTLE");
        all.add(all.createResource(BBF + "HSIIntent"), all.createProperty(
                "http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/result"), all.createTypedLiteral(true));
        all.add(all.createResource(BBF + "HSI_LatencyCondition"), org.apache.jena.vocabulary.RDF.value, all.createTypedLiteral(5));
        all.add(all.createResource(BBF + "HSI_LatencyCondition"),
                all.createProperty("urn:tmf921:evaluation#observation"), all.createResource("http://example.org/obs/x"));
        Model f = ResultContract.filter(all);

        assertFalse(ask(f, "?s icm:result ?o"));
        assertFalse(ask(f, "?s ?p ?o . FILTER(STRSTARTS(STR(?p), \"urn:tmf921:evaluation#\"))"));
        assertFalse(ask(f, "?s rdf:value ?v . FILTER(datatype(?v) != xsd:boolean)"));
        assertFalse(ask(f, "?s a ?t"));
        // Seed target members (e.g. bbf:HSIServiceTarget rdfs:member ...) are IRI-to-IRI members: the filter
        // cannot tell them from chosen ones; restricting to chosen members is the extractor's job (class javadoc).
    }

    @Test
    void filterFlattensANestedObservationQuantity() {
        Model in = ModelFactory.createDefaultModel();
        in.read(new StringReader("""
                @prefix met: <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
                @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
                @prefix bbf: <http://broadband-forum.org/Intent#> .
                bbf:C met:observed <http://example.org/o> .
                <http://example.org/o> a met:Observation ; met:observedMetric bbf:LatencyMetric ;
                   <http://www.w3.org/1999/02/22-rdf-syntax-ns#value> [ <http://www.w3.org/1999/02/22-rdf-syntax-ns#value> 8 ; quan:unit "ms" ] .
                """), "http://tmforum.org/", "TURTLE");
        Model f = ResultContract.filter(in);
        assertTrue(ask(f, "bbf:C met:observed ?o . ?o rdf:value 8 ; quan:unit \"ms\""));
        assertEquals(0, count(f, "?s ?p ?o . FILTER(isBlank(?o))"));
    }

    @Test
    void reevaluationWhileHoldingTheResources_givesAnIdenticalGraph() {
        Model first = graph(intent, inventory, ALL_OK);
        String allocated = inventory
                .replaceFirst("(pon:UNI-001-1\\s[^.]*?)pon:inUse\\s+false", "$1pon:inUse true ;\n pon:assignedToService \"a\"")
                .replaceFirst("(pon:CTAG-N-001\\s[^.]*?)pon:inUse\\s+false", "$1pon:inUse true ;\n pon:assignedToService \"a\"");
        var held = java.util.List.of("http://broadband-forum.org/ont/pon-resource#UNI-001-1",
                "http://broadband-forum.org/ont/pon-resource#CTAG-N-001");
        Model second = graphHolding(intent, allocated, held, ALL_OK);
        assertTrue(ResultContract.sameResults(first, second));
    }
}
