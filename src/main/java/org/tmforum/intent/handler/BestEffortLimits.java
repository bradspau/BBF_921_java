package org.tmforum.intent.handler;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.springframework.stereotype.Service;
import org.tmforum.intent.graph.TioNamespaces;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.*;

/**
 * Best-effort bound substitution for Flow 3 (BestPropose).
 *
 * For each failed two-argument quantity condition, substitutes the bound with the
 * observed value (actual capability). Mirrors Python limits.py:apply_best_effort_bounds().
 *
 * Two-step fallback per failed condition:
 *  1. Use conditions[].observed (actual measured value)
 *  2. Fall back to HANDLER_LIMITS if configured
 */
@Service
public class BestEffortLimits {

    /**
     * Apply best-effort bound substitution to an expression Turtle.
     *
     * @param turtle     The intent's expressionValue Turtle string.
     * @param conditions The conditions list from EvaluationResult.
     * @return {@code Optional.of(newTurtle)} if any bound was changed, {@code Optional.empty()} otherwise.
     */
    public Optional<String> apply(String turtle, List<Map<String, Object>> conditions) {
        if (turtle == null || turtle.isBlank() || conditions == null || conditions.isEmpty()) {
            return Optional.empty();
        }

        // Build best-effort value per condition type from evaluator output
        Map<String, BigDecimal> bestByType = new LinkedHashMap<>();
        for (Map<String, Object> c : conditions) {
            if (Boolean.TRUE.equals(c.get("passed"))) continue;
            String typeName = (String) c.get("type");
            if (!isQuantityType(typeName)) continue;
            Object observed = c.get("observed");
            if (observed != null) {
                try {
                    bestByType.putIfAbsent(typeName, new BigDecimal(observed.toString()));
                    continue;
                } catch (NumberFormatException ignored) {}
            }
            // No observed value — could fall back to handler limits here if configured
        }

        if (bestByType.isEmpty()) return Optional.empty();

        Model g = ModelFactory.createDefaultModel();
        try {
            g.read(new StringReader(turtle), null, "TURTLE");
        } catch (Exception e) {
            return Optional.empty();
        }

        boolean changed = false;
        for (Map.Entry<String, BigDecimal> entry : bestByType.entrySet()) {
            String typeName = entry.getKey();
            BigDecimal bestVal = entry.getValue();
            Resource typeRes = ResourceFactory.createResource(
                    TioNamespaces.QUAN + typeName);
            Property typeProp = ResourceFactory.createProperty(
                    TioNamespaces.QUAN + typeName);

            // Type-form: ?node a quan:atLeast ; rdf:first ?val ; rdf:rest [ rdf:first ?bound ]
            for (ResIterator it = g.listSubjectsWithProperty(RDF.type, typeRes); it.hasNext(); ) {
                Resource node = it.next();
                if (substituteTypedFormBound(g, node, bestVal)) changed = true;
            }

            // Predicate-form: ?cond quan:atLeast (?list)
            for (StmtIterator it = g.listStatements(null, typeProp, (RDFNode) null); it.hasNext(); ) {
                Statement stmt = it.next();
                if (!stmt.getObject().isResource()) continue;
                Resource listNode = stmt.getObject().asResource();
                Statement restStmt = g.getProperty(listNode, RDF.rest);
                if (restStmt == null || !restStmt.getObject().isResource()) continue;
                Resource rest = restStmt.getObject().asResource();
                Statement bndStmt = g.getProperty(rest, RDF.first);
                if (bndStmt == null || !bndStmt.getObject().isResource()) continue;
                if (updateBoundValue(g, bndStmt.getObject().asResource(), bestVal)) changed = true;
            }
        }

        if (!changed) return Optional.empty();

        StringWriter sw = new StringWriter();
        RDFDataMgr.write(sw, g, Lang.TURTLE);
        return Optional.of(sw.toString());
    }

    private boolean substituteTypedFormBound(Model g, Resource node, BigDecimal bestVal) {
        Statement restStmt = g.getProperty(node, RDF.rest);
        if (restStmt == null || !restStmt.getObject().isResource()) return false;
        Resource rest = restStmt.getObject().asResource();
        Statement bndStmt = g.getProperty(rest, RDF.first);
        if (bndStmt == null || !bndStmt.getObject().isResource()) return false;
        return updateBoundValue(g, bndStmt.getObject().asResource(), bestVal);
    }

    private boolean updateBoundValue(Model g, Resource bndNode, BigDecimal newVal) {
        Statement oldValStmt = g.getProperty(bndNode, RDF.value);
        if (oldValStmt == null) return false;
        try {
            BigDecimal old = new BigDecimal(oldValStmt.getObject().asLiteral().getLexicalForm());
            if (old.compareTo(newVal) == 0) return false;
        } catch (NumberFormatException ignored) {}
        g.remove(oldValStmt);
        g.addLiteral(bndNode, RDF.value,
                ResourceFactory.createTypedLiteral(newVal.toPlainString(), XSDDatatype.XSDdecimal));
        return true;
    }

    private boolean isQuantityType(String typeName) {
        if (typeName == null) return false;
        return switch (typeName) {
            case "quanatLeast", "atLeast",
                 "quanatMost", "atMost",
                 "quangreater", "greater",
                 "quansmaller", "smaller",
                 "quanexactly", "exactly",
                 "quaninRange", "inRange" -> true;
            default -> false;
        };
    }
}
