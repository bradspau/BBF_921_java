package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.TioNamespaces;
import org.tmforum.intent.model.EvaluationResult;

import java.math.BigDecimal;
import java.util.*;

/**
 * Builds the handler's {@link EvaluationResult} (state, reason, flat condition list) for the rule engine (spec 5.3),
 * so Flows 1-4, {@code HandlerStateWriter} and {@code BestEffortLimits} keep working and give what the Java engine
 * gives.
 *
 * <p>It reads the engine's <b>inferred model</b> (the output of the stage chain, before contract extraction), not
 * the contract: the handler's list has one entry per function <em>application</em>, including applications on
 * anonymous and untyped conditions and several applications on one element, whose own values are not in the contract
 * (Task 16 fix round, ruling R1). Every value, failure text, chosen resource and observation comes from the rules'
 * facts on the application's contribution node ({@code tmf:contribution}, {@code tmf:cls}, {@code tmf:reasonText},
 * {@code tmf:failure}, {@code tmf:arg1}/{@code tmf:argValue}/{@code tmf:argUnit}, {@code tmf:observedVia},
 * {@code tmf:chosenFor}); the input model is read for structure only (evaluation roots, list order, the name of the
 * predicate an application used).
 *
 * <ul>
 *   <li><b>Order</b>, as {@code TurtleEvaluator}: the evaluation roots (subjects of {@code log:allOf/anyOf/noneOf/
 *       oneOf} that are no operator's member, then of {@code log:match*}; each property's subjects in IRI order, blank
 *       nodes last), depth first through the operators' lists in list order (a node reached twice is listed twice),
 *       then each node's own applications: statement functions, quantity functions, delivery contributions, others.
 *       Without any root, the Java engine's flat scan: quantity subjects by function (legacy name before the
 *       specification name, in the order atLeast, atMost, greater, smaller, exactly, inRange), then delivery and
 *       property expectations.</li>
 *   <li><b>Entries</b> (the Java engine's keys):
 *       <ul>
 *         <li>quantity: {@code type} (the predicate's local name; {@code quaninRange} for inRange), {@code operator}
 *             ({@code >=}, {@code <=}, {@code >}, {@code <}, {@code ==}, {@code <=<=}), {@code observed} /
 *             {@code observedUnit} (first argument, when it has a numeric value), {@code bound} / {@code boundUnit}
 *             (second argument, only with an observed value; for {@code quan:exactly} the pair its reason names) or
 *             {@code lower}/{@code upper}, {@code metric}, {@code observation}, {@code error};</li>
 *         <li>delivery: one entry per delivery contribution (target, type): {@code DeliveryExpectation},
 *             {@code deliveryType}, {@code selected} (the resource chosen or held for that target and type, given to
 *             the first contribution of the group only, as the Java engine lists the expectation that chose it),
 *             {@code held}, {@code error}; in (target, type) order;</li>
 *         <li>{@code logMatch}, {@code logMatchAll}, ... and other functions by local name; a property expectation
 *             with no function: {@code PropertyExpectation}.</li>
 *       </ul>
 *       {@code passed} is the application's own value; {@code error} its failure text (an error, never the plain
 *       comparison text, which the label is built from instead).</li>
 *   <li><b>State.</b> With an IRI-identified {@code icm:Intent} (the exact type, as the Java engine and
 *       {@code ReportBuilder} read it): every such intent true gives {@code Fulfilled}, else {@code Degraded}. Without
 *       one, the Java engine's rule: all roots true, or (no root) all scanned conditions true. With neither a root nor
 *       a condition the result is {@code Degraded} "No quantity conditions found in expression", as in the Java
 *       engine.</li>
 *   <li><b>Reason.</b> {@link Reasons#conditionsNotMet}, the same code as the Java engine.</li>
 * </ul>
 *
 * <p>Keys the Java engine also writes and no code in {@code src/main} reads are not produced: {@code candidates},
 * {@code member_count}, {@code count}, and the {@code subject}/{@code predicate}/{@code object} of the statement
 * functions. {@code report} (the TR290 element tree) is null: the report is built from the contract (spec 5.3).
 */
public final class ResultAdapter {

    private ResultAdapter() {}

    static final Resource ICM_INTENT = ResourceFactory.createResource(TioNamespaces.ICM + "Intent");
    static final Resource ICM_DELIVERY = ResourceFactory.createResource(TioNamespaces.ICM + "DeliveryExpectation");
    static final Resource ICM_PROPERTY = ResourceFactory.createResource(TioNamespaces.ICM + "PropertyExpectation");

    private static final String TMF = Namespaces.TMF;
    static final Property CONTRIBUTION = ResourceFactory.createProperty(TMF + "contribution");
    static final Property FUNCTION = ResourceFactory.createProperty(TMF + "function");
    static final Property ARGUMENTS = ResourceFactory.createProperty(TMF + "arguments");
    static final Property CLS = ResourceFactory.createProperty(TMF + "cls");
    static final Resource T = ResourceFactory.createResource(TMF + "T");
    static final Resource F = ResourceFactory.createResource(TMF + "F");
    static final Property RESULT = ResourceFactory.createProperty(TMF + "result");
    static final Property FAILURE = ResourceFactory.createProperty(TMF + "failure");
    static final Property REASON_TEXT = ResourceFactory.createProperty(TMF + "reasonText");
    static final Property ARG1 = ResourceFactory.createProperty(TMF + "arg1");
    static final Property ARG2 = ResourceFactory.createProperty(TMF + "arg2");
    static final Property ARG3 = ResourceFactory.createProperty(TMF + "arg3");
    static final Property ARG_VALUE = ResourceFactory.createProperty(TMF + "argValue");
    static final Property ARG_UNIT = ResourceFactory.createProperty(TMF + "argUnit");
    static final Property OBSERVED_VIA = ResourceFactory.createProperty(TMF + "observedVia");
    static final Property PAIR_VIOLATION = ResourceFactory.createProperty(TMF + "pairViolation");
    static final Property EXACTLY_CELL_OF = ResourceFactory.createProperty(TMF + "exactlyCellOf");
    static final Property DELIVERY_TARGET = ResourceFactory.createProperty(TMF + "deliveryTarget");
    static final Property NEEDS_CLASS = ResourceFactory.createProperty(TMF + "needsClass");
    static final Property CHOICE_GROUP = ResourceFactory.createProperty(TMF + "choiceGroup");
    static final Property INSTANCE_OF = ResourceFactory.createProperty(TMF + "instanceOf");
    static final Property HELD = ResourceFactory.createProperty(TMF + "held");
    static final Resource YES = ResourceFactory.createResource(TMF + "Yes");
    static final Resource ANY = ResourceFactory.createResource(TMF + "Any");
    static final Resource DELIVERY = ResourceFactory.createResource(TMF + "Delivery");
    static final Resource REPORTING = ResourceFactory.createResource(TMF + "Reporting");

    static final List<Property> OPERATORS = List.of(TioNamespaces.LOG_allOf, TioNamespaces.LOG_anyOf,
            TioNamespaces.LOG_noneOf, TioNamespaces.LOG_oneOf);
    static final Map<String, String> MATCHES = new LinkedHashMap<>();
    static {
        MATCHES.put("match", "logMatch");
        MATCHES.put("matchAll", "logMatchAll");
        MATCHES.put("matchAny", "logMatchAny");
        MATCHES.put("matchNone", "logMatchNone");
        MATCHES.put("matchOne", "logMatchOne");
        MATCHES.put("matchStatement", "logMatchStatement");
    }
    /** Quantity function names in the Java engine's flat-scan order (TurtleEvaluator.flatScan). */
    static final List<String> QUANTITY = List.of("quanatLeast", "atLeast", "quanatMost", "atMost", "quangreater",
            "greater", "quansmaller", "smaller", "quanexactly", "exactly", "quaninRange", "inRange");
    private static final int MAX_DEPTH = 64;

    /** True when the input has an IRI subject typed exactly {@code icm:Intent} (TurtleEvaluator, ReportBuilder). */
    public static boolean hasIriIntent(Model input) {
        return !intents(input).isEmpty();
    }

    private static List<Resource> intents(Model input) {
        return input.listSubjectsWithProperty(RDF.type, ICM_INTENT).toList().stream().filter(Resource::isURIResource)
                .sorted(Comparator.comparing(Resource::getURI)).toList();
    }

    /**
     * @param inferred the rule engine's prepared inferred model (stage chain output)
     * @param input    the evaluation input (expression, observations, resources, held facts), for structure only
     */
    public static EvaluationResult adapt(Model inferred, Model input) {
        Walk w = new Walk(inferred, input);
        List<Resource> roots = w.roots();
        List<Map<String, Object>> conditions;
        boolean passed;
        if (!roots.isEmpty()) {
            boolean all = true;
            for (Resource r : roots) {
                w.visit(r, 0);
                all &= Boolean.TRUE.equals(nodeValue(inferred, r));
            }
            conditions = w.out;
            passed = all;
        } else {
            conditions = w.flatScan();
            passed = conditions.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
            if (conditions.isEmpty()) {
                return EvaluationResult.degraded("No quantity conditions found in expression", List.of());
            }
        }
        List<Resource> intents = intents(input);
        if (!intents.isEmpty()) {
            passed = true;
            for (Resource intent : intents) {
                Boolean v = nodeValue(inferred, intent);
                if (v == null) {
                    return EvaluationResult.degraded("No truth value derived for intent " + intent.getURI(), conditions);
                }
                passed &= v;
            }
        }
        return passed ? EvaluationResult.fulfilled(conditions)
                : EvaluationResult.degraded(Reasons.conditionsNotMet(conditions), conditions);
    }

    /** A node's value: the conjunction of its contributions, else its own boolean rdf:value (input or default). */
    static Boolean nodeValue(Model m, Resource n) {
        if (m.contains(n, RESULT, F)) return false;
        if (m.contains(n, RESULT, T)) return true;
        if (m.contains(n, CONTRIBUTION)) return null;
        Boolean v = null;
        for (RDFNode o : m.listObjectsOfProperty(n, RDF.value).toList()) {
            if (!o.isLiteral() || !(o.asLiteral().getValue() instanceof Boolean b)) continue;
            if (!b) return false;
            v = true;
        }
        return v;
    }

    private static final class Walk {
        private final Model m;
        private final Model x;
        private final Set<Resource> groupsSelected = new HashSet<>();
        final List<Map<String, Object>> out = new ArrayList<>();

        Walk(Model inferred, Model input) {
            this.m = inferred;
            this.x = input;
        }

        /** As TurtleEvaluator.findEvaluationRoots, with the subjects of each property in IRI order. */
        List<Resource> roots() {
            Set<RDFNode> inner = new HashSet<>();
            for (Property op : OPERATORS) {
                for (Statement st : x.listStatements(null, op, (RDFNode) null).toList()) {
                    if (st.getObject().isResource()) inner.addAll(list(st.getObject().asResource()));
                }
            }
            Set<Resource> roots = new LinkedHashSet<>();
            List<Property> props = new ArrayList<>(OPERATORS);
            for (String name : MATCHES.keySet()) props.add(ResourceFactory.createProperty(TioNamespaces.LOG + name));
            for (Property p : props) {
                x.listSubjectsWithProperty(p).toList().stream().filter(s -> !inner.contains(s))
                        .sorted(Comparator.comparing(ResultAdapter::sortKey)).forEach(roots::add);
            }
            return new ArrayList<>(roots);
        }

        void visit(Resource node, int depth) {
            if (depth > MAX_DEPTH) return;                       // a cyclic expression: the Java engine never ends
            for (Property op : OPERATORS) {
                for (RDFNode l : x.listObjectsOfProperty(node, op).toList()) {
                    if (!l.isResource()) continue;
                    for (RDFNode item : list(l.asResource())) {
                        if (item.isResource()) visit(item.asResource(), depth + 1);
                    }
                }
            }
            out.addAll(entries(node));
        }

        /** TurtleEvaluator.flatScan: quantity subjects by function, then delivery and property expectations. */
        List<Map<String, Object>> flatScan() {
            Set<Resource> seen = new HashSet<>();
            for (String name : QUANTITY) {
                Property p = ResourceFactory.createProperty(TioNamespaces.QUAN + name);
                Resource t = ResourceFactory.createResource(TioNamespaces.QUAN + name);
                Set<Resource> nodes = new TreeSet<>(Comparator.comparing(ResultAdapter::sortKey));
                nodes.addAll(x.listSubjectsWithProperty(p).toList());
                nodes.addAll(x.listSubjectsWithProperty(RDF.type, t).toList());
                for (Resource n : nodes) if (seen.add(n)) out.addAll(entries(n));
            }
            for (Resource t : List.of(ICM_DELIVERY, ICM_PROPERTY)) {
                x.listSubjectsWithProperty(RDF.type, t).toList().stream()
                        .sorted(Comparator.comparing(ResultAdapter::sortKey))
                        .forEach(n -> { if (seen.add(n)) out.addAll(entries(n)); });
            }
            return out;
        }

        /** One entry per function application on {@code node} (its contributions), in the Java engine's order. */
        private List<Map<String, Object>> entries(Resource node) {
            SortedMap<String, Map<String, Object>> byKey = new TreeMap<>();
            for (RDFNode kn : m.listObjectsOfProperty(node, CONTRIBUTION).toList()) {
                Resource k = kn.asResource();
                Resource f = object(m, k, FUNCTION);
                if (f == null || f.equals(REPORTING)) continue;
                String ns = f.getNameSpace();
                String local = f.getLocalName();
                if (ns.equals(TioNamespaces.LOG) && OPERATORS.stream().anyMatch(o -> o.getLocalName().equals(local))) continue;
                Map<String, Object> e;
                String order;
                if (ns.equals(TioNamespaces.LOG) && MATCHES.containsKey(local)) {
                    e = entry(MATCHES.get(local), node, k);
                    order = "1" + new ArrayList<>(MATCHES.keySet()).indexOf(local);
                } else if (ns.equals(TioNamespaces.QUAN) && QUANTITY.contains(local)) {
                    String name = quantityName(node, k, local);
                    e = quantity(node, k, name);
                    order = "2" + String.format("%02d", QUANTITY.indexOf(name));
                } else if (f.equals(DELIVERY)) {
                    e = delivery(node, k);
                    order = "3" + sortKey(object(m, k, DELIVERY_TARGET)) + "\u0000" + sortKey(object(m, k, NEEDS_CLASS));
                } else {
                    e = entry(local, node, k);
                    order = "4" + local;
                }
                byKey.put(order + "\u0000" + sortKey(k), e);
            }
            List<Map<String, Object>> entries = new ArrayList<>(byKey.values());
            if (entries.isEmpty() && x.contains(node, RDF.type, ICM_PROPERTY)
                    && OPERATORS.stream().noneMatch(op -> x.contains(node, op))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("type", "PropertyExpectation");
                c.put("passed", Boolean.TRUE.equals(nodeValue(m, node)));
                entries.add(c);
            }
            return entries;
        }

        /** The predicate the input used for this application (a legacy alias keeps its name), else the type form's. */
        private String quantityName(Resource node, Resource k, String local) {
            Resource args = object(m, k, ARGUMENTS);
            for (String name : List.of("quan" + local, local)) {
                Property p = ResourceFactory.createProperty(TioNamespaces.QUAN + name);
                if (args != null && x.contains(node, p, args)) return name;
                if (node.equals(args) && x.contains(node, RDF.type, ResourceFactory.createResource(TioNamespaces.QUAN + name))) {
                    return name;
                }
            }
            return local;
        }

        /** Failures about the argument list itself, before any value is read (TurtleEvaluator.evalTwoArg). */
        private static final Set<String> STRUCTURAL = Set.of("missing operand nodes", "missing bound node");

        private Map<String, Object> quantity(Resource node, Resource k, String name) {
            boolean range = name.endsWith("inRange");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", range ? "quaninRange" : name);
            c.put("operator", range ? "<=<=" : TioNamespaces.operatorSymbol(TioNamespaces.QUAN + name));
            Resource a1 = object(m, k, ARG1);
            if (a1 != null && a1.isResource()) {
                Statement first = x.getProperty(a1, RDF.first);
                if (first != null && first.getObject().isURIResource()) c.put("metric", first.getObject().asResource().getURI());
            }
            Resource obs = object(m, k, OBSERVED_VIA);
            if (obs != null && obs.isURIResource()) c.put("observation", obs.getURI());
            if (node.isURIResource()) c.put("element", node.getURI());
            BigDecimal v1 = number(a1);
            if (range) {
                Resource a2 = object(m, k, ARG2), a3 = object(m, k, ARG3);
                BigDecimal lo = number(a2), hi = number(a3);
                if (v1 != null && lo != null && hi != null) {
                    c.put("observed", v1);
                    c.put("lower", lo);
                    c.put("upper", hi);
                    putUnit(c, "observedUnit", a1);
                    putUnit(c, "boundUnit", a2);
                }
            } else if (!STRUCTURAL.contains(String.valueOf(error(k)))) {
                // The Java engine reads no value when the operand cells themselves are missing (evalTwoArg).
                Resource b = object(m, k, ARG2);
                Resource pair = violationPair(k);
                if (pair != null) {
                    a1 = pair;
                    v1 = number(a1);
                    b = object(x, pair, RDF.rest);
                }
                if (v1 != null) {
                    c.put("observed", v1);
                    BigDecimal v2 = number(b);
                    if (v2 != null) {
                        c.put("bound", v2);
                        putUnit(c, "observedUnit", a1);
                        putUnit(c, "boundUnit", b);
                    }
                }
            }
            String error = error(k);
            if (error != null) c.put("error", error);
            c.put("passed", passed(k));
            return c;
        }

        /** For quan:exactly, the argument cell whose pair the reason text names. */
        private Resource violationPair(Resource k) {
            Statement t = m.getProperty(k, REASON_TEXT);
            if (t == null) return null;
            for (Resource cell : m.listSubjectsWithProperty(PAIR_VIOLATION, t.getObject()).toList()) {
                if (m.contains(cell, EXACTLY_CELL_OF, k)) return cell;
            }
            return null;
        }

        private Map<String, Object> delivery(Resource node, Resource k) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "DeliveryExpectation");
            if (node.isURIResource()) c.put("element", node.getURI());
            Resource cls = object(m, k, NEEDS_CLASS);
            if (cls != null && !cls.equals(ANY) && cls.isURIResource()) c.put("deliveryType", cls.getURI());
            boolean passed = passed(k);
            Resource target = object(m, k, DELIVERY_TARGET);
            Resource group = object(m, k, CHOICE_GROUP);
            if (passed && target != null && group != null && groupsSelected.add(group)) {
                m.listSubjectsWithProperty(ContractExtractor.CHOSEN_FOR, target).toList().stream()
                        .filter(Resource::isURIResource)
                        .filter(r -> ANY.equals(cls) || m.contains(r, INSTANCE_OF, cls))
                        .min(Comparator.comparing(Resource::getURI)).ifPresent(r -> {
                            c.put("selected", r.getURI());
                            if (m.contains(r, HELD, YES)) c.put("held", true);
                        });
            }
            String error = error(k);
            if (error != null) c.put("error", error);
            c.put("passed", passed);
            return c;
        }

        private Map<String, Object> entry(String type, Resource node, Resource k) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", type);
            if (node.isURIResource()) c.put("element", node.getURI());
            String error = error(k);
            if (error != null) c.put("error", error);
            c.put("passed", passed(k));
            return c;
        }

        private boolean passed(Resource k) {
            return m.contains(k, CLS, T) && !m.contains(k, CLS, F);
        }

        /** The application's failure text when it is false because of an error (never a plain comparison text). */
        private String error(Resource k) {
            if (passed(k)) return null;
            for (RDFNode t : m.listObjectsOfProperty(k, REASON_TEXT).toList()) {
                if (m.contains(k, FAILURE, t)) return t.asLiteral().getString();
            }
            return null;
        }

        private BigDecimal number(Resource cell) {
            if (cell == null) return null;
            for (RDFNode v : m.listObjectsOfProperty(cell, ARG_VALUE).toList()) {
                if (!v.isLiteral()) continue;
                try {
                    return new BigDecimal(v.asLiteral().getLexicalForm());
                } catch (NumberFormatException ignored) {
                    // not a number: no value, as in the Java engine
                }
            }
            return null;
        }

        private void putUnit(Map<String, Object> c, String key, Resource cell) {
            if (cell == null) return;
            for (RDFNode u : m.listObjectsOfProperty(cell, ARG_UNIT).toList()) {
                if (!u.isLiteral()) continue;
                String s = u.asLiteral().getLexicalForm().trim();
                if (!s.isEmpty()) {
                    c.put(key, s);
                    return;
                }
            }
        }

        private List<RDFNode> list(Resource head) {
            List<RDFNode> items = new ArrayList<>();
            Set<Resource> seen = new HashSet<>();
            Resource cur = head;
            while (cur != null && !cur.equals(RDF.nil) && seen.add(cur)) {
                Statement f = x.getProperty(cur, RDF.first);
                if (f != null) items.add(f.getObject());
                Statement r = x.getProperty(cur, RDF.rest);
                cur = r != null && r.getObject().isResource() ? r.getObject().asResource() : null;
            }
            return items;
        }
    }

    private static Resource object(Model m, Resource s, Property p) {
        if (s == null) return null;
        Statement st = m.getProperty(s, p);
        return st != null && st.getObject().isResource() ? st.getObject().asResource() : null;
    }

    private static String sortKey(Resource r) {
        if (r == null) return "";
        return r.isURIResource() ? r.getURI() : "~" + r;
    }
}
