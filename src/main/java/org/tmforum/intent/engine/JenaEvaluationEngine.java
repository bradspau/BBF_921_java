package org.tmforum.intent.engine;

import org.springframework.stereotype.Component;

/** Rule-based engine (placeholder until the rule engine is implemented). */
@Component
public class JenaEvaluationEngine implements EvaluationEngine {

    @Override public String name() { return "jena"; }

    @Override public EngineStatus status() { return EngineStatus.notReady("rule engine not implemented"); }

    @Override
    public EvaluationOutcome evaluate(EvaluationInput in) {
        throw new IllegalStateException("jena evaluation engine is not implemented");
    }
}
