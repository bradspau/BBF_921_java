package org.tmforum.intent.model;

import java.util.List;
import java.util.Map;

/**
 * Result of evaluating a TIO expression against current observations.
 * Mirrors the Python evaluator's return shape:
 *   {"intentHandlingState": "Fulfilled"|"Degraded", "reason": ..., "conditions": [...]}
 */
public record EvaluationResult(
        String intentHandlingState,
        String reason,
        List<Map<String, Object>> conditions
) {
    public static EvaluationResult fulfilled(List<Map<String, Object>> conditions) {
        return new EvaluationResult("Fulfilled", null, conditions);
    }

    public static EvaluationResult degraded(String reason, List<Map<String, Object>> conditions) {
        return new EvaluationResult("Degraded", reason, conditions);
    }

    public boolean isFulfilled() {
        return "Fulfilled".equals(intentHandlingState);
    }
}
