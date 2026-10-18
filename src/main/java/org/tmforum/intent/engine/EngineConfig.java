package org.tmforum.intent.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Selects the active {@link EvaluationEngine} from {@code intent.evaluator}. */
@Configuration
public class EngineConfig {

    @Bean
    @Primary
    public EvaluationEngine activeEngine(JavaEvaluationEngine java, JenaEvaluationEngine jena,
                                         @Value("${intent.evaluator:java}") String mode) {
        return switch (mode == null ? "" : mode.trim().toLowerCase()) {
            case "java" -> java;
            case "jena" -> jena;
            case "compare" -> new CompareStub(java);
            default -> throw new IllegalArgumentException(
                    "intent.evaluator must be java, jena or compare but was '" + mode + "'");
        };
    }

    /** Placeholder for the comparing engine: delegates to the Java engine under the name "compare". */
    private record CompareStub(EvaluationEngine delegate) implements EvaluationEngine {
        @Override public String name() { return "compare"; }
        @Override public EngineStatus status() { return delegate.status(); }
        @Override public EvaluationOutcome evaluate(EvaluationInput in) { return delegate.evaluate(in); }
    }
}
