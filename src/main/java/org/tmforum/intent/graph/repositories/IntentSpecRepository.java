package org.tmforum.intent.graph.repositories;

import org.apache.jena.query.Dataset;
import org.springframework.stereotype.Repository;
import org.tmforum.intent.graph.GraphNodes;

import java.util.*;

@Repository
public class IntentSpecRepository extends BaseRepository {

    private static final String GET_SELECT =
            "?id ?href ?name ?type ?baseType ?schemaLocation " +
            "?lifecycleStatus ?created ?modified ?description ?version ?isBundle";

    private static final Map<String, String[]> PATCHABLE = new LinkedHashMap<>();
    static {
        PATCHABLE.put("name",            new String[]{"tmf:name",            "false", ""});
        PATCHABLE.put("description",     new String[]{"tmf:description",     "false", ""});
        PATCHABLE.put("lifecycleStatus", new String[]{"tmf:lifecycleStatus", "false", ""});
        PATCHABLE.put("version",         new String[]{"tmf:version",         "false", ""});
    }

    public IntentSpecRepository(Dataset dataset) {
        super(dataset);
    }

    public Map<String, Object> create(Map<String, Object> data) {
        String specId = (String) data.get("id");
        String graphUri = GraphNodes.specGraphUri(specId);
        String uri = GraphNodes.specNode(specId);

        StringBuilder sb = new StringBuilder();
        sb.append("INSERT DATA {\n    GRAPH <").append(graphUri).append("> {\n");
        sb.append("        <").append(uri).append("> rdf:type tmf:IntentSpecification .\n");
        sb.append("        <").append(uri).append("> tmf:id \"").append(esc((String) data.get("id"))).append("\" .\n");
        sb.append("        <").append(uri).append("> tmf:href \"").append(esc((String) data.get("href"))).append("\" .\n");
        sb.append("        <").append(uri).append("> tmf:name \"").append(esc((String) data.get("name"))).append("\" .\n");
        sb.append("        <").append(uri).append("> dcterms:modified \"").append(esc((String) data.get("lastUpdate"))).append("\"^^xsd:dateTime .\n");

        for (String[] opt : new String[][]{
                {"@baseType", "tmf:baseType"}, {"@schemaLocation", "tmf:schemaLocation"},
                {"description", "tmf:description"}, {"version", "tmf:version"},
                {"lifecycleStatus", "tmf:lifecycleStatus"}}) {
            Object val = data.get(opt[0]);
            if (val != null && !val.toString().isBlank()) {
                sb.append("        <").append(uri).append("> ").append(opt[1]).append(" \"").append(esc(val.toString())).append("\" .\n");
            }
        }
        if (data.get("isBundle") != null) {
            sb.append("        <").append(uri).append("> tmf:isBundle \"")
              .append((Boolean) data.get("isBundle") ? "true" : "false").append("\"^^xsd:boolean .\n");
        }
        sb.append("    }\n}");
        execUpdate(sb.toString());
        return data;
    }

    public Map<String, Object> findById(String specId) {
        String graphUri = GraphNodes.specGraphUri(specId);
        String uri = GraphNodes.specNode(specId);
        String sparql = "SELECT " + GET_SELECT + "\nWHERE {\n    GRAPH <" + graphUri + "> {\n" +
                "        <" + uri + "> rdf:type tmf:IntentSpecification .\n" +
                "        OPTIONAL { <" + uri + "> tmf:id ?id }\n" +
                "        OPTIONAL { <" + uri + "> tmf:href ?href }\n" +
                "        OPTIONAL { <" + uri + "> tmf:name ?name }\n" +
                "        OPTIONAL { <" + uri + "> rdf:type ?type }\n" +
                "        OPTIONAL { <" + uri + "> tmf:baseType ?baseType }\n" +
                "        OPTIONAL { <" + uri + "> tmf:schemaLocation ?schemaLocation }\n" +
                "        OPTIONAL { <" + uri + "> tmf:lifecycleStatus ?lifecycleStatus }\n" +
                "        OPTIONAL { <" + uri + "> dcterms:created ?created }\n" +
                "        OPTIONAL { <" + uri + "> dcterms:modified ?modified }\n" +
                "        OPTIONAL { <" + uri + "> tmf:description ?description }\n" +
                "        OPTIONAL { <" + uri + "> tmf:version ?version }\n" +
                "        OPTIONAL { <" + uri + "> tmf:isBundle ?isBundle }\n" +
                "    }\n}";
        List<Map<String, String>> rows = execSelect(sparql);
        if (rows.isEmpty()) return null;
        return bindingsToSpec(rows.get(0));
    }

    public long count(Map<String, Object> filters) {
        String filterClauses = buildFilterClauses(filters);
        String sparql = "SELECT (COUNT(DISTINCT ?specUri) AS ?count)\nWHERE {\n    GRAPH ?g {\n" +
                "        ?specUri rdf:type tmf:IntentSpecification .\n" +
                filterClauses + "    }\n}";
        List<Map<String, String>> rows = execSelect(sparql);
        if (rows.isEmpty()) return 0;
        String c = v(rows.get(0), "count");
        return c != null ? Long.parseLong(c) : 0;
    }

    public List<Map<String, Object>> findAll(int limit, int offset, Map<String, Object> filters) {
        String filterClauses = buildFilterClauses(filters);
        String sparql = "SELECT " + GET_SELECT + "\nWHERE {\n    GRAPH ?g {\n" +
                "        ?specUri rdf:type tmf:IntentSpecification .\n" +
                "        OPTIONAL { ?specUri tmf:id ?id }\n" +
                "        OPTIONAL { ?specUri tmf:href ?href }\n" +
                "        OPTIONAL { ?specUri tmf:name ?name }\n" +
                "        OPTIONAL { ?specUri rdf:type ?type }\n" +
                "        OPTIONAL { ?specUri tmf:baseType ?baseType }\n" +
                "        OPTIONAL { ?specUri tmf:schemaLocation ?schemaLocation }\n" +
                "        OPTIONAL { ?specUri tmf:lifecycleStatus ?lifecycleStatus }\n" +
                "        OPTIONAL { ?specUri dcterms:created ?created }\n" +
                "        OPTIONAL { ?specUri dcterms:modified ?modified }\n" +
                "        OPTIONAL { ?specUri tmf:description ?description }\n" +
                "        OPTIONAL { ?specUri tmf:version ?version }\n" +
                "        OPTIONAL { ?specUri tmf:isBundle ?isBundle }\n" +
                filterClauses +
                "    }\n}\nORDER BY ?id\nLIMIT " + limit + " OFFSET " + offset;
        List<Map<String, String>> rows = execSelect(sparql);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, String> row : rows) result.add(bindingsToSpec(row));
        return result;
    }

    public Map<String, Object> update(String specId, Map<String, Object> updates, String modifiedAt) {
        String graphUri = GraphNodes.specGraphUri(specId);
        String uri = GraphNodes.specNode(specId);

        List<String> deleteLines = new ArrayList<>();
        List<String> insertLines = new ArrayList<>();
        List<String> whereLines = new ArrayList<>();

        deleteLines.add("        <" + uri + "> dcterms:modified ?oldMod .");
        insertLines.add("        <" + uri + "> dcterms:modified \"" + esc(modifiedAt) + "\"^^xsd:dateTime .");
        whereLines.add("        OPTIONAL { <" + uri + "> dcterms:modified ?oldMod }");

        for (Map.Entry<String, String[]> e : PATCHABLE.entrySet()) {
            String field = e.getKey();
            if (!updates.containsKey(field)) continue;
            String pred = e.getValue()[0];
            String var = field.replace("@", "");
            String val = String.valueOf(updates.get(field));
            deleteLines.add("        <" + uri + "> " + pred + " ?old_" + var + " .");
            insertLines.add("        <" + uri + "> " + pred + " \"" + esc(val) + "\" .");
            whereLines.add("        OPTIONAL { <" + uri + "> " + pred + " ?old_" + var + " }");
        }

        execUpdate(
            "DELETE {\n    GRAPH <" + graphUri + "> {\n" + String.join("\n", deleteLines) + "\n    }\n}\n" +
            "INSERT {\n    GRAPH <" + graphUri + "> {\n" + String.join("\n", insertLines) + "\n    }\n}\n" +
            "WHERE {\n    GRAPH <" + graphUri + "> {\n" + String.join("\n", whereLines) + "\n    }\n}"
        );
        return findById(specId);
    }

    public boolean delete(String specId) {
        String graphUri = GraphNodes.specGraphUri(specId);
        String uri = GraphNodes.specNode(specId);
        boolean exists = execAsk("ASK { GRAPH <" + graphUri + "> { <" + uri + "> rdf:type ?t } }");
        if (!exists) return false;
        execUpdate("DROP GRAPH <" + graphUri + ">");
        return true;
    }

    private Map<String, Object> bindingsToSpec(Map<String, String> row) {
        String typeUri = v(row, "type");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", v(row, "id"));
        result.put("href", v(row, "href"));
        result.put("@type", typeUri != null ? GraphNodes.localName(typeUri) : "IntentSpecification");
        result.put("@baseType", v(row, "baseType"));
        result.put("@schemaLocation", v(row, "schemaLocation"));
        result.put("name", v(row, "name"));
        result.put("description", v(row, "description"));
        result.put("lifecycleStatus", v(row, "lifecycleStatus"));
        result.put("lastUpdate", v(row, "modified"));
        result.put("version", v(row, "version"));
        result.put("isBundle", vBool(row, "isBundle"));
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
                sb.append("        ?specUri ").append(e.getValue())
                  .append(" \"").append(esc(filters.get(e.getKey()).toString())).append("\" .\n");
            }
        }
        return sb.toString();
    }
}
