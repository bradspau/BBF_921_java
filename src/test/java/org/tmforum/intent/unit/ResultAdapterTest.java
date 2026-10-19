package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tmforum.intent.conformance.ConformanceCase;
import org.tmforum.intent.conformance.ConformanceLoader;
import org.tmforum.intent.conformance.HandlerCheck;
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
 * {@link ResultAdapter} over the whole conformance corpus (design §5.3; Task 16 and its fix round): the handler's
 * {@link EvaluationResult} from the rule engine, invoked directly (its declared readiness is not consulted), against
 * the Java engine's or the case's {@code handler.txt}, as {@link HandlerCheck} defines it (the same check the
 * readiness guard applies): state, reason string, per condition and in order the consumed keys
 * ({@link HandlerProjection#KEYS}), and the Flow 3 ({@link BestEffortLimits}) proposal.
 */
class ResultAdapterTest {

    static final EvaluationEngine JAVA = new JavaEvaluationEngine(new TurtleEvaluator());
    static final EvaluationEngine JENA = new JenaEvaluationEngine();

    static Stream<ConformanceCase> cases() {
        return ConformanceLoader.loadAll().stream();
    }

    static EvaluationInput input(ConformanceCase c) {
        return new EvaluationInput("adapter-" + c.name(), c.expression(), c.observations(), c.resources(), c.held());
    }

    // ── projection ─────────────────────────────────────────────────────────────

    static HandlerProjection of(EvaluationResult r) {
        return HandlerProjection.of(r);
    }

    static Optional<Model> proposal(String expression, EvaluationResult r) {
        return HandlerProjection.proposal(expression, r);
    }

    static void assertSameProposal(String expression, EvaluationResult java, EvaluationResult jena, String what) {
        Optional<Model> a = proposal(expression, java);
        Optional<Model> b = proposal(expression, jena);
        assertEquals(a.isPresent(), b.isPresent(), what + ": BestEffortLimits proposes from one engine only");
        a.ifPresent(m -> assertTrue(m.isIsomorphicWith(b.orElseThrow()), what + ": different proposals"));
    }

    // ── the corpus ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void jenaGivesTheJavaHandlerResultOrTheRegisteredOne(ConformanceCase c) {
        assumeTrue(JenaLogicRulesTest.ontology().isPresent(), "the rule engine needs the licensed ontology");
        EvaluationResult java = JAVA.evaluate(input(c)).result();
        EvaluationResult jena = JENA.evaluate(input(c)).result();
        String mismatch = HandlerCheck.mismatch(c, java, jena);
        assertNull(mismatch, mismatch);
    }

    @Test
    void theDemoShapesProposeTheSameBoundsAsTheJavaEngine() {
        assumeTrue(JenaLogicRulesTest.ontology().isPresent(), "the rule engine needs the licensed ontology");
        for (String name : List.of("demo-hsi-inline-bad", "demo-seed-probe", "quantity-violated")) {
            ConformanceCase c = cases().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
            EvaluationResult jena = JENA.evaluate(input(c)).result();
            assertTrue(proposal(c.expression(), jena).isPresent(), name + ": Flow 3 proposes from the rule engine");
        }
    }

    // ── single expressions ───────────────────────────────────────────────────────

    static final String PREFIXES = JenaSetRulesTest.PREFIXES;

    static EvaluationResult jena(String turtle) {
        return JENA.evaluate(new EvaluationInput("x", PREFIXES + turtle, null, null, List.of())).result();
    }

    @Test
    void severalFunctionsOnOneElementEachHaveTheirOwnValueAndOnlyTheFailingOneIsProposed() {
        String turtle = """
                ex:I a icm:Intent ; log:allOf ( ex:C ) .
                ex:C a icm:Condition ; quan:atLeast ( 6 [ rdf:value 5 ] ) ; quan:atMost ( 9 [ rdf:value 5 ] ) .
                """;
        EvaluationResult r = jena(turtle);
        assertEquals("Degraded", r.intentHandlingState());
        assertEquals("Conditions not met: 9 <= 5: FAIL", r.reason());
        assertEquals(List.of("bound=5 | observed=6 | operator=>= | passed=true | type=atLeast",
                "bound=5 | observed=9 | operator=<= | passed=false | type=atMost"), of(r).conditions());
        Model proposed = proposal(PREFIXES + turtle, r).orElseThrow();
        String p = proposed.toString();
        Model expected = ModelFactory.createDefaultModel();
        expected.read(new StringReader(PREFIXES + """
                ex:I a icm:Intent ; log:allOf ( ex:C ) .
                ex:C a icm:Condition ; quan:atLeast ( 6 [ rdf:value 5 ] ) ;
                    quan:atMost ( 9 [ rdf:value "9"^^xsd:decimal ] ) .
                """), null, "TURTLE");
        assertTrue(proposed.isIsomorphicWith(expected), () -> "only the failing atMost bound changes: " + p);
    }

    @Test
    void nAryExactlyShowsThePairItsReasonNames() {
        EvaluationResult r = jena("""
                ex:I a icm:Intent ; log:allOf ( ex:C ) .
                ex:C a icm:Condition ; quan:exactly ( 5 5 6 ) .
                """);
        assertEquals("Conditions not met: 5 == 6: FAIL", r.reason());
    }

    @Test
    void anonymousAndUntypedConditionsAreListedWithJavaLabels() {
        String turtle = """
                ex:Top log:allOf ( ex:DL [ quan:atLeast ( ex:UL 20 ) ] ) .
                ex:DL quan:atLeast ( ex:Down [ rdf:value 100 ] ) .
                ex:o a met:Observation ; met:observedMetric ex:Down ; rdf:value 80 .
                """;
        EvaluationResult java = JAVA.evaluate(new EvaluationInput("x", PREFIXES + turtle, null, null, List.of())).result();
        EvaluationResult r = jena(turtle);
        assertEquals("Conditions not met: 80 >= 100: FAIL; atLeast: no observation: UL", r.reason());
        assertEquals(of(java), of(r));
        assertSameProposal(PREFIXES + turtle, java, r, "anonymous and untyped");
    }

    @Test
    void anExpressionWithoutConditionsIsDegradedAsInTheJavaEngine() {
        EvaluationResult r = jena("ex:X ex:p ex:Y .");
        assertEquals("Degraded", r.intentHandlingState());
        assertEquals("No quantity conditions found in expression", r.reason());
        assertEquals(List.of(), r.conditions());
    }

    @Test
    void noIntentGivesAnEmptyContractAndNoReport() {
        EvaluationOutcome o = JENA.evaluate(new EvaluationInput("x", PREFIXES
                + "_:c quan:atLeast ( 5 [ rdf:value 4 ] ) .", null, null, List.of()));
        assertTrue(o.resultGraph().isEmpty());
        assertEquals("Fulfilled", o.result().intentHandlingState());
        assertNull(o.result().report());
    }
}
