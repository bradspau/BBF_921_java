package org.tmforum.intent.service;

import java.util.Set;

/**
 * Lifecycle FSM for TMF921 Intent resources.
 *
 * States: ACKNOWLEDGED, ACTIVE, FULFILLED, DEGRADED, SUSPENDED, TERMINATED
 * TERMINATED is terminal — no exit transitions.
 */
public final class StateMachine {

    private StateMachine() {}

    public static final Set<String> ALL_STATES = Set.of(
            "ACKNOWLEDGED", "ACTIVE", "FULFILLED", "DEGRADED", "SUSPENDED", "TERMINATED");

    public static final Set<String> TERMINAL_STATES = Set.of("TERMINATED");

    // Valid (from → to) pairs
    private record Transition(String from, String to) {}

    private static final Set<Transition> TRANSITIONS = Set.of(
            new Transition("ACKNOWLEDGED", "ACTIVE"),
            new Transition("ACKNOWLEDGED", "TERMINATED"),
            new Transition("ACTIVE",       "FULFILLED"),
            new Transition("ACTIVE",       "DEGRADED"),
            new Transition("ACTIVE",       "SUSPENDED"),
            new Transition("ACTIVE",       "TERMINATED"),
            new Transition("FULFILLED",    "ACTIVE"),
            new Transition("FULFILLED",    "TERMINATED"),
            new Transition("DEGRADED",     "ACTIVE"),
            new Transition("DEGRADED",     "SUSPENDED"),
            new Transition("DEGRADED",     "TERMINATED"),
            new Transition("SUSPENDED",    "ACTIVE"),
            new Transition("SUSPENDED",    "TERMINATED")
    );

    /**
     * Validate a lifecycle transition.
     *
     * @throws IllegalArgumentException with an HTTP-400-appropriate message if invalid.
     * @throws IllegalStateException    with an HTTP-422-appropriate message if toStatus unknown.
     */
    public static void validateTransition(String fromStatus, String toStatus) {
        if (!ALL_STATES.contains(toStatus)) {
            throw new IllegalStateException("Unknown lifecycleStatus: '" + toStatus + "'");
        }
        if (fromStatus.equals(toStatus)) return; // no-op always valid
        if (TERMINAL_STATES.contains(fromStatus)) {
            throw new IllegalArgumentException(
                    "Intent is in terminal state '" + fromStatus
                    + "'; no further transitions are allowed.");
        }
        if (!TRANSITIONS.contains(new Transition(fromStatus, toStatus))) {
            throw new IllegalArgumentException(
                    "Invalid lifecycle transition: '" + fromStatus + "' → '" + toStatus + "'");
        }
    }

    /** True if a transition is valid (no exception thrown). */
    public static boolean isValidTransition(String fromStatus, String toStatus) {
        try {
            validateTransition(fromStatus, toStatus);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }
}
