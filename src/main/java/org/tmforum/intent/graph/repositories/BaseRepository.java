package org.tmforum.intent.graph.repositories;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.update.UpdateExecutionFactory;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateProcessor;
import org.apache.jena.update.UpdateRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.tmforum.intent.graph.Namespaces.SPARQL_PREFIXES;

public abstract class BaseRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    protected final Dataset dataset;

    protected BaseRepository(Dataset dataset) {
        this.dataset = dataset;
    }

    // ── Transaction helpers ─────────────────────────────────────────────────

    protected List<Map<String, String>> execSelect(String sparql) {
        dataset.begin(ReadWrite.READ);
        try {
            Query q = QueryFactory.create(SPARQL_PREFIXES + sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                ResultSet rs = qe.execSelect();
                List<Map<String, String>> rows = new ArrayList<>();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.nextSolution();
                    Map<String, String> row = new HashMap<>();
                    sol.varNames().forEachRemaining(var -> {
                        RDFNode node = sol.get(var);
                        if (node != null) row.put(var, nodeValue(node));
                    });
                    rows.add(row);
                }
                return rows;
            }
        } finally {
            dataset.end();
        }
    }

    protected boolean execAsk(String sparql) {
        dataset.begin(ReadWrite.READ);
        try {
            Query q = QueryFactory.create(SPARQL_PREFIXES + sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(q, dataset)) {
                return qe.execAsk();
            }
        } finally {
            dataset.end();
        }
    }

    protected void execUpdate(String sparql) {
        dataset.begin(ReadWrite.WRITE);
        try {
            UpdateRequest ur = UpdateFactory.create(SPARQL_PREFIXES + sparql);
            UpdateProcessor up = UpdateExecutionFactory.create(ur, dataset);
            up.execute();
            dataset.commit();
        } catch (Exception e) {
            dataset.abort();
            throw e;
        } finally {
            dataset.end();
        }
    }

    // ── Value extraction ────────────────────────────────────────────────────

    protected static String v(Map<String, String> row, String key) {
        return row.getOrDefault(key, null);
    }

    protected static Boolean vBool(Map<String, String> row, String key) {
        String val = row.get(key);
        if (val == null) return null;
        return "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    // ── SPARQL literal escaping ─────────────────────────────────────────────

    protected static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Expression value serialisation ─────────────────────────────────────

    protected static String serExprValue(Object value) {
        if (value == null) return "";
        if (value instanceof Map || value instanceof List) {
            try {
                return JSON.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    protected static Object deserExprValue(String exprType, String raw) {
        if (raw == null) return null;
        if ("JsonLdExpression".equals(exprType)) {
            try {
                return JSON.readValue(raw, Map.class);
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    // ── RDF node value extraction ───────────────────────────────────────────

    private static String nodeValue(RDFNode node) {
        if (node.isLiteral()) return node.asLiteral().getString();
        if (node.isURIResource()) return node.asResource().getURI();
        return node.toString();
    }
}
