package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.tmforum.intent.handler.BestEffortLimits;
import org.tmforum.intent.model.EvaluationResult;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The part of a handler result ({@link EvaluationResult}) that code in {@code src/main} consumes: the state, the reason
 * string and, per condition and in order, the keys {@link #KEYS} read by {@code HandlerStateWriter}, Flow 3
 * ({@link BestEffortLimits}) and Flow 4 (allocation write-back). Two engines agree on a handler result when their
 * projections are equal and Flow 3 proposes the same expression from both ({@link #firstDifference}). Shared by
 * {@link ComparingEngine} and the conformance tests.
 *
 * @param state      {@code intentHandlingState}
 * @param reason     the reason, {@code ""} for none
 * @param conditions one line per condition: {@code key=value} pairs of {@link #KEYS}, in key order, joined by
 *                   {@code " | "}
 */
public record HandlerProjection(String state, String reason, List<String> conditions) {

    /** The condition keys a consumer in src/main reads (HandlerStateWriter, Flow 3 BestEffortLimits, Flow 4). */
    public static final Set<String> KEYS = Set.of("type", "passed", "operator", "observed", "bound", "selected",
            "error", "held", "lower", "upper", "observedUnit", "boundUnit");

    private static final BestEffortLimits FLOW_3 = new BestEffortLimits();

    public static HandlerProjection of(EvaluationResult r) {
        return new HandlerProjection(r.intentHandlingState(), r.reason() == null ? "" : r.reason(),
                r.conditions() == null ? List.of() : r.conditions().stream().map(HandlerProjection::line).toList());
    }

    /** One condition as {@code key=value} pairs of {@link #KEYS} with a value, in key order. */
    public static String line(Map<String, Object> c) {
        StringJoiner j = new StringJoiner(" | ");
        new TreeMap<>(c).forEach((k, v) -> {
            if (KEYS.contains(k) && v != null) j.add(k + "=" + v);
        });
        return j.toString();
    }

    /** The expression Flow 3 would propose from {@code r}, parsed, or empty when it proposes nothing. */
    public static Optional<Model> proposal(String expression, EvaluationResult r) {
        return FLOW_3.apply(expression, r.conditions()).map(t -> {
            Model m = ModelFactory.createDefaultModel();
            m.read(new StringReader(t), null, "TURTLE");
            return m;
        });
    }

    /**
     * The first difference between two handler results for {@code expression}, as text naming the two sides
     * {@code nameA} and {@code nameB}; {@code null} when they agree. Checked in order: state, reason, number of
     * conditions, each condition entry, the Flow 3 proposal.
     */
    public static String firstDifference(String expression, EvaluationResult a, String nameA,
                                         EvaluationResult b, String nameB) {
        HandlerProjection pa = of(a);
        HandlerProjection pb = of(b);
        if (!Objects.equals(pa.state(), pb.state())) {
            return "state: " + nameA + "=" + pa.state() + ", " + nameB + "=" + pb.state();
        }
        if (!pa.reason().equals(pb.reason())) {
            return "reason: " + nameA + "='" + pa.reason() + "', " + nameB + "='" + pb.reason() + "'";
        }
        int n = Math.min(pa.conditions().size(), pb.conditions().size());
        for (int i = 0; i < n; i++) {
            if (!pa.conditions().get(i).equals(pb.conditions().get(i))) {
                return "condition " + (i + 1) + ": " + nameA + "={" + pa.conditions().get(i) + "}, "
                        + nameB + "={" + pb.conditions().get(i) + "}";
            }
        }
        if (pa.conditions().size() != pb.conditions().size()) {
            return "condition count: " + nameA + "=" + pa.conditions().size() + ", " + nameB + "="
                    + pb.conditions().size();
        }
        Optional<Model> qa = proposal(expression, a);
        Optional<Model> qb = proposal(expression, b);
        if (qa.isPresent() != qb.isPresent()) {
            return "Flow 3 proposal: only " + (qa.isPresent() ? nameA : nameB) + " proposes one";
        }
        if (qa.isPresent() && !qa.get().isIsomorphicWith(qb.get())) {
            return "Flow 3 proposal: " + nameA + " and " + nameB + " propose different expressions";
        }
        return null;
    }
}
