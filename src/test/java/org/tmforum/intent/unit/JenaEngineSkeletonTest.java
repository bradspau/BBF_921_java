package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tmforum.intent.engine.*;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.TioNamespaces;
import org.tmforum.intent.model.EvaluationResult;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Task 11: the Jena rule engine skeleton (no rule content yet). */
class JenaEngineSkeletonTest {

    static final Path ONTOLOGY = Path.of("ontology");
    static final String ICM = TioNamespaces.ICM;
    static final Property ICM_RESULT = ResourceFactory.createProperty(ICM + "result");

    static final String PREFIXES = """
            @prefix icm: <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/> .
            @prefix log: <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
            @prefix met: <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix tmf: <http://tmforum.org/api/v5/> .
            @prefix ex: <http://example.org/> .
            """;

    static final String INTENT = PREFIXES + """
            ex:I a icm:Intent ; log:allOf ( ex:E ) .
            ex:E a icm:DeliveryExpectation ; icm:target ex:T ; log:allOf ( ex:C ) .
            ex:C a log:Condition ; quan:atLeast ( ex:m 10 ) .
            """;

    private static void assumeOntology() {
        assumeTrue(Files.isRegularFile(ONTOLOGY.resolve("IntentCommonModel.ttl")),
                "needs the licensed ontology in ontology/ (gitignored; present only on the owner's machine)");
    }

    private static Model ttl(String s) {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(s), "http://tmforum.org/", "TURTLE");
        return m;
    }

    private static InfModel noRules(Model m) {
        return ModelFactory.createInfModel(new GenericRuleReasoner(List.of()), m);
    }

    // ── status ───────────────────────────────────────────────────────────────

    @Test
    void statusNotReadyWhenOntologyAbsent(@TempDir Path empty) {
        EngineStatus s = new JenaEvaluationEngine(empty).status();
        assertFalse(s.ready());
        assertEquals("ontology files not found in ontology/", s.reason());
    }

    @Test
    void statusNotReadyWhileConformanceNotPassing() {
        assumeOntology();
        EngineStatus s = new JenaEvaluationEngine(ONTOLOGY).status();
        assertFalse(s.ready());
        assertFalse(JenaEvaluationEngine.READY);
        assertEquals("conformance suite not passing", s.reason());
    }

    // ── OntologyModels ───────────────────────────────────────────────────────

    @Test
    void missingDirectoryGivesEmpty(@TempDir Path tmp) {
        assertEquals(Optional.empty(), OntologyModels.load(tmp.resolve("does-not-exist")));
        assertEquals(Optional.empty(), OntologyModels.load(tmp));     // exists, no TTL
    }

    @Test
    void intentTtlIsNotLoaded(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("a.ttl"), "<http://example.org/a> <http://example.org/p> 1 .");
        Files.writeString(tmp.resolve("intent.ttl"), "<http://example.org/sentinel> <http://example.org/p> 2 .");
        Model m = OntologyModels.load(tmp).orElseThrow();
        assertTrue(m.containsResource(ResourceFactory.createResource("http://example.org/a")));
        assertFalse(m.containsResource(ResourceFactory.createResource("http://example.org/sentinel")));
        assertSame(m, OntologyModels.load(tmp).orElseThrow(), "cached: loaded once per path");
    }

    @Test
    void cachedOntologyIsReadOnly(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("a.ttl"), "<http://example.org/a> <http://example.org/p> 1 .");
        Model m = OntologyModels.load(tmp).orElseThrow();
        assertThrows(Exception.class, () -> m.add(RDF.nil, RDF.type, RDF.List));
    }

    @Test
    void realOntologyHasDeliveryExpectationSubclassAndNoIntentTtl() {
        assumeOntology();
        Model m = OntologyModels.load(ONTOLOGY).orElseThrow();
        assertTrue(m.contains(m.createResource(ICM + "DeliveryExpectation"), RDFS.subClassOf,
                m.createResource(ICM + "Expectation")));
        List<Path> sources = OntologyModels.sources(ONTOLOGY);
        assertFalse(sources.isEmpty());
        assertTrue(sources.stream().noneMatch(p -> p.getFileName().toString().equals("intent.ttl")));
    }

    // ── evaluate ─────────────────────────────────────────────────────────────

    @Test
    void evaluateWithNoMatchingRulesGivesNoIcmResult(@TempDir Path empty) {
        EvaluationOutcome out = new JenaEvaluationEngine(empty).evaluate(
                new EvaluationInput("x", INTENT + "ex:I icm:result true .", null, null, List.of("http://example.org/r1")));
        assertFalse(out.resultGraph().contains(null, ICM_RESULT, (RDFNode) null));
        assertTrue(out.resultGraph().isEmpty(), "no rules, no contract triples");
        assertEquals("Degraded", out.result().intentHandlingState(), "no truth value is never Fulfilled");
        assertNotNull(out.result().reason());
    }

    @Test
    void evaluateWithOntologyGivesNoIcmResult() {
        assumeOntology();
        EvaluationOutcome out = new JenaEvaluationEngine(ONTOLOGY).evaluate(
                new EvaluationInput("x", INTENT, null, null, List.of()));
        assertFalse(out.resultGraph().contains(null, ICM_RESULT, (RDFNode) null));
        assertEquals("Degraded", out.result().intentHandlingState());
    }

    @Test
    void evaluateParseErrorIsDegradedLikeJava(@TempDir Path empty) {
        EvaluationOutcome out = new JenaEvaluationEngine(empty).evaluate(
                new EvaluationInput("x", "this is not turtle", null, null, List.of()));
        assertEquals("Degraded", out.result().intentHandlingState());
        assertTrue(out.result().reason().startsWith("Turtle parse error: "));
        assertTrue(out.resultGraph().isEmpty());
    }

    @Test
    void evaluateEmptyExpressionIsDegradedLikeJava(@TempDir Path empty) {
        EvaluationOutcome out = new JenaEvaluationEngine(empty).evaluate(
                new EvaluationInput("x", " ", null, null, List.of()));
        assertEquals("Empty expression", out.result().reason());
    }

    // ── ContractExtractor ────────────────────────────────────────────────────

    @Test
    void extractorRestrictsValuesToIriElements() {
        Model m = ttl(INTENT + """
                ex:I rdf:value true ; icm:result true .
                ex:E rdf:value true .
                ex:C rdf:value false .
                ex:fn rdf:value true .
                [] a log:Condition ; rdf:value false .
                icm:DeliveryExpectation rdfs:subClassOf icm:Expectation .
                """.replace("rdf:value", "<http://www.w3.org/1999/02/22-rdf-syntax-ns#value>"));
        Model c = ContractExtractor.extract(noRules(m));
        Resource ex = c.createResource("http://example.org/I");
        assertTrue(c.containsLiteral(ex, RDF.value, true));
        assertTrue(c.containsLiteral(c.createResource("http://example.org/E"), RDF.value, true));
        assertTrue(c.containsLiteral(c.createResource("http://example.org/C"), RDF.value, false));
        assertFalse(c.containsResource(c.createResource("http://example.org/fn")), "function node is not an element");
        assertFalse(c.contains(null, ICM_RESULT, (RDFNode) null));
        assertEquals(3, c.size());
    }

    @Test
    void extractorEmitsOnlyChosenMembers() {
        Model m = ttl(INTENT + """
                ex:T rdfs:member ex:seed .
                ex:pool rdfs:member ex:r1 .
                """);
        assertFalse(ContractExtractor.extract(noRules(m)).contains(null, RDFS.member, (RDFNode) null));

        m.add(m.createResource("http://example.org/T"), RDFS.member, m.createResource("http://example.org/r1"));
        m.add(m.createResource("http://example.org/r1"), ContractExtractor.CHOSEN_FOR, m.createResource("http://example.org/T"));
        Model c = ContractExtractor.extract(noRules(m));
        assertTrue(c.contains(c.createResource("http://example.org/T"), RDFS.member, c.createResource("http://example.org/r1")));
        assertEquals(1, c.listStatements(null, RDFS.member, (RDFNode) null).toList().size());
        assertEquals(Namespaces.TMF + "chosenFor", ContractExtractor.CHOSEN_FOR.getURI());
    }

    @Test
    void extractorFlattensNestedObservationQuantity() {
        Model m = ttl(INTENT + """
                ex:C met:observed ex:obs1 .
                ex:obs1 a met:Observation ; met:observedMetric ex:m ;
                    <http://www.w3.org/1999/02/22-rdf-syntax-ns#value> [ <http://www.w3.org/1999/02/22-rdf-syntax-ns#value> 12 ; quan:unit "Mbit/s" ] .
                """);
        Model c = ContractExtractor.extract(noRules(m));
        Resource obs = c.createResource("http://example.org/obs1");
        assertTrue(c.contains(c.createResource("http://example.org/C"), ResultContract.MET_OBSERVED, obs));
        assertTrue(c.contains(obs, RDF.type, ResultContract.MET_OBSERVATION));
        assertTrue(c.contains(obs, ResultContract.MET_OBSERVED_METRIC, c.createResource("http://example.org/m")));
        assertEquals(12, c.getProperty(obs, RDF.value).getInt());
        assertEquals("Mbit/s", c.getProperty(obs, ResultContract.QUAN_UNIT).getString());
        assertTrue(c.listSubjects().toList().stream().allMatch(Resource::isURIResource), "no nested blank node");
    }

    @Test
    void extractorKeepsReasonsOnElements() {
        Model m = ttl(INTENT + """
                ex:C icm:reason [ a icm:Reason ; rdfs:label "no observation" ] .
                ex:fn icm:reason [ a icm:Reason ; rdfs:label "helper" ] .
                """);
        Model c = ContractExtractor.extract(noRules(m));
        assertEquals(1, c.listStatements(null, ResultContract.ICM_REASON, (RDFNode) null).toList().size());
        assertTrue(c.contains(null, RDFS.label, "no observation"));
    }

    // ── ResultAdapter ────────────────────────────────────────────────────────

    @Test
    void adapterMapsIntentValueAndBuildsJavaStyleReason() {
        Model expression = ttl(INTENT + "ex:C2 a log:Condition .");
        Model g = ModelFactory.createDefaultModel();
        Resource i = g.createResource("http://example.org/I");
        g.addLiteral(i, RDF.value, true);
        g.addLiteral(g.createResource("http://example.org/C"), RDF.value, true);
        EvaluationResult ok = ResultAdapter.adapt(g, expression);
        assertEquals("Fulfilled", ok.intentHandlingState());
        assertNull(ok.reason());

        g.removeAll(i, RDF.value, null).addLiteral(i, RDF.value, false);
        g.addLiteral(g.createResource("http://example.org/C2"), RDF.value, false);
        EvaluationResult bad = ResultAdapter.adapt(g, expression);
        assertEquals("Degraded", bad.intentHandlingState());
        assertEquals("Conditions not met: Condition: FAIL", bad.reason());
        List<Map<String, Object>> conds = bad.conditions();
        assertEquals(2, conds.size());
        assertEquals(Boolean.TRUE, conds.get(0).get("passed"));
        assertEquals(Boolean.FALSE, conds.get(1).get("passed"));
        assertNull(conds.get(1).get("observed"), "stubbed: not invented");
    }

    @Test
    void sharedReasonFormatMatchesJava() {
        assertEquals("Conditions not met: atLeast: FAIL; 5 Mbit/s >= 10 Mbit/s: FAIL",
                Reasons.conditionsNotMet(List.of(
                        Map.of("type", "x", "passed", true),
                        Map.of("type", "atLeast", "passed", false),
                        Map.of("type", "atLeast", "operator", ">=", "observed", 5, "observedUnit", "Mbit/s",
                                "bound", 10, "boundUnit", "Mbit/s", "passed", false))));
        assertEquals("Conditions not met", Reasons.conditionsNotMet(List.of()));
    }
}
