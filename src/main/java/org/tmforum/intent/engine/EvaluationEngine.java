package org.tmforum.intent.engine;

/** A pluggable strategy that evaluates an intent's expression against observations and resources. */
public interface EvaluationEngine {
    String name();
    EngineStatus status();
    EvaluationOutcome evaluate(EvaluationInput in);
}
