package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.ModelFactory;
import org.springframework.stereotype.Component;
import org.tmforum.intent.handler.TurtleEvaluator;
import org.tmforum.intent.model.EvaluationResult;

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
        return new EvaluationOutcome(ModelFactory.createDefaultModel(), result);
    }
}
