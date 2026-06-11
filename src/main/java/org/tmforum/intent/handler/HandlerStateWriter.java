package org.tmforum.intent.handler;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.*;
import org.apache.jena.update.UpdateExecutionFactory;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tmforum.intent.graph.GraphNodes;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.model.EvaluationResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Writes evaluation results to the intent's handlerState named graph (OODA working memory).
 *
 * Named graph URI: http://tmforum.org/api/v5/intents/{uuid}/handlerState
 *
 * Replaces the entire graph on each evaluation cycle (atomic overwrite).
 * Mirrors Python's write_handler_state() + build_handler_state_turtle().
 */
@Service
public class HandlerStateWriter {

    private static final Logger log = LoggerFactory.getLogger(HandlerStateWriter.class);

    private static final String IMO  = "http://tio.models.tmforum.org/tio/v3.6.0/IntentManagementOntology/";
    private static final String QUAN = "http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/";
    private static final String PON  = "http://broadband-forum.org/ont/pon-resource#";

    private final Dataset dataset;

    public HandlerStateWriter(Dataset dataset) {
        this.dataset = dataset;
    }

    /**
     * Persist evaluation result to the handlerState named graph.
     * Failures are logged and swallowed so graph write errors don't block reporting.
     */
    public void writeHandlerState(String intentId, EvaluationResult result) {
        String graphUri = GraphNodes.handlerStateGraphUri(intentId);
        String intentNodeUri = GraphNodes.intentNode(intentId);
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        try {
            Model model = buildHandlerStateModel(intentId, intentNodeUri, result, timestamp);
            dataset.begin(ReadWrite.WRITE);
            try {
                if (dataset.containsNamedModel(graphUri)) {
                    dataset.removeNamedModel(graphUri);
                }
                dataset.addNamedModel(graphUri, model);
                dataset.commit();
            } catch (Exception e) {
                dataset.abort();
                throw e;
            } finally {
                dataset.end();
            }
            log.debug("writeHandlerState: {} conditions for intent {} → {}",
                    result.conditions().size(), intentId, result.intentHandlingState());
        } catch (Exception e) {
            log.error("writeHandlerState: failed for intent {}: {}", intentId, e.getMessage());
        }
    }

    private Model buildHandlerStateModel(String intentId, String intentNodeUri,
                                          EvaluationResult result, String timestamp) {
        Model model = ModelFactory.createDefaultModel();
        Resource intentNode = model.createResource(intentNodeUri);

        // Intent node state triples
        intentNode.addProperty(model.createProperty(IMO + "intentHandlingState"),
                model.createResource(IMO + result.intentHandlingState()));
        intentNode.addProperty(model.createProperty(IMO + "lastEvaluated"),
                model.createTypedLiteral(timestamp, XSDDatatype.XSDdateTime));

        List<Map<String, Object>> conditions = result.conditions();
        for (int i = 0; i < conditions.size(); i++) {
            Resource condNode = model.createResource(
                    GraphNodes.handlerStateConditionUri(intentId, i));
            intentNode.addProperty(model.createProperty(IMO + "hasConditionResult"), condNode);
            buildConditionNode(model, condNode, conditions.get(i), timestamp);
        }
        return model;
    }

    private void buildConditionNode(Model model, Resource condNode,
                                     Map<String, Object> c, String timestamp) {
        String type = (String) c.get("type");
        if (type != null) {
            condNode.addProperty(RDF.type, model.createResource(QUAN + type));
        }

        boolean passed = Boolean.TRUE.equals(c.get("passed"));
        condNode.addProperty(model.createProperty(IMO + "conditionPassed"),
                model.createTypedLiteral(passed, XSDDatatype.XSDboolean));

        if (c.containsKey("error")) {
            condNode.addProperty(model.createProperty(IMO + "conditionError"),
                    model.createTypedLiteral(String.valueOf(c.get("error")), XSDDatatype.XSDstring));
        } else if ("quaninRange".equals(type)) {
            addDecimal(model, condNode, IMO + "observedValue", c.get("observed"));
            addDecimal(model, condNode, IMO + "lowerBound", c.get("lower"));
            addDecimal(model, condNode, IMO + "upperBound", c.get("upper"));
        } else if (c.containsKey("observed")) {
            addDecimal(model, condNode, IMO + "observedValue", c.get("observed"));
            addDecimal(model, condNode, IMO + "boundValue", c.get("bound"));
        } else if (c.containsKey("selected")) {
            condNode.addProperty(model.createProperty(IMO + "selectedResource"),
                    model.createResource(String.valueOf(c.get("selected"))));
        }

        condNode.addProperty(model.createProperty(IMO + "evaluatedAt"),
                model.createTypedLiteral(timestamp, XSDDatatype.XSDdateTime));
    }

    private void addDecimal(Model model, Resource node, String predUri, Object value) {
        if (value == null) return;
        try {
            String val = value instanceof BigDecimal bd ? bd.toPlainString() : value.toString();
            node.addProperty(model.createProperty(predUri),
                    model.createTypedLiteral(val, XSDDatatype.XSDdecimal));
        } catch (Exception ignored) {}
    }

    /**
     * Mark selected resources as in-use in the resource inventory graph.
     * Flow 4 (Resource Allocation) — idempotent via WHERE clause guard.
     */
    public void writeResourceAllocation(String intentId, EvaluationResult result) {
        List<Map<String, Object>> conditions = result.conditions();
        String resourcesGraph = Namespaces.RESOURCES_GRAPH;

        for (Map<String, Object> c : conditions) {
            if (!"DeliveryExpectation".equals(c.get("type"))) continue;
            Object sel = c.get("selected");
            if (sel == null) continue;
            String resourceUri = sel.toString();

            String sparql = String.format("""
                    PREFIX pon: <%s>
                    WITH <%s>
                    DELETE { <%s> pon:inUse false }
                    INSERT { <%s> pon:inUse true ;
                                  pon:assignedToService "%s" }
                    WHERE  { <%s> pon:inUse false }
                    """, PON, resourcesGraph, resourceUri, resourceUri, intentId, resourceUri);

            dataset.begin(ReadWrite.WRITE);
            try {
                UpdateExecutionFactory.create(UpdateFactory.create(sparql), dataset).execute();
                dataset.commit();
                log.info("writeResourceAllocation: allocated {} to intent {}", resourceUri, intentId);
            } catch (Exception e) {
                dataset.abort();
                log.error("writeResourceAllocation: failed for {}/intent {}: {}",
                        resourceUri, intentId, e.getMessage());
            } finally {
                dataset.end();
            }
        }
    }

    /**
     * Check if resources are already allocated to this intent (idempotency guard).
     */
    public boolean resourcesAlreadyAllocated(String intentId) {
        String sparql = String.format("""
                ASK { GRAPH <%s> { ?r <%sassignedToService> "%s" } }
                """, Namespaces.RESOURCES_GRAPH, PON, intentId);
        dataset.begin(ReadWrite.READ);
        try {
            return org.apache.jena.query.QueryExecutionFactory
                    .create(sparql, dataset).execAsk();
        } finally {
            dataset.end();
        }
    }
}
