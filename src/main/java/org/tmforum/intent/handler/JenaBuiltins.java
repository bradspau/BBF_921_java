package org.tmforum.intent.handler;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.reasoner.rulesys.BuiltinRegistry;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;
import org.apache.jena.util.iterator.ClosableIterator;
import org.apache.jena.vocabulary.RDF;
import org.tmforum.intent.graph.TioNamespaces;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Custom Jena rule builtins for the TIO rules, for what Jena's own builtins cannot express.
 *
 * <ul>
 *   <li>{@code lexLess(?a, ?b)}: true if the name of {@code ?a} sorts before the name of {@code ?b}.
 *       Jena's {@code lessThan} compares numbers only; the choice rule for {@code icm:chooseFrom} needs a
 *       stable order over resources so the same candidate is picked every time.</li>
 *   <li>{@code unitCompare(?va, ?ua, ?vb, ?ub, ?cmp)}: binds {@code ?cmp} to -1, 0 or 1 as quantity
 *       {@code va ua} is less than, equal to or greater than {@code vb ub} after unit conversion
 *       ({@link Quantities#compare}). Fails on a unit mismatch or a value that is not a number. A unit is a literal;
 *       an empty one means "no unit".</li>
 *   <li>{@code unitMismatch(?ua, ?ub)} and {@code unitMismatch(?ua, ?ub, ?message)}: succeeds when the two units
 *       have different dimensions ({@link Quantities#parse}); the three-argument form binds the mismatch text the
 *       Java engine reports.</li>
 *   <li>{@code isNumber(?v)} / {@code notNumber(?v)}: whether {@code ?v} is a literal whose lexical form reads as a
 *       decimal number (as the Java engine reads values). Exactly one of the two succeeds for any bound node.</li>
 *   <li>{@code newestObservation(?metric, ?obs)}: binds the {@code met:Observation} of {@code ?metric} with the
 *       greatest {@code met:obtainedAt}; {@code noObservation(?metric)} succeeds when there is none.</li>
 * </ul>
 *
 * <p>Every builtin fails (never throws) on unbound or unexpected input.
 *
 * <p><b>Newest observation.</b> Candidates are subjects with {@code rdf:type met:Observation},
 * {@code met:observedMetric ?metric} and a usable value: a literal {@code rdf:value}, or a nested quantity whose
 * {@code rdf:value} is a literal (the same candidates as the Java engine). {@code met:obtainedAt} is read from its
 * lexical form as an instant (ISO-8601 with offset); a missing or unreadable time counts as the oldest. Among
 * candidates with the same time the lexically lowest node (IRI) wins, so the choice is deterministic. These
 * builtins read only facts present when the stage starts (observations are input), so they are safe in a forward
 * stage.
 */
public final class JenaBuiltins {

    private JenaBuiltins() {}

    /** Registers the builtins with Jena. Safe to call more than once. */
    public static void register() {
        BuiltinRegistry.theRegistry.register(new LexLess());
        BuiltinRegistry.theRegistry.register(new UnitCompare());
        BuiltinRegistry.theRegistry.register(new UnitMismatch());
        BuiltinRegistry.theRegistry.register(new IsNumber(true));
        BuiltinRegistry.theRegistry.register(new IsNumber(false));
        BuiltinRegistry.theRegistry.register(new NewestObservation());
        BuiltinRegistry.theRegistry.register(new NoObservation());
    }

    private static final Node RDF_TYPE = RDF.type.asNode();
    private static final Node RDF_VALUE = RDF.value.asNode();
    private static final Node MET_OBSERVATION = NodeFactory.createURI(TioNamespaces.MET + "Observation");
    private static final Node MET_OBSERVED_METRIC = NodeFactory.createURI(TioNamespaces.MET + "observedMetric");
    private static final Node MET_OBTAINED_AT = NodeFactory.createURI(TioNamespaces.MET + "obtainedAt");

    /** {@code lexLess(?a, ?b)}: URI (or literal text, or blank-node label) of a < that of b. */
    static final class LexLess extends BaseBuiltin {
        @Override
        public String getName() { return "lexLess"; }

        @Override
        public int getArgLength() { return 2; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            checkArgs(length, context);
            Node a = getArg(0, args, context);
            Node b = getArg(1, args, context);
            return text(a).compareTo(text(b)) < 0;
        }
    }

    private static String text(Node n) {
        if (n.isURI()) return n.getURI();
        if (n.isLiteral()) return n.getLiteralLexicalForm();
        if (n.isBlank()) return n.getBlankNodeLabel();
        return String.valueOf(n);
    }

    /** {@code unitCompare(?va, ?ua, ?vb, ?ub, ?cmp)}. */
    static final class UnitCompare extends BaseBuiltin {
        @Override
        public String getName() { return "unitCompare"; }

        @Override
        public int getArgLength() { return 5; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            if (length != 5) return false;
            BigDecimal a = number(getArg(0, args, context));
            BigDecimal b = number(getArg(2, args, context));
            Node ua = getArg(1, args, context);
            Node ub = getArg(3, args, context);
            if (a == null || b == null || !ua.isLiteral() || !ub.isLiteral()) return false;
            int cmp;
            try {
                cmp = Integer.signum(Quantities.compare(a, unit(ua), b, unit(ub)));
            } catch (Quantities.UnitMismatch e) {
                return false;
            }
            Node result = NodeFactory.createLiteral(Integer.toString(cmp), XSDDatatype.XSDint);
            return context.getEnv().bind(args[4], result);
        }
    }

    /** {@code unitMismatch(?ua, ?ub)} or {@code unitMismatch(?ua, ?ub, ?message)}. */
    static final class UnitMismatch extends BaseBuiltin {
        @Override
        public String getName() { return "unitMismatch"; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            if (length != 2 && length != 3) return false;
            Node ua = getArg(0, args, context);
            Node ub = getArg(1, args, context);
            if (!ua.isLiteral() || !ub.isLiteral()) return false;
            try {
                Quantities.compare(BigDecimal.ONE, unit(ua), BigDecimal.ONE, unit(ub));
                return false;
            } catch (Quantities.UnitMismatch e) {
                return length == 2 || context.getEnv().bind(args[2], NodeFactory.createLiteral(e.getMessage()));
            }
        }
    }

    /** {@code isNumber(?v)} (when {@code expected}) or {@code notNumber(?v)}. */
    static final class IsNumber extends BaseBuiltin {
        private final boolean expected;

        IsNumber(boolean expected) { this.expected = expected; }

        @Override
        public String getName() { return expected ? "isNumber" : "notNumber"; }

        @Override
        public int getArgLength() { return 1; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            if (length != 1) return false;
            Node v = getArg(0, args, context);
            if (v == null || v.isVariable()) return false;
            return (number(v) != null) == expected;
        }
    }

    /** {@code newestObservation(?metric, ?obs)}. */
    static final class NewestObservation extends BaseBuiltin {
        @Override
        public String getName() { return "newestObservation"; }

        @Override
        public int getArgLength() { return 2; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            if (length != 2) return false;
            Node newest = newest(getArg(0, args, context), context);
            return newest != null && context.getEnv().bind(args[1], newest);
        }
    }

    /** {@code noObservation(?metric)}: the metric is bound and has no observation {@code newestObservation} would use. */
    static final class NoObservation extends BaseBuiltin {
        @Override
        public String getName() { return "noObservation"; }

        @Override
        public int getArgLength() { return 1; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            if (length != 1) return false;
            Node metric = getArg(0, args, context);
            if (metric == null || metric.isVariable() || metric.isLiteral()) return false;
            return newest(metric, context) == null;
        }
    }

    /** The newest usable observation of {@code metric}, or null (also for an unbound or literal metric). */
    static Node newest(Node metric, RuleContext context) {
        if (metric == null || metric.isVariable() || metric.isLiteral()) return null;
        Node best = null;
        Instant bestAt = null;
        ClosableIterator<Triple> it = context.find(null, MET_OBSERVED_METRIC, metric);
        try {
            while (it.hasNext()) {
                Node obs = it.next().getSubject();
                if (!context.contains(obs, RDF_TYPE, MET_OBSERVATION) || !hasUsableValue(obs, context)) continue;
                Instant at = obtainedAt(obs, context);
                if (best == null || at.isAfter(bestAt) || (at.equals(bestAt) && text(obs).compareTo(text(best)) < 0)) {
                    best = obs;
                    bestAt = at;
                }
            }
        } finally {
            it.close();
        }
        return best;
    }

    /** A literal rdf:value, or a nested quantity whose rdf:value is a literal. */
    private static boolean hasUsableValue(Node obs, RuleContext context) {
        ClosableIterator<Triple> it = context.find(obs, RDF_VALUE, null);
        try {
            while (it.hasNext()) {
                Node v = it.next().getObject();
                if (v.isLiteral()) return true;
                if (!v.equals(obs) && anyLiteral(context.find(v, RDF_VALUE, null))) return true;
            }
            return false;
        } finally {
            it.close();
        }
    }

    private static boolean anyLiteral(ClosableIterator<Triple> it) {
        try {
            while (it.hasNext()) if (it.next().getObject().isLiteral()) return true;
            return false;
        } finally {
            it.close();
        }
    }

    /** The latest met:obtainedAt of the observation (Instant.MIN if missing or unreadable), read as the Java engine reads it. */
    private static Instant obtainedAt(Node obs, RuleContext context) {
        Instant latest = Instant.MIN;
        ClosableIterator<Triple> it = context.find(obs, MET_OBTAINED_AT, null);
        try {
            while (it.hasNext()) {
                Node t = it.next().getObject();
                if (!t.isLiteral()) continue;
                Instant at = parseInstant(t.getLiteralLexicalForm());
                if (at.isAfter(latest)) latest = at;
            }
        } finally {
            it.close();
        }
        return latest;
    }

    private static Instant parseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(s, Instant::from);
            } catch (RuntimeException e2) {
                return Instant.MIN;
            }
        }
    }

    /** A literal's value as a decimal, read from its lexical form (as the Java engine does), or null. */
    private static BigDecimal number(Node n) {
        if (n == null || !n.isLiteral()) return null;
        try {
            return new BigDecimal(n.getLiteralLexicalForm());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A unit literal's text; blank means no unit. */
    private static String unit(Node n) {
        String u = n.getLiteralLexicalForm();
        return u.isBlank() ? null : u;
    }
}
