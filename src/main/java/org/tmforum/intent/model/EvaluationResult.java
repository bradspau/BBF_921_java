package org.tmforum.intent.model;

import java.util.List;
import java.util.Map;

/**
 * Result of evaluating a TIO expression against current observations.
 * Mirrors the Python evaluator's return shape:
 *   {"intentHandlingState": "Fulfilled"|"Degraded", "reason": ..., "conditions": [...]}
 * plus {@code report}, the element tree a TR290 intent report is built from (null when the expression
 * has nothing reportable).
 */
public record EvaluationResult(
        String intentHandlingState,
        String reason,
        List<Map<String, Object>> conditions,
        ReportTree report
) {
    public EvaluationResult(String intentHandlingState, String reason, List<Map<String, Object>> conditions) {
        this(intentHandlingState, reason, conditions, null);
    }

    public static EvaluationResult fulfilled(List<Map<String, Object>> conditions) {
        return new EvaluationResult("Fulfilled", null, conditions);
    }

    public static EvaluationResult degraded(String reason, List<Map<String, Object>> conditions) {
        return new EvaluationResult("Degraded", reason, conditions);
    }

    public EvaluationResult withReport(ReportTree report) {
        return new EvaluationResult(intentHandlingState, reason, conditions, report);
    }

    public boolean isFulfilled() {
        return "Fulfilled".equals(intentHandlingState);
    }
}
