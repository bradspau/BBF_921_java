package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.tmforum.intent.model.ElementResult;
import org.tmforum.intent.model.ReportTree;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes the shared result graph (spec 5.2) from the Java evaluator's element tree. */
public final class ResultGraphWriter {

    private ResultGraphWriter() {}

    /**
     * @param tree         the evaluator's element tree; null, or one without an icm:Intent, gives an empty graph
     * @param observations the parsed observations, from which linked observation individuals are copied (may be null)
     */
    public static Model write(ReportTree tree, Model observations) {
        Model m = ModelFactory.createDefaultModel();
        if (tree == null || tree.elements().stream().noneMatch(e -> "Intent".equals(e.kind()))) return m;
        Set<String> seen = new HashSet<>();
        for (ElementResult e : tree.elements()) element(m, e, observations, seen);
        return m;
    }

    private static void element(Model m, ElementResult e, Model observations, Set<String> seen) {
        if (!seen.add(e.uri())) return;                    // one element, one set of triples
        Resource r = m.createResource(e.uri());
        r.addLiteral(RDF.value, e.passed());
        for (ElementResult child : e.children()) element(m, child, observations, seen);

        for (Map<String, Object> d : e.details()) {
            if (!Boolean.TRUE.equals(d.get("passed"))) {
                String why = Reasons.of(d);
                if (why != null) {
                    Resource reason = m.createResource();
                    reason.addProperty(RDF.type, ResultContract.ICM_REASON_CLASS);
                    reason.addProperty(RDFS.label, why);
                    r.addProperty(ResultContract.ICM_REASON, reason);
                }
            }
            if (d.get("selected") != null && e.targetUri() != null) {
                m.createResource(e.targetUri()).addProperty(RDFS.member, m.createResource(d.get("selected").toString()));
            }
            if (d.get("observation") != null) {
                r.addProperty(ResultContract.MET_OBSERVED, observation(m, d, observations));
            }
        }
    }

    /** The observation individual: copied from the observations when present there, else rebuilt from the detail. */
    private static Resource observation(Model m, Map<String, Object> d, Model observations) {
        Resource obs = m.createResource(d.get("observation").toString());
        obs.addProperty(RDF.type, ResultContract.MET_OBSERVATION);
        Resource src = observations == null ? null : observations.createResource(d.get("observation").toString());
        boolean known = src != null && observations.contains(src, RDF.type, ResultContract.MET_OBSERVATION);
        Literal value = null;
        Literal unit = null;
        if (known) {
            ResultContract.copyObservation(observations, m, src);
            return obs;
        }
        if (d.get("metric") != null) obs.addProperty(ResultContract.MET_OBSERVED_METRIC, m.createResource(d.get("metric").toString()));
        if (d.get("observed") instanceof BigDecimal bd) value = m.createTypedLiteral(bd);
        if (d.get("observedUnit") != null) unit = m.createLiteral(d.get("observedUnit").toString());
        if (value != null) obs.addLiteral(RDF.value, value);
        if (unit != null) obs.addLiteral(ResultContract.QUAN_UNIT, unit);
        return obs;
    }
}
