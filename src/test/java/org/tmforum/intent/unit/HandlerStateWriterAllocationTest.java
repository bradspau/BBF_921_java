package org.tmforum.intent.unit;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.handler.HandlerStateWriter;
import org.tmforum.intent.model.EvaluationResult;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HandlerStateWriterAllocationTest {

    private static final String PON = "http://broadband-forum.org/ont/pon-resource#";

    private Dataset dataset;
    private HandlerStateWriter writer;

    @BeforeEach
    void setUp() {
        dataset = DatasetFactory.createTxnMem();
        Model m = ModelFactory.createDefaultModel();
        RDFDataMgr.read(m, new StringReader("""
                @prefix pon: <%s> .
                pon:UNI-1 a pon:UNIPort ; pon:inUse false .
                pon:CTAG-1 a pon:CTAGAllocation ; pon:inUse false .
                pon:SVLAN-1 a pon:SVLANAllocation .
                """.formatted(PON)), null, Lang.TURTLE);
        dataset.begin(ReadWrite.WRITE);
        dataset.addNamedModel(Namespaces.RESOURCES_GRAPH, m);
        dataset.commit();
        dataset.end();
        writer = new HandlerStateWriter(dataset);
    }

    @AfterEach
    void tearDown() {
        dataset.close();
    }

    private static EvaluationResult selected(boolean held, String... uris) {
        List<Map<String, Object>> conds = new java.util.ArrayList<>();
        for (String u : uris) {
            Map<String, Object> c = new java.util.LinkedHashMap<>();
            c.put("type", "DeliveryExpectation");
            c.put("selected", PON + u);
            c.put("passed", true);
            if (held) c.put("held", true);
            conds.add(c);
        }
        return EvaluationResult.fulfilled(conds);
    }

    private boolean ask(String body) {
        dataset.begin(ReadWrite.READ);
        try {
            return QueryExecutionFactory.create("PREFIX pon: <" + PON + "> ASK { GRAPH <"
                    + Namespaces.RESOURCES_GRAPH + "> { " + body + " } }", dataset).execAsk();
        } finally {
            dataset.end();
        }
    }

    @Test
    void allocatesExclusiveResources_butOnlySelectsSharedOnes() {
        writer.writeResourceAllocation("intent-a", selected(false, "UNI-1", "SVLAN-1", "CTAG-1"));

        assertTrue(ask("pon:UNI-1 pon:inUse true ; pon:assignedToService \"intent-a\""));
        assertTrue(ask("pon:CTAG-1 pon:inUse true ; pon:assignedToService \"intent-a\""));
        assertFalse(ask("pon:SVLAN-1 pon:assignedToService ?x"), "a shared S-VLAN is never claimed");
        assertEquals(List.of(PON + "CTAG-1", PON + "UNI-1"), writer.heldResources("intent-a"));
    }

    @Test
    void heldResourcesAreSkipped_andACompetitorCannotTakeAnAllocatedOne() {
        writer.writeResourceAllocation("intent-a", selected(false, "UNI-1"));

        // intent-b chose UNI-1 from a stale view of the pool: its claim must lose, silently changing nothing
        writer.writeResourceAllocation("intent-b", selected(false, "UNI-1"));

        assertTrue(ask("pon:UNI-1 pon:assignedToService \"intent-a\""));
        assertFalse(ask("pon:UNI-1 pon:assignedToService \"intent-b\""));
        assertTrue(writer.heldResources("intent-b").isEmpty());
    }

    @Test
    void completesAPartialAllocation_withoutTouchingWhatIsAlreadyHeld() {
        writer.writeResourceAllocation("intent-a", selected(false, "UNI-1"));          // C-tag still missing
        EvaluationResult again = EvaluationResult.fulfilled(List.of(
                Map.of("type", "DeliveryExpectation", "selected", PON + "UNI-1", "held", true, "passed", true),
                Map.of("type", "DeliveryExpectation", "selected", PON + "CTAG-1", "passed", true)));

        writer.writeResourceAllocation("intent-a", again);

        assertEquals(List.of(PON + "CTAG-1", PON + "UNI-1"), writer.heldResources("intent-a"));
    }
}
