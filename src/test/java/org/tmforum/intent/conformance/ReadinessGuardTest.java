package org.tmforum.intent.conformance;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.EvaluationInput;
import org.tmforum.intent.engine.JenaEvaluationEngine;
import org.tmforum.intent.engine.ResultContract;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the rule engine's declared readiness (design 5.1, 5.6): {@link JenaEvaluationEngine#READY} must equal
 * "every conformance case passes for the jena engine". The guard evaluates every case directly with the rule engine
 * (its declared status is not consulted), with the same expectation and comparison as {@link ConformanceTest}, so it
 * fails both when READY is true and a case fails and when READY is false although the whole corpus passes.
 *
 * <p>The rule engine reasons over the licensed ontology, which is not in the repository: without
 * {@code ontology/IntentCommonModel.ttl} the guard is skipped.
 */
class ReadinessGuardTest {

    @Test
    void declaredReadinessAgreesWithTheConformanceSuite() {
        assumeTrue(Files.isRegularFile(ConformanceTest.ONTOLOGY),
                () -> "readiness guard skipped: the rule engine needs the licensed ontology ("
                        + ConformanceTest.ONTOLOGY + " is absent)");
        JenaEvaluationEngine jena = new JenaEvaluationEngine();
        List<String> failing = new ArrayList<>();
        List<ConformanceCase> cases = ConformanceLoader.loadAll();
        for (ConformanceCase c : cases) {
            if (!passes(jena, c)) failing.add(c.name());
        }
        boolean allPass = failing.isEmpty();
        assertEquals(allPass, JenaEvaluationEngine.READY, () -> allPass
                ? "all " + cases.size() + " conformance cases pass for jena: set JenaEvaluationEngine.READY = true"
                : "JenaEvaluationEngine.READY is true but " + failing.size() + " of " + cases.size()
                        + " conformance cases fail for jena: " + failing);
    }

    private static boolean passes(JenaEvaluationEngine jena, ConformanceCase c) {
        Model expected = ConformanceTest.expectedFor("jena", c);
        if (expected == null) return false;
        try {
            Model actual = ResultContract.filter(jena.evaluate(new EvaluationInput(
                    "readiness-" + c.name(), c.expression(), c.observations(), c.resources(), c.held())).resultGraph());
            return ResultContract.sameResults(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
