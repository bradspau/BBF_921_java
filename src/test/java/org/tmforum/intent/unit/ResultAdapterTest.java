package org.tmforum.intent.unit;

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

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ResultAdapter} over the whole conformance corpus (design §5.3; Task 16 and its fix round): the handler's
 * {@link EvaluationResult} from the rule engine, invoked directly (its declared readiness is not consulted), against
 * the Java engine's.
 *
 * <ul>
 *   <li>A case without a handler expectation file: Java and Jena agree on state, reason string and, per condition and
 *       in order, every key a consumer in {@code src/main} reads ({@link #KEYS}); {@link BestEffortLimits} (Flow 3)
 *       proposes the same expression from both.</li>
 *   <li>A case with {@code handler.txt} (every case with a registered deviation, and the no-intent case): the Jena
 *       result must equal that hand-derived expectation; the Java result must equal it too, or the {@code java-}
 *       lines recorded next to it where the Java engine differs, so the deviation stays visible.</li>
 * </ul>
 *
 * <p>{@code handler.txt} format: {@code state:}, {@code reason:} (empty for none) and one {@code condition:} line per
 * entry, {@code key=value} pairs separated by {@code " | "} in key order; {@code java-state:}, {@code java-reason:},
 * {@code java-condition:} likewise for the Java engine where it differs. {@code #} starts a comment.
 */
class ResultAdapterTest {

    /** The keys a consumer in src/main reads (HandlerStateWriter, Flow 3 BestEffortLimits, Flow 4 write-back). */
    static final Set<String> KEYS = Set.of("type", "passed", "operator", "observed", "bound", "selected", "error",
            "held", "lower", "upper", "observedUnit", "boundUnit");

    static final EvaluationEngine JAVA = new JavaEvaluationEngine(new TurtleEvaluator());
    static final EvaluationEngine JENA = new JenaEvaluationEngine();

    static Stream<ConformanceCase> cases() {
        return ConformanceLoader.loadAll().stream();
    }

    static EvaluationInput input(ConformanceCase c) {
        return new EvaluationInput("adapter-" + c.name(), c.expression(), c.observations(), c.resources(), c.held());
    }

    // ── projection and expectations ────────────────────────────────────────────

    record Handler(String state, String reason, List<String> conditions) {}

    static String line(Map<String, Object> c) {
        StringJoiner j = new StringJoiner(" | ");
        new TreeMap<>(c).forEach((k, v) -> {
            if (KEYS.contains(k) && v != null) j.add(k + "=" + v);
        });
        return j.toString();
    }

    static Handler of(EvaluationResult r) {
        return new Handler(r.intentHandlingState(), r.reason() == null ? "" : r.reason(),
                r.conditions().stream().map(ResultAdapterTest::line).toList());
    }

    /** The case's handler.txt as {jena, java} expectations, or null when the case has none. */
    static Handler[] expectations(ConformanceCase c) throws IOException {
        String path = ConformanceLoader.ROOT + "/" + c.name() + "/handler.txt";
        try (InputStream in = ResultAdapterTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> one = new HashMap<>();
            List<String> conds = new ArrayList<>();
            List<String> javaConds = new ArrayList<>();
            for (String l : text.split("\\R")) {
                if (l.isBlank() || l.startsWith("#")) continue;
                int i = l.indexOf(':');
                String key = l.substring(0, i).trim();
                String value = l.substring(i + 1).trim();
                switch (key) {
                    case "condition" -> conds.add(value);
                    case "java-condition" -> javaConds.add(value);
                    default -> one.put(key, value);
                }
            }
            Handler jena = new Handler(one.get("state"), one.getOrDefault("reason", ""), conds);
            Handler java = one.containsKey("java-state")
                    ? new Handler(one.get("java-state"), one.getOrDefault("java-reason", ""), javaConds) : jena;
            return new Handler[]{jena, java};
        }
    }

    static Optional<Model> proposal(String expression, EvaluationResult r) {
        return new BestEffortLimits().apply(expression, r.conditions()).map(t -> {
            Model m = ModelFactory.createDefaultModel();
            m.read(new StringReader(t), null, "TURTLE");
            return m;
        });
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
    void jenaGivesTheJavaHandlerResultOrTheRegisteredOne(ConformanceCase c) throws IOException {
        assumeTrue(JenaLogicRulesTest.ontology().isPresent(), "the rule engine needs the licensed ontology");
        EvaluationResult java = JAVA.evaluate(input(c)).result();
        EvaluationResult jena = JENA.evaluate(input(c)).result();
        Handler[] expected = expectations(c);
        if (expected == null) {
            assertNull(c.expectedJena(), c.name() + ": a registered deviation needs a hand-derived handler.txt");
            assertEquals(of(java), of(jena), c.name());
            assertSameProposal(c.expression(), java, jena, c.name());
            return;
        }
        assertEquals(expected[0], of(jena), c.name() + ": Jena against handler.txt");
        assertEquals(expected[1], of(java), c.name() + ": Java against handler.txt");
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
