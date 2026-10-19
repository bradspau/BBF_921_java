package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.vocabulary.RDFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Runs a primary and a secondary engine, logs a warning when their result graphs differ on the contract
 * triples, and always returns the primary's outcome. A not-ready or failing secondary never affects the result; a
 * failing primary does (its exception propagates).
 */
public class ComparingEngine implements EvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(ComparingEngine.class);
    private static final int MAX_SHOWN = 5;

    private final EvaluationEngine primary;
    private final EvaluationEngine secondary;

    public ComparingEngine(EvaluationEngine primary, EvaluationEngine secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override public String name() { return "compare"; }

    @Override public EngineStatus status() { return primary.status(); }

    /**
     * Evaluates with the primary (its exceptions propagate), then, if the secondary is ready, with the secondary;
     * anything the secondary does, its status check included, is caught and logged.
     */
    @Override
    public EvaluationOutcome evaluate(EvaluationInput in) {
        EvaluationOutcome main = primary.evaluate(in);
        try {
            EngineStatus st = secondary.status();
            if (!st.ready()) {
                log.info("compare: secondary engine '{}' skipped, not ready: {}", secondary.name(), st.reason());
                return main;
            }
            EvaluationOutcome other = secondary.evaluate(in);
            Model a = ResultContract.filter(main.resultGraph());
            Model b = ResultContract.filter(other.resultGraph());
            if (!a.isIsomorphicWith(b)) {
                log.warn("compare: '{}' ({} triples) and '{}' ({} triples) differ for intent {}; "
                                + "first differences: {}",
                        primary.name(), a.size(), secondary.name(), b.size(), in.intentId(), differences(a, b));
            }
        } catch (RuntimeException e) {
            log.warn("compare: secondary engine '{}' failed for intent {}: {}", secondary.name(), in.intentId(), e.toString());
        }
        return main;
    }

    /**
     * Up to {@value #MAX_SHOWN} statements (shared by both directions) present in only one graph, prefixed {@code <}
     * (primary) or {@code >} (secondary). Statements are compared as text in which a blank node is written as its
     * {@code rdfs:label} (reasons) or {@code []}, so blank nodes with different ids but the same content are not
     * reported.
     */
    private static List<String> differences(Model a, Model b) {
        Set<String> la = lines(a);
        Set<String> lb = lines(b);
        List<String> out = new ArrayList<>();
        for (String l : la) if (out.size() < MAX_SHOWN && !lb.contains(l)) out.add("< " + l);
        for (String l : lb) if (out.size() < MAX_SHOWN && !la.contains(l)) out.add("> " + l);
        return out;
    }

    private static Set<String> lines(Model m) {
        Set<String> out = new TreeSet<>();
        for (StmtIterator it = m.listStatements(); it.hasNext(); ) {
            Statement st = it.next();
            out.add(text(m, st.getSubject()) + " <" + st.getPredicate().getURI() + "> " + text(m, st.getObject()));
        }
        return out;
    }

    private static String text(Model m, RDFNode n) {
        if (n.isURIResource()) return "<" + n.asResource().getURI() + ">";
        if (n.isLiteral()) return n.asNode().toString();
        Statement label = m.getProperty(n.asResource(), RDFS.label);
        return label == null ? "[]" : "[\"" + label.getString() + "\"]";
    }
}
