package org.tmforum.intent.engine;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.springframework.stereotype.Component;
import org.tmforum.intent.graph.GraphNodes;

/** Keeps the latest evaluation result graph of each intent in its own named graph of the TDB2 dataset. */
@Component
public class InferredGraphStore {

    private final Dataset dataset;

    public InferredGraphStore(Dataset dataset) {
        this.dataset = dataset;
    }

    /** Replace the intent's inferred graph with the given one. */
    public void replace(String intentId, Model graph) {
        String uri = GraphNodes.inferredGraphUri(intentId);
        dataset.begin(ReadWrite.WRITE);
        try {
            if (dataset.containsNamedModel(uri)) dataset.removeNamedModel(uri);
            if (graph != null && !graph.isEmpty()) dataset.addNamedModel(uri, graph);
            dataset.commit();
        } finally {
            dataset.end();
        }
    }

    /** Remove the intent's inferred graph, if any. */
    public void drop(String intentId) {
        String uri = GraphNodes.inferredGraphUri(intentId);
        dataset.begin(ReadWrite.WRITE);
        try {
            if (dataset.containsNamedModel(uri)) dataset.removeNamedModel(uri);
            dataset.commit();
        } finally {
            dataset.end();
        }
    }
}
