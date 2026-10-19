package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.*;
import org.tmforum.intent.handler.TurtleEvaluator;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reasons and observation links of the rule engine (design §5.2; {@code tio_a_reasons.rules},
 * {@code tio_b_reasons.rules}), through the engine's real chain ({@link JenaEvaluationEngine#infer}) with the input
 * and the rules of every stage shuffled by fixed seeds, with and without the licensed ontology, read back from the
 * contract ({@link ContractExtractor}).
 *
 * <p>Where the Java engine is the oracle (no registered deviation), every run also compares the reason labels and
 * {@code met:observed} links per element with what the Java engine writes for the same input.
 */
class JenaReasonsRulesTest {

    static final String EX = "http://example.org/";

    static final String INTENT = """
            ex:Intent a icm:Intent ; log:allOf ( ex:E ) .
            ex:E a icm:PropertyExpectation ; icm:target ex:T ; log:allOf ( ex:C ) .
            ex:T a icm:Target .
            """;

    static final String OBS_BW_80 = """
            ex:obs-bw a met:Observation ; met:observedMetric ex:BandwidthMetric ;
                rdf:value "80"^^xsd:decimal ; quan:unit "Mbps" ; met:obtainedAt "2026-01-01T00:00:00Z" .
            """;

    // ── running ──────────────────────────────────────────────────────────────────

    interface Check {
        void check(Model contract, String where);
    }

    /** Every seed and ontology mode: invariants, then {@code check} on the contract. */
    static void allRuns(String turtle, Check check) {
        for (boolean withOntology : JenaLogicRulesTest.ontologyModes()) {
            for (long seed : JenaDeliveryRulesTest.SEEDS) {
                InfModel m = JenaDeliveryRulesTest.run(turtle, seed, withOntology);
                String where = " (seed " + seed + ", ontology " + withOntology + ")\n" + turtle;
                JenaDeliveryRulesTest.assertInvariants(m, where);
                // without the ontology no subclass of icm:Expectation is known, so ContractExtractor reports no
                // expectation; the contract triples are then read from the whole model
                check.check(withOntology ? ContractExtractor.extract(m) : ResultContract.filter(m), where);
            }
        }
    }

    /** The reason labels of each element of the contract, by local name. */
    static Map<String, Set<String>> reasons(Model contract) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Statement st : contract.listStatements(null, ResultContract.ICM_REASON, (RDFNode) null).toList()) {
            Resource r = st.getObject().asResource();
            assertTrue(contract.contains(r, RDF.type, ResultContract.ICM_REASON_CLASS), "reason without its type");
            for (RDFNode l : contract.listObjectsOfProperty(r, RDFS.label).toList()) {
                out.computeIfAbsent(st.getSubject().getLocalName(), k -> new TreeSet<>()).add(l.asLiteral().getString());
            }
        }
        return out;
    }

    /** The observations linked by met:observed, by element local name, with value and unit as "obs=value unit". */
    static Map<String, Set<String>> observed(Model contract) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Statement st : contract.listStatements(null, ResultContract.MET_OBSERVED, (RDFNode) null).toList()) {
            Resource o = st.getObject().asResource();
            Statement v = contract.getProperty(o, RDF.value);
            Statement u = contract.getProperty(o, ResultContract.QUAN_UNIT);
            assertTrue(contract.contains(o, RDF.type, ResultContract.MET_OBSERVATION), "observation without its type");
            String text = o.getLocalName() + "=" + (v == null ? "?" : v.getLiteral().getLexicalForm())
                    + (u == null ? "" : " " + u.getString());
            out.computeIfAbsent(st.getSubject().getLocalName(), k -> new TreeSet<>()).add(text);
        }
        return out;
    }

    /** The Java engine's contract graph for the same input (the oracle). */
    static Model java(String turtle) {
        EvaluationOutcome o = new JavaEvaluationEngine(new TurtleEvaluator()).evaluate(new EvaluationInput(
                "t", JenaSetRulesTest.PREFIXES + turtle, null, null, List.of()));
        return ResultContract.filter(o.resultGraph());
    }

    static void assertReasons(String turtle, Map<String, Set<String>> expected, boolean javaAgrees) {
        if (javaAgrees) assertEquals(expected, reasons(java(turtle)), "the Java engine (oracle) disagrees with the test");
        allRuns(turtle, (c, where) -> assertEquals(expected, reasons(c), () -> "reasons" + where));
    }

    static void assertObserved(String turtle, Map<String, Set<String>> expected, boolean javaAgrees) {
        if (javaAgrees) assertEquals(expected, observed(java(turtle)), "the Java engine (oracle) disagrees with the test");
        allRuns(turtle, (c, where) -> assertEquals(expected, observed(c), () -> "met:observed" + where));
    }

    static Set<String> set(String... s) {
        return new TreeSet<>(Arrays.asList(s));
    }

    // ── quantity errors: the Java engine's exact text ───────────────────────────

    @Test
    void noObservationNamesTheMetric() {
        String t = INTENT + "ex:C a icm:Condition ; quan:atLeast ( ex:BandwidthMetric [ rdf:value 100 ; quan:unit \"Mbps\" ] ) .";
        assertReasons(t, Map.of("C", set("no observation: BandwidthMetric")), true);
        assertObserved(t, Map.of(), true);
    }

    @Test
    void unitMismatchHasTheJavaTextAndTheObservationLink() {
        String t = INTENT + OBS_BW_80.replace("\"Mbps\"", "\"ms\"")
                + "ex:C a icm:Condition ; quan:atLeast ( ex:BandwidthMetric [ rdf:value 100 ; quan:unit \"Mbps\" ] ) .";
        assertReasons(t, Map.of("C", set("unit mismatch: 'ms' cannot be compared with 'Mbps'")), true);
        assertObserved(t, Map.of("C", set("obs-bw=80 ms")), true);
    }

    @Test
    void missingBoundValue() {
        String t = INTENT + OBS_BW_80
                + "ex:C a icm:Condition ; quan:atLeast ( ex:BandwidthMetric [ quan:unit \"Mbps\" ] ) .";
        assertReasons(t, Map.of("C", set("missing bound value")), true);
        assertObserved(t, Map.of(), true);               // as Java: no link when the comparison never ran
    }

    @Test
    void missingOperands() {
        String t = INTENT + "ex:C a icm:Condition ; quan:atLeast ( 5 ) .";
        assertReasons(t, Map.of("C", set("missing bound node")), true);
    }

    // ── plain violations (DEVIATIONS.md entry 4, D3) ─────────────────────────────

    @Test
    void violationTextAndObservationLink() {
        String t = INTENT + OBS_BW_80
                + "ex:C a icm:Condition ; quan:atLeast ( ex:BandwidthMetric [ rdf:value 100 ; quan:unit \"Mbps\" ] ) .";
        assertReasons(t, Map.of("C", set("80 Mbps >= 100 Mbps is false")), true);
        assertObserved(t, Map.of("C", set("obs-bw=80 Mbps")), true);
    }

    @Test
    void violationTextTrimsUnitsAndWritesPlainNumbers() {
        String t = INTENT + OBS_BW_80.replace("\"Mbps\"", "\" Mbps \"").replace("\"80\"^^xsd:decimal", "\"8.0E1\"^^xsd:double")
                + "ex:C a icm:Condition ; quan:greater ( ex:BandwidthMetric [ rdf:value \"1e2\"^^xsd:double ; quan:unit \"Mbps\" ] ) .";
        assertReasons(t, Map.of("C", set("80 Mbps > 100 Mbps is false")), true);
    }

    @Test
    void violationOfEveryBinaryOperatorAndOfExactly() {
        String t = """
                ex:Intent a icm:Intent ; log:allOf ( ex:E ) .
                ex:E a icm:PropertyExpectation ; icm:target ex:T ; log:allOf ( ex:C1 ex:C2 ex:C3 ex:C4 ex:C5 ) .
                ex:T a icm:Target .
                ex:C1 a icm:Condition ; quan:atMost ( 5 4 ) .
                ex:C2 a icm:Condition ; quan:greater ( 4 4 ) .
                ex:C3 a icm:Condition ; quan:smaller ( 4 4 ) .
                ex:C4 a icm:Condition ; quan:exactly ( 4 5 ) .
                ex:C5 a icm:Condition ; quan:atLeast ( [ rdf:value 1 ; quan:unit "Gbps" ] [ rdf:value 999 ; quan:unit "Mbps" ] ) .
                """;
        assertReasons(t, Map.of("C1", set("5 <= 4 is false"), "C2", set("4 > 4 is false"), "C3", set("4 < 4 is false"),
                "C4", set("4 == 5 is false")), true);
    }

    @Test
    void inRangeViolationHasNoReasonAndNoObservationLink() {
        String t = INTENT + OBS_BW_80
                + "ex:C a icm:Condition ; quan:inRange ( ex:BandwidthMetric [ rdf:value 100 ; quan:unit \"Mbps\" ] "
                + "[ rdf:value 200 ; quan:unit \"Mbps\" ] ) .";
        assertReasons(t, Map.of(), true);
        assertObserved(t, Map.of(), true);
    }

    @Test
    void passingConditionLinksItsObservationAndHasNoReason() {
        String t = INTENT + OBS_BW_80
                + "ex:C a icm:Condition ; quan:atLeast ( ex:BandwidthMetric [ rdf:value 50 ; quan:unit \"Mbps\" ] ) .";
        assertReasons(t, Map.of(), true);
        assertObserved(t, Map.of("C", set("obs-bw=80 Mbps")), true);
    }

    @Test
    void newestObservationIsLinked() {
        String t = INTENT + OBS_BW_80 + """
                ex:obs-new a met:Observation ; met:observedMetric ex:BandwidthMetric ;
                    rdf:value [ rdf:value 120 ; quan:unit "Mbps" ] ; met:obtainedAt "2026-02-01T00:00:00Z" .
                ex:C a icm:Condition ; quan:atLeast ( ex:BandwidthMetric [ rdf:value 100 ; quan:unit "Mbps" ] ) .
                """;
        assertReasons(t, Map.of(), true);
        assertObserved(t, Map.of("C", set("obs-new=120 Mbps")), true);   // nested quantity flattened
    }

    // ── one reason per failing contribution ───────────────────────────────────────

    @Test
    void exactlyWithSeveralFailuresReportsOne() {
        // two unit mismatches (Mbps/ms and ms/s): one reason, the lexically first failure, which is also the Java
        // engine's (it compares the first two arguments only, DEVIATIONS.md)
        String t = INTENT + """
                ex:C a icm:Condition ; quan:exactly ( [ rdf:value 5 ; quan:unit "Mbps" ] [ rdf:value 5 ; quan:unit "ms" ]
                                                      [ rdf:value 5 ; quan:unit "s" ] ) .
                """;
        assertReasons(t, Map.of("C", set("unit mismatch: 'Mbps' cannot be compared with 'ms'")), true);
    }

    @Test
    void exactlyViolationReportsOnePair() {
        String t = INTENT + "ex:C a icm:Condition ; quan:exactly ( 4 4 6 5 ) .";
        assertReasons(t, Map.of("C", set("4 == 6 is false")), false);   // Java compares 4 and 4 only: true
    }

    @Test
    void anErrorWinsOverAViolationOfTheSameFunction() {
        String t = INTENT + """
                ex:C a icm:Condition ; quan:exactly ( [ rdf:value 5 ; quan:unit "Mbps" ] [ rdf:value 6 ; quan:unit "Mbps" ]
                                                      [ rdf:value 5 ; quan:unit "ms" ] ) .
                """;
        assertReasons(t, Map.of("C", set("unit mismatch: 'Mbps' cannot be compared with 'ms'")), false);
    }

    @Test
    void severalFailingFunctionsOnOneElementGiveOneReasonEach() {
        String t = INTENT + "ex:C a icm:Condition ; quan:atLeast ( ex:M1 5 ) ; quan:atMost ( 9 5 ) .";
        assertReasons(t, Map.of("C", set("no observation: M1", "9 <= 5 is false")), false);
    }

    // ── delivery ─────────────────────────────────────────────────────────────────

    @Test
    void noCandidateOfTheDeliveryType() {
        String t = """
                ex:Intent a icm:Intent ; log:allOf ( ex:E ) .
                ex:E a icm:DeliveryExpectation ; icm:target ex:T ; icm:deliveryType ex:Widget ; icm:chooseFrom ex:Pool .
                ex:T a icm:Target .
                ex:Pool a icm:Target ; rdfs:member ex:g1 .
                ex:g1 a ex:Gadget .
                """;
        assertReasons(t, Map.of("E", set("no candidate of type Widget in chooseFrom pool")), true);
    }

    @Test
    void noTargetMemberWithoutAPoolAndMissingTarget() {
        String t = """
                ex:Intent a icm:Intent ; log:allOf ( ex:E1 ex:E2 ) .
                ex:E1 a icm:DeliveryExpectation ; icm:target ex:T ; icm:deliveryType ex:Widget .
                ex:E2 a icm:DeliveryExpectation ; icm:deliveryType ex:Widget .
                ex:T a icm:Target .
                """;
        assertReasons(t, Map.of("E1", set("no target member of type Widget"), "E2", set("missing icm:target")), true);
    }

    // ── statement functions ───────────────────────────────────────────────────────

    @Test
    void matchArityAndArgumentErrorsHaveTheJavaText() {
        String t = """
                ex:Intent a icm:Intent ; log:allOf ( ex:E ) .
                ex:E a icm:PropertyExpectation ; icm:target ex:T ; log:allOf ( ex:M1 ex:M2 ex:M3 ex:M4 ex:M5 ) .
                ex:T a icm:Target ; rdfs:member ex:a .
                ex:M1 a icm:Condition ; log:match ( ex:a ex:p ) .
                ex:M2 a icm:Condition ; log:match ( "a" ex:p ex:o ) .
                ex:M3 a icm:Condition ; log:matchAll ( ex:T ex:p ) .
                ex:M4 a icm:Condition ; log:matchAny ( ex:T "p" ex:o ) .
                ex:M5 a icm:Condition ; log:match ( ex:a ex:p ex:o ) .
                """;
        assertReasons(t, Map.of("M1", set("expected 3 args, got 2"), "M2", set("subject/predicate must be URIs"),
                "M3", set("expected (container, predicate, object), got 2"), "M4", set("invalid args")), true);
    }

    // ── where reasons go ──────────────────────────────────────────────────────────

    @Test
    void anonymousConditionsReportOnTheirNamedParent() {
        String t = INTENT + OBS_BW_80 + """
                ex:C a icm:Condition ; log:allOf ( [ quan:atLeast ( ex:BandwidthMetric [ rdf:value 100 ; quan:unit "Mbps" ] ) ]
                                                   [ log:allOf ( [ quan:atLeast ( ex:LatencyMetric 5 ) ] ) ] ) .
                """;
        assertReasons(t, Map.of("C", set("80 Mbps >= 100 Mbps is false", "no observation: LatencyMetric")), true);
        assertObserved(t, Map.of("C", set("obs-bw=80 Mbps")), true);
    }

    @Test
    void namedConditionsKeepTheirOwnReasons() {
        String t = """
                ex:Intent a icm:Intent ; log:allOf ( ex:E ) .
                ex:E a icm:PropertyExpectation ; icm:target ex:T ; log:allOf ( ex:C1 ex:C2 ) .
                ex:T a icm:Target .
                ex:C1 a icm:Condition ; quan:atLeast ( ex:M1 5 ) .
                ex:C2 a icm:Condition ; quan:atLeast ( ex:M2 5 ) .
                """;
        assertReasons(t, Map.of("C1", set("no observation: M1"), "C2", set("no observation: M2")), true);
    }

    @Test
    void aTrueElementHasNoReason() {
        // anyOf ( failing , true ) is true: no reason (design §5.2). The Java engine attaches the failing member's
        // reason to the true parent (DEVIATIONS.md entry 26).
        String t = INTENT + "ex:C a icm:Condition ; log:anyOf ( [ quan:atLeast ( ex:M 5 ) ] [ quan:atLeast ( 6 5 ) ] ) .";
        assertReasons(t, Map.of(), false);
    }

    @Test
    void aFalseElementWithoutAnErrorOrComparisonHasNoReason() {
        String t = INTENT + "ex:C a icm:Condition ; log:noneOf ( [ quan:atLeast ( 6 5 ) ] ) .";
        assertReasons(t, Map.of(), true);
    }
}
