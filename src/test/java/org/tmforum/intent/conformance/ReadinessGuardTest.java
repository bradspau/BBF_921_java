package org.tmforum.intent.conformance;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.EvaluationInput;
import org.tmforum.intent.engine.EvaluationOutcome;
import org.tmforum.intent.engine.JavaEvaluationEngine;
import org.tmforum.intent.engine.JenaEvaluationEngine;
import org.tmforum.intent.engine.ResultContract;
import org.tmforum.intent.handler.TurtleEvaluator;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the rule engine's declared readiness (design 5.1, 5.6): {@link JenaEvaluationEngine#READY} must equal
 * "every conformance case passes for the jena engine". The guard evaluates every case directly with the rule engine
 * (its declared status is not consulted): a case passes when its contract graph matches as in {@link ConformanceTest}
 * AND its handler result meets the expectation of {@link HandlerCheck} (as in {@code ResultAdapterTest}). The guard
 * fails both when READY is true and a case fails and when READY is false although the whole corpus passes.
 *
 * <p>The rule engine reasons over the licensed ontology, which is not in the repository: without
 * {@code ontology/IntentCommonModel.ttl} the guard is skipped.
 */
class ReadinessGuardTest {

    private static final JavaEvaluationEngine JAVA = new JavaEvaluationEngine(new TurtleEvaluator());

    @Test
    void declaredReadinessAgreesWithTheConformanceSuite() {
        assumeTrue(Files.isRegularFile(ConformanceTest.ONTOLOGY),
                () -> "readiness guard skipped: the rule engine needs the licensed ontology ("
                        + ConformanceTest.ONTOLOGY + " is absent)");
        JenaEvaluationEngine jena = new JenaEvaluationEngine();
        List<String> failing = new ArrayList<>();
        List<ConformanceCase> cases = ConformanceLoader.loadAll();
        for (ConformanceCase c : cases) {
            String f = failure(jena, c);
            if (f != null) failing.add(f);
        }
        boolean allPass = failing.isEmpty();
        assertEquals(allPass, JenaEvaluationEngine.READY, () -> allPass
                ? "all " + cases.size() + " conformance cases pass for jena: set JenaEvaluationEngine.READY = true"
                : "JenaEvaluationEngine.READY is true but " + failing.size() + " of " + cases.size()
                        + " conformance cases fail for jena: " + failing);
    }

    /**
     * The case passes for the rule engine when both its contract graph matches ({@link ConformanceTest}) and its
     * handler result meets the case's handler expectation ({@link HandlerCheck}: Java's result, or handler.txt).
     * Eight cases have an empty contract (no IRI intent); only the handler check can fail for them.
     */
    private static String failure(JenaEvaluationEngine jena, ConformanceCase c) {
        Model expected = ConformanceTest.expectedFor("jena", c);
        if (expected == null) return c.name() + ": no expected.ttl";
        try {
            EvaluationInput in = new EvaluationInput(
                    "readiness-" + c.name(), c.expression(), c.observations(), c.resources(), c.held());
            EvaluationOutcome outcome = jena.evaluate(in);
            if (!ResultContract.sameResults(ResultContract.filter(outcome.resultGraph()), expected)) {
                return c.name() + ": contract graph differs";
            }
            return HandlerCheck.mismatch(c, JAVA.evaluate(in).result(), outcome.result());
        } catch (RuntimeException e) {
            return c.name() + ": " + e;
        }
    }
}
