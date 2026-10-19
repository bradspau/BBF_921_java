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
            case "compare" -> new ComparingEngine(java, jena);
            default -> throw new IllegalArgumentException(
                    "intent.evaluator must be java, jena or compare but was '" + mode + "'");
        };
    }
}
