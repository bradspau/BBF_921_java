package org.tmforum.intent.graph.repositories;

import org.apache.jena.query.Dataset;
import org.springframework.stereotype.Repository;
import org.tmforum.intent.graph.GraphNodes;

import java.util.*;

@Repository
public class IntentReportRepository extends BaseRepository {

    private static final String GET_SELECT =
            "?id ?href ?name ?type ?baseType ?schemaLocation " +
            "?created ?exprType ?exprIri ?exprValue ?parentIntent " +
            "?intentHandlingState ?intentHandlingReason";

    public IntentReportRepository(Dataset dataset) {
        super(dataset);
    }

    public Map<String, Object> create(String intentId, Map<String, Object> data) {
        String reportId = (String) data.get("id");
        String graphUri = GraphNodes.reportGraphUri(reportId);
        String reportUri = GraphNodes.reportNode(reportId);
        String intentUri = GraphNodes.intentNode(intentId);
        String exprUri = GraphNodes.reportExpressionUri(reportId);

        StringBuilder sb = new StringBuilder();
        sb.append("INSERT DATA {\n    GRAPH <").append(graphUri).append("> {\n");
        sb.append("        <").append(reportUri).append("> rdf:type tmf:IntentReport .\n");
        sb.append("        <").append(reportUri).append("> tmf:id \"").append(esc((String) data.get("id"))).append("\" .\n");
        sb.append("        <").append(reportUri).append("> tmf:href \"").append(esc((String) data.get("href"))).append("\" .\n");
        sb.append("        <").append(reportUri).append("> tmf:name \"").append(esc((String) data.get("name"))).append("\" .\n");
        sb.append("        <").append(reportUri).append("> dcterms:created \"").append(esc((String) data.get("creationDate"))).append("\"^^xsd:dateTime .\n");
        sb.append("        <").append(reportUri).append("> tmf:parentIntent <").append(intentUri).append("> .\n");

        if (data.get("@baseType") != null) {
            sb.append("        <").append(reportUri).append("> tmf:baseType \"").append(esc((String) data.get("@baseType"))).append("\" .\n");
        }
        if (data.get("intentHandlingState") != null) {
            sb.append("        <").append(reportUri).append("> tmf:intentHandlingState \"").append(esc((String) data.get("intentHandlingState"))).append("\" .\n");
        }
        if (data.get("intentHandlingReason") != null) {
            sb.append("        <").append(reportUri).append("> tmf:intentHandlingReason \"").append(esc((String) data.get("intentHandlingReason"))).append("\" .\n");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> expr = (Map<String, Object>) data.get("expression");
        if (expr != null) {
            String exprType = (String) expr.getOrDefault("@type", "JsonLdExpression");
            sb.append("        <").append(reportUri).append("> tmf:hasExpression <").append(exprUri).append("> .\n");
            sb.append("        <").append(exprUri).append("> rdf:type tmf:").append(esc(exprType)).append(" .\n");
            if (expr.get("iri") != null) {
                sb.append("        <").append(exprUri).append("> tmf:expressionIri \"").append(esc((String) expr.get("iri"))).append("\" .\n");
            }
            if (expr.get("expressionValue") != null) {
                String raw = serExprValue(expr.get("expressionValue"));
                sb.append("        <").append(exprUri).append("> tmf:expressionValue \"").append(esc(raw)).append("\"^^xsd:string .\n");
            }
        }

        sb.append("    }\n}");
        execUpdate(sb.toString());
        return data;
    }

    public Map<String, Object> findById(String intentId, String reportId) {
        String graphUri = GraphNodes.reportGraphUri(reportId);
        String reportUri = GraphNodes.reportNode(reportId);
        String intentUri = GraphNodes.intentNode(intentId);
        String sparql = "SELECT " + GET_SELECT + "\nWHERE {\n    GRAPH <" + graphUri + "> {\n" +
                "        <" + reportUri + "> rdf:type tmf:IntentReport .\n" +
                "        OPTIONAL { <" + reportUri + "> tmf:id ?id }\n" +
                "        OPTIONAL { <" + reportUri + "> tmf:href ?href }\n" +
                "        OPTIONAL { <" + reportUri + "> tmf:name ?name }\n" +
                "        OPTIONAL { <" + reportUri + "> rdf:type ?type }\n" +
                "        OPTIONAL { <" + reportUri + "> tmf:baseType ?baseType }\n" +
                "        OPTIONAL { <" + reportUri + "> tmf:schemaLocation ?schemaLocation }\n" +
                "        OPTIONAL { <" + reportUri + "> dcterms:created ?created }\n" +
                "        OPTIONAL { <" + reportUri + "> tmf:parentIntent ?parentIntent }\n" +
                "        OPTIONAL { <" + reportUri + "> tmf:intentHandlingState ?intentHandlingState }\n" +
                "        OPTIONAL { <" + reportUri + "> tmf:intentHandlingReason ?intentHandlingReason }\n" +
                "        OPTIONAL {\n" +
                "            <" + reportUri + "> tmf:hasExpression ?exprUri .\n" +
                "            ?exprUri rdf:type ?exprType .\n" +
                "            OPTIONAL { ?exprUri tmf:expressionIri ?exprIri }\n" +
                "            OPTIONAL { ?exprUri tmf:expressionValue ?exprValue }\n" +
                "        }\n" +
                "        FILTER(?parentIntent = <" + intentUri + ">)\n" +
                "    }\n}";
        List<Map<String, String>> rows = execSelect(sparql);
        if (rows.isEmpty()) return null;
        return bindingsToReport(rows.get(0));
    }

    public long count(String intentId) {
        String intentUri = GraphNodes.intentNode(intentId);
        String sparql = "SELECT (COUNT(DISTINCT ?reportUri) AS ?count)\nWHERE {\n    GRAPH ?g {\n" +
                "        ?reportUri rdf:type tmf:IntentReport .\n" +
                "        ?reportUri tmf:parentIntent <" + intentUri + "> .\n" +
                "    }\n}";
        List<Map<String, String>> rows = execSelect(sparql);
        if (rows.isEmpty()) return 0;
        String c = v(rows.get(0), "count");
        return c != null ? Long.parseLong(c) : 0;
    }

    public List<Map<String, Object>> findAll(String intentId, int limit, int offset) {
        String intentUri = GraphNodes.intentNode(intentId);
        String sparql = "SELECT " + GET_SELECT + "\nWHERE {\n    GRAPH ?g {\n" +
                "        ?reportUri rdf:type tmf:IntentReport .\n" +
                "        ?reportUri tmf:parentIntent <" + intentUri + "> .\n" +
                "        OPTIONAL { ?reportUri tmf:id ?id }\n" +
                "        OPTIONAL { ?reportUri tmf:href ?href }\n" +
                "        OPTIONAL { ?reportUri tmf:name ?name }\n" +
                "        OPTIONAL { ?reportUri rdf:type ?type }\n" +
                "        OPTIONAL { ?reportUri tmf:baseType ?baseType }\n" +
                "        OPTIONAL { ?reportUri tmf:schemaLocation ?schemaLocation }\n" +
                "        OPTIONAL { ?reportUri dcterms:created ?created }\n" +
                "        OPTIONAL { ?reportUri tmf:parentIntent ?parentIntent }\n" +
                "        OPTIONAL { ?reportUri tmf:intentHandlingState ?intentHandlingState }\n" +
                "        OPTIONAL { ?reportUri tmf:intentHandlingReason ?intentHandlingReason }\n" +
                "        OPTIONAL {\n" +
                "            ?reportUri tmf:hasExpression ?exprUri .\n" +
                "            ?exprUri rdf:type ?exprType .\n" +
                "            OPTIONAL { ?exprUri tmf:expressionIri ?exprIri }\n" +
                "            OPTIONAL { ?exprUri tmf:expressionValue ?exprValue }\n" +
                "        }\n" +
                "    }\n}\nORDER BY DESC(?created)\nLIMIT " + limit + " OFFSET " + offset;
        List<Map<String, String>> rows = execSelect(sparql);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, String> row : rows) result.add(bindingsToReport(row));
        return result;
    }

    public boolean delete(String intentId, String reportId) {
        if (findById(intentId, reportId) == null) return false;
        execUpdate("DROP GRAPH <" + GraphNodes.reportGraphUri(reportId) + ">");
        return true;
    }

    private Map<String, Object> bindingsToReport(Map<String, String> row) {
        String eTypeUri = v(row, "exprType");
        String eType = eTypeUri != null ? GraphNodes.localName(eTypeUri) : null;
        String typeUri = v(row, "type");
        String parentUri = v(row, "parentIntent");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", v(row, "id"));
        result.put("href", v(row, "href"));
        result.put("@type", typeUri != null ? GraphNodes.localName(typeUri) : "IntentReport");
        result.put("@baseType", v(row, "baseType"));
        result.put("@schemaLocation", v(row, "schemaLocation"));
        result.put("name", v(row, "name"));
        result.put("creationDate", v(row, "created"));
        result.put("intentId", parentUri != null ? GraphNodes.localName(parentUri) : null);
        result.put("intentHandlingState", v(row, "intentHandlingState"));
        result.put("intentHandlingReason", v(row, "intentHandlingReason"));

        if (eType != null) {
            Map<String, Object> expr = new LinkedHashMap<>();
            expr.put("@type", eType);
            expr.put("iri", v(row, "exprIri"));
            expr.put("expressionValue", deserExprValue(eType, v(row, "exprValue")));
            result.put("expression", expr);
        } else {
            result.put("expression", null);
        }
        return result;
    }
}
