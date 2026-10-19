package org.tmforum.intent.unit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tmforum.intent.engine.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void jenaIsReadyWithTheOntology() {
        assumeTrue(Files.isRegularFile(Path.of("ontology/IntentCommonModel.ttl")),
                "needs the licensed ontology in ontology/ (gitignored; present only on the owner's machine)");
        EvaluationEngine e = config.activeEngine(java, jena, "jena");
        assertEquals("jena", e.name());
        assertTrue(e.status().ready(), () -> "not ready: " + e.status().reason());
    }

    @Test
    void jenaIsNotReadyWithReasonWithoutTheOntology(@TempDir Path empty) {
        EvaluationEngine e = config.activeEngine(java, new JenaEvaluationEngine(empty), "jena");
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
