package org.tmforum.intent.handler;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.update.*;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Service;
import org.tmforum.intent.graph.GraphNodes;
import org.tmforum.intent.graph.TioNamespaces;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Persists and retrieves met:Observation triples in the observations named graph
 * (http://tmforum.org/api/v5/intents/{uuid}/observations).
 *
 * Observation format (same as Python observation_store.py):
 *   <obs_uri> a met:Observation ;
 *       met:observedMetric <metric_uri> ;
 *       rdf:value "42.5"^^xsd:decimal ;
 *       met:obtainedAt "2026-06-11T10:00:00Z"^^xsd:dateTime .
 */
@Service
public class ObservationStore {

    static final int MAX_OBS_PER_METRIC = 10;

    private static final String BASE = "http://tmforum.org/api/v5";
    private static final String MET = TioNamespaces.MET;

    private final Dataset dataset;

    public ObservationStore(Dataset dataset) {
        this.dataset = dataset;
    }

    /**
     * Persist a single observation for an intent's metric.
     *
     * @param intentId   Intent UUID
     * @param metricUri  Metric URI (the thing being measured)
     * @param value      Observed value
     * @param obtainedAt ISO-8601 timestamp (null → now)
     * @return observation URI
     */
    public String writeObservation(String intentId, String metricUri,
                                    BigDecimal value, String obtainedAt) {
        String obsId = UUID.randomUUID().toString();
        String obsUri = BASE + "/intents/" + intentId + "/observations/" + obsId;
        String graphUri = GraphNodes.observationsGraphUri(intentId);
        String timestamp = (obtainedAt != null && !obtainedAt.isBlank())
                ? obtainedAt : Instant.now().toString();

        dataset.begin(ReadWrite.WRITE);
        try {
            Model graphModel = dataset.containsNamedModel(graphUri)
                    ? dataset.getNamedModel(graphUri)
                    : ModelFactory.createDefaultModel();
            Resource obs = graphModel.createResource(obsUri);
            obs.addProperty(RDF.type, graphModel.createResource(MET + "Observation"));
            obs.addProperty(graphModel.createProperty(MET + "observedMetric"),
                    graphModel.createResource(metricUri));
            obs.addProperty(RDF.value,
                    graphModel.createTypedLiteral(value.toPlainString(), XSDDatatype.XSDdecimal));
            obs.addProperty(graphModel.createProperty(MET + "obtainedAt"),
                    graphModel.createTypedLiteral(timestamp, XSDDatatype.XSDdateTime));
            dataset.addNamedModel(graphUri, graphModel);
            dataset.commit();
        } catch (Exception e) {
            dataset.abort();
            throw new RuntimeException("Failed to write observation for intent " + intentId, e);
        } finally {
            dataset.end();
        }

        pruneOldObservations(intentId, metricUri, MAX_OBS_PER_METRIC);
        return obsUri;
    }

    /**
     * Read the observations named graph as a Turtle string for use in evaluation.
     *
     * @param intentId Intent UUID
     * @return Turtle string (empty string if no observations exist)
     */
    public String getObservationsTurtle(String intentId) {
        String graphUri = GraphNodes.observationsGraphUri(intentId);
        dataset.begin(ReadWrite.READ);
        try {
            if (!dataset.containsNamedModel(graphUri)) return "";
            Model model = dataset.getNamedModel(graphUri);
            if (model.isEmpty()) return "";
            StringWriter sw = new StringWriter();
            RDFDataMgr.write(sw, model, Lang.TURTLE);
            return sw.toString();
        } finally {
            dataset.end();
        }
    }

    /**
     * Prune old observations to keep at most {@code maxPerMetric} per metric URI.
     * Retains the most recent {@code maxPerMetric} by met:obtainedAt timestamp.
     */
    void pruneOldObservations(String intentId, String metricUri, int maxPerMetric) {
        String graphUri = GraphNodes.observationsGraphUri(intentId);
        String sparql = """
                SELECT ?obs ?t WHERE {
                  GRAPH <%s> {
                    ?obs a <%sObservation> ;
                         <%sobservedMetric> <%s> ;
                         <%sobtainedAt> ?t .
                  }
                } ORDER BY DESC(?t)
                """.formatted(graphUri, MET, MET, metricUri, MET);

        dataset.begin(ReadWrite.READ);
        List<String> allObs;
        try {
            allObs = new ArrayList<>();
            try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution row = rs.next();
                    allObs.add(row.getResource("obs").getURI());
                }
            }
        } finally {
            dataset.end();
        }

        if (allObs.size() <= maxPerMetric) return;

        List<String> toDelete = allObs.subList(maxPerMetric, allObs.size());
        dataset.begin(ReadWrite.WRITE);
        try {
            for (String obsUri : toDelete) {
                String del = "DELETE WHERE { GRAPH <%s> { <%s> ?p ?o } }"
                        .formatted(graphUri, obsUri);
                UpdateRequest req = UpdateFactory.create(del);
                UpdateExecutionFactory.create(req, dataset).execute();
            }
            dataset.commit();
        } catch (Exception e) {
            dataset.abort();
            throw new RuntimeException("Failed to prune observations", e);
        } finally {
            dataset.end();
        }
    }

    /**
     * Delete all observations for an intent (called when the intent is deleted).
     */
    public void deleteAllObservations(String intentId) {
        String graphUri = GraphNodes.observationsGraphUri(intentId);
        dataset.begin(ReadWrite.WRITE);
        try {
            if (dataset.containsNamedModel(graphUri)) {
                dataset.removeNamedModel(graphUri);
            }
            dataset.commit();
        } catch (Exception e) {
            dataset.abort();
            throw new RuntimeException("Failed to delete observations for intent " + intentId, e);
        } finally {
            dataset.end();
        }
    }

    /**
     * Count observations for a given metric within an intent's observation graph.
     */
    public long countObservations(String intentId, String metricUri) {
        String graphUri = GraphNodes.observationsGraphUri(intentId);
        String sparql = """
                SELECT (COUNT(?obs) AS ?n) WHERE {
                  GRAPH <%s> {
                    ?obs a <%sObservation> ;
                         <%sobservedMetric> <%s> .
                  }
                }
                """.formatted(graphUri, MET, MET, metricUri);
        dataset.begin(ReadWrite.READ);
        try (QueryExecution qe = QueryExecutionFactory.create(sparql, dataset)) {
            ResultSet rs = qe.execSelect();
            if (rs.hasNext()) {
                QuerySolution row = rs.next();
                return row.getLiteral("n").getLong();
            }
            return 0;
        } finally {
            dataset.end();
        }
    }
}
