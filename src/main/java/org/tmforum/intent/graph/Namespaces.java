package org.tmforum.intent.graph;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

public final class Namespaces {

    private Namespaces() {}

    public static final String TMF    = "http://tmforum.org/api/v5/";
    public static final String DCTERMS = "http://purl.org/dc/terms/";
    public static final String RDF    = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    public static final String XSD    = "http://www.w3.org/2001/XMLSchema#";

    // Named graph URIs (static, not per-resource)
    public static final String ONTOLOGY_GRAPH  = "http://tmforum.org/api/v5/ontology";
    public static final String HUBS_GRAPH      = "http://tmforum.org/api/v5/hubs";
    public static final String RESOURCES_GRAPH = "http://tmforum.org/api/v5/resources";

    // RDF class resources
    public static final Resource CLASS_INTENT          = ResourceFactory.createResource(TMF + "Intent");
    public static final Resource CLASS_PROBE_INTENT    = ResourceFactory.createResource(TMF + "ProbeIntent");
    public static final Resource CLASS_INTENT_REPORT   = ResourceFactory.createResource(TMF + "IntentReport");
    public static final Resource CLASS_INTENT_SPEC     = ResourceFactory.createResource(TMF + "IntentSpecification");
    public static final Resource CLASS_HUB             = ResourceFactory.createResource(TMF + "Hub");
    public static final Resource CLASS_STATE_CHANGE    = ResourceFactory.createResource(TMF + "StateChange");

    // Predicate properties
    public static final Property PRED_ID               = ResourceFactory.createProperty(TMF, "id");
    public static final Property PRED_HREF             = ResourceFactory.createProperty(TMF, "href");
    public static final Property PRED_NAME             = ResourceFactory.createProperty(TMF, "name");
    public static final Property PRED_DESCRIPTION      = ResourceFactory.createProperty(TMF, "description");
    public static final Property PRED_LIFECYCLE_STATUS = ResourceFactory.createProperty(TMF, "lifecycleStatus");
    public static final Property PRED_STATUS_CHANGE_DATE = ResourceFactory.createProperty(TMF, "statusChangeDate");
    public static final Property PRED_BASE_TYPE        = ResourceFactory.createProperty(TMF, "baseType");
    public static final Property PRED_SCHEMA_LOCATION  = ResourceFactory.createProperty(TMF, "schemaLocation");
    public static final Property PRED_HAS_EXPRESSION   = ResourceFactory.createProperty(TMF, "hasExpression");
    public static final Property PRED_EXPRESSION_IRI   = ResourceFactory.createProperty(TMF, "expressionIri");
    public static final Property PRED_EXPRESSION_TYPE  = ResourceFactory.createProperty(TMF, "expressionType");
    public static final Property PRED_EXPRESSION_VALUE = ResourceFactory.createProperty(TMF, "expressionValue");
    public static final Property PRED_IS_BUNDLE        = ResourceFactory.createProperty(TMF, "isBundle");
    public static final Property PRED_VERSION          = ResourceFactory.createProperty(TMF, "version");
    public static final Property PRED_PRIORITY         = ResourceFactory.createProperty(TMF, "priority");
    public static final Property PRED_CONTEXT          = ResourceFactory.createProperty(TMF, "context");
    public static final Property PRED_CREATED          = ResourceFactory.createProperty(DCTERMS, "created");
    public static final Property PRED_MODIFIED         = ResourceFactory.createProperty(DCTERMS, "modified");

    // SPARQL prefix block used in all queries
    public static final String SPARQL_PREFIXES =
            "PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n" +
            "PREFIX xsd:     <http://www.w3.org/2001/XMLSchema#>\n" +
            "PREFIX tmf:     <http://tmforum.org/api/v5/>\n" +
            "PREFIX dcterms: <http://purl.org/dc/terms/>\n";
}
