package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.tmforum.intent.graph.TioNamespaces;
import org.tmforum.intent.model.EvaluationResult;

import java.math.BigDecimal;
import java.util.*;

/**
 * Builds the handler's {@link EvaluationResult} (state, reason, flat condition list) from a rule-engine result
 * graph (spec 5.3), so Flows 1-4, {@code HandlerStateWriter} and {@code BestEffortLimits} keep working. Truth values,
 * reasons, chosen members and observations are read from the result graph only; the expression is read for structure
 * (which elements exist, which functions they apply, in which order) and for the constants a condition compares
 * with (its bound), never to decide whether something is true.
 *
 * <ul>
 *   <li><b>State.</b> Every IRI-identified {@code icm:Intent} of the expression with value {@code true} gives
 *       {@code Fulfilled}; any intent with value {@code false} gives {@code Degraded}. An intent without a value
 *       (nothing derived it) is {@code Degraded} with a reason saying so, never {@code Fulfilled}. An expression
 *       without an intent is {@code Degraded} "No icm:Intent in expression" with no conditions (the rule engine
 *       evaluates nothing then; the Java engine's flat scan of such an expression is not part of the contract).</li>
 *   <li><b>Reason.</b> {@link Reasons#conditionsNotMet} over the condition list, the same code and format as the Java
 *       engine.</li>
 *   <li><b>Conditions</b>, in the Java engine's order (its {@code TurtleEvaluator} tree walk): from each evaluation root
 *       (a subject of {@code log:allOf/anyOf/noneOf/oneOf} that is no operator's member, or of a {@code log:match*}
 *       function), depth first through the operators' lists in list order, a node reached twice is listed twice. One
 *       entry per function application on an IRI element that has a value in the graph:
 *       <ul>
 *         <li>quantity functions: {@code type} (the predicate's local name, {@code quaninRange} for inRange, as the
 *             Java engine), {@code operator} ({@code >=}, {@code <=}, {@code >}, {@code <}, {@code ==},
 *             {@code <=<=}), {@code observed}/{@code observedUnit} (a metric argument: the {@code met:observed}
 *             observation of the graph for that metric; a constant: the expression), {@code bound}/{@code boundUnit}
 *             (or {@code lower}/{@code upper}) from the expression, only when there is an observed value, as in the
 *             Java engine; {@code metric}, {@code observation};</li>
 *         <li>delivery expectations: {@code type DeliveryExpectation}, {@code deliveryType}, {@code selected} (the
 *             lowest chosen member of the target in the graph that is of the delivery type, listed for the first
 *             expectation of that target and type only, as the Java engine lists the one that chose it) and
 *             {@code held} (the selected resource is held);</li>
 *         <li>{@code log:match*}: {@code logMatch}, {@code logMatchAll}, ...; other functions (set predicates,
 *             unimplemented functions): their local name; a property expectation without functions:
 *             {@code PropertyExpectation}.</li>
 *       </ul>
 *       {@code passed} is the element's value (TR292A §4: an element's value is the conjunction of its functions; an
 *       element with one function, the usual case, has exactly that function's result). {@code error} is a reason
 *       label of the element that is not the entry's own comparison text ({@link Reasons#of}), given to the failing
 *       entries in order.</li>
 * </ul>
 *
 * <p>Not listed (the contract has no values for them, TR290 §4.2): function applications on anonymous (blank-node)
 * conditions. Their reasons and observations are on the nearest named element in the graph.
 *
 * <p>{@code report} (the TR290 element tree) is null: the report is built from the graph (spec 5.3).
 */
public final class ResultAdapter {

    private ResultAdapter() {}

    static final Resource ICM_INTENT = ResourceFactory.createResource(TioNamespaces.ICM + "Intent");
    static final Resource ICM_DELIVERY = ResourceFactory.createResource(TioNamespaces.ICM + "DeliveryExpectation");
    static final Resource ICM_PROPERTY = ResourceFactory.createResource(TioNamespaces.ICM + "PropertyExpectation");
    static final Resource FUN_FUNCTION =
            ResourceFactory.createResource("http://tio.models.tmforum.org/tio/v3.6.0/FunctionOntology/Function");
    static final Property ICM_TARGET = ResourceFactory.createProperty(TioNamespaces.ICM + "target");
    static final Property ICM_DELIVERY_TYPE = ResourceFactory.createProperty(TioNamespaces.ICM + "deliveryType");
    static final Property MET_LAST_VALUE = ResourceFactory.createProperty(TioNamespaces.MET + "lastValue");

    static final List<Property> OPERATORS = List.of(TioNamespaces.LOG_allOf, TioNamespaces.LOG_anyOf,
            TioNamespaces.LOG_noneOf, TioNamespaces.LOG_oneOf);
    static final Map<Property, String> MATCHES = new LinkedHashMap<>();
    static {
        MATCHES.put(TioNamespaces.LOG_match, "logMatch");
        MATCHES.put(TioNamespaces.LOG_matchAll, "logMatchAll");
        MATCHES.put(TioNamespaces.LOG_matchAny, "logMatchAny");
        MATCHES.put(TioNamespaces.LOG_matchNone, "logMatchNone");
        MATCHES.put(TioNamespaces.LOG_matchOne, "logMatchOne");
        MATCHES.put(TioNamespaces.LOG_matchStatement, "logMatchStatement");
    }
    /** Quantity function local names (the specification's and the accepted legacy aliases). */
    static final List<String> QUANTITY = List.of("atLeast", "atMost", "greater", "smaller", "exactly", "inRange",
            "quanatLeast", "quanatMost", "quangreater", "quansmaller", "quanexactly", "quaninRange");
    /** Functions that produce a container, not a value (tio_a_core producesSet): never an entry. */
    static final Set<String> SET_CONSTRUCTORS = Set.of("resourcesOfType", "resourcesWithProperty",
            "resourcesWithPropertyObject", "valuesOfObjectProperty", "union", "intersection", "difference");
    /** Boolean set functions (TR292F §5), listed whether or not the ontology declares them. */
    static final Set<String> SET_PREDICATES = Set.of("isMember", "elementOf", "empty", "includedIn", "intersectsWith",
            "identical", "forAll");
    private static final int MAX_DEPTH = 64;

    /**
     * @param resultGraph the contract graph (truth values, reasons, chosen members and observations are read only
     *                    from here)
     * @param expression  the evaluation's input model: the expression, plus whatever else was evaluated with it
     *                    (observations, resources, held facts, ontology); read for structure, never for truth values
     */
    public static EvaluationResult adapt(Model resultGraph, Model expression) {
        Set<Resource> intents = ContractExtractor.elements(expression, List.of(ICM_INTENT));
        if (intents.isEmpty()) return EvaluationResult.degraded("No icm:Intent in expression", List.of());
        List<Map<String, Object>> conditions = new Walk(resultGraph, expression).run();

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

    /** The Java engine's tree walk over the expression, reading values from the graph. */
    private static final class Walk {
        private final Model g;
        private final Model x;
        private final Set<Resource> deliveryClasses;
        private final Set<String> selectedFor = new HashSet<>();
        private final List<Map<String, Object>> out = new ArrayList<>();

        Walk(Model resultGraph, Model expression) {
            this.g = resultGraph;
            this.x = expression;
            this.deliveryClasses = ContractExtractor.withSubclasses(expression, List.of(ICM_DELIVERY));
        }

        List<Map<String, Object>> run() {
            for (Resource root : roots()) visit(root, 0);
            return out;
        }

        /** As TurtleEvaluator.findEvaluationRoots, with the subjects of each property in IRI order. */
        private List<Resource> roots() {
            Set<RDFNode> inner = new HashSet<>();
            for (Property op : OPERATORS) {
                for (Statement st : x.listStatements(null, op, (RDFNode) null).toList()) {
                    if (st.getObject().isResource()) inner.addAll(list(st.getObject().asResource()));
                }
            }
            Set<Resource> roots = new LinkedHashSet<>();
            List<Property> props = new ArrayList<>(OPERATORS);
            props.addAll(MATCHES.keySet());
            for (Property p : props) {
                x.listSubjectsWithProperty(p).toList().stream().filter(s -> !inner.contains(s))
                        .sorted(Comparator.comparing(ResultAdapter::sortKey)).forEach(roots::add);
            }
            return new ArrayList<>(roots);
        }

        private void visit(Resource node, int depth) {
            if (depth > MAX_DEPTH) return;                       // a cyclic expression: the Java engine never ends
            for (Property op : OPERATORS) {
                for (RDFNode l : x.listObjectsOfProperty(node, op).toList()) {
                    if (!l.isResource()) continue;
                    for (RDFNode item : list(l.asResource())) {
                        if (item.isResource()) visit(item.asResource(), depth + 1);
                    }
                }
            }
            Boolean value = node.isURIResource() ? value(g, node) : null;
            if (value == null) return;                           // anonymous, or not an element: no entries
            List<Map<String, Object>> entries = new ArrayList<>();
            for (var m : MATCHES.entrySet()) {
                for (RDFNode ignored : x.listObjectsOfProperty(node, m.getKey()).toList()) {
                    entries.add(entry(m.getValue(), node, value));
                }
            }
            quantities(node, value, entries);
            others(node, value, entries);
            if (isDelivery(node)) entries.add(delivery(node, value));
            if (entries.isEmpty() && x.contains(node, RDF.type, ICM_PROPERTY) && !hasOperator(node)) {
                entries.add(entry("PropertyExpectation", node, value));
            }
            assignErrors(node, entries);
            out.addAll(entries);
        }

        private boolean hasOperator(Resource node) {
            return OPERATORS.stream().anyMatch(op -> x.contains(node, op));
        }

        private boolean isDelivery(Resource node) {
            return deliveryClasses.stream().anyMatch(c -> x.contains(node, RDF.type, c));
        }

        // ── quantity functions ──────────────────────────────────────────────────

        private void quantities(Resource node, boolean value, List<Map<String, Object>> entries) {
            for (String name : QUANTITY) {
                Property p = ResourceFactory.createProperty(TioNamespaces.QUAN + name);
                for (RDFNode l : x.listObjectsOfProperty(node, p).toList()) {
                    if (l.isResource()) entries.add(quantity(node, name, l.asResource(), value));
                }
                // legacy type form: the node is its own argument list (DEVIATIONS.md entry 5)
                if (x.contains(node, RDF.type, ResourceFactory.createResource(TioNamespaces.QUAN + name))
                        && x.contains(node, RDF.first)) {
                    entries.add(quantity(node, name, node, value));
                }
            }
        }

        private Map<String, Object> quantity(Resource node, String name, Resource args, boolean value) {
            List<RDFNode> a = list(args);
            boolean range = name.endsWith("inRange");
            Map<String, Object> c = entry(range ? "quaninRange" : name, node, value);
            c.put("operator", range ? "<=<=" : TioNamespaces.operatorSymbol(TioNamespaces.QUAN + name));
            if (a.isEmpty()) return c;
            Quantity observed = argument(node, a.get(0));
            if (observed.metric != null) c.put("metric", observed.metric);
            if (observed.observation != null) c.put("observation", observed.observation);
            if (range) {
                Quantity lo = a.size() > 1 ? argument(null, a.get(1)) : Quantity.NONE;
                Quantity hi = a.size() > 2 ? argument(null, a.get(2)) : Quantity.NONE;
                if (observed.value == null || lo.value == null || hi.value == null) return c;
                c.put("observed", observed.value);
                c.put("lower", lo.value);
                c.put("upper", hi.value);
                if (observed.unit != null) c.put("observedUnit", observed.unit);
                if (lo.unit != null) c.put("boundUnit", lo.unit);
                return c;
            }
            if (observed.value == null) return c;
            c.put("observed", observed.value);
            Quantity bound = a.size() > 1 ? argument(null, a.get(1)) : Quantity.NONE;
            if (bound.value == null) return c;
            c.put("bound", bound.value);
            if (observed.unit != null) c.put("observedUnit", observed.unit);
            if (bound.unit != null) c.put("boundUnit", bound.unit);
            return c;
        }

        /**
         * An argument's value and unit. A constant (a literal, or a node with its own {@code rdf:value}, nested
         * quantity included, its inner unit first as the rules read it) from the expression; a metric or
         * {@code [ met:lastValue ( m ) ]} from the observation of that metric the graph links to {@code node} with
         * {@code met:observed} (none for a bound: the contract links the first argument's observation only).
         */
        private Quantity argument(Resource node, RDFNode arg) {
            if (arg.isLiteral()) return new Quantity(decimal(arg), null, null, null);
            Resource r = arg.asResource();
            Statement v = x.getProperty(r, RDF.value);
            if (v != null) {
                if (v.getObject().isLiteral()) return new Quantity(decimal(v.getObject()), unit(x, r), null, null);
                Resource q = v.getObject().asResource();
                Statement inner = x.getProperty(q, RDF.value);
                if (inner == null || !inner.getObject().isLiteral()) return Quantity.NONE;
                String u = unit(x, q);
                return new Quantity(decimal(inner.getObject()), u != null ? u : unit(x, r), null, null);
            }
            Resource metric = r;
            Statement lv = x.getProperty(r, MET_LAST_VALUE);
            if (lv != null && lv.getObject().isResource()) {
                List<RDFNode> ms = list(lv.getObject().asResource());
                RDFNode m = ms.isEmpty() ? lv.getObject() : ms.get(0);
                if (!m.isResource()) return Quantity.NONE;
                metric = m.asResource();
            }
            String metricUri = r.isURIResource() ? r.getURI() : null;
            if (node == null) return new Quantity(null, null, metricUri, null);
            for (RDFNode o : g.listObjectsOfProperty(g.createResource(node.getURI()), ResultContract.MET_OBSERVED).toList()) {
                if (!o.isResource() || !g.contains(o.asResource(), ResultContract.MET_OBSERVED_METRIC, metric)) continue;
                Statement ov = g.getProperty(o.asResource(), RDF.value);
                if (ov == null) continue;
                return new Quantity(decimal(ov.getObject()), unit(g, o.asResource()), metricUri,
                        o.isURIResource() ? o.asResource().getURI() : null);
            }
            return new Quantity(null, null, metricUri, null);
        }

        // ── delivery expectations ───────────────────────────────────────────────

        private Map<String, Object> delivery(Resource node, boolean value) {
            Map<String, Object> c = entry("DeliveryExpectation", node, value);
            Resource type = first(x, node, ICM_DELIVERY_TYPE);
            if (type != null) c.put("deliveryType", type.getURI());
            Resource target = first(x, node, ICM_TARGET);
            if (target == null || !value) return c;
            String key = target + "|" + type;
            if (!selectedFor.add(key)) return c;                 // the first expectation of the target chose it
            Set<Resource> types = type == null ? null : ContractExtractor.withSubclasses(x, List.of(type));
            Optional<Resource> chosen = g.listObjectsOfProperty(g.createResource(target.getURI()), RDFS.member).toList()
                    .stream().filter(RDFNode::isURIResource).map(RDFNode::asResource)
                    .filter(m -> types == null || types.stream().anyMatch(t -> x.contains(m, RDF.type, t)))
                    .min(Comparator.comparing(Resource::getURI));
            chosen.ifPresent(m -> {
                c.put("selected", m.getURI());
                if (x.contains(EvaluationInputs.EVAL_CONTEXT, EvaluationInputs.EVAL_HOLDS, m)) c.put("held", true);
            });
            return c;
        }

        // ── other functions ─────────────────────────────────────────────────────

        /** Set predicates and any other declared function (unimplemented ones are false with a reason). */
        private void others(Resource node, boolean value, List<Map<String, Object>> entries) {
            SortedSet<String> names = new TreeSet<>();
            for (Statement st : x.listStatements(node, null, (RDFNode) null).toList()) {
                Property p = st.getPredicate();
                String ns = p.getNameSpace();
                String local = p.getLocalName();
                if (ns.equals(TioNamespaces.LOG) || ns.equals(TioNamespaces.QUAN) && QUANTITY.contains(local)) continue;
                if (SET_CONSTRUCTORS.contains(local) && ns.equals(TioNamespaces.SET)) continue;
                boolean setPredicate = ns.equals(TioNamespaces.SET) && SET_PREDICATES.contains(local);
                boolean declared = x.contains(p, RDF.type, FUN_FUNCTION) && !SET_CONSTRUCTORS.contains(local);
                if (setPredicate || declared) names.add(local + "\u0000" + p.getURI());
            }
            for (String n : names) entries.add(entry(n.substring(0, n.indexOf('\u0000')), node, value));
        }

        // ── errors ──────────────────────────────────────────────────────────────

        /** Gives the element's reason labels that are not an entry's own comparison text to its failing entries. */
        private void assignErrors(Resource node, List<Map<String, Object>> entries) {
            if (entries.isEmpty()) return;
            SortedSet<String> labels = new TreeSet<>();
            for (RDFNode r : g.listObjectsOfProperty(g.createResource(node.getURI()), ResultContract.ICM_REASON).toList()) {
                if (!r.isResource()) continue;
                g.listObjectsOfProperty(r.asResource(), RDFS.label).forEachRemaining(l -> {
                    if (l.isLiteral()) labels.add(l.asLiteral().getString());
                });
            }
            for (Map<String, Object> c : entries) {
                String own = Reasons.of(c);
                if (own != null) labels.remove(own);
            }
            Iterator<String> it = labels.iterator();
            for (Map<String, Object> c : entries) {
                if (!it.hasNext()) break;
                if (!Boolean.TRUE.equals(c.get("passed"))) c.put("error", it.next());
            }
            // A comparison without values and without an error (nothing in the graph explains it) is labelled by its
            // type, never "null >= null" (Reasons.conditionsNotMet reads the operator as "values present").
            for (Map<String, Object> c : entries) {
                if (c.get("observed") == null && !c.containsKey("error")) c.remove("operator");
            }
        }

        private Map<String, Object> entry(String type, Resource node, boolean value) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", type);
            c.put("element", node.getURI());
            c.put("passed", value);
            return c;
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

    private record Quantity(BigDecimal value, String unit, String metric, String observation) {
        static final Quantity NONE = new Quantity(null, null, null, null);
    }

    private static String sortKey(Resource r) {
        return r.isURIResource() ? r.getURI() : "~" + r;
    }

    private static Resource first(Model m, Resource s, Property p) {
        return m.listObjectsOfProperty(s, p).toList().stream().filter(RDFNode::isURIResource).map(RDFNode::asResource)
                .min(Comparator.comparing(Resource::getURI)).orElse(null);
    }

    private static BigDecimal decimal(RDFNode n) {
        if (n == null || !n.isLiteral()) return null;
        try {
            return new BigDecimal(n.asLiteral().getLexicalForm());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A node's quan:unit, trimmed; null when it has none or it is blank (the Java engine's reading). */
    private static String unit(Model m, Resource r) {
        Statement u = m.getProperty(r, ResultContract.QUAN_UNIT);
        if (u == null || !u.getObject().isLiteral()) return null;
        String s = u.getObject().asLiteral().getLexicalForm().trim();
        return s.isEmpty() ? null : s;
    }

    private static Boolean value(Model g, Resource e) {
        Statement st = g.getProperty(g.createResource(e.getURI()), RDF.value);
        if (st == null || !st.getObject().isLiteral()) return null;
        Object v = st.getLiteral().getValue();
        return v instanceof Boolean b ? b : null;
    }
}
