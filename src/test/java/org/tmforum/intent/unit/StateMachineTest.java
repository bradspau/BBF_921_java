package org.tmforum.intent.unit;

import org.junit.jupiter.api.Test;
import org.tmforum.intent.service.StateMachine;

import static org.junit.jupiter.api.Assertions.*;

class StateMachineTest {

    // ── Valid transitions ──────────────────────────────────────────────────────

    @Test
    void acknowledged_to_active_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("ACKNOWLEDGED", "ACTIVE"));
    }

    @Test
    void acknowledged_to_terminated_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("ACKNOWLEDGED", "TERMINATED"));
    }

    @Test
    void active_to_fulfilled_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("ACTIVE", "FULFILLED"));
    }

    @Test
    void active_to_degraded_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("ACTIVE", "DEGRADED"));
    }

    @Test
    void active_to_suspended_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("ACTIVE", "SUSPENDED"));
    }

    @Test
    void active_to_terminated_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("ACTIVE", "TERMINATED"));
    }

    @Test
    void fulfilled_to_active_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("FULFILLED", "ACTIVE"));
    }

    @Test
    void degraded_to_active_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("DEGRADED", "ACTIVE"));
    }

    @Test
    void degraded_to_suspended_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("DEGRADED", "SUSPENDED"));
    }

    @Test
    void suspended_to_active_isValid() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("SUSPENDED", "ACTIVE"));
    }

    // ── No-op transitions (same → same) are always valid ──────────────────────

    @Test
    void noOp_acknowledged_to_acknowledged() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("ACKNOWLEDGED", "ACKNOWLEDGED"));
    }

    @Test
    void noOp_active_to_active() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("ACTIVE", "ACTIVE"));
    }

    @Test
    void noOp_terminated_to_terminated() {
        assertDoesNotThrow(() -> StateMachine.validateTransition("TERMINATED", "TERMINATED"));
    }

    // ── Invalid transitions ────────────────────────────────────────────────────

    @Test
    void acknowledged_to_fulfilled_isInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> StateMachine.validateTransition("ACKNOWLEDGED", "FULFILLED"));
    }

    @Test
    void acknowledged_to_degraded_isInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> StateMachine.validateTransition("ACKNOWLEDGED", "DEGRADED"));
    }

    @Test
    void terminated_to_active_isInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> StateMachine.validateTransition("TERMINATED", "ACTIVE"));
    }

    @Test
    void terminated_to_acknowledged_isInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> StateMachine.validateTransition("TERMINATED", "ACKNOWLEDGED"));
    }

    @Test
    void fulfilled_to_degraded_isInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> StateMachine.validateTransition("FULFILLED", "DEGRADED"));
    }

    // ── Unknown target state ───────────────────────────────────────────────────

    @Test
    void unknownTargetState_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> StateMachine.validateTransition("ACTIVE", "UNKNOWN_STATE"));
    }

    @Test
    void unknownTargetState_lowercase_throwsIllegalStateException() {
        // States must be ALL_CAPS
        assertThrows(IllegalStateException.class,
                () -> StateMachine.validateTransition("ACTIVE", "fulfilled"));
    }

    // ── isValidTransition helper ───────────────────────────────────────────────

    @Test
    void isValidTransition_returnsTrue_forValid() {
        assertTrue(StateMachine.isValidTransition("ACTIVE", "FULFILLED"));
    }

    @Test
    void isValidTransition_returnsFalse_forInvalid() {
        assertFalse(StateMachine.isValidTransition("ACKNOWLEDGED", "FULFILLED"));
    }

    @Test
    void isValidTransition_returnsFalse_forUnknownState() {
        assertFalse(StateMachine.isValidTransition("ACTIVE", "LIMBO"));
    }

    // ── Terminal state guard ───────────────────────────────────────────────────

    @Test
    void terminated_noExitTransitions() {
        for (String state : StateMachine.ALL_STATES) {
            if ("TERMINATED".equals(state)) continue;
            assertFalse(StateMachine.isValidTransition("TERMINATED", state),
                    "TERMINATED → " + state + " should be invalid");
        }
    }

    // ── ALL_STATES covers expected values ─────────────────────────────────────

    @Test
    void allStates_containsExpectedValues() {
        assertTrue(StateMachine.ALL_STATES.contains("ACKNOWLEDGED"));
        assertTrue(StateMachine.ALL_STATES.contains("ACTIVE"));
        assertTrue(StateMachine.ALL_STATES.contains("FULFILLED"));
        assertTrue(StateMachine.ALL_STATES.contains("DEGRADED"));
        assertTrue(StateMachine.ALL_STATES.contains("SUSPENDED"));
        assertTrue(StateMachine.ALL_STATES.contains("TERMINATED"));
        assertEquals(6, StateMachine.ALL_STATES.size());
    }
}
