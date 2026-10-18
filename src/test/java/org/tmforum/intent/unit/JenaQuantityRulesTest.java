package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.graph.compose.Union;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tmforum.intent.engine.ContractExtractor;
import org.tmforum.intent.engine.EvaluationInputs;
import org.tmforum.intent.engine.JenaEvaluationEngine;
import org.tmforum.intent.engine.OntologyModels;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.handler.JenaBuiltins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stage A quantity rules ({@code rules/tio_a_quantity.rules}) on spec-form input (TR292C §2:
 * {@code ?s quan:fn ( arg1 arg2 )}), run through the real {@link GenericRuleReasoner} with the real rule file loaded
 * the way the engine loads it. Stage A only: stage B (defaults, combination into the element's own
 * {@code rdf:value}) is Task 13, so these tests read the function results ("contributions") directly.
 *
 * <p>Result shape: {@code ?s tmf:contribution ?k . ?k tmf:function quan:fn ; tmf:arguments ?list ;
 * rdf:value true|false} and, for an error, {@code ?k tmf:failure "text"}.
 */
class JenaQuantityRulesTest {

    static final String TMF = Namespaces.TMF;
    static final String EX = "http://example.org/";
    static final String QUAN = "http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/";

    static final String PREFIXES = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix icm:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/> .
            @prefix log:  <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
            @prefix met:  <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
            @prefix ex:   <http://example.org/> .
            """;

    static { JenaBuiltins.register(); }

    private static InfModel stageA(Model base) {
        GenericRuleReasoner r = new GenericRuleReasoner(JenaEvaluationEngine.parseRules("tio_a_quantity.rules"));
        r.setMode(GenericRuleReasoner.FORWARD_RETE);
        InfModel inf = ModelFactory.createInfModel(r, base);
        inf.prepare();
        assertSingleValuedContributions(inf);
        return inf;
    }

    private static InfModel stageA(String turtle) {
        return stageA(EvaluationInputs.parse(PREFIXES + turtle));
    }

    private static Property tmf(String local) { return ResourceFactory.createProperty(TMF + local); }

    /** Every function result has exactly one boolean value (an explicit true or false, never both or none). */
    private static void assertSingleValuedContributions(Model m) {
        m.listObjectsOfProperty(tmf("contribution")).forEach(k -> {
            List<RDFNode> values = m.listObjectsOfProperty(k.asResource(), RDF.value).toList();
            assertEquals(1, values.size(), "contribution " + k + " values " + values);
            assertEquals("http://www.w3.org/2001/XMLSchema#boolean", values.get(0).asLiteral().getDatatypeURI());
        });
    }

    /** The values of the functions applied to {@code ex:<subject>}, as "true"/"false". */
    private static Set<String> values(Model m, String subject) {
        Set<String> out = new TreeSet<>();
        for (RDFNode k : contributions(m, subject)) {
            m.listObjectsOfProperty(k.asResource(), RDF.value).forEach(v -> out.add(v.asLiteral().getLexicalForm()));
        }
        return out;
    }

    private static Set<String> failures(Model m, String subject) {
        Set<String> out = new TreeSet<>();
        for (RDFNode k : contributions(m, subject)) {
            m.listObjectsOfProperty(k.asResource(), tmf("failure")).forEach(v -> out.add(v.asLiteral().getLexicalForm()));
        }
        return out;
    }

    private static List<RDFNode> contributions(Model m, String subject) {
        return m.listObjectsOfProperty(m.getResource(EX + subject), tmf("contribution")).toList();
    }

    private static String value(Model m, String subject) {
        Set<String> v = values(m, subject);
        assertEquals(1, v.size(), subject + " should have exactly one function value, has " + v);
        return v.iterator().next();
    }

    private static String q(String value, String unit) {
        return "[ rdf:value " + value + (unit == null ? "" : " ; quan:unit \"" + unit + "\"") + " ]";
    }

    private static String cond(String fn, String... args) {
        return "ex:c quan:" + fn + " ( " + String.join(" ", args) + " ) .\n";
    }

    // ── each operator, true and false, with explicit quantity arguments ─────────────

    @ParameterizedTest(name = "{0}({1} {2}, {3} {4}) = {5}")
    @CsvSource({
            "atLeast, 150, Mbps, 100, Mbps, true",
            "atLeast, 100, Mbps, 100, Mbps, true",
            "atLeast, 80,  Mbps, 100, Mbps, false",
            "atMost,  80,  Mbps, 100, Mbps, true",
            "atMost,  100, Mbps, 100, Mbps, true",
            "atMost,  150, Mbps, 100, Mbps, false",
            "greater, 150, Mbps, 100, Mbps, true",
            "greater, 100, Mbps, 100, Mbps, false",
            "greater, 80,  Mbps, 100, Mbps, false",
            "smaller, 8,   ms,   25,  ms,   true",
            "smaller, 25,  ms,   25,  ms,   false",
            "smaller, 30,  ms,   25,  ms,   false",
            "exactly, 1,   Gbps, 1000, Mbps, true",
            "exactly, 1,   Gbps, 999, Mbps, false",
            "atLeast, 0.15, Gbps, 100, Mbps, true",
            "atMost,  10.35, ms,  0.01, s,  false",
    })
    void twoArgumentOperators(String fn, String va, String ua, String vb, String ub, String expected) {
        InfModel m = stageA(cond(fn, q(va, ua), q(vb, ub)));
        assertEquals(expected, value(m, "c"));
        assertEquals(Set.of(), failures(m, "c"));
    }

    @ParameterizedTest(name = "inRange({0}, {1}, {2}) = {3}")
    @CsvSource({
            "50,  10, 100, true",
            "10,  10, 100, true",
            "100, 10, 100, true",
            "9,   10, 100, false",
            "101, 10, 100, false",
            "50,  100, 10, false",   // bounds in the wrong order: second argument is the lower bound (TR292D §8.9)
    })
    void inRangeIsInclusiveAndOrderedValueLowerUpper(String v, String lo, String hi, String expected) {
        InfModel m = stageA(cond("inRange", q(v, "ms"), q(lo, "ms"), q(hi, "ms")));
        assertEquals(expected, value(m, "c"));
        assertEquals(Set.of(), failures(m, "c"));
    }

    @Test
    void inRangeConvertsUnits() {
        assertEquals("true", value(stageA(cond("inRange", q("0.5", "s"), q("100", "ms"), q("1000", "ms"))), "c"));
        assertEquals("false", value(stageA(cond("inRange", q("2", "s"), q("100", "ms"), q("1000", "ms"))), "c"));
    }

    @Test
    void exactlyTakesAllArguments() {
        assertEquals("true", value(stageA(cond("exactly", q("1", "s"), q("1000", "ms"), q("1000000", "us"))), "c"));
        assertEquals("false", value(stageA(cond("exactly", q("1", "s"), q("1000", "ms"), q("999", "ms"))), "c"));
        assertEquals("false", value(stageA(cond("exactly", q("2", "s"), q("1000", "ms"), q("1000", "ms"))), "c"));
    }

    @Test
    void functionsOnAnonymousNodesInsideAnOperatorListHaveValuesToo() {
        InfModel m = stageA("""
                ex:c log:allOf ( ex:x [ quan:atLeast ( 5 3 ) ] ) .
                ex:x quan:smaller ( 5 3 ) .
                """);
        assertEquals("false", value(m, "x"));
        Resource anon = m.listSubjectsWithProperty(m.getProperty(QUAN + "atLeast"))
                .filterKeep(Resource::isAnon).next();
        assertEquals(1, m.listObjectsOfProperty(anon, tmf("contribution")).toList().size());
    }

    // ── argument kinds ──────────────────────────────────────────────────────────────

    static final String OBSERVATIONS = """
            ex:o1 a met:Observation ; met:observedMetric ex:Latency ; rdf:value 30 ; quan:unit "ms" ;
                met:obtainedAt "2026-06-11T10:00:01Z"^^xsd:dateTime .
            ex:o3 a met:Observation ; met:observedMetric ex:Latency ; rdf:value 8 ; quan:unit "ms" ;
                met:obtainedAt "2026-06-11T10:00:03Z"^^xsd:dateTime .
            ex:o2 a met:Observation ; met:observedMetric ex:Latency ; rdf:value 40 ; quan:unit "ms" ;
                met:obtainedAt "2026-06-11T10:00:02Z"^^xsd:dateTime .
            """;

    @Test
    void aMetricArgumentResolvesThroughItsNewestObservation() {
        InfModel m = stageA(OBSERVATIONS + "ex:c quan:smaller ( ex:Latency " + q("25", "ms") + " ) .");
        assertEquals("true", value(m, "c"), "newest is 8 ms; the older 30 and 40 ms would be false");
        assertEquals(Set.of(), failures(m, "c"));
        assertEquals(Set.of(EX + "o3"), observationsUsed(m, "c"));
    }

    @Test
    void aMetricArgumentFalseOnItsNewestObservation() {
        String newer = "ex:o4 a met:Observation ; met:observedMetric ex:Latency ; rdf:value 26 ; quan:unit \"ms\" ;"
                + " met:obtainedAt \"2026-06-11T10:00:04Z\"^^xsd:dateTime .\n";
        InfModel m = stageA(OBSERVATIONS + newer + "ex:c quan:smaller ( ex:Latency " + q("25", "ms") + " ) .");
        assertEquals("false", value(m, "c"));
        assertEquals(Set.of(), failures(m, "c"), "a plain violation has no failure text (its reason is built later)");
    }

    @Test
    void lastValueArgumentResolvesLikeTheBareMetric() {
        InfModel m = stageA(OBSERVATIONS
                + "ex:c quan:smaller ( [ met:lastValue ( ex:Latency ) ] " + q("25", "ms") + " ) .\n"
                + "ex:d quan:greater ( [ met:lastValue ( ex:Latency ) ] " + q("25", "ms") + " ) .\n");
        assertEquals("true", value(m, "c"));
        assertEquals("false", value(m, "d"));
        assertEquals(Set.of(EX + "o3"), observationsUsed(m, "c"));
    }

    @Test
    void literalArgumentsAreUnitlessQuantities() {
        // TR292D §2.2 example: a unitless quantity against an integer literal
        InfModel m = stageA("""
                ex:NumberOfUsers a quan:Quantity ; rdf:value "10"^^xsd:integer .
                ex:c quan:smaller ( ex:NumberOfUsers "20"^^xsd:integer ) .
                ex:d quan:smaller ( 30 ex:NumberOfUsers ) .
                ex:e quan:atLeast ( 5 4.5 ) .
                """);
        assertEquals("true", value(m, "c"));
        assertEquals("false", value(m, "d"));
        assertEquals("true", value(m, "e"));
    }

    @Test
    void aLiteralAgainstAQuantityWithUnitIsAMismatch() {
        InfModel m = stageA(cond("atLeast", "150", q("100", "Mbps")));
        assertEquals("false", value(m, "c"));
        assertEquals(Set.of("unit mismatch: a value without unit cannot be compared with 'Mbps'"), failures(m, "c"));
    }

    @Test
    void nestedQuantityValuesAreRead() {
        InfModel m = stageA("""
                ex:o a met:Observation ; met:observedMetric ex:Bw ; rdf:value [ rdf:value 0.2 ; quan:unit "Gbps" ] ;
                    met:obtainedAt "2026-06-11T10:00:00Z"^^xsd:dateTime .
                ex:c quan:atLeast ( ex:Bw [ rdf:value 100 ; quan:unit "Mbps" ] ) .
                ex:d quan:atLeast ( [ rdf:value [ rdf:value 1 ; quan:unit "s" ] ] [ rdf:value 999 ; quan:unit "ms" ] ) .
                """);
        assertEquals("true", value(m, "c"));
        assertEquals("true", value(m, "d"));
    }

    @Test
    void integerDecimalAndDoubleValuesCompareNumerically() {
        InfModel m = stageA("""
                ex:o a met:Observation ; met:observedMetric ex:Bw ; rdf:value "1.5E2"^^xsd:double ; quan:unit "Mbps" ;
                    met:obtainedAt "2026-06-11T10:00:00Z"^^xsd:dateTime .
                ex:c quan:exactly ( ex:Bw [ rdf:value "150"^^xsd:integer ; quan:unit "Mbps" ] ) .
                ex:d quan:greater ( "10"^^xsd:integer "9.5"^^xsd:decimal ) .
                """);
        assertEquals("true", value(m, "c"));
        assertEquals("true", value(m, "d"));
    }

    // ── errors: false plus a failure fact with the Java engine's text ───────────────

    @Test
    void unitMismatchIsFalseWithAFailure() {
        InfModel m = stageA("""
                ex:o a met:Observation ; met:observedMetric ex:BandwidthMetric ; rdf:value 8 ; quan:unit "ms" ;
                    met:obtainedAt "2026-06-11T10:00:00Z"^^xsd:dateTime .
                ex:c quan:atLeast ( ex:BandwidthMetric [ rdf:value 100 ; quan:unit "Mbps" ] ) .
                """);
        assertEquals("false", value(m, "c"));
        assertEquals(Set.of("unit mismatch: 'ms' cannot be compared with 'Mbps'"), failures(m, "c"));
    }

    @Test
    void noObservationIsFalseWithAFailure() {
        InfModel m = stageA("""
                ex:BandwidthMetric a met:metric .
                ex:c quan:atLeast ( ex:BandwidthMetric [ rdf:value 100 ; quan:unit "Mbps" ] ) .
                ex:d quan:atLeast ( [ met:lastValue ( ex:BandwidthMetric ) ] [ rdf:value 100 ; quan:unit "Mbps" ] ) .
                """);
        assertEquals("false", value(m, "c"));
        assertEquals(Set.of("no observation: BandwidthMetric"), failures(m, "c"));
        assertEquals("false", value(m, "d"));
        assertEquals(Set.of("no observation: BandwidthMetric"), failures(m, "d"));
    }

    @Test
    void aMetricBoundWithoutObservationIsAMissingBoundValue() {
        InfModel m = stageA(cond("atLeast", q("5", null), "ex:Unobserved"));
        assertEquals("false", value(m, "c"));
        assertEquals(Set.of("missing bound value"), failures(m, "c"));
    }

    @Test
    void aMetricBoundResolvesThroughItsObservation() {
        InfModel m = stageA(OBSERVATIONS + cond("atLeast", q("10", "ms"), "ex:Latency"));
        assertEquals("true", value(m, "c"), "10 ms >= newest 8 ms");
    }

    @Test
    void missingAndNonNumericArguments() {
        InfModel m = stageA("""
                ex:a quan:atLeast ( 5 ) .
                ex:b quan:atLeast ( ) .
                ex:c quan:atLeast ( "abc" 5 ) .
                ex:d quan:atLeast ( 5 "abc" ) .
                ex:e quan:inRange ( 5 1 ) .
                ex:f quan:inRange ( 5 "x" 10 ) .
                ex:g quan:exactly ( 5 ) .
                """);
        for (String s : List.of("a", "b", "c", "d", "e", "f", "g")) assertEquals("false", value(m, s), s);
        assertEquals(Set.of("missing bound node"), failures(m, "a"));
        assertEquals(Set.of("missing operand nodes"), failures(m, "b"));
        assertEquals(Set.of("no observation: ?"), failures(m, "c"));
        assertEquals(Set.of("missing bound value"), failures(m, "d"));
        assertEquals(Set.of("missing operand nodes"), failures(m, "e"));
        assertEquals(Set.of("missing rdf:value"), failures(m, "f"));
        assertEquals(Set.of("missing bound node"), failures(m, "g"));
    }

    @Test
    void inRangeMismatchTextFollowsTheComparisonOrder() {
        InfModel m = stageA("""
                ex:a quan:inRange ( [ rdf:value 5 ; quan:unit "ms" ] [ rdf:value 1 ; quan:unit "Mbps" ] [ rdf:value 9 ; quan:unit "ms" ] ) .
                ex:b quan:inRange ( [ rdf:value 5 ; quan:unit "ms" ] [ rdf:value 1 ; quan:unit "ms" ] [ rdf:value 9 ; quan:unit "Mbps" ] ) .
                """);
        assertEquals("false", value(m, "a"));
        assertEquals(Set.of("unit mismatch: 'Mbps' cannot be compared with 'ms'"), failures(m, "a"));
        assertEquals("false", value(m, "b"));
        assertEquals(Set.of("unit mismatch: 'ms' cannot be compared with 'Mbps'"), failures(m, "b"));
    }

    // ── accepted input extensions (DEVIATIONS.md entry 5) ─────────────────────────────

    @Test
    void legacyPredicateAliasesAndTypeFormAreAccepted() {
        InfModel m = stageA("""
                ex:a quan:quanatLeast ( 5 3 ) .
                ex:b a quan:quanatMost ; rdf:first 5 ; rdf:rest ( 3 ) .
                ex:c a quan:smaller ; rdf:first 2 ; rdf:rest ( 3 ) .
                """);
        assertEquals("true", value(m, "a"));
        assertEquals("false", value(m, "b"));
        assertEquals("true", value(m, "c"));
    }

    // ── corpus inputs: stage A gives each quantity condition the Java engine's value and error text ──

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "quantity-ok              | BandwidthCondition    | true  | ",
            "quantity-ok              | LatencyCondition      | true  | ",
            "quantity-violated        | BandwidthCondition    | false | ",
            "quantity-unit-conversion | BandwidthCondition    | true  | ",
            "quantity-unit-mismatch   | BandwidthCondition    | false | unit mismatch: 'ms' cannot be compared with 'Mbps'",
            "quantity-unitless        | BandwidthCondition    | false | unit mismatch: a value without unit cannot be compared with 'Mbps'",
            "quantity-unitless        | SessionCountCondition | true  | ",
            "quantity-no-observation  | BandwidthCondition    | false | no observation: BandwidthMetric",
    })
    void corpusQuantityCases(String dir, String condition, String expected, String failure) throws IOException {
        InfModel m = stageA(EvaluationInputs.parse(corpus(dir)));
        assertEquals(expected, value(m, condition));
        assertEquals(failure == null ? Set.of() : Set.of(failure), failures(m, condition));
    }

    private static String corpus(String dir) throws IOException {
        Path d = Path.of("src/test/resources/conformance", dir);
        List<String> parts = new ArrayList<>();
        parts.add(Files.readString(d.resolve("expression.ttl")));
        Path obs = d.resolve("observations.ttl");
        if (Files.exists(obs)) parts.add(Files.readString(obs));
        return EvaluationInputs.join(parts.toArray(String[]::new));
    }

    // ── helpers stay out of the contract; the engine loads the file ─────────────────

    @Test
    void helperFactsNeverReachTheContract() throws IOException {
        InfModel a = stageA(EvaluationInputs.parse(corpus("quantity-unit-mismatch")));
        InfModel noB = ModelFactory.createInfModel(new GenericRuleReasoner(List.of()), a);
        Model contract = ContractExtractor.extract(noB);
        contract.listStatements().forEach(st -> {
            assertFalse(st.getPredicate().getURI().startsWith(TMF), "helper predicate in contract: " + st);
            assertFalse(st.getSubject().isAnon() && st.getPredicate().equals(RDF.value)
                    && !st.getObject().isResource(), "function value in contract: " + st);
        });
        assertFalse(contract.listStatements().toList().stream().map(Statement::getObject)
                .anyMatch(o -> o.isURIResource() && o.asResource().getURI().startsWith(TMF)));
    }

    @Test
    void theEngineLoadsTheQuantityRulesInStageA() {
        assertTrue(JenaEvaluationEngine.STAGE_A.contains("tio_a_quantity.rules"));
        assertFalse(JenaEvaluationEngine.parseRules("tio_a_quantity.rules").isEmpty());
    }

    @Test
    void sameResultsWithTheOntologyInTheModel() throws IOException {
        Optional<Model> ontology = OntologyModels.load(Path.of("ontology"));
        assumeTrue(ontology.isPresent() && Files.exists(Path.of("ontology/QuantityOntology.ttl")));
        Model input = EvaluationInputs.parse(corpus("quantity-ok"));
        Model base = ModelFactory.createModelForGraph(new Union(input.getGraph(), ontology.get().getGraph()));
        InfModel m = stageA(base);
        assertEquals("true", value(m, "BandwidthCondition"));
        assertEquals("true", value(m, "LatencyCondition"));
        // the ontology's own function definitions get no contributions
        assertEquals(2, m.listStatements(null, tmf("contribution"), (RDFNode) null).toList().size());
    }

    private static Set<String> observationsUsed(Model m, String subject) {
        Set<String> out = new TreeSet<>();
        for (RDFNode k : contributions(m, subject)) {
            Resource list = m.getProperty(k.asResource(), tmf("arguments")).getResource();
            m.listObjectsOfProperty(list, tmf("argObservation")).forEach(o -> out.add(o.asResource().getURI()));
        }
        return out;
    }
}
