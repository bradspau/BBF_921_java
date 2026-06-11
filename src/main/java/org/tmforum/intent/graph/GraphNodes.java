package org.tmforum.intent.graph;

/**
 * URI builder methods for TMF921 RDF resource nodes and named graphs.
 * Named graph URI doubles as the primary resource node URI.
 */
public final class GraphNodes {

    private GraphNodes() {}

    private static final String BASE = "http://tmforum.org/api/v5";

    public static String intentGraphUri(String intentId) {
        return BASE + "/intents/" + intentId;
    }

    public static String reportGraphUri(String reportId) {
        return BASE + "/reports/" + reportId;
    }

    public static String specGraphUri(String specId) {
        return BASE + "/intentSpecifications/" + specId;
    }

    public static String auditGraphUri(String changeId) {
        return BASE + "/audit/" + changeId;
    }

    public static String handlerStateGraphUri(String intentId) {
        return BASE + "/intents/" + intentId + "/handlerState";
    }

    public static String handlerStateConditionUri(String intentId, int index) {
        return BASE + "/intents/" + intentId + "/handlerState/condition/" + index;
    }

    public static String observationsGraphUri(String intentId) {
        return BASE + "/intents/" + intentId + "/observations";
    }

    // Resource node URIs — same value as graph URI
    public static String intentNode(String intentId)  { return intentGraphUri(intentId); }
    public static String reportNode(String reportId)  { return reportGraphUri(reportId); }
    public static String specNode(String specId)      { return specGraphUri(specId); }

    public static String expressionUri(String intentId) {
        return BASE + "/intents/" + intentId + "/expression";
    }

    public static String reportExpressionUri(String reportId) {
        return BASE + "/reports/" + reportId + "/expression";
    }

    public static String hubUri(String hubId) {
        return BASE + "/hubs/" + hubId;
    }

    public static String stateChangeUri(String changeId) {
        return BASE + "/statechanges/" + changeId;
    }

    /** Extract local name from a TMF URI (fragment or last path segment). */
    public static String localName(String uri) {
        if (uri == null) return null;
        int hash = uri.lastIndexOf('#');
        if (hash >= 0) return uri.substring(hash + 1);
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }
}
