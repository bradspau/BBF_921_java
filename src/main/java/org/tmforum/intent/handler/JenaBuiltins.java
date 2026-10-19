package org.tmforum.intent.handler;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.reasoner.rulesys.BuiltinRegistry;
import org.apache.jena.reasoner.rulesys.RuleContext;
import org.apache.jena.reasoner.rulesys.builtins.BaseBuiltin;
import org.apache.jena.util.iterator.ClosableIterator;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.apache.jena.vocabulary.RDF;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.TioNamespaces;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
 *   <li>{@code allSubjectsIn(?p1, ?o1, ?p2, ?o2)}: every subject {@code s} of {@code (s ?p1 ?o1)} in the <em>previous
 *       stage</em> (the inference graph's raw graph) also has {@code (s ?p2 ?o2)} in the current graph. A universal
 *       ("for all members ...") test that a single {@code noValue} cannot express. Its universe is complete when the
 *       stage starts, and the tested facts only grow, so once it succeeds it stays true: it is monotone and safe in a
 *       later stage (tio_b_sets.rules). In stage A the raw graph is the input, so it must not be used there for
 *       facts that stage A derives.</li>
 *   <li>{@code forAllCopy(?k, ?q, ?x, ?m)} (rule head only): copies the condition template {@code ?q} of the
 *       {@code set:forAll} application {@code ?k} for member {@code ?m}, replacing the variable {@code ?x} by
 *       {@code ?m} (or by the literal of {@code ?m tmf:proxyOf}, a literal member's proxy node), and adds
 *       {@code ?q2 tmf:copyFor ?k ; tmf:copyOf ?q ; tmf:copyMember ?m}. The template is
 *       {@code ?q} and the blank nodes reachable from it, read from the raw (input) graph; every copied node is a
 *       blank node named deterministically from (k, node, m). All triples of one copy are added in one rule firing,
 *       so a {@code noValue} test on a copied node never sees a partial copy (RETEEngine.addTriple writes the
 *       deduction at once).</li>
 *   <li>{@code prevLowest(?p1, ?o1, ..., ?pn, ?on, ?out)}: binds {@code ?out} to the lowest subject {@code s} that has
 *       every {@code (s ?pi ?oi)} in the <em>previous stage</em> (the raw graph), in the Java engine's candidate
 *       order ({@code TurtleEvaluator.sortedMembers}: an IRI by {@link String#compareTo} of its text, which is also
 *       {@code lexLess}'s order, every IRI before every blank node); literal-member proxies ({@code tmf:proxyOf}) are
 *       not candidates. Fails when there is none. Used by the choice strata (tio_c_choose.rules): the minimum in one
 *       pass instead of a quadratic "a lower candidate exists" join, read from facts that are complete when the stage
 *       starts, so the choice is the same whatever order the rules fire in.</li>
 *   <li>{@code prevNone(?s, ?p, ?o)}: the previous stage (the raw graph) has no {@code (?s ?p ?o)}. A
 *       {@code noValue} that cannot see facts derived in the current stage.</li>
 * </ul>
 *
 * <p>Every builtin fails (never throws) on unbound or unexpected input. Values whose decimal exponent exceeds
 * {@link #MAX_SCALE} are not numbers, so unit conversion cannot overflow.
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
        BuiltinRegistry.theRegistry.register(new AllSubjectsIn());
        BuiltinRegistry.theRegistry.register(new ForAllCopy());
        BuiltinRegistry.theRegistry.register(new PrevLowest());
        BuiltinRegistry.theRegistry.register(new PrevNone());
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
            } catch (Quantities.UnitMismatch | ArithmeticException e) {
                return false;                     // ArithmeticException: backstop, number() already bounds the scale
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
            } catch (ArithmeticException e) {
                return false;                     // backstop only: comparing ONE with ONE cannot overflow
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
            Node newest;
            try {
                newest = newest(getArg(0, args, context), context);
            } catch (RuntimeException e) {
                return false;                     // backstop: a builtin fails, never throws
            }
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
            try {
                return newest(metric, context) == null;
            } catch (RuntimeException e) {
                return false;                     // backstop: a builtin fails, never throws
            }
        }
    }

    /** {@code allSubjectsIn(?p1, ?o1, ?p2, ?o2)}: see the class comment. */
    static final class AllSubjectsIn extends BaseBuiltin {
        @Override
        public String getName() { return "allSubjectsIn"; }

        @Override
        public int getArgLength() { return 4; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            if (length != 4) return false;
            Node p1 = getArg(0, args, context), o1 = getArg(1, args, context);
            Node p2 = getArg(2, args, context), o2 = getArg(3, args, context);
            for (Node n : new Node[]{p1, o1, p2, o2}) if (n == null || n.isVariable()) return false;
            if (!p1.isURI() || !p2.isURI()) return false;
            ExtendedIterator<Triple> it = previousStage(context).find(Node.ANY, p1, o1);
            try {
                while (it.hasNext()) {
                    if (!context.contains(it.next().getSubject(), p2, o2)) return false;
                }
                return true;
            } catch (RuntimeException e) {
                return false;                     // backstop: a builtin fails, never throws
            } finally {
                it.close();
            }
        }
    }

    /** {@code prevLowest(?p1, ?o1, ..., ?out)}: see the class comment. */
    static final class PrevLowest extends BaseBuiltin {
        @Override
        public String getName() { return "prevLowest"; }

        @Override
        public int getArgLength() { return 0; }          // variable: one or more (property, object) pairs, then ?out

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            if (length < 3 || length % 2 == 0) return false;
            int pairs = (length - 1) / 2;
            Node[] p = new Node[pairs], o = new Node[pairs];
            for (int i = 0; i < pairs; i++) {
                p[i] = getArg(2 * i, args, context);
                o[i] = getArg(2 * i + 1, args, context);
                if (p[i] == null || p[i].isVariable() || !p[i].isURI() || o[i] == null || o[i].isVariable()) return false;
            }
            Graph prev = previousStage(context);
            Node best = null;
            String bestKey = null;
            ExtendedIterator<Triple> it = prev.find(Node.ANY, p[0], o[0]);
            try {
                while (it.hasNext()) {
                    Node s = it.next().getSubject();
                    if (s.isLiteral() || prev.contains(s, TMF_PROXY_OF, Node.ANY)) continue;
                    String key = candidateKey(s);
                    if (bestKey != null && key.compareTo(bestKey) >= 0) continue;
                    boolean all = true;
                    for (int i = 1; i < pairs && all; i++) all = prev.contains(s, p[i], o[i]);
                    if (all) { best = s; bestKey = key; }
                }
            } catch (RuntimeException e) {
                return false;                     // backstop: a builtin fails, never throws
            } finally {
                it.close();
            }
            if (best == null) return false;
            Node out = getArg(length - 1, args, context);
            if (out != null && !out.isVariable()) return out.equals(best);
            return context.getEnv().bind(args[length - 1], best);
        }
    }

    /** The Java engine's candidate order (TurtleEvaluator.sortedMembers): IRIs by text, then blank nodes. */
    static String candidateKey(Node n) {
        return n.isURI() ? n.getURI() : "~" + n.getBlankNodeLabel();
    }

    /** {@code prevNone(?s, ?p, ?o)}: see the class comment. */
    static final class PrevNone extends BaseBuiltin {
        @Override
        public String getName() { return "prevNone"; }

        @Override
        public int getArgLength() { return 3; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            if (length != 3) return false;
            Node s = getArg(0, args, context), p = getArg(1, args, context), o = getArg(2, args, context);
            for (Node n : new Node[]{s, p, o}) if (n == null || n.isVariable()) return false;
            try {
                return !previousStage(context).contains(s, p, o);
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** The graph the current stage reads as its input: the raw graph of the inference graph. */
    private static Graph previousStage(RuleContext context) {
        return context.getGraph().getRawGraph();
    }

    private static final Node TMF_COPY_FOR = NodeFactory.createURI(Namespaces.TMF + "copyFor");
    private static final Node TMF_PROXY_OF = NodeFactory.createURI(Namespaces.TMF + "proxyOf");
    private static final Node TMF_COPY_OF = NodeFactory.createURI(Namespaces.TMF + "copyOf");
    private static final Node TMF_COPY_MEMBER = NodeFactory.createURI(Namespaces.TMF + "copyMember");

    /** {@code forAllCopy(?k, ?q, ?x, ?m)} in a rule head: see the class comment. */
    static final class ForAllCopy extends BaseBuiltin {
        @Override
        public String getName() { return "forAllCopy"; }

        @Override
        public int getArgLength() { return 4; }

        @Override
        public boolean bodyCall(Node[] args, int length, RuleContext context) {
            return false;                         // only meaningful in a rule head
        }

        @Override
        public void headAction(Node[] args, int length, RuleContext context) {
            if (length != 4) return;
            Node k = getArg(0, args, context), q = getArg(1, args, context);
            Node x = getArg(2, args, context), m = getArg(3, args, context);
            for (Node n : new Node[]{k, q, x, m}) if (n == null || n.isVariable()) return;
            if (q.isLiteral() || q.equals(x)) return;
            try {
                Node value = m;                   // a literal member is represented by its proxy node
                ClosableIterator<Triple> proxy = context.find(m, TMF_PROXY_OF, null);
                try {
                    if (proxy.hasNext()) value = proxy.next().getObject();
                } finally {
                    proxy.close();
                }
                List<Triple> copy = copy(previousStage(context), k, q, x, m, value);
                for (Triple t : copy) context.add(t);
            } catch (RuntimeException e) {
                // backstop: never throw from a rule; without a copy the forAll stays undecided for this member
            }
        }

        /** The copy of template {@code q} for member {@code m}; {@code x} is replaced by {@code value}. */
        static List<Triple> copy(Graph raw, Node k, Node q, Node x, Node m, Node value) {
            Set<Node> template = new LinkedHashSet<>();
            Deque<Node> todo = new ArrayDeque<>(List.of(q));
            while (!todo.isEmpty()) {
                Node n = todo.pop();
                if (!template.add(n)) continue;
                ExtendedIterator<Triple> it = raw.find(n, Node.ANY, Node.ANY);
                try {
                    while (it.hasNext()) {
                        Node o = it.next().getObject();
                        if (o.isBlank() && !o.equals(x) && !template.contains(o)) todo.push(o);
                    }
                } finally {
                    it.close();
                }
            }
            Map<Node, Node> names = new HashMap<>();
            for (Node n : template) names.put(n, copyName(k, n, m));
            List<Triple> out = new ArrayList<>();
            for (Node n : template) {
                for (Triple t : raw.find(n, Node.ANY, Node.ANY).toList()) {
                    Node o = t.getObject();
                    Node o2 = o.equals(x) ? value : names.getOrDefault(o, o);
                    out.add(Triple.create(names.get(n), t.getPredicate(), o2));
                }
            }
            Node q2 = names.get(q);
            out.add(Triple.create(q2, TMF_COPY_FOR, k));
            out.add(Triple.create(q2, TMF_COPY_OF, q));
            out.add(Triple.create(q2, TMF_COPY_MEMBER, m));
            return out;
        }

        private static Node copyName(Node k, Node n, Node m) {
            String key = k + "\u0000" + n + "\u0000" + m;
            return NodeFactory.createBlankNode(
                    "forall-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
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

    /**
     * Largest |scale| (decimal exponent) of a value accepted as a number. Unit conversion multiplies by factors up to
     * 10^±30 (and 1024^8), which adds their scale; a scale near Integer.MAX_VALUE (e.g. {@code "1E-2147483647"})
     * would underflow and throw. 10,000 is far beyond any real quantity (xsd:double spans about 10^±324) and keeps
     * the arithmetic cheap. A value outside it is "not a number": the function is false with the missing-value text.
     */
    static final int MAX_SCALE = 10_000;

    /** A literal's value as a decimal, read from its lexical form (as the Java engine does), or null. */
    private static BigDecimal number(Node n) {
        if (n == null || !n.isLiteral()) return null;
        try {
            BigDecimal d = new BigDecimal(n.getLiteralLexicalForm());
            return Math.abs((long) d.scale()) > MAX_SCALE ? null : d;
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
