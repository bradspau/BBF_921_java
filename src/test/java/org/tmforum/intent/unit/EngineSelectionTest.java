package org.tmforum.intent.unit;

import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.*;

import static org.junit.jupiter.api.Assertions.*;

class EngineSelectionTest {

    private final EngineConfig config = new EngineConfig();
    private final JavaEvaluationEngine java = new JavaEvaluationEngine(null);
    private final JenaEvaluationEngine jena = new JenaEvaluationEngine();

    @Test
    void javaIsReady() {
        EvaluationEngine e = config.activeEngine(java, jena, "java");
        assertEquals("java", e.name());
        assertTrue(e.status().ready());
    }

    @Test
    void jenaIsNotReadyWithReason() {
        EvaluationEngine e = config.activeEngine(java, jena, "jena");
        assertEquals("jena", e.name());
        assertFalse(e.status().ready());
        assertFalse(e.status().reason().isBlank());
    }

    @Test
    void compareIsSelectable() {
        assertEquals("compare", config.activeEngine(java, jena, "compare").name());
    }

    @Test
    void bogusRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> config.activeEngine(java, jena, "bogus"));
        assertTrue(ex.getMessage().contains("intent.evaluator must be java, jena or compare"));
    }
}
