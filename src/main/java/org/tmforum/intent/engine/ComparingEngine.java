package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a primary and a secondary engine, logs a warning when their result graphs differ on the contract
 * triples, and always returns the primary's outcome. A not-ready or failing secondary never affects the result.
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

    @Override
    public EvaluationOutcome evaluate(EvaluationInput in) {
        EvaluationOutcome main = primary.evaluate(in);
        EngineStatus st = secondary.status();
        if (!st.ready()) {
            log.info("compare: secondary engine '{}' skipped, not ready: {}", secondary.name(), st.reason());
            return main;
        }
        try {
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

    /** Up to {@value #MAX_SHOWN} triples present in only one graph, prefixed {@code <} (primary) or {@code >}. */
    private List<String> differences(Model a, Model b) {
        List<String> out = new ArrayList<>();
        collect(a, b, "<", out);
        collect(b, a, ">", out);
        return out;
    }

    private void collect(Model from, Model other, String tag, List<String> out) {
        for (StmtIterator it = from.listStatements(); it.hasNext() && out.size() < MAX_SHOWN; ) {
            Statement s = it.next();
            if (!other.contains(s)) out.add(tag + " " + s);
        }
    }
}
