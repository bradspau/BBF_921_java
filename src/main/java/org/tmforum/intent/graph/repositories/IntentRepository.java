package org.tmforum.intent.graph.repositories;

import org.apache.jena.query.Dataset;
import org.springframework.stereotype.Repository;
import org.tmforum.intent.graph.GraphNodes;

import java.util.*;

@Repository
public class IntentRepository extends BaseRepository {

    private static final String GET_SELECT =
            "?id ?href ?name ?type ?baseType ?schemaLocation " +
            "?lifecycleStatus ?statusChangeDate ?created ?modified " +
            "?description ?priority ?context ?version ?isBundle " +
            "?exprType ?exprIri ?exprValue";

    private static final String GET_WHERE_PATTERN =
            "        <%s> rdf:type ?type .\n" +
            "        OPTIONAL { <%s> tmf:id ?id }\n" +
            "        OPTIONAL { <%s> tmf:href ?href }\n" +
            "        OPTIONAL { <%s> tmf:name ?name }\n" +
            "        OPTIONAL { <%s> tmf:lifecycleStatus ?lifecycleStatus }\n" +
            "        OPTIONAL { <%s> tmf:statusChangeDate ?statusChangeDate }\n" +
            "        OPTIONAL { <%s> dcterms:created ?created }\n" +
            "        OPTIONAL { <%s> dcterms:modified ?modified }\n" +
            "        OPTIONAL { <%s> tmf:baseType ?baseType }\n" +
            "        OPTIONAL { <%s> tmf:schemaLocation ?schemaLocation }\n" +
            "        OPTIONAL { <%s> tmf:description ?description }\n" +
            "        OPTIONAL { <%s> tmf:priority ?priority }\n" +
            "        OPTIONAL { <%s> tmf:context ?context }\n" +
            "        OPTIONAL { <%s> tmf:version ?version }\n" +
            "        OPTIONAL { <%s> tmf:isBundle ?isBundle }\n" +
            "        OPTIONAL {\n" +
            "            <%s> tmf:hasExpression ?exprUri .\n" +
            "            ?exprUri rdf:type ?exprType .\n" +
            "            OPTIONAL { ?exprUri tmf:expressionIri ?exprIri }\n" +
            "            OPTIONAL { ?exprUri tmf:expressionValue ?exprValue }\n" +
            "        }\n";

    // Fields patchable via PATCH: (predicate, isTyped, xsdType)
    private static final Map<String, String[]> PATCHABLE = new LinkedHashMap<>();
    static {
        PATCHABLE.put("name",             new String[]{"tmf:name",             "false", ""});
        PATCHABLE.put("description",      new String[]{"tmf:description",      "false", ""});
        PATCHABLE.put("priority",         new String[]{"tmf:priority",         "false", ""});
        PATCHABLE.put("context",          new String[]{"tmf:context",          "false", ""});
        PATCHABLE.put("lifecycleStatus",  new String[]{"tmf:lifecycleStatus",  "false", ""});
        PATCHABLE.put("statusChangeDate", new String[]{"tmf:statusChangeDate", "true",  "xsd:dateTime"});
        PATCHABLE.put("version",          new String[]{"tmf:version",          "false", ""});
    }

    public IntentRepository(Dataset dataset) {
        super(dataset);
    }

    // ── Create ──────────────────────────────────────────────────────────────

    public Map<String, Object> create(Map<String, Object> data) {
        String intentId = (String) data.get("id");
        String graphUri = GraphNodes.intentGraphUri(intentId);
        String intentUri = GraphNodes.intentNode(intentId);
        String exprUri = GraphNodes.expressionUri(intentId);

        String triples = buildIntentTriples(data, intentUri, exprUri);
        execUpdate("INSERT DATA {\n    GRAPH <" + graphUri + "> {\n" + triples + "\n    }\n}");
        return data;
    }

    // ── Get ─────────────────────────────────────────────────────────────────

    public Map<String, Object> findById(String intentId) {
        String graphUri = GraphNodes.intentGraphUri(intentId);
        String uri = GraphNodes.intentNode(intentId);
        String pattern = buildGetPattern(uri);
        String sparql = "SELECT " + GET_SELECT + "\nWHERE {\n    GRAPH <" + graphUri + "> {\n" + pattern + "    }\n}";
        List<Map<String, String>> rows = execSelect(sparql);
        if (rows.isEmpty()) return null;
        return bindingsToIntent(rows.get(0));
    }

    // ── List ────────────────────────────────────────────────────────────────

    public long count(Map<String, Object> filters) {
        String filterClauses = buildFilterClauses(filters);
        String sparql = "SELECT (COUNT(DISTINCT ?intentUri) AS ?count)\nWHERE {\n    GRAPH ?g {\n" +
                "        ?intentUri rdf:type ?type .\n" +
                "        VALUES ?type { tmf:Intent tmf:ProbeIntent }\n" +
                filterClauses + "    }\n}";
        List<Map<String, String>> rows = execSelect(sparql);
        if (rows.isEmpty()) return 0;
        String c = v(rows.get(0), "count");
        return c != null ? Long.parseLong(c) : 0;
    }

    public List<Map<String, Object>> findAll(int limit, int offset, Map<String, Object> filters) {
        String filterClauses = buildFilterClauses(filters);
        String sparql = "SELECT " + GET_SELECT + "\n" +
                "WHERE {\n    GRAPH ?g {\n" +
                "        ?intentUri rdf:type ?type .\n" +
                "        VALUES ?type { tmf:Intent tmf:ProbeIntent }\n" +
                "        OPTIONAL { ?intentUri tmf:id ?id }\n" +
                "        OPTIONAL { ?intentUri tmf:href ?href }\n" +
                "        OPTIONAL { ?intentUri tmf:name ?name }\n" +
                "        OPTIONAL { ?intentUri tmf:lifecycleStatus ?lifecycleStatus }\n" +
                "        OPTIONAL { ?intentUri tmf:statusChangeDate ?statusChangeDate }\n" +
                "        OPTIONAL { ?intentUri dcterms:created ?created }\n" +
                "        OPTIONAL { ?intentUri dcterms:modified ?modified }\n" +
                "        OPTIONAL { ?intentUri tmf:baseType ?baseType }\n" +
                "        OPTIONAL { ?intentUri tmf:schemaLocation ?schemaLocation }\n" +
                "        OPTIONAL { ?intentUri tmf:description ?description }\n" +
                "        OPTIONAL { ?intentUri tmf:priority ?priority }\n" +
                "        OPTIONAL { ?intentUri tmf:context ?context }\n" +
                "        OPTIONAL { ?intentUri tmf:version ?version }\n" +
                "        OPTIONAL { ?intentUri tmf:isBundle ?isBundle }\n" +
                "        OPTIONAL {\n" +
                "            ?intentUri tmf:hasExpression ?exprUri .\n" +
                "            ?exprUri rdf:type ?exprType .\n" +
                "            OPTIONAL { ?exprUri tmf:expressionIri ?exprIri }\n" +
                "            OPTIONAL { ?exprUri tmf:expressionValue ?exprValue }\n" +
                "        }\n" +
                filterClauses +
                "    }\n}\nORDER BY ?id\nLIMIT " + limit + " OFFSET " + offset;
        List<Map<String, String>> rows = execSelect(sparql);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, String> row : rows) result.add(bindingsToIntent(row));
        return result;
    }

    // ── Update ──────────────────────────────────────────────────────────────

    public Map<String, Object> update(String intentId, Map<String, Object> updates, String modifiedAt) {
        String graphUri = GraphNodes.intentGraphUri(intentId);
        String uri = GraphNodes.intentNode(intentId);

        List<String> deleteLines = new ArrayList<>();
        List<String> insertLines = new ArrayList<>();
        List<String> whereLines = new ArrayList<>();

        // dcterms:modified always refreshed
        deleteLines.add("        <" + uri + "> dcterms:modified ?oldMod .");
        insertLines.add("        <" + uri + "> dcterms:modified \"" + esc(modifiedAt) + "\"^^xsd:dateTime .");
        whereLines.add("        OPTIONAL { <" + uri + "> dcterms:modified ?oldMod }");

        for (Map.Entry<String, String[]> e : PATCHABLE.entrySet()) {
            String field = e.getKey();
            if (!updates.containsKey(field)) continue;
            String pred = e.getValue()[0];
            boolean typed = "true".equals(e.getValue()[1]);
            String dtype = e.getValue()[2];
            String var = field.replace("@", "").replace(":", "_");
            String val = String.valueOf(updates.get(field));

            deleteLines.add("        <" + uri + "> " + pred + " ?old_" + var + " .");
            if (typed) {
                insertLines.add("        <" + uri + "> " + pred + " \"" + esc(val) + "\"^^" + dtype + " .");
            } else {
                insertLines.add("        <" + uri + "> " + pred + " \"" + esc(val) + "\" .");
            }
            whereLines.add("        OPTIONAL { <" + uri + "> " + pred + " ?old_" + var + " }");
        }

        // Expression update
        @SuppressWarnings("unchecked")
        Map<String, Object> expr = (Map<String, Object>) updates.get("expression");
        if (expr != null) {
            String exprUri = GraphNodes.expressionUri(intentId);
            String exprType = expr.getOrDefault("@type", "JsonLdExpression").toString();
            deleteLines.addAll(List.of(
                    "        <" + uri + "> tmf:hasExpression ?exprUri .",
                    "        ?exprUri rdf:type ?oldExprType .",
                    "        ?exprUri tmf:expressionIri ?oldIri .",
                    "        ?exprUri tmf:expressionValue ?oldExprVal ."
            ));
            insertLines.addAll(List.of(
                    "        <" + uri + "> tmf:hasExpression <" + exprUri + "> .",
                    "        <" + exprUri + "> rdf:type tmf:" + esc(exprType) + " ."
            ));
            if (expr.get("iri") != null) {
                insertLines.add("        <" + exprUri + "> tmf:expressionIri \"" + esc((String) expr.get("iri")) + "\" .");
            }
            if (expr.get("expressionValue") != null) {
                String raw = serExprValue(expr.get("expressionValue"));
                insertLines.add("        <" + exprUri + "> tmf:expressionValue \"" + esc(raw) + "\"^^xsd:string .");
            }
            whereLines.addAll(List.of(
                    "        OPTIONAL {",
                    "            <" + uri + "> tmf:hasExpression ?exprUri .",
                    "            OPTIONAL { ?exprUri rdf:type ?oldExprType }",
                    "            OPTIONAL { ?exprUri tmf:expressionIri ?oldIri }",
                    "            OPTIONAL { ?exprUri tmf:expressionValue ?oldExprVal }",
                    "        }"
            ));
        }

        execUpdate(
            "DELETE {\n    GRAPH <" + graphUri + "> {\n" + String.join("\n", deleteLines) + "\n    }\n}\n" +
            "INSERT {\n    GRAPH <" + graphUri + "> {\n" + String.join("\n", insertLines) + "\n    }\n}\n" +
            "WHERE {\n    GRAPH <" + graphUri + "> {\n" + String.join("\n", whereLines) + "\n    }\n}"
        );
        return findById(intentId);
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    public boolean delete(String intentId) {
        String graphUri = GraphNodes.intentGraphUri(intentId);
        String uri = GraphNodes.intentNode(intentId);
        boolean exists = execAsk("ASK { GRAPH <" + graphUri + "> { <" + uri + "> rdf:type ?t } }");
        if (!exists) return false;
        execUpdate("DROP GRAPH <" + graphUri + ">");
        return true;
    }

    // ── State change audit ──────────────────────────────────────────────────

    public void writeStateChange(String intentId, String changeId, String fromStatus, String toStatus, String timestamp) {
        String auditUri = GraphNodes.auditGraphUri(changeId);
        String scUri = GraphNodes.stateChangeUri(changeId);
        String intentUri = GraphNodes.intentNode(intentId);
        execUpdate(
            "INSERT DATA {\n    GRAPH <" + auditUri + "> {\n" +
            "        <" + scUri + "> rdf:type tmf:StateChange .\n" +
            "        <" + scUri + "> tmf:forIntent <" + intentUri + "> .\n" +
            "        <" + scUri + "> tmf:fromStatus \"" + esc(fromStatus) + "\" .\n" +
            "        <" + scUri + "> tmf:toStatus \"" + esc(toStatus) + "\" .\n" +
            "        <" + scUri + "> dcterms:created \"" + esc(timestamp) + "\"^^xsd:dateTime .\n" +
            "    }\n}"
        );
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private String buildGetPattern(String uri) {
        String u = uri;
        return String.format(GET_WHERE_PATTERN,
                u, u, u, u, u, u, u, u, u, u, u, u, u, u, u, u);
    }

    private String buildIntentTriples(Map<String, Object> data, String intentUri, String exprUri) {
        String typeName = (String) data.get("@type");
        StringBuilder sb = new StringBuilder();
        sb.append("        <").append(intentUri).append("> rdf:type tmf:").append(typeName).append(" .\n");
        sb.append("        <").append(intentUri).append("> tmf:id \"").append(esc((String) data.get("id"))).append("\" .\n");
        sb.append("        <").append(intentUri).append("> tmf:href \"").append(esc((String) data.get("href"))).append("\" .\n");
        sb.append("        <").append(intentUri).append("> tmf:name \"").append(esc((String) data.get("name"))).append("\" .\n");
        sb.append("        <").append(intentUri).append("> dcterms:created \"").append(esc((String) data.get("creationDate"))).append("\"^^xsd:dateTime .\n");
        sb.append("        <").append(intentUri).append("> dcterms:modified \"").append(esc((String) data.get("lastUpdate"))).append("\"^^xsd:dateTime .\n");
        sb.append("        <").append(intentUri).append("> tmf:lifecycleStatus \"").append(esc((String) data.get("lifecycleStatus"))).append("\" .\n");

        for (String[] opt : new String[][]{
                {"@baseType", "tmf:baseType"}, {"@schemaLocation", "tmf:schemaLocation"},
                {"description", "tmf:description"}, {"priority", "tmf:priority"},
                {"context", "tmf:context"}, {"version", "tmf:version"}}) {
            Object val = data.get(opt[0]);
            if (val != null && !val.toString().isBlank()) {
                sb.append("        <").append(intentUri).append("> ").append(opt[1]).append(" \"").append(esc(val.toString())).append("\" .\n");
            }
        }
        if (data.get("isBundle") != null) {
            sb.append("        <").append(intentUri).append("> tmf:isBundle \"")
              .append((Boolean) data.get("isBundle") ? "true" : "false").append("\"^^xsd:boolean .\n");
        }
        if (data.get("statusChangeDate") != null) {
            sb.append("        <").append(intentUri).append("> tmf:statusChangeDate \"")
              .append(esc((String) data.get("statusChangeDate"))).append("\"^^xsd:dateTime .\n");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> expr = (Map<String, Object>) data.get("expression");
        if (expr != null) {
            String exprType = (String) expr.getOrDefault("@type", "JsonLdExpression");
            sb.append("        <").append(intentUri).append("> tmf:hasExpression <").append(exprUri).append("> .\n");
            sb.append("        <").append(exprUri).append("> rdf:type tmf:").append(esc(exprType)).append(" .\n");
            if (expr.get("iri") != null) {
                sb.append("        <").append(exprUri).append("> tmf:expressionIri \"").append(esc((String) expr.get("iri"))).append("\" .\n");
            }
            if (expr.get("expressionValue") != null) {
                String raw = serExprValue(expr.get("expressionValue"));
                sb.append("        <").append(exprUri).append("> tmf:expressionValue \"").append(esc(raw)).append("\"^^xsd:string .\n");
            }
        }
        return sb.toString();
    }

    private Map<String, Object> bindingsToIntent(Map<String, String> row) {
        String eTypeUri = v(row, "exprType");
        String eType = eTypeUri != null ? GraphNodes.localName(eTypeUri) : null;
        String rawExprVal = v(row, "exprValue");
        String typeUri = v(row, "type");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", v(row, "id"));
        result.put("href", v(row, "href"));
        result.put("@type", typeUri != null ? GraphNodes.localName(typeUri) : null);
        result.put("@baseType", v(row, "baseType"));
        result.put("@schemaLocation", v(row, "schemaLocation"));
        result.put("name", v(row, "name"));
        result.put("description", v(row, "description"));
        result.put("lifecycleStatus", v(row, "lifecycleStatus"));
        result.put("statusChangeDate", v(row, "statusChangeDate"));
        result.put("creationDate", v(row, "created"));
        result.put("lastUpdate", v(row, "modified"));
        result.put("priority", v(row, "priority"));
        result.put("context", v(row, "context"));
        result.put("version", v(row, "version"));
        result.put("isBundle", vBool(row, "isBundle"));

        if (eType != null) {
            Map<String, Object> expr = new LinkedHashMap<>();
            expr.put("@type", eType);
            expr.put("iri", v(row, "exprIri"));
            expr.put("expressionValue", deserExprValue(eType, rawExprVal));
            result.put("expression", expr);
        } else {
            result.put("expression", null);
        }
        return result;
    }

    private String buildFilterClauses(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Map<String, String> predMap = Map.of(
                "lifecycleStatus", "tmf:lifecycleStatus",
                "name", "tmf:name");
        for (Map.Entry<String, String> e : predMap.entrySet()) {
            if (filters.containsKey(e.getKey())) {
                sb.append("        ?intentUri ").append(e.getValue())
                  .append(" \"").append(esc(filters.get(e.getKey()).toString())).append("\" .\n");
            }
        }
        if (filters.containsKey("@type")) {
            sb.append("        FILTER(?type = tmf:").append(esc(filters.get("@type").toString())).append(")\n");
        }
        return sb.toString();
    }
}
