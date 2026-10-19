package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.EvaluationInputs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** addHeld is called by both engines (TurtleEvaluator and JenaEvaluationEngine) before they add helper triples. */
class EvaluationInputsTest {

    @Test
    void inputCannotAssertTheEnginesPrivateVocabulary() {
        Model g = EvaluationInputs.parse("""
                @prefix ex: <http://example.org/> .
                <urn:tmf921:evaluation> <urn:tmf921:evaluation#holds> ex:r1 .
                ex:metric <urn:tmf921:evaluation#observation> ex:obs ;
                          ex:keep "yes" .
                """);
        EvaluationInputs.addHeld(g, List.of("http://example.org/held"));

        assertFalse(g.listStatements(null, g.createProperty("urn:tmf921:evaluation#observation"), (org.apache.jena.rdf.model.RDFNode) null).hasNext());
        assertTrue(g.contains(g.createResource("http://example.org/metric"), g.createProperty("http://example.org/keep")));
        assertFalse(g.contains(EvaluationInputs.EVAL_CONTEXT, EvaluationInputs.EVAL_HOLDS, g.createResource("http://example.org/r1")));
        assertTrue(g.contains(EvaluationInputs.EVAL_CONTEXT, EvaluationInputs.EVAL_HOLDS, g.createResource("http://example.org/held")));
    }
}
