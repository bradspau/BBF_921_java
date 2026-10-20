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
 * <p>Each failed condition is matched to its own bound node by function type and metric, so a passing
 * condition of the same function type (another {@code atLeast} on a different metric) keeps its bound.
 * A failed condition without a metric is only rewritten when its function type identifies exactly one
 * condition in the expression; with several candidates it cannot be told apart and is left alone.
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

        // One substitution per failed condition: (function type, metric) -> observed value
        record Failed(String typeName, String metric, BigDecimal best) {}
        List<Failed> failed = new ArrayList<>();
        for (Map<String, Object> c : conditions) {
            if (Boolean.TRUE.equals(c.get("passed"))) continue;
            String typeName = (String) c.get("type");
            if (!isQuantityType(typeName)) continue;
            Object metric = c.get("metric");
            Object observed = c.get("observed");
            if (observed == null) continue; // no value to propose
            try {
                failed.add(new Failed(typeName, metric == null ? null : metric.toString(),
                        new BigDecimal(observed.toString())));
            } catch (NumberFormatException ignored) {}
        }

        if (failed.isEmpty()) return Optional.empty();

        Model g = ModelFactory.createDefaultModel();
        try {
            g.read(new StringReader(turtle), null, "TURTLE");
        } catch (Exception e) {
            return Optional.empty();
        }

        boolean changed = false;
        for (Failed f : failed) {
            Resource typeRes = ResourceFactory.createResource(TioNamespaces.QUAN + f.typeName());
            Property typeProp = ResourceFactory.createProperty(TioNamespaces.QUAN + f.typeName());

            // The list node of each condition of this function type:
            //   type-form:      ?node a quan:atLeast ; rdf:first ?metric ; rdf:rest [ rdf:first ?bound ]   (the node is the list)
            //   predicate-form: ?cond quan:atLeast ( ?metric ?bound )                                      (the object is the list)
            List<Resource> lists = new ArrayList<>();
            for (ResIterator it = g.listSubjectsWithProperty(RDF.type, typeRes); it.hasNext(); ) {
                lists.add(it.next());
            }
            for (StmtIterator it = g.listStatements(null, typeProp, (RDFNode) null); it.hasNext(); ) {
                Statement stmt = it.next();
                if (stmt.getObject().isResource()) lists.add(stmt.getObject().asResource());
            }

            List<Resource> targets = new ArrayList<>();
            if (f.metric() != null) {
                Resource metric = ResourceFactory.createResource(f.metric());
                for (Resource l : lists) if (firstIs(g, l, metric)) targets.add(l);
            } else if (lists.size() == 1) {
                targets.addAll(lists);   // the type alone identifies the condition
            }

            for (Resource list : targets) {
                Statement restStmt = g.getProperty(list, RDF.rest);
                if (restStmt == null || !restStmt.getObject().isResource()) continue;
                Resource rest = restStmt.getObject().asResource();
                Statement bndStmt = g.getProperty(rest, RDF.first);
                if (bndStmt == null || !bndStmt.getObject().isResource()) continue;
                if (updateBoundValue(g, bndStmt.getObject().asResource(), f.best())) changed = true;
            }
        }

        if (!changed) return Optional.empty();

        StringWriter sw = new StringWriter();
        RDFDataMgr.write(sw, g, Lang.TURTLE);
        return Optional.of(sw.toString());
    }

    /** True when the list node's {@code rdf:first} is the given metric resource. */
    private boolean firstIs(Model g, Resource listNode, Resource metric) {
        Statement first = g.getProperty(listNode, RDF.first);
        return first != null && first.getObject().isResource() && first.getObject().asResource().equals(metric);
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
