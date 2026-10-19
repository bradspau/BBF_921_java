package org.tmforum.intent.unit;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.InferredGraphStore;
import org.tmforum.intent.graph.GraphNodes;

import static org.junit.jupiter.api.Assertions.*;

class InferredGraphStoreTest {

    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";

    private Model graphWith(String object) {
        Model m = ModelFactory.createDefaultModel();
        m.add(m.createResource("http://x/s"), m.createProperty("http://x/p"), object);
        return m;
    }

    private long size(Dataset ds) {
        ds.begin(ReadWrite.READ);
        try {
            String uri = GraphNodes.inferredGraphUri(ID);
            return ds.containsNamedModel(uri) ? ds.getNamedModel(uri).size() : 0;
        } finally {
            ds.end();
        }
    }

    @Test
    void replace_thenReplaceAgain_leavesOnlyTheLatest() {
        Dataset ds = DatasetFactory.createTxnMem();
        InferredGraphStore store = new InferredGraphStore(ds);
        store.replace(ID, graphWith("old"));
        store.replace(ID, graphWith("new"));

        ds.begin(ReadWrite.READ);
        try {
            Model m = ds.getNamedModel(GraphNodes.inferredGraphUri(ID));
            assertEquals(1, m.size());
            assertTrue(m.contains(null, null, "new"));
        } finally {
            ds.end();
        }
    }

    @Test
    void drop_removesTheGraph() {
        Dataset ds = DatasetFactory.createTxnMem();
        InferredGraphStore store = new InferredGraphStore(ds);
        store.replace(ID, graphWith("x"));
        assertEquals(1, size(ds));
        store.drop(ID);
        assertEquals(0, size(ds));
    }
}
