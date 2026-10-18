package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.tmforum.intent.graph.TioNamespaces;
import org.tmforum.intent.model.EvaluationResult;

import java.util.*;

/**
 * Builds the handler's {@link EvaluationResult} (state, reason, flat condition list) from a rule-engine result
 * graph (spec 5.3), so Flows 1-4, {@code HandlerStateWriter} and {@code BestEffortLimits} keep working.
 *
 * <ul>
 *   <li><b>State.</b> Every IRI-identified {@code icm:Intent} of the expression with value {@code true} gives
 *       {@code Fulfilled}; any intent with value {@code false} gives {@code Degraded}. An intent without a value
 *       (nothing derived it) or an expression without an intent is {@code Degraded} with a reason saying so,
 *       never {@code Fulfilled}.</li>
 *   <li><b>Reason.</b> {@link Reasons#conditionsNotMet}, the same code and format as the Java engine.</li>
 *   <li><b>Conditions.</b> One entry per IRI-identified condition ({@code icm:Condition}/{@code log:Condition}
 *       and subclasses) that has a value in the graph, in IRI order, with {@code type}, {@code passed} and
 *       {@code element} (its IRI).</li>
 * </ul>
 *
 * <p><b>Stubbed until Task 16</b> (the keys are left out, never filled with guesses):
 * <ul>
 *   <li>{@code type} is the local name of the condition's own type (e.g. {@code Condition}), not yet the
 *       function type the Java engine reports ({@code quanatLeast}, ...), so reason labels read
 *       {@code "Condition: FAIL"};</li>
 *   <li>{@code observed}, {@code observedUnit}, {@code bound}, {@code boundUnit}, {@code operator},
 *       {@code lower}/{@code upper}, {@code metric}, {@code error} are absent, so {@code BestEffortLimits}
 *       proposes nothing for rule-engine results yet;</li>
 *   <li>no {@code DeliveryExpectation} entries with {@code selected}/{@code held}, so allocation write-back
 *       (Flow 4) claims nothing for rule-engine results yet;</li>
 *   <li>{@code report} (the TR290 element tree) is null.</li>
 * </ul>
 */
public final class ResultAdapter {

    private ResultAdapter() {}

    static final Resource ICM_INTENT = ResourceFactory.createResource(TioNamespaces.ICM + "Intent");
    static final List<Resource> CONDITION_CLASSES = List.of(
            ResourceFactory.createResource(TioNamespaces.ICM + "Condition"),
            ResourceFactory.createResource(TioNamespaces.LOG + "Condition"));

    /**
     * @param resultGraph the contract graph (truth values are read only from here)
     * @param expression  the evaluation's input model: the expression, plus whatever else was evaluated with it
     *                    (observations, resources, ontology); read for structure (which elements are intents and
     *                    conditions), never for truth values
     */
    public static EvaluationResult adapt(Model resultGraph, Model expression) {
        List<Map<String, Object>> conditions = conditions(resultGraph, expression);
        Set<Resource> intents = ContractExtractor.elements(expression, List.of(ICM_INTENT));
        if (intents.isEmpty()) return EvaluationResult.degraded("No icm:Intent in expression", conditions);

        boolean allTrue = true;
        for (Resource intent : intents) {
            Boolean v = value(resultGraph, intent);
            if (v == null) {
                return EvaluationResult.degraded("No truth value derived for intent " + intent.getURI(), conditions);
            }
            allTrue &= v;
        }
        return allTrue ? EvaluationResult.fulfilled(conditions)
                : EvaluationResult.degraded(Reasons.conditionsNotMet(conditions), conditions);
    }

    private static List<Map<String, Object>> conditions(Model resultGraph, Model expression) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Resource> condClasses = ContractExtractor.withSubclasses(expression, CONDITION_CLASSES);
        for (Resource c : ContractExtractor.elements(expression, CONDITION_CLASSES)) {
            Boolean v = value(resultGraph, c);
            if (v == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", typeName(expression, c, condClasses));
            m.put("element", c.getURI());
            m.put("passed", v);
            out.add(m);
        }
        return out;
    }

    /** Local name of the element's most specific condition type (one that is not a root class), else of a root. */
    private static String typeName(Model expression, Resource c, Set<Resource> condClasses) {
        String root = null;
        SortedSet<String> specific = new TreeSet<>();
        for (RDFNode t : expression.listObjectsOfProperty(c, RDF.type).toList()) {
            if (!t.isURIResource() || !condClasses.contains(t.asResource())) continue;
            if (CONDITION_CLASSES.contains(t.asResource())) root = t.asResource().getLocalName();
            else specific.add(t.asResource().getLocalName());
        }
        return !specific.isEmpty() ? specific.first() : root != null ? root : "Condition";
    }

    private static Boolean value(Model g, Resource e) {
        Statement st = g.getProperty(g.createResource(e.getURI()), RDF.value);
        if (st == null || !st.getObject().isLiteral()) return null;
        Object v = st.getLiteral().getValue();
        return v instanceof Boolean b ? b : null;
    }
}
