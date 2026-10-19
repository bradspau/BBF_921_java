package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.stereotype.Component;
import org.tmforum.intent.handler.TurtleEvaluator;
import org.tmforum.intent.model.EvaluationResult;

import java.io.StringReader;

/** Engine backed by the hand-written {@link TurtleEvaluator}. */
@Component
public class JavaEvaluationEngine implements EvaluationEngine {

    private final TurtleEvaluator evaluator;

    public JavaEvaluationEngine(TurtleEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override public String name() { return "java"; }

    @Override public EngineStatus status() { return EngineStatus.ok(); }

    @Override
    public EvaluationOutcome evaluate(EvaluationInput in) {
        EvaluationResult result = evaluator.evaluate(in.expression(), in.observations(), in.resources(), in.held());
        Model observations = ModelFactory.createDefaultModel();
        if (in.observations() != null && !in.observations().isBlank()) {
            try {
                observations.read(new StringReader(in.observations()), EvaluationInputs.BASE, "TURTLE");
            } catch (Exception ignored) {
                // the evaluator has already reported the parse error; there is nothing to copy
            }
        }
        return new EvaluationOutcome(ResultGraphWriter.write(result.report(), observations), result);
    }
}
