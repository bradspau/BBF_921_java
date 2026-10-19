package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.EvaluationInput;
import org.tmforum.intent.engine.EvaluationOutcome;
import org.tmforum.intent.engine.HandlerProjection;
import org.tmforum.intent.engine.JavaEvaluationEngine;
import org.tmforum.intent.engine.JenaEvaluationEngine;
import org.tmforum.intent.engine.ResultContract;
import org.tmforum.intent.handler.TurtleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the expression behind {@code docs/HSI_DEMO.md} ({@code seed_data/hsi_demo_intent.ttl}): the states the demo
 * shows at each step hold for the Java engine, and the rule engine gives the same contract graph and the same
 * handler result for it. The rule-engine comparison needs the licensed ontology and is skipped without it.
 */
class HsiDemoIntentTest {

    private static final String EXPRESSION = read("seed_data/hsi_demo_intent.ttl");
    private static final String NS = "http://broadband-forum.org/Intent#";
    private static final JavaEvaluationEngine JAVA = new JavaEvaluationEngine(new TurtleEvaluator());

    private static final String[] LATENCY_VIOLATED = {
            "DownstreamBandwidthMetric", "150", "Mbps", "UpstreamBandwidthMetric", "30", "Mbps",
            "LatencyMetric", "40", "ms", "JitterMetric", "1.2", "ms", "PacketLossMetric", "0.02", "percent"};

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EvaluationOutcome java(String... observations) {
        return JAVA.evaluate(new EvaluationInput("hsi-demo", EXPRESSION,
                observations.length == 0 ? "" : TestInputs.observations(observations), "", Set.of()));
    }

    private static boolean value(Model graph, String element) {
        Resource r = graph.getResource(NS + element);
        Property value = graph.getProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#value");
        assertTrue(graph.contains(r, value), element + " has no rdf:value in the result graph");
        return graph.getRequiredProperty(r, value).getBoolean();
    }

    @Test
    void withoutObservationsTheIntentIsDegradedAndOnlyTheStructuralChecksPass() {
        EvaluationOutcome o = java();
        assertEquals("Degraded", o.result().intentHandlingState());
        Model g = o.resultGraph();
        assertEquals(false, value(g, "HSIIntent"));
        assertEquals(false, value(g, "PerformanceCheck"));
        for (String c : new String[] {"DL_Check", "UL_Check", "Lat_Check", "Jit_Check", "PL_Check"}) {
            assertEquals(false, value(g, c), c);
        }
        assertEquals(true, value(g, "DeliveryCheck"));
        assertEquals(true, value(g, "UNICheck"));
        assertEquals(true, value(g, "UNIUpCondition"));
        assertEquals(true, value(g, "UNIReadyCondition"));
        assertEquals(true, value(g, "HSIMonitoringExpectation"));
    }

    @Test
    void withBandwidthOnlyTheThreeOtherMetricsAreStillMissing() {
        EvaluationOutcome o = java(TestInputs.BANDWIDTH_ONLY);
        assertEquals("Degraded", o.result().intentHandlingState());
        assertEquals(true, value(o.resultGraph(), "DL_Check"));
        assertEquals(true, value(o.resultGraph(), "UL_Check"));
        assertEquals(false, value(o.resultGraph(), "Lat_Check"));
    }

    @Test
    void withEveryMetricWithinItsBoundTheIntentIsFulfilled() {
        EvaluationOutcome o = java(TestInputs.ALL_OK);
        assertEquals("Fulfilled", o.result().intentHandlingState());
        assertEquals(true, value(o.resultGraph(), "HSIIntent"));
        assertEquals(true, value(o.resultGraph(), "PerformanceCheck"));
    }

    @Test
    void aLatencySpikeMakesTheIntentDegradedAndOnlyLatencyFails() {
        EvaluationOutcome o = java(LATENCY_VIOLATED);
        assertEquals("Degraded", o.result().intentHandlingState());
        assertEquals(false, value(o.resultGraph(), "Lat_Check"));
        assertEquals(true, value(o.resultGraph(), "DL_Check"));
        assertEquals(false, value(o.resultGraph(), "PerformanceCheck"));
        assertEquals(false, value(o.resultGraph(), "HSIIntent"));
    }

    @Test
    void theRuleEngineGivesTheSameContractAndHandlerResultAsJavaAtEveryStepOfTheDemo() {
        Path ontology = Path.of("ontology/IntentCommonModel.ttl");
        assumeTrue(Files.isRegularFile(ontology),
                () -> "rule-engine comparison skipped: the licensed ontology is absent (" + ontology + ")");
        JenaEvaluationEngine jena = new JenaEvaluationEngine();
        String[][] steps = {{}, TestInputs.BANDWIDTH_ONLY, TestInputs.ALL_OK, LATENCY_VIOLATED};
        for (String[] observations : steps) {
            EvaluationInput in = new EvaluationInput("hsi-demo-jena", EXPRESSION,
                    observations.length == 0 ? "" : TestInputs.observations(observations), "", Set.of());
            EvaluationOutcome expected = JAVA.evaluate(in);
            EvaluationOutcome actual = jena.evaluate(in);
            String label = observations.length / 3 + " metrics posted";
            assertTrue(ResultContract.sameResults(
                            ResultContract.filter(actual.resultGraph()), ResultContract.filter(expected.resultGraph())),
                    () -> label + ": contract graphs differ");
            assertNull(HandlerProjection.firstDifference(
                    EXPRESSION, expected.result(), "java", actual.result(), "jena"), label);
        }
    }

    @Test
    void theExpressionIsAnIcmIntentWithTheDocumentedElements() {
        Model m = ModelFactory.createDefaultModel();
        m.read(new java.io.StringReader(EXPRESSION), null, "TURTLE");
        Resource intent = m.getResource(NS + "HSIIntent");
        assertTrue(m.contains(intent, RDF.type, m.getResource(
                "http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/Intent")));
    }
}
