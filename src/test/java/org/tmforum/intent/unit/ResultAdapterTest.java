package org.tmforum.intent.unit;

import org.apache.jena.graph.compose.Union;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tmforum.intent.conformance.ConformanceCase;
import org.tmforum.intent.conformance.ConformanceLoader;
import org.tmforum.intent.engine.*;
import org.tmforum.intent.handler.BestEffortLimits;
import org.tmforum.intent.handler.TurtleEvaluator;
import org.tmforum.intent.model.EvaluationResult;

import java.io.StringReader;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ResultAdapter} equivalence over the whole conformance corpus (design §5.3, Task 16): the Jena engine, invoked
 * directly (its declared readiness is not consulted), and the Java engine agree on the handler's
 * {@link EvaluationResult}: state, reason string, and per condition (type, passed, operator, observed, bound,
 * selected), in order; and {@link BestEffortLimits} (Flow 3) proposes the same expression from both.
 *
 * <p>Where the corpus registers a deviation ({@code expected.jena.ttl}, DEVIATIONS.md) the two engines legitimately
 * differ. There the test asserts instead that the Jena result is exactly what the adapter makes of the registered
 * graph: the adapter reads truth values from the graph only. The no-intent case is the documented exception on the
 * reason (Task 16 report, no-intent policy): both are Degraded, and the Jena engine says why without a flat scan.
 */
class ResultAdapterTest {

    static final Set<String> KEYS = Set.of("type", "passed", "operator", "observed", "bound", "selected");
    static final String NO_INTENT = "no-intent-metric-only";

    static final EvaluationEngine JAVA = new JavaEvaluationEngine(new TurtleEvaluator());
    static final EvaluationEngine JENA = new JenaEvaluationEngine();

    static Stream<ConformanceCase> cases() {
        return ConformanceLoader.loadAll().stream();
    }

    static EvaluationInput input(ConformanceCase c) {
        return new EvaluationInput("adapter-" + c.name(), c.expression(), c.observations(), c.resources(), c.held());
    }

    /** The input model the Jena engine evaluates: expression, observations, resources, held facts, ontology. */
    static Model base(ConformanceCase c) {
        Model input = EvaluationInputs.parse(EvaluationInputs.join(c.expression(), c.observations(), c.resources()));
        JenaEvaluationEngine.stripHelperTriples(input);
        EvaluationInputs.addHeld(input, c.held());
        return ModelFactory.createModelForGraph(
                new Union(input.getGraph(), JenaLogicRulesTest.ontology().orElseThrow().getGraph()));
    }

    static List<Map<String, Object>> projection(EvaluationResult r) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : r.conditions()) {
            Map<String, Object> m = new TreeMap<>();
            c.forEach((k, v) -> {
                if (KEYS.contains(k) && v != null) m.put(k, String.valueOf(v));
            });
            out.add(m);
        }
        return out;
    }

    static void assertSameResult(EvaluationResult expected, EvaluationResult actual, String what) {
        assertEquals(expected.intentHandlingState(), actual.intentHandlingState(), what + ": state");
        assertEquals(expected.reason(), actual.reason(), what + ": reason");
        assertEquals(projection(expected), projection(actual), what + ": conditions");
    }

    static Optional<Model> proposal(ConformanceCase c, EvaluationResult r) {
        return new BestEffortLimits().apply(c.expression(), r.conditions()).map(t -> {
            Model m = ModelFactory.createDefaultModel();
            m.read(new StringReader(t), null, "TURTLE");
            return m;
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void jenaAndJavaGiveTheSameHandlerResult(ConformanceCase c) {
        assumeTrue(JenaLogicRulesTest.ontology().isPresent(), "the rule engine needs the licensed ontology");
        EvaluationOutcome java = JAVA.evaluate(input(c));
        EvaluationOutcome jena = JENA.evaluate(input(c));

        if (NO_INTENT.equals(c.name())) {
            assertTrue(jena.resultGraph().isEmpty(), "no intent: empty contract");
            assertTrue(java.resultGraph().isEmpty(), "no intent: the Java engine's contract is empty too");
            assertEquals("Degraded", jena.result().intentHandlingState());
            assertEquals(java.result().intentHandlingState(), jena.result().intentHandlingState());
            assertEquals("No icm:Intent in expression", jena.result().reason());
            assertEquals(List.of(), jena.result().conditions());
            assertNull(jena.result().report(), "no report");
            return;
        }
        if (c.expectedJena() != null) {
            // a registered deviation: the Jena result is the adapter's reading of the registered graph
            assertSameResult(ResultAdapter.adapt(c.expectedJena(), base(c)), jena.result(), c.name() + " (deviation)");
            return;
        }
        assertSameResult(java.result(), jena.result(), c.name());
        Optional<Model> a = proposal(c, java.result());
        Optional<Model> b = proposal(c, jena.result());
        assertEquals(a.isPresent(), b.isPresent(), c.name() + ": BestEffortLimits proposes from one engine only");
        a.ifPresent(m -> assertTrue(m.isIsomorphicWith(b.orElseThrow()), c.name() + ": different proposals"));
    }

    /** The adapter reads values only from the graph: given the Java engine's own graph it gives the Java result. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void adapterOfTheJavaGraphGivesTheJavaResult(ConformanceCase c) {
        assumeTrue(JenaLogicRulesTest.ontology().isPresent(), "the base model includes the licensed ontology");
        assumeTrue(c.expectedJena() == null && !NO_INTENT.equals(c.name()), "deviation or no-intent case");
        EvaluationOutcome java = JAVA.evaluate(input(c));
        assertSameResult(java.result(), ResultAdapter.adapt(java.resultGraph(), base(c)), c.name());
    }

    @Test
    void theCorpusHasAProposalToCompare() {
        assumeTrue(JenaLogicRulesTest.ontology().isPresent(), "the rule engine needs the licensed ontology");
        ConformanceCase violated = cases().filter(c -> c.name().equals("quantity-violated")).findFirst().orElseThrow();
        Optional<Model> p = proposal(violated, JENA.evaluate(input(violated)).result());
        assertTrue(p.isPresent(), "Flow 3 proposes the observed bound from the rule engine's result");
    }

    // ── synthetic graphs ──────────────────────────────────────────────────────────

    static final String PREFIXES = JenaSetRulesTest.PREFIXES;

    static Model ttl(String s) {
        return EvaluationInputs.parse(PREFIXES + s);
    }

    @Test
    void errorsComeFromReasonsThatAreNotTheComparisonText() {
        Model x = ttl("""
                ex:I a icm:Intent ; log:allOf ( ex:C1 ex:C2 ex:C3 ) .
                ex:C1 a icm:Condition ; quan:atLeast ( ex:M [ rdf:value 100 ; quan:unit "Mbps" ] ) .
                ex:C2 a icm:Condition ; quan:atLeast ( ex:M [ rdf:value 100 ; quan:unit "Mbps" ] ) .
                ex:C3 a icm:Condition ; quan:atLeast ( ex:N 5 ) .
                """);
        Model g = ttl("""
                ex:I rdf:value false .
                ex:C1 rdf:value false ; met:observed ex:o1 ;
                    icm:reason [ a icm:Reason ; rdfs:label "80 Mbps >= 100 Mbps is false" ] .
                ex:C2 rdf:value false ; met:observed ex:o2 ;
                    icm:reason [ a icm:Reason ; rdfs:label "unit mismatch: 'ms' cannot be compared with 'Mbps'" ] .
                ex:C3 rdf:value false ; icm:reason [ a icm:Reason ; rdfs:label "no observation: N" ] .
                ex:o1 a met:Observation ; met:observedMetric ex:M ; rdf:value 80 ; quan:unit "Mbps" .
                ex:o2 a met:Observation ; met:observedMetric ex:M ; rdf:value 8 ; quan:unit "ms" .
                """);
        EvaluationResult r = ResultAdapter.adapt(g, x);
        assertEquals("Conditions not met: 80 Mbps >= 100 Mbps: FAIL; "
                + "atLeast: unit mismatch: 'ms' cannot be compared with 'Mbps'; atLeast: no observation: N", r.reason());
        assertEquals(new java.math.BigDecimal("80"), r.conditions().get(0).get("observed"));
        assertEquals("Mbps", r.conditions().get(0).get("boundUnit"));
        assertEquals("http://example.org/o1", r.conditions().get(0).get("observation"));
        assertNull(r.conditions().get(2).get("observed"));
    }

    @Test
    void lastValueArgumentReadsTheLinkedObservationAndInRangeHasLowerAndUpper() {
        Model x = ttl("""
                ex:I a icm:Intent ; log:allOf ( ex:C1 ex:C2 ) .
                ex:C1 a icm:Condition ; quan:smaller ( [ met:lastValue ( ex:M ) ] 10 ) .
                ex:C2 a icm:Condition ; quan:inRange ( 5 [ rdf:value 1 ; quan:unit "ms" ] 9 ) .
                """);
        Model g = ttl("""
                ex:I rdf:value true . ex:C1 rdf:value true ; met:observed ex:o . ex:C2 rdf:value true .
                ex:o a met:Observation ; met:observedMetric ex:M ; rdf:value 3 .
                """);
        EvaluationResult r = ResultAdapter.adapt(g, x);
        assertEquals("Fulfilled", r.intentHandlingState());
        assertEquals(new java.math.BigDecimal("3"), r.conditions().get(0).get("observed"));
        assertEquals(new java.math.BigDecimal("10"), r.conditions().get(0).get("bound"));
        Map<String, Object> range = r.conditions().get(1);
        assertEquals("quaninRange", range.get("type"));
        assertEquals("<=<=", range.get("operator"));
        assertEquals(new java.math.BigDecimal("1"), range.get("lower"));
        assertEquals("ms", range.get("boundUnit"));
    }

    @Test
    void deliverySelectionIsListedOnceAndHeldIsMarked() {
        Model x = ttl("""
                ex:I a icm:Intent ; log:allOf ( ex:E1 ex:E2 ) .
                ex:E1 a icm:DeliveryExpectation ; icm:target ex:T ; icm:deliveryType ex:Widget .
                ex:E2 a icm:DeliveryExpectation ; icm:target ex:T ; icm:deliveryType ex:Widget .
                ex:w1 a ex:Widget . ex:w2 a ex:Widget .
                <urn:tmf921:evaluation> <urn:tmf921:evaluation#holds> ex:w2 .
                """);
        Model g = ttl("""
                ex:I rdf:value true . ex:E1 rdf:value true . ex:E2 rdf:value true .
                ex:T rdfs:member ex:w2 .
                """);
        List<Map<String, Object>> conds = ResultAdapter.adapt(g, x).conditions();
        assertEquals(2, conds.size());
        assertEquals("http://example.org/w2", conds.get(0).get("selected"));
        assertEquals(true, conds.get(0).get("held"));
        assertNull(conds.get(1).get("selected"), "the target was already satisfied for the second expectation");
    }

    @Test
    void noIntentIsDegradedWithoutConditions() {
        EvaluationResult r = ResultAdapter.adapt(ModelFactory.createDefaultModel(),
                ttl("ex:C a icm:Condition ; quan:atLeast ( 5 4 ) ."));
        assertEquals("Degraded", r.intentHandlingState());
        assertEquals("No icm:Intent in expression", r.reason());
        assertEquals(List.of(), r.conditions());
    }

    @Test
    void jenaEngineWithoutAnIntentReturnsAnEmptyContract() {
        EvaluationOutcome o = JENA.evaluate(new EvaluationInput("x", PREFIXES
                + "ex:C a icm:Condition ; quan:atLeast ( 5 4 ) .", null, null, List.of()));
        assertTrue(o.resultGraph().isEmpty());
        assertEquals("No icm:Intent in expression", o.result().reason());
        assertNull(o.result().report());
    }
}
