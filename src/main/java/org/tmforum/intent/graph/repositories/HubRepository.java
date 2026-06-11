package org.tmforum.intent.graph.repositories;

import org.apache.jena.query.Dataset;
import org.springframework.stereotype.Repository;
import org.tmforum.intent.graph.GraphNodes;
import org.tmforum.intent.graph.Namespaces;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class HubRepository extends BaseRepository {

    public HubRepository(Dataset dataset) {
        super(dataset);
    }

    public Map<String, Object> create(Map<String, Object> data) {
        String hubId = (String) data.get("id");
        String hubUri = GraphNodes.hubUri(hubId);
        String g = Namespaces.HUBS_GRAPH;

        StringBuilder sb = new StringBuilder();
        sb.append("INSERT DATA {\n    GRAPH <").append(g).append("> {\n");
        sb.append("        <").append(hubUri).append("> rdf:type tmf:Hub .\n");
        sb.append("        <").append(hubUri).append("> tmf:id \"").append(esc(hubId)).append("\" .\n");
        sb.append("        <").append(hubUri).append("> tmf:href \"").append(esc((String) data.get("href"))).append("\" .\n");
        sb.append("        <").append(hubUri).append("> tmf:callback \"").append(esc((String) data.get("callback"))).append("\" .\n");
        if (data.get("query") != null) {
            sb.append("        <").append(hubUri).append("> tmf:query \"").append(esc((String) data.get("query"))).append("\" .\n");
        }
        sb.append("    }\n}");
        execUpdate(sb.toString());
        return data;
    }

    public Map<String, Object> findById(String hubId) {
        String hubUri = GraphNodes.hubUri(hubId);
        String sparql = "SELECT ?id ?href ?callback ?query\n" +
                "WHERE {\n    GRAPH <" + Namespaces.HUBS_GRAPH + "> {\n" +
                "        <" + hubUri + "> rdf:type tmf:Hub .\n" +
                "        OPTIONAL { <" + hubUri + "> tmf:id ?id }\n" +
                "        OPTIONAL { <" + hubUri + "> tmf:href ?href }\n" +
                "        OPTIONAL { <" + hubUri + "> tmf:callback ?callback }\n" +
                "        OPTIONAL { <" + hubUri + "> tmf:query ?query }\n" +
                "    }\n}";
        List<Map<String, String>> rows = execSelect(sparql);
        if (rows.isEmpty()) return null;
        return bindingsToHub(hubUri, rows.get(0));
    }

    public List<Map<String, Object>> findAll() {
        String sparql = "SELECT ?hubUri ?id ?href ?callback ?query\n" +
                "WHERE {\n    GRAPH <" + Namespaces.HUBS_GRAPH + "> {\n" +
                "        ?hubUri rdf:type tmf:Hub .\n" +
                "        OPTIONAL { ?hubUri tmf:id ?id }\n" +
                "        OPTIONAL { ?hubUri tmf:href ?href }\n" +
                "        OPTIONAL { ?hubUri tmf:callback ?callback }\n" +
                "        OPTIONAL { ?hubUri tmf:query ?query }\n" +
                "    }\n}";
        List<Map<String, String>> rows = execSelect(sparql);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String hubUri = v(row, "hubUri");
            result.add(bindingsToHub(hubUri, row));
        }
        return result;
    }

    public boolean delete(String hubId) {
        String hubUri = GraphNodes.hubUri(hubId);
        boolean exists = execAsk("ASK { GRAPH <" + Namespaces.HUBS_GRAPH + "> { <" + hubUri + "> rdf:type tmf:Hub } }");
        if (!exists) return false;
        execUpdate("DELETE WHERE {\n    GRAPH <" + Namespaces.HUBS_GRAPH + "> {\n        <" + hubUri + "> ?p ?o .\n    }\n}");
        return true;
    }

    private Map<String, Object> bindingsToHub(String hubUri, Map<String, String> row) {
        Map<String, Object> hub = new HashMap<>();
        hub.put("id", v(row, "id") != null ? v(row, "id") : GraphNodes.localName(hubUri));
        hub.put("href", v(row, "href"));
        hub.put("callback", v(row, "callback"));
        hub.put("query", v(row, "query"));
        return hub;
    }
}
