package org.tmforum.intent.handler;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.stereotype.Service;
import org.tmforum.intent.graph.TioNamespaces;
import org.tmforum.intent.model.ElementResult;
import org.tmforum.intent.model.EvaluationResult;
import org.tmforum.intent.model.ReportTree;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

import static org.tmforum.intent.graph.TioNamespaces.*;

/**
 * Pure-Java TIO expression evaluator.
 *
 * Mirrors the Python evaluate_turtle_conditions() pipeline:
 *   1. Parse Turtle (expression + observations + resources)
 *   2. Pre-processing passes (normalize predicate-forms, inject metric values, etc.)
 *   3. Recursive tree evaluation (_eval_node equivalent)
 *   4. Build EvaluationResult
 */
@Service
public class TurtleEvaluator {

    private static final int MAX_TURTLE_BYTES = 512 * 1024;
    private static final Resource ICM_TARGET_CLASS = ResourceFactory.createResource(ICM + "Target");
    private static final Property QUAN_unit = ResourceFactory.createProperty(QUAN + "unit");
    private static final Property SET_P_intersection = ResourceFactory.createProperty(SET + "intersection");
    private static final Property SET_P_union = ResourceFactory.createProperty(SET + "union");
    private static final Property SET_P_difference = ResourceFactory.createProperty(SET + "difference");

    /** An observed value together with its unit (null = dimensionless). */
    private record Observed(Literal value, String unit, Resource source) {}

    // ── Combinator descriptors ───────────────────────────────────────────────
    private enum Combinator { ALL, ANY, NONE, ONE }
    private record CombSpec(Property prop, Combinator comb) {}
    private static final List<CombSpec> LOG_COMBINATORS = List.of(
            new CombSpec(LOG_allOf,  Combinator.ALL),
            new CombSpec(LOG_anyOf,  Combinator.ANY),
            new CombSpec(LOG_noneOf, Combinator.NONE),
            new CombSpec(LOG_oneOf,  Combinator.ONE)
    );

    // ── Internal evaluation result ─────────────────────────────────────────
    private record EvalNode(boolean passed, List<Map<String, Object>> conditions, List<ElementResult> elements) {
        EvalNode(boolean passed, List<Map<String, Object>> conditions) { this(passed, conditions, List.of()); }
        static EvalNode ok()                  { return new EvalNode(true,  List.of()); }
        static EvalNode fail(Map<String,Object> c) { return new EvalNode(false, List.of(c)); }
        static EvalNode of(boolean p, Map<String,Object> c) { return new EvalNode(p, List.of(c)); }
    }

    // ── Public entry point ──────────────────────────────────────────────────

    /**
     * Evaluate a TIO expression against current observations and optional resource inventory.
     *
     * @param expressionTurtle  The intent's expressionValue (Turtle string).
     * @param observationsTurtle Turtle from the observations named graph (may be null/empty).
     * @param resourcesTurtle    Turtle from the resources named graph (may be null/empty).
     */
    public EvaluationResult evaluate(String expressionTurtle,
                                      String observationsTurtle,
                                      String resourcesTurtle) {
        return evaluate(expressionTurtle, observationsTurtle, resourcesTurtle, List.of());
    }

    /**
     * @param heldResources URIs of resources already allocated to this intent. A delivery expectation
     *        whose deliveryType matches one of them keeps it as its choice instead of choosing again from
     *        the free pool (which no longer contains it, being in use).
     */
    public EvaluationResult evaluate(String expressionTurtle,
                                      String observationsTurtle,
                                      String resourcesTurtle,
                                      Collection<String> heldResources) {
        return doEvaluate(expressionTurtle, observationsTurtle, resourcesTurtle,
                heldResources == null ? List.of() : heldResources);
    }

    /** Evaluation context carried in the model: the resources the intent being evaluated already holds. */
    private static final Resource EVAL_CONTEXT = ResourceFactory.createResource("urn:tmf921:evaluation");
    /** Helper triple: the observation individual that supplied a metric node's value (reported as met:observed). */
    private static final Property OBSERVATION_USED = ResourceFactory.createProperty("urn:tmf921:evaluation#observation");
    private static final Property EVAL_HOLDS = ResourceFactory.createProperty("urn:tmf921:evaluation#holds");

    private EvaluationResult doEvaluate(String expressionTurtle,
                                         String observationsTurtle,
                                         String resourcesTurtle,
                                         Collection<String> heldResources) {
        if (expressionTurtle == null || expressionTurtle.isBlank()) {
            return EvaluationResult.degraded("Empty expression", List.of());
        }
        String combined = joinTurtle(expressionTurtle, observationsTurtle, resourcesTurtle);
        if (combined.getBytes().length > MAX_TURTLE_BYTES) {
            return EvaluationResult.degraded(
                    "Expression exceeds size limit (" + MAX_TURTLE_BYTES + " bytes)", List.of());
        }

        Model g = ModelFactory.createDefaultModel();
        try {
            g.read(new StringReader(combined), "http://tmforum.org/", "TURTLE");
        } catch (Exception e) {
            return EvaluationResult.degraded("Turtle parse error: " + e.getMessage(), List.of());
        }

        for (String uri : heldResources) g.add(EVAL_CONTEXT, EVAL_HOLDS, g.createResource(uri));

        // Pre-processing pipeline
        normalizeQuantityPredicates(g);
        normalizeSetPredicates(g);
        resolveMetricRefs(g);
        computeMathFunctions(g);
        computeSetConstructors(g);
        resolveValidityChains(g);
        deriveGuaranteeStates(g);
        deriveExtTypes(g);

        // Evaluation
        List<Resource> roots = findEvaluationRoots(g);
        List<Map<String, Object>> allConditions = new ArrayList<>();
        List<ElementResult> elements = new ArrayList<>();
        boolean overallPassed;

        if (!roots.isEmpty()) {
            boolean allRootsPassed = true;
            for (Resource root : roots) {
                EvalNode r = evalNode(g, root);
                allRootsPassed = allRootsPassed && r.passed();
                allConditions.addAll(r.conditions());
                elements.addAll(r.elements());
            }
            overallPassed = allRootsPassed;
        } else {
            allConditions = flatScan(g);
            if (allConditions.isEmpty()) {
                return EvaluationResult.degraded(
                        "No quantity conditions found in expression", List.of());
            }
            overallPassed = allConditions.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
        }

        ReportTree report = reportTree(g, elements, overallPassed);
        if (!overallPassed) {
            String reason = buildFailReason(allConditions);
            return EvaluationResult.degraded(reason, allConditions).withReport(report);
        }
        return EvaluationResult.fulfilled(allConditions).withReport(report);
    }

    // ── Pre-processing: normalizeQuantityPredicates ─────────────────────────

    /**
     * Converts predicate-form quantity conditions to type-form.
     * e.g. ?cond quan:atLeast (?metric ?bound) → ?cond a quan:atLeast ; rdf:first ?metric ; rdf:rest ?r
     */
    private void normalizeQuantityPredicates(Model g) {
        for (Property pred : ALL_QUAN_PREDICATES) {
            Resource typeRes = ResourceFactory.createResource(pred.getURI());
            for (StmtIterator it = g.listStatements(null, pred, (RDFNode) null); it.hasNext(); ) {
                Statement stmt = it.next();
                Resource cond = stmt.getSubject();
                RDFNode listNode = stmt.getObject();
                if (!listNode.isResource()) continue;
                if (g.contains(cond, RDF.first)) continue; // already type-form
                Resource listRes = listNode.asResource();
                RDFNode arg1 = g.getProperty(listRes, RDF.first) != null
                        ? g.getProperty(listRes, RDF.first).getObject() : null;
                if (arg1 == null) continue;
                g.add(cond, RDF.type, typeRes);
                g.add(cond, RDF.first, arg1);
                Statement restStmt = g.getProperty(listRes, RDF.rest);
                if (restStmt != null) g.add(cond, RDF.rest, restStmt.getObject());
            }
        }
    }

    // ── Pre-processing: normalizeSetPredicates ─────────────────────────────

    /**
     * Materialises rdfs:member directly for set:resourcesOfType and
     * set:resourcesWithPropertyObject predicates.
     *
     * An object argument of set:resourcesWithPropertyObject that is itself a set node
     * (e.g. {@code ( pon:fromSVLAN bbf:HSISVLANPool )}) stands for all members of that set,
     * so one pool can be filtered by properties of another.
     */
    private void normalizeSetPredicates(Model g) {
        Set<Resource> setNodes = setNodes(g);
        Set<Resource> done = new HashSet<>();
        for (Resource target : setNodes) {
            materialiseSet(g, target, setNodes, done);
        }
    }

    /** A set node, or a plain icm:Target (e.g. a delivery target), stands for its members rather than itself. */
    private boolean isContainerArg(Model g, Resource arg, Set<Resource> setNodes) {
        return setNodes.contains(arg) || g.contains(arg, RDF.type, ICM_TARGET_CLASS);
    }

    private Set<Resource> setNodes(Model g) {
        Set<Resource> setNodes = new HashSet<>();
        g.listSubjectsWithProperty(SET_P_resourcesOfType).forEachRemaining(setNodes::add);
        g.listSubjectsWithProperty(SET_P_resourcesWithPropertyObject).forEachRemaining(setNodes::add);
        for (Property comb : List.of(SET_P_intersection, SET_P_union, SET_P_difference)) {
            g.listSubjectsWithProperty(comb).forEachRemaining(setNodes::add);
        }
        return setNodes;
    }

    /**
     * Pools are normally materialised once, up front. A pool filtered by a plain icm:Target (such as the
     * target another delivery expectation fills with its choice) can only be computed once that choice
     * exists, so it is recomputed when the dependent expectation is evaluated. This means the expectation
     * providing the choice must come first in the enclosing list.
     */
    private void refreshPoolIfDependsOnChoice(Model g, Resource pool) {
        Set<Resource> setNodes = setNodes(g);
        Set<Resource> derived = new LinkedHashSet<>();
        boolean dependsOnChoice = collectDerived(g, pool, setNodes, derived);
        if (!dependsOnChoice) return;
        for (Resource d : derived) g.removeAll(d, RDFS.member, null);
        Set<Resource> done = new HashSet<>();
        materialiseSet(g, pool, setNodes, done);
    }

    private boolean collectDerived(Model g, Resource node, Set<Resource> setNodes, Set<Resource> derived) {
        if (!derived.add(node)) return false;
        boolean dependsOnChoice = false;
        for (Property comb : List.of(SET_P_intersection, SET_P_union, SET_P_difference)) {
            for (RDFNode listNode : g.listObjectsOfProperty(node, comb).toList()) {
                if (!listNode.isResource()) continue;
                for (RDFNode item : iterRdfList(g, listNode.asResource())) {
                    if (!item.isResource()) continue;
                    Resource r = item.asResource();
                    if (setNodes.contains(r)) dependsOnChoice |= collectDerived(g, r, setNodes, derived);
                    else if (g.contains(r, RDF.type, ICM_TARGET_CLASS)) dependsOnChoice = true;
                }
            }
        }
        for (StmtIterator it = g.listStatements(node, SET_P_resourcesWithPropertyObject, (RDFNode) null);
             it.hasNext(); ) {
            RDFNode listNode = it.next().getObject();
            if (!listNode.isResource()) continue;
            List<RDFNode> args = iterRdfList(g, listNode.asResource());
            for (int i = 1; i < args.size(); i++) {
                RDFNode arg = args.get(i);
                if (!arg.isResource()) continue;
                Resource r = arg.asResource();
                if (setNodes.contains(r)) {
                    dependsOnChoice |= collectDerived(g, r, setNodes, derived);
                } else if (g.contains(r, RDF.type, ICM_TARGET_CLASS)) {
                    dependsOnChoice = true;
                }
            }
        }
        return dependsOnChoice;
    }

    private void materialiseSet(Model g, Resource target, Set<Resource> setNodes, Set<Resource> done) {
        if (!done.add(target)) return;                          // already handled (or cyclic reference)
        if (g.contains(target, RDFS.member)) return;            // already materialised

        // Collect type-class members
        // set:resourcesOfType ( <class> ... ) takes a list (TR292F); a single class is also accepted
        List<RDFNode> typeClasses = functionArguments(g, target, SET_P_resourcesOfType);
        Set<Resource> members = null;
        if (!typeClasses.isEmpty()) {
            members = new HashSet<>();
            for (RDFNode cls : typeClasses) {
                if (!cls.isResource()) continue;
                // instances of the class or of any of its subclasses
                for (Resource c : withSubclasses(g, cls.asResource())) {
                    g.listSubjectsWithProperty(RDF.type, c).forEachRemaining(members::add);
                }
            }
        }

        // Intersect with property-object filters
        for (StmtIterator it = g.listStatements(target, SET_P_resourcesWithPropertyObject,
                (RDFNode) null); it.hasNext(); ) {
            RDFNode listNode = it.next().getObject();
            if (!listNode.isResource()) continue;
            List<RDFNode> args = iterRdfList(g, listNode.asResource());
            if (args.size() < 2) continue;
            Property prop = ResourceFactory.createProperty(args.get(0).asResource().getURI());
            Set<Resource> filterSet = new HashSet<>();
            for (int i = 1; i < args.size(); i++) {
                RDFNode arg = args.get(i);
                if (arg.isResource() && isContainerArg(g, arg.asResource(), setNodes)) {
                    materialiseSet(g, arg.asResource(), setNodes, done);
                    g.listObjectsOfProperty(arg.asResource(), RDFS.member).forEachRemaining(o ->
                            g.listSubjectsWithProperty(prop, o).forEachRemaining(filterSet::add));
                } else {
                    g.listSubjectsWithProperty(prop, arg).forEachRemaining(filterSet::add);
                }
            }
            members = (members == null) ? filterSet : intersect(members, filterSet);
        }

        // set:intersection / set:union / set:difference ( <container> ... ): each item is a container, usually an
        // anonymous node defined by another set function (TR292F)
        for (Property comb : List.of(SET_P_intersection, SET_P_union, SET_P_difference)) {
            for (RDFNode listNode : g.listObjectsOfProperty(target, comb).toList()) {
                if (!listNode.isResource()) continue;
                Set<Resource> combined = null;
                for (RDFNode item : iterRdfList(g, listNode.asResource())) {
                    if (!item.isResource()) continue;
                    if (setNodes.contains(item.asResource())) materialiseSet(g, item.asResource(), setNodes, done);
                    Set<Resource> itemMembers = new HashSet<>();
                    g.listObjectsOfProperty(item.asResource(), RDFS.member)
                            .forEachRemaining(m -> { if (m.isResource()) itemMembers.add(m.asResource()); });
                    if (combined == null) combined = itemMembers;
                    else if (comb.equals(SET_P_intersection)) combined = intersect(combined, itemMembers);
                    else if (comb.equals(SET_P_union)) combined.addAll(itemMembers);
                    else combined.removeAll(itemMembers);
                }
                if (combined != null) members = (members == null) ? combined : intersect(members, combined);
            }
        }

        if (members != null) {
            for (Resource m : members) g.add(target, RDFS.member, m);
        }
    }

    /**
     * The arguments of a function applied as a property: the items of the list that is its object, or the
     * object itself if it is not a list (the older single-argument form).
     */
    private List<RDFNode> functionArguments(Model g, Resource subject, Property function) {
        List<RDFNode> args = new ArrayList<>();
        for (RDFNode o : g.listObjectsOfProperty(subject, function).toList()) {
            if (o.isResource() && (o.asResource().equals(RDF.nil) || g.contains(o.asResource(), RDF.first))) {
                args.addAll(iterRdfList(g, o.asResource()));
            } else {
                args.add(o);
            }
        }
        return args;
    }

    /** The class and every class below it (rdfs:subClassOf), when the model holds the subclass statements. */
    private Set<Resource> withSubclasses(Model g, Resource cls) {
        Set<Resource> all = new LinkedHashSet<>();
        Deque<Resource> todo = new ArrayDeque<>(List.of(cls));
        while (!todo.isEmpty()) {
            Resource c = todo.pop();
            if (!all.add(c)) continue;
            g.listSubjectsWithProperty(RDFS.subClassOf, c).forEachRemaining(todo::push);
        }
        return all;
    }

    // ── Pre-processing: resolveMetricRefs ──────────────────────────────────

    /**
     * Injects rdf:value onto metric/function nodes from met:Observation triples.
     */
    private void resolveMetricRefs(Model g) {
        Map<Resource, Observed> obsIndex = buildObsIndex(g);

        // Pattern B: _:fn a met:metlastValue ; rdfs:member <metric>
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, MET_metlastValue);
             it.hasNext(); ) {
            Resource fn = it.next();
            if (g.contains(fn, RDF.value)) continue;
            Resource metric = objectResource(g, fn, RDFS.member);
            if (metric == null) continue;
            Observed val = obsIndex.get(metric);
            if (val != null) assignObserved(g, fn, val);
        }

        // Pattern C: _:fn a met:metobservedValue ; rdf:first <obs>
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, MET_metobservedValue);
             it.hasNext(); ) {
            Resource fn = it.next();
            if (g.contains(fn, RDF.value)) continue;
            Resource obs = objectResource(g, fn, RDF.first);
            if (obs == null) continue;
            Statement vs = g.getProperty(obs, RDF.value);
            if (vs != null) g.add(fn, RDF.value, vs.getObject());
            Statement us = g.getProperty(obs, QUAN_unit);
            if (us != null) g.add(fn, QUAN_unit, us.getObject());
            if (obs.isURIResource()) g.add(fn, OBSERVATION_USED, obs);
        }

        // Pattern A: direct metric URI as rdf:first of a quantity condition
        Set<Resource> seen = new HashSet<>();
        for (Resource typeRes : ALL_QUAN_TYPES) {
            for (ResIterator it = g.listSubjectsWithProperty(RDF.type, typeRes); it.hasNext(); ) {
                Resource cond = it.next();
                Statement firstStmt = g.getProperty(cond, RDF.first);
                if (firstStmt == null) continue;
                RDFNode valNode = firstStmt.getObject();
                if (!valNode.isResource()) continue;
                Resource valRes = valNode.asResource();
                if (seen.contains(valRes)) continue;
                seen.add(valRes);
                if (g.contains(valRes, RDF.value)) continue;
                Observed val = obsIndex.get(valRes);
                if (val != null) assignObserved(g, valRes, val);
            }
        }
    }

    /** Makes the metric (or function result) node carry the observed value and unit as a quantity. */
    private void assignObserved(Model g, Resource node, Observed o) {
        g.addLiteral(node, RDF.value, o.value());
        if (o.source() != null && o.source().isURIResource()) g.add(node, OBSERVATION_USED, o.source());
        if (o.unit() != null) g.add(node, QUAN_unit, g.createLiteral(o.unit()));
    }

    private Map<Resource, Observed> buildObsIndex(Model g) {
        Map<Resource, Pair<Instant, Observed>> best = new HashMap<>();
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, MET_Observation); it.hasNext(); ) {
            Resource obs = it.next();
            Resource metric = objectResource(g, obs, MET_observedMetric);
            if (metric == null) continue;
            Observed observed = observedOf(g, obs);
            if (observed == null) continue;
            Statement ts = g.getProperty(obs, MET_obtainedAt);
            Instant dt = ts != null ? parseInstant(ts.getString()) : Instant.MIN;
            best.merge(metric, new Pair<>(dt, observed),
                    (existing, candidate) -> candidate.first.isAfter(existing.first) ? candidate : existing);
        }
        Map<Resource, Observed> index = new HashMap<>();
        best.forEach((m, p) -> index.put(m, p.second));
        return index;
    }

    /**
     * The value and unit of an observation. The value may be a plain literal, with quan:unit on the
     * observation, or a nested quantity ({@code rdf:value [ rdf:value 10 ; quan:unit "ms" ]}); TR292C §4
     * treats the two as equivalent.
     */
    private Observed observedOf(Model g, Resource obs) {
        Statement vs = g.getProperty(obs, RDF.value);
        if (vs == null) return null;
        if (vs.getObject().isLiteral()) {
            return new Observed(vs.getObject().asLiteral(), unitOf(g, obs), obs);
        }
        if (vs.getObject().isResource()) {
            Resource q = vs.getObject().asResource();
            Statement inner = g.getProperty(q, RDF.value);
            if (inner != null && inner.getObject().isLiteral()) {
                String unit = unitOf(g, q);
                return new Observed(inner.getObject().asLiteral(), unit != null ? unit : unitOf(g, obs), obs);
            }
        }
        return null;
    }

    /** The quan:unit of a quantity node (null if it has none, or if the node is a plain literal). */
    private String unitOf(Model g, RDFNode node) {
        if (node == null || !node.isResource()) return null;
        Resource r = node.asResource();
        Statement us = g.getProperty(r, QUAN_unit);
        if (us != null && us.getObject().isLiteral()) {
            String u = us.getObject().asLiteral().getLexicalForm().trim();
            return u.isEmpty() ? null : u;
        }
        Statement vs = g.getProperty(r, RDF.value);          // nested quantity
        if (vs != null && vs.getObject().isResource() && !vs.getObject().asResource().equals(r)) {
            return unitOf(g, vs.getObject());
        }
        return null;
    }

    // ── Pre-processing: computeMathFunctions ─────────────────────────────────

    private void computeMathFunctions(Model g) {
        // mf:mflogistic — L / (1 + exp(-k*(x-x0))) + c
        // Args: L, k, x0, c, x (rdf:first … rdf:rest chain)
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, MF_logistic); it.hasNext(); ) {
            Resource fn = it.next();
            if (g.contains(fn, RDF.value)) continue;
            List<RDFNode> args = iterRdfList(g, fn);
            if (args.size() < 5) continue;
            BigDecimal l = decimalValue(g, args.get(0));
            BigDecimal k = decimalValue(g, args.get(1));
            BigDecimal x0 = decimalValue(g, args.get(2));
            BigDecimal c = decimalValue(g, args.get(3));
            BigDecimal x = decimalValue(g, args.get(4));
            if (l == null || k == null || x0 == null || c == null || x == null) continue;
            double result = l.doubleValue() /
                    (1 + Math.exp(-k.doubleValue() * (x.doubleValue() - x0.doubleValue())))
                    + c.doubleValue();
            g.addLiteral(fn, RDF.value,
                    ResourceFactory.createTypedLiteral(String.valueOf(result),
                            org.apache.jena.datatypes.xsd.XSDDatatype.XSDdecimal));
        }

        // mf:mfpoly — polynomial: sum(coeff_i * x^i) for args (x, c0, c1, c2, ...)
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, MF_poly); it.hasNext(); ) {
            Resource fn = it.next();
            if (g.contains(fn, RDF.value)) continue;
            List<RDFNode> args = iterRdfList(g, fn);
            if (args.size() < 2) continue;
            BigDecimal x = decimalValue(g, args.get(0));
            if (x == null) continue;
            double result = 0;
            for (int i = 1; i < args.size(); i++) {
                BigDecimal coeff = decimalValue(g, args.get(i));
                if (coeff == null) continue;
                result += coeff.doubleValue() * Math.pow(x.doubleValue(), i - 1);
            }
            g.addLiteral(fn, RDF.value,
                    ResourceFactory.createTypedLiteral(String.valueOf(result),
                            org.apache.jena.datatypes.xsd.XSDDatatype.XSDdecimal));
        }

        // Arithmetic: quan:sum, quan:difference, quan:multiplication, quan:division
        computeArithmetic(g, QUAN_sum, 0, (a, b) -> a + b);
        computeArithmetic(g, QUAN_difference, 0, (a, b) -> a - b);
        computeArithmetic(g, QUAN_multiplication, 1, (a, b) -> a * b);
        computeArithmetic(g, QUAN_division, Double.NaN, (a, b) -> b == 0 ? Double.NaN : a / b);
    }

    @FunctionalInterface private interface DoubleBinaryOp { double apply(double a, double b); }

    private void computeArithmetic(Model g, Resource type, double identity, DoubleBinaryOp op) {
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, type); it.hasNext(); ) {
            Resource fn = it.next();
            if (g.contains(fn, RDF.value)) continue;
            List<RDFNode> args = iterRdfList(g, fn);
            if (args.isEmpty()) continue;
            BigDecimal first = decimalValue(g, args.get(0));
            if (first == null) continue;
            double result = first.doubleValue();
            for (int i = 1; i < args.size(); i++) {
                BigDecimal v = decimalValue(g, args.get(i));
                if (v == null) continue;
                result = op.apply(result, v.doubleValue());
            }
            g.addLiteral(fn, RDF.value,
                    ResourceFactory.createTypedLiteral(String.valueOf(result),
                            org.apache.jena.datatypes.xsd.XSDDatatype.XSDdecimal));
        }
    }

    // ── Pre-processing: computeSetConstructors ────────────────────────────────

    private void computeSetConstructors(Model g) {
        // union
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, g.createResource(SET + "union"));
             it.hasNext(); ) {
            Resource fn = it.next();
            if (g.contains(fn, RDFS.member)) continue;
            List<RDFNode> items = iterRdfList(g, fn);
            Set<Resource> members = new HashSet<>();
            for (RDFNode c : items) {
                if (!c.isResource()) continue;
                g.listObjectsOfProperty(c.asResource(), RDFS.member)
                        .forEachRemaining(m -> { if (m.isResource()) members.add(m.asResource()); });
            }
            members.forEach(m -> g.add(fn, RDFS.member, m));
        }

        // intersection
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type,
                g.createResource(SET + "intersection")); it.hasNext(); ) {
            Resource fn = it.next();
            if (g.contains(fn, RDFS.member)) continue;
            List<RDFNode> items = iterRdfList(g, fn);
            if (items.isEmpty()) continue;
            Set<Resource> members = null;
            for (RDFNode c : items) {
                if (!c.isResource()) continue;
                Set<Resource> s = new HashSet<>();
                g.listObjectsOfProperty(c.asResource(), RDFS.member)
                        .forEachRemaining(m -> { if (m.isResource()) s.add(m.asResource()); });
                members = (members == null) ? s : intersect(members, s);
            }
            if (members != null) members.forEach(m -> g.add(fn, RDFS.member, m));
        }

        // difference
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type,
                g.createResource(SET + "difference")); it.hasNext(); ) {
            Resource fn = it.next();
            if (g.contains(fn, RDFS.member)) continue;
            List<RDFNode> items = iterRdfList(g, fn);
            if (items.isEmpty()) continue;
            Set<Resource> members = new HashSet<>();
            if (items.get(0).isResource()) {
                g.listObjectsOfProperty(items.get(0).asResource(), RDFS.member)
                        .forEachRemaining(m -> { if (m.isResource()) members.add(m.asResource()); });
            }
            for (int i = 1; i < items.size(); i++) {
                if (!items.get(i).isResource()) continue;
                List<RDFNode> toRemove = g.listObjectsOfProperty(items.get(i).asResource(),
                        RDFS.member).toList();
                toRemove.stream().filter(RDFNode::isResource)
                        .forEach(m -> members.remove(m.asResource()));
            }
            members.forEach(m -> g.add(fn, RDFS.member, m));
        }
    }

    // ── Pre-processing: resolveValidityChains ─────────────────────────────────

    private void resolveValidityChains(Model g) {
        Property sameAs = IV_sameValidityAs;
        Property isValid = IV_isValid;
        Literal FALSE = g.createTypedLiteral(false);
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<Resource, Literal> proposed = new HashMap<>();
            Set<Resource> conflicted = new HashSet<>();
            for (StmtIterator it = g.listStatements(null, sameAs, (RDFNode) null); it.hasNext(); ) {
                Statement s = it.next();
                Resource x = s.getSubject();
                if (!s.getObject().isResource()) continue;
                Resource y = s.getObject().asResource();
                Statement bStmt = g.getProperty(y, isValid);
                if (bStmt == null || !bStmt.getObject().isLiteral()) continue;
                Literal b = bStmt.getObject().asLiteral();
                if (conflicted.contains(x)) continue;
                if (proposed.containsKey(x) && !proposed.get(x).equals(b)) {
                    conflicted.add(x);
                    proposed.remove(x);
                } else if (!proposed.containsKey(x)) {
                    proposed.put(x, b);
                }
            }
            for (Resource x : conflicted) {
                Statement ex = g.getProperty(x, isValid);
                if (ex == null || !ex.getObject().isLiteral()
                        || !ex.getObject().asLiteral().equals(FALSE)) {
                    g.removeAll(x, isValid, null);
                    g.addLiteral(x, isValid, false);
                    changed = true;
                }
            }
            for (Map.Entry<Resource, Literal> e : proposed.entrySet()) {
                Statement ex = g.getProperty(e.getKey(), isValid);
                if (ex == null || !ex.getObject().isLiteral()
                        || !ex.getObject().asLiteral().equals(e.getValue())) {
                    g.removeAll(e.getKey(), isValid, null);
                    g.add(e.getKey(), isValid, e.getValue());
                    changed = true;
                }
            }
        }
    }

    // ── Pre-processing: deriveGuaranteeStates ────────────────────────────────

    private void deriveGuaranteeStates(Model g) {
        Property igGuaranteeReport = ResourceFactory.createProperty(IG, "igGuaranteeReport");
        Property igAccepted = ResourceFactory.createProperty(IG, "igGuaranteeAccepted");
        Property igRejected = ResourceFactory.createProperty(IG, "igGuaranteeRejected");
        Property imoIssuedFor = ResourceFactory.createProperty(IMO, "imoeventIssuedFor");
        Property icmAbout = ResourceFactory.createProperty(ICM, "icmabout");

        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, IG_GuaranteeReport);
             it.hasNext(); ) {
            Resource r = it.next();
            if (g.contains(r, IG_igstate)) continue;
            Resource intent = objectResource(g, r, icmAbout);
            if (intent == null) continue;
            // igReportStateCompliant: exists accepted event issued for this intent
            for (ResIterator eit = g.listSubjectsWithProperty(RDF.type,
                    g.createResource(IG + "igGuaranteeAccepted")); eit.hasNext(); ) {
                Resource e = eit.next();
                if (g.contains(e, imoIssuedFor, intent)) {
                    g.add(r, IG_igstate, IG_stateCompliant);
                    break;
                }
            }
        }
    }

    // ── Pre-processing: deriveExtTypes ───────────────────────────────────────

    private void deriveExtTypes(Model g) {
        // Extension type propagation — Utility, Preference, Proposal inherit from base types.
        // Handled by ontology axioms loaded at startup; no additional pre-processing needed here.
    }

    // ── Evaluation roots ─────────────────────────────────────────────────────

    private List<Resource> findEvaluationRoots(Model g) {
        Set<Resource> inner = new HashSet<>();
        for (CombSpec cs : LOG_COMBINATORS) {
            for (StmtIterator it = g.listStatements(null, cs.prop(), (RDFNode) null); it.hasNext(); ) {
                RDFNode listNode = it.next().getObject();
                if (!listNode.isResource()) continue;
                iterRdfList(g, listNode.asResource()).forEach(
                        n -> { if (n.isResource()) inner.add(n.asResource()); });
            }
        }

        Set<Resource> seen = new LinkedHashSet<>();
        for (CombSpec cs : LOG_COMBINATORS) {
            for (ResIterator it = g.listSubjectsWithProperty(cs.prop()); it.hasNext(); ) {
                Resource subj = it.next();
                if (!inner.contains(subj)) seen.add(subj);
            }
        }
        // Bare log:match-family nodes
        for (Property mp : new Property[]{LOG_match, LOG_matchAll, LOG_matchAny,
                LOG_matchNone, LOG_matchOne, LOG_matchStatement}) {
            for (ResIterator it = g.listSubjectsWithProperty(mp); it.hasNext(); ) {
                Resource subj = it.next();
                if (!inner.contains(subj)) seen.add(subj);
            }
        }
        return new ArrayList<>(seen);
    }

    // ── Recursive evaluator ───────────────────────────────────────────────────

    /** Evaluates a node and records the outcome as an element of the report tree when it is a reportable IRI. */
    private EvalNode evalNode(Model g, Resource node) {
        EvalNode r = evalNodeCore(g, node);
        String kind = node.isURIResource() ? kindOf(g, node) : null;
        if (kind == null) return r;                       // blank or unknown: its elements bubble up unchanged

        List<ElementResult> children = r.elements();
        String targetUri = null;
        int targetCount = 0;
        List<String> targetMembers = List.of();
        Resource target = kind.endsWith("Expectation") ? objectResource(g, node, ICM_target) : null;
        if (target != null && target.isURIResource()) {
            targetUri = target.getURI();
            List<Resource> members = sortedMembers(g, target, null);
            targetCount = members.size();
            targetMembers = members.stream().filter(Resource::isURIResource).map(Resource::getURI)
                    .limit(ElementResult.MAX_TARGET_MEMBERS).toList();
        }
        ElementResult e = new ElementResult(node.getURI(), kind, r.passed(), children,
                children.isEmpty() ? r.conditions() : List.of(), targetUri, targetCount, targetMembers);
        return new EvalNode(r.passed(), r.conditions(), List.of(e));
    }

    /** The kind of intent element a node is, or null if it is not one that TR290 reports on. */
    private String kindOf(Model g, Resource n) {
        if (g.contains(n, RDF.type, g.createResource(ICM + "Intent"))) return "Intent";
        if (g.contains(n, RDF.type, g.createResource(ICM + "DeliveryExpectation"))) return "DeliveryExpectation";
        if (g.contains(n, RDF.type, g.createResource(ICM + "PropertyExpectation"))) return "PropertyExpectation";
        if (g.contains(n, RDF.type, g.createResource(ICM + "ReportingExpectation"))
                || g.contains(n, RDF.type, g.createResource(ICM + "ObservationReportingExpectation"))) {
            return "ReportingExpectation";
        }
        if (g.contains(n, RDF.type, g.createResource(ICM + "Expectation"))) return "Expectation";
        if (g.contains(n, RDF.type, g.createResource(ICM + "Condition"))
                || g.contains(n, RDF.type, g.createResource(LOG + "Condition"))) return "Condition";
        for (StmtIterator it = g.listStatements(n, RDF.type, (RDFNode) null); it.hasNext(); ) {
            RDFNode o = it.next().getObject();
            if (o.isURIResource() && o.asResource().getURI().startsWith(QUAN)) return "Condition";
        }
        return null;
    }

    /** The reporting scope: IRIs in the target of each reporting expectation (TR290 §4.2). */
    private ReportTree reportTree(Model g, List<ElementResult> elements, boolean overallPassed) {
        Set<String> scope = new TreeSet<>();
        boolean observations = false;
        Set<Resource> reporting = new HashSet<>();
        g.listSubjectsWithProperty(RDF.type, g.createResource(ICM + "ReportingExpectation")).forEachRemaining(reporting::add);
        g.listSubjectsWithProperty(RDF.type, g.createResource(ICM + "ObservationReportingExpectation")).forEachRemaining(reporting::add);
        for (Resource re : reporting) {
            if (g.contains(re, RDF.type, g.createResource(ICM + "ObservationReportingExpectation"))) observations = true;
            Resource target = objectResource(g, re, ICM_target);
            if (target == null) continue;
            for (Resource m : sortedMembers(g, target, null)) {
                if (m.isURIResource()) scope.add(m.getURI());
            }
        }

        List<Resource> intents = g.listSubjectsWithProperty(RDF.type, g.createResource(ICM + "Intent")).toList().stream()
                .filter(Resource::isURIResource).sorted(Comparator.comparing(Resource::getURI)).toList();
        List<ElementResult> top = elements;
        boolean intentEvaluated = elements.stream().anyMatch(e -> "Intent".equals(e.kind()));
        if (!intents.isEmpty() && !intentEvaluated) {          // the intent links to its expectations in another way
            top = List.of(new ElementResult(intents.get(0).getURI(), "Intent", overallPassed, elements,
                    List.of(), null, 0, List.of()));
        }
        return top.isEmpty() ? null : new ReportTree(top, scope, observations);
    }

    private EvalNode evalNodeCore(Model g, Resource node) {
        // iv:ivvalidIf gate
        Resource validityCtx = objectResource(g, node, IV_validIf);
        if (validityCtx != null) {
            Statement sv = g.getProperty(validityCtx, IV_isValid);
            if (sv == null || !sv.getObject().isLiteral()
                    || !sv.getObject().asLiteral().getBoolean()) {
                return EvalNode.fail(cond("validityGate",
                        Map.of("context",
                               validityCtx.isURIResource() ? validityCtx.getURI() : validityCtx.toString(),
                               "passed", false)));
            }
        }

        // Combinators
        for (CombSpec cs : LOG_COMBINATORS) {
            Statement listStmt = g.getProperty(node, cs.prop());
            if (listStmt == null) continue;
            if (!listStmt.getObject().isResource()) continue;
            List<RDFNode> items = iterRdfList(g, listStmt.getObject().asResource());
            List<EvalNode> childResults = items.stream()
                    .filter(RDFNode::isResource)
                    .map(i -> evalNode(g, i.asResource()))
                    .toList();
            List<Map<String, Object>> allConds = childResults.stream()
                    .flatMap(r -> r.conditions().stream()).collect(Collectors.toList());
            if (!items.isEmpty() && allConds.isEmpty()) {
                return EvalNode.fail(cond("opaqueChildren",
                        Map.of("count", items.size(),
                               "error", "no evaluable conditions in children",
                               "passed", false)));
            }
            boolean passed = applyCombinator(cs.comb(),
                    childResults.stream().map(EvalNode::passed).toList());
            return new EvalNode(passed, allConds,
                    childResults.stream().flatMap(r -> r.elements().stream()).toList());
        }

        // log:match
        Statement matchStmt = g.getProperty(node, LOG_match);
        if (matchStmt != null && matchStmt.getObject().isResource()) {
            return evalMatch(g, matchStmt.getObject().asResource());
        }

        // log:matchAll/Any/None/One
        record ContainerOp(Property prop, String name, Combinator comb) {}
        for (ContainerOp co : new ContainerOp[]{
                new ContainerOp(LOG_matchAll, "logMatchAll", Combinator.ALL),
                new ContainerOp(LOG_matchAny, "logMatchAny", Combinator.ANY),
                new ContainerOp(LOG_matchNone, "logMatchNone", Combinator.NONE),
                new ContainerOp(LOG_matchOne, "logMatchOne", Combinator.ONE)}) {
            Statement s = g.getProperty(node, co.prop());
            if (s != null && s.getObject().isResource()) {
                return evalMatchContainer(g, s.getObject().asResource(), co.name(), co.comb());
            }
        }

        // log:matchStatement
        Statement msStmt = g.getProperty(node, LOG_matchStatement);
        if (msStmt != null && msStmt.getObject().isResource()) {
            return evalMatchStatement(g, msStmt.getObject().asResource());
        }

        // Type-based dispatch
        for (StmtIterator it = g.listStatements(node, RDF.type, (RDFNode) null); it.hasNext(); ) {
            Resource t = it.next().getObject().asResource();
            String typeUri = t.getURI();
            if (typeUri == null) continue;
            EvalNode res = dispatchByType(g, node, typeUri);
            if (res != null) return res;
        }

        // Unknown/opaque — pass silently
        return EvalNode.ok();
    }

    private EvalNode dispatchByType(Model g, Resource node, String typeUri) {
        String local = localName(typeUri);
        // Quantity conditions
        if (typeUri.startsWith(QUAN)) {
            String sym = TioNamespaces.operatorSymbol(typeUri);
            if (!sym.equals("?")) {
                if (isRangeQuan(typeUri)) return evalRange(g, node);
                return evalTwoArg(g, node, local, sym);
            }
        }
        return switch (typeUri) {
            case ICM + "DeliveryExpectation" -> evalDeliveryExpectation(g, node);
            case ICM + "PropertyExpectation" -> evalPropertyExpectation(g, node);
            case SET + "setisMember"         -> evalSetOp(g, node, "setIsMember");
            case SET + "setintersectsWith"   -> evalSetOp(g, node, "setIntersectsWith");
            case SET + "setincludedIn"       -> evalSetOp(g, node, "setIncludedIn");
            case SET + "setforAll"           -> evalForAll(g, node);
            case SET + "empty"               -> evalSetEmpty(g, node);
            case SET + "elementOf"           -> evalSetOp(g, node, "elementOf");
            case IV  + "ivvalidityOf"        -> evalValidityOf(g, node);
            case IG  + "igGuaranteeReport"   -> evalGuaranteeReport(g, node);
            default -> null;
        };
    }

    // ── Quantity condition evaluators ─────────────────────────────────────────

    private EvalNode evalTwoArg(Model g, Resource node, String typeName, String sym) {
        Statement firstStmt = g.getProperty(node, RDF.first);
        Statement restStmt  = g.getProperty(node, RDF.rest);
        if (firstStmt == null || restStmt == null || !restStmt.getObject().isResource()) {
            return EvalNode.fail(qCond(typeName, sym, null, null, "missing operand nodes"));
        }
        Resource bndList = restStmt.getObject().asResource();
        Statement bndStmt = g.getProperty(bndList, RDF.first);
        if (bndStmt == null) return EvalNode.fail(qCond(typeName, sym, null, null, "missing bound node"));

        BigDecimal obs = rdfValue(g, firstStmt.getObject());
        BigDecimal bnd = rdfValue(g, bndStmt.getObject());
        String metricUri = firstStmt.getObject().isURIResource() ? firstStmt.getObject().asResource().getURI() : null;
        if (obs == null) {
            String name = firstStmt.getObject().isResource()
                    ? localName(firstStmt.getObject().asResource().getURI()) : "?";
            Map<String, Object> missing = qCond(typeName, sym, null, null, "no observation: " + name);
            if (metricUri != null) missing.put("metric", metricUri);
            if (node.isURIResource()) missing.put("element", node.getURI());
            return EvalNode.fail(missing);
        }
        if (bnd == null) return EvalNode.fail(qCond(typeName, sym, obs, null, "missing bound value"));

        String obsUnit = unitOf(g, firstStmt.getObject());
        String bndUnit = unitOf(g, bndStmt.getObject());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", typeName);
        c.put("operator", sym);
        c.put("observed", obs);
        c.put("bound", bnd);
        if (obsUnit != null) c.put("observedUnit", obsUnit);
        if (bndUnit != null) c.put("boundUnit", bndUnit);
        if (metricUri != null) c.put("metric", metricUri);
        if (firstStmt.getObject().isResource()) {
            Statement used = g.getProperty(firstStmt.getObject().asResource(), OBSERVATION_USED);
            if (used != null && used.getObject().isURIResource()) c.put("observation", used.getObject().asResource().getURI());
        }
        if (node.isURIResource()) c.put("element", node.getURI());
        boolean passed;
        try {
            passed = compare(Quantities.compare(obs, obsUnit, bnd, bndUnit), sym);
        } catch (Quantities.UnitMismatch e) {
            c.put("error", e.getMessage());                  // unit dimensions differ: evaluates to false (TR292D §5.2)
            c.put("passed", false);
            return EvalNode.fail(c);
        }
        c.put("passed", passed);
        return new EvalNode(passed, List.of(c));
    }

    private EvalNode evalRange(Model g, Resource node) {
        Statement r1Stmt = g.getProperty(node, RDF.rest);
        Statement valStmt = g.getProperty(node, RDF.first);
        if (valStmt == null || r1Stmt == null || !r1Stmt.getObject().isResource()) {
            return EvalNode.fail(Map.of("type", "quaninRange", "operator", "<=<=",
                    "error", "missing operand nodes", "passed", false));
        }
        Resource r1 = r1Stmt.getObject().asResource();
        Statement loStmt  = g.getProperty(r1, RDF.first);
        Statement r2Stmt  = g.getProperty(r1, RDF.rest);
        Resource r2 = (r2Stmt != null && r2Stmt.getObject().isResource())
                ? r2Stmt.getObject().asResource() : null;
        Statement hiStmt = r2 != null ? g.getProperty(r2, RDF.first) : null;
        if (loStmt == null || hiStmt == null) {
            return EvalNode.fail(Map.of("type", "quaninRange", "operator", "<=<=",
                    "error", "missing operand nodes", "passed", false));
        }
        BigDecimal val = rdfValue(g, valStmt.getObject());
        BigDecimal lo  = rdfValue(g, loStmt.getObject());
        BigDecimal hi  = rdfValue(g, hiStmt.getObject());
        if (val == null || lo == null || hi == null) {
            return EvalNode.fail(Map.of("type", "quaninRange", "operator", "<=<=",
                    "error", "missing rdf:value", "passed", false));
        }
        String valUnit = unitOf(g, valStmt.getObject());
        String loUnit = unitOf(g, loStmt.getObject());
        String hiUnit = unitOf(g, hiStmt.getObject());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "quaninRange"); c.put("operator", "<=<=");
        c.put("observed", val); c.put("lower", lo); c.put("upper", hi);
        if (valUnit != null) c.put("observedUnit", valUnit);
        if (loUnit != null) c.put("boundUnit", loUnit);
        boolean passed;
        try {
            passed = Quantities.compare(lo, loUnit, val, valUnit) <= 0
                    && Quantities.compare(val, valUnit, hi, hiUnit) <= 0;
        } catch (Quantities.UnitMismatch e) {
            c.put("error", e.getMessage());
            c.put("passed", false);
            return EvalNode.fail(c);
        }
        c.put("passed", passed);
        return new EvalNode(passed, List.of(c));
    }

    // ── Match evaluators ──────────────────────────────────────────────────────

    private EvalNode evalMatch(Model g, Resource listNode) {
        List<RDFNode> items = iterRdfList(g, listNode);
        if (items.size() != 3) {
            return EvalNode.fail(cond("logMatch",
                    Map.of("error", "expected 3 args, got " + items.size(), "passed", false)));
        }
        RDFNode s = items.get(0), p = items.get(1), o = items.get(2);
        if (!s.isResource() || !p.isResource()) {
            return EvalNode.fail(Map.of("type", "logMatch",
                    "error", "subject/predicate must be URIs", "passed", false));
        }
        boolean passed = g.contains(s.asResource(),
                ResourceFactory.createProperty(p.asResource().getURI()), o);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "logMatch");
        c.put("subject", s.toString()); c.put("predicate", p.toString()); c.put("object", o.toString());
        c.put("passed", passed);
        return new EvalNode(passed, List.of(c));
    }

    private EvalNode evalMatchContainer(Model g, Resource listNode,
                                        String opName, Combinator comb) {
        List<RDFNode> args = iterRdfList(g, listNode);
        if (args.size() < 3) {
            return EvalNode.fail(cond(opName, Map.of(
                    "error", "expected (container, predicate, object), got " + args.size(),
                    "passed", false)));
        }
        Resource container = args.get(0).isResource() ? args.get(0).asResource() : null;
        RDFNode predNode = args.get(1);
        RDFNode obj = args.get(2);
        if (container == null || !predNode.isResource()) {
            return EvalNode.fail(cond(opName, Map.of("error", "invalid args", "passed", false)));
        }
        Property pred = ResourceFactory.createProperty(predNode.asResource().getURI());
        List<Resource> members = g.listObjectsOfProperty(container, RDFS.member)
                .filterKeep(RDFNode::isResource)
                .mapWith(RDFNode::asResource).toList();
        if (members.isEmpty()) {
            return EvalNode.fail(cond(opName, Map.of(
                    "predicate", predNode.toString(), "object", obj.toString(),
                    "member_count", 0, "passed", false)));
        }
        List<Boolean> checks = members.stream()
                .map(m -> g.contains(m, pred, obj)).toList();
        boolean passed = applyCombinator(comb, checks);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", opName); c.put("predicate", predNode.toString());
        c.put("object", obj.toString()); c.put("member_count", members.size());
        c.put("passed", passed);
        return new EvalNode(passed, List.of(c));
    }

    private EvalNode evalMatchStatement(Model g, Resource listNode) {
        List<RDFNode> args = iterRdfList(g, listNode);
        if (args.size() < 4) {
            return EvalNode.fail(cond("logMatchStatement",
                    Map.of("error", "expected 4 args (s p o ctx)", "passed", false)));
        }
        RDFNode s = args.get(0), p = args.get(1), o = args.get(2);
        if (!s.isResource() || !p.isResource()) {
            return EvalNode.fail(cond("logMatchStatement",
                    Map.of("error", "subject/predicate must be URIs", "passed", false)));
        }
        boolean passed = g.contains(s.asResource(),
                ResourceFactory.createProperty(p.asResource().getURI()), o);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "logMatchStatement");
        c.put("subject", s.toString()); c.put("predicate", p.toString()); c.put("object", o.toString());
        c.put("passed", passed);
        return new EvalNode(passed, List.of(c));
    }

    // ── Set operator evaluators ───────────────────────────────────────────────

    private EvalNode evalForAll(Model g, Resource node) {
        Resource memberVar = objectResource(g, node, RDF.first);
        Resource rest = objectResource(g, node, RDF.rest);
        Resource container = rest != null ? objectResource(g, rest, RDF.first) : null;
        Resource rest2 = rest != null ? objectResource(g, rest, RDF.rest) : null;
        Resource conditionOrig = rest2 != null ? objectResource(g, rest2, RDF.first) : null;

        if (memberVar == null || container == null || conditionOrig == null) {
            return EvalNode.fail(cond("setForAll",
                    Map.of("error", "missing member_var, container, or condition", "passed", false)));
        }
        List<Resource> members = g.listObjectsOfProperty(container, RDFS.member)
                .filterKeep(RDFNode::isResource).mapWith(RDFNode::asResource).toList();
        if (members.isEmpty()) {
            return new EvalNode(true, List.of(cond("setForAll",
                    Map.of("member_count", 0, "passed", true))));
        }

        // Split triples: those referencing memberVar vs. those that don't
        List<Statement> varTriples = new ArrayList<>();
        List<Statement> nonVarTriples = new ArrayList<>();
        for (StmtIterator it = g.listStatements(); it.hasNext(); ) {
            Statement s = it.next();
            boolean involvesVar = s.getSubject().equals(memberVar)
                    || (s.getObject().isResource() && s.getObject().asResource().equals(memberVar));
            (involvesVar ? varTriples : nonVarTriples).add(s);
        }

        List<Map<String, Object>> allConds = new ArrayList<>();
        boolean allPassed = true;
        for (Resource member : members) {
            Model gSub = ModelFactory.createDefaultModel();
            nonVarTriples.forEach(gSub::add);
            for (Statement s : varTriples) {
                Resource newS = s.getSubject().equals(memberVar) ? member : s.getSubject();
                RDFNode newO = (s.getObject().isResource()
                        && s.getObject().asResource().equals(memberVar)) ? member : s.getObject();
                gSub.add(newS, s.getPredicate(), newO);
            }
            Resource condNode = conditionOrig.equals(memberVar) ? member : conditionOrig;
            EvalNode r = evalNode(gSub, condNode);
            if (!r.passed()) allPassed = false;
            allConds.addAll(r.conditions());
        }
        if (allConds.isEmpty()) {
            allConds.add(cond("setForAll", Map.of("member_count", members.size(),
                    "passed", allPassed)));
        }
        return new EvalNode(allPassed, allConds);
    }

    private EvalNode evalSetOp(Model g, Resource node, String typeName) {
        Statement vs = g.getProperty(node, RDF.value);
        boolean passed = vs != null && vs.getObject().isLiteral()
                && "true".equals(vs.getObject().asLiteral().getLexicalForm());
        return new EvalNode(passed, List.of(cond(typeName, Map.of("passed", passed))));
    }

    private EvalNode evalSetEmpty(Model g, Resource node) {
        Statement firstStmt = g.getProperty(node, RDF.first);
        if (firstStmt == null) return EvalNode.ok();
        Resource container = firstStmt.getObject().isResource()
                ? firstStmt.getObject().asResource() : null;
        boolean empty = container == null
                || !g.listObjectsOfProperty(container, RDFS.member).hasNext();
        return new EvalNode(empty, List.of(cond("empty", Map.of("passed", empty))));
    }

    // ── ICM evaluators ────────────────────────────────────────────────────────

    /**
     * TR290 §2.5: a delivery expectation is compliant when a member of its target is an instance of
     * icm:deliveryType (the class of the delivered resource). The handler fills the target by choosing
     * from the icm:chooseFrom pool (§2.5.3); the choice becomes an rdfs:member of the target. An empty
     * target is not compliant (§2.3.3). If icm:deliveryType is absent, any target member satisfies it.
     * Candidates are tried in URI order so the choice is deterministic.
     */
    private EvalNode evalDeliveryExpectation(Model g, Resource node) {
        Resource target = objectResource(g, node, ICM_target);
        Resource deliveryType = objectResource(g, node, ICM_deliveryType);
        Resource pool = objectResource(g, node, ResourceFactory.createProperty(ICM + "chooseFrom"));

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", "DeliveryExpectation");
        if (node.isURIResource()) c.put("element", node.getURI());
        if (deliveryType != null) c.put("deliveryType", deliveryType.getURI());

        if (target == null) {
            c.put("error", "missing icm:target");
            c.put("passed", false);
            return EvalNode.fail(c);
        }

        // Resources this intent already holds are its choice: keep them rather than choosing again.
        Resource selected = null;
        boolean heldChoice = false;
        if (deliveryType != null) {
            List<Resource> holdings = g.listObjectsOfProperty(EVAL_CONTEXT, EVAL_HOLDS).toList().stream()
                    .filter(RDFNode::isURIResource).map(RDFNode::asResource)
                    .sorted(Comparator.comparing(Resource::getURI)).collect(Collectors.toList());
            for (Resource h : holdings) {
                if (g.contains(h, RDF.type, deliveryType)) {
                    g.add(target, RDFS.member, h);
                    selected = h;
                    heldChoice = true;
                    break;
                }
            }
        }
        int candidateCount = 0;
        if (!heldChoice && sortedMembers(g, target, deliveryType).isEmpty() && pool != null) {
            refreshPoolIfDependsOnChoice(g, pool);
            List<Resource> candidates = sortedMembers(g, pool, deliveryType);
            candidateCount = candidates.size();
            c.put("candidates", candidateCount);
            if (!candidates.isEmpty()) {
                selected = candidates.get(0);
                g.add(target, RDFS.member, selected);
            }
        }

        List<Resource> matching = sortedMembers(g, target, deliveryType);
        boolean passed = !matching.isEmpty();
        c.put("member_count", matching.size());
        c.put("passed", passed);
        if (passed && selected != null) {
            c.put("selected", selected.isURIResource() ? selected.getURI() : selected.toString());
            if (heldChoice) c.put("held", true);
        } else if (!passed) {
            String of = deliveryType != null ? " of type " + localName(deliveryType.getURI()) : "";
            c.put("error", pool != null
                    ? "no candidate" + of + " in chooseFrom pool"
                    : "no target member" + of);
        }
        return passed ? new EvalNode(true, List.of(c)) : EvalNode.fail(c);
    }

    /** rdfs:members of a container, optionally only instances of {@code type}, ordered by URI. */
    private List<Resource> sortedMembers(Model g, Resource container, Resource type) {
        return g.listObjectsOfProperty(container, RDFS.member).toList().stream()
                .filter(RDFNode::isResource)
                .map(RDFNode::asResource)
                .filter(m -> type == null || g.contains(m, RDF.type, type))
                .sorted(Comparator.comparing(m -> m.isURIResource() ? m.getURI() : "~" + m))
                .collect(Collectors.toList());
    }

    private EvalNode evalPropertyExpectation(Model g, Resource node) {
        Statement vs = g.getProperty(node, RDF.value);
        boolean passed = vs != null && vs.getObject().isLiteral()
                && "true".equals(vs.getObject().asLiteral().getLexicalForm());
        return new EvalNode(passed, List.of(cond("PropertyExpectation",
                Map.of("value", vs != null ? vs.getString() : null, "passed", passed))));
    }

    private EvalNode evalValidityOf(Model g, Resource node) {
        List<RDFNode> members = g.listObjectsOfProperty(node, RDFS.member).toList();
        if (members.isEmpty()) return EvalNode.ok();
        boolean passed = members.stream().allMatch(m -> {
            if (!m.isResource()) return true;
            Statement sv = g.getProperty(m.asResource(), IV_isValid);
            return sv != null && sv.getObject().isLiteral()
                    && sv.getObject().asLiteral().getBoolean();
        });
        return new EvalNode(passed, List.of(cond("validityOf", Map.of("passed", passed))));
    }

    private EvalNode evalGuaranteeReport(Model g, Resource node) {
        boolean passed = g.contains(node, IG_igstate, IG_stateCompliant);
        return new EvalNode(passed, List.of(cond("GuaranteeReport", Map.of("passed", passed))));
    }

    // ── Flat scan ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> flatScan(Model g) {
        Set<Resource> embeddedInSetOps = setForAllConditions(g);
        List<Map<String, Object>> conditions = new ArrayList<>();
        Set<Resource> seen = new HashSet<>();

        Resource[] scanTypes = {
                QUAN_atLeast, QUAN_atLeast2, QUAN_atMost, QUAN_atMost2,
                QUAN_greater, QUAN_greater2, QUAN_smaller, QUAN_smaller2,
                QUAN_exactly, QUAN_exactly2, QUAN_inRange, QUAN_inRange2,
                SET_setisMember, SET_setintersectsWith, SET_setincludedIn, SET_setforAll,
                SET_empty, SET_elementOf,
                ICM_DeliveryExpectation, ICM_PropertyExpectation,
                IG_GuaranteeReport, IV_validityOf,
        };
        for (Resource type : scanTypes) {
            for (ResIterator it = g.listSubjectsWithProperty(RDF.type, type); it.hasNext(); ) {
                Resource node = it.next();
                if (embeddedInSetOps.contains(node) || seen.contains(node)) continue;
                seen.add(node);
                EvalNode r = evalNode(g, node);
                conditions.addAll(r.conditions());
            }
        }
        return conditions;
    }

    private Set<Resource> setForAllConditions(Model g) {
        Set<Resource> embedded = new HashSet<>();
        for (ResIterator it = g.listSubjectsWithProperty(RDF.type, SET_setforAll); it.hasNext(); ) {
            Resource node = it.next();
            Resource rest = objectResource(g, node, RDF.rest);
            Resource rest2 = rest != null ? objectResource(g, rest, RDF.rest) : null;
            Resource cond = rest2 != null ? objectResource(g, rest2, RDF.first) : null;
            if (cond != null) embedded.add(cond);
        }
        return embedded;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private List<RDFNode> iterRdfList(Model g, Resource listNode) {
        List<RDFNode> result = new ArrayList<>();
        Resource current = listNode;
        Set<Resource> visited = new HashSet<>();
        while (current != null && !current.equals(RDF.nil) && visited.add(current)) {
            Statement firstStmt = g.getProperty(current, RDF.first);
            if (firstStmt != null) result.add(firstStmt.getObject());
            Statement restStmt = g.getProperty(current, RDF.rest);
            current = (restStmt != null && restStmt.getObject().isResource())
                    ? restStmt.getObject().asResource() : null;
        }
        return result;
    }

    private Resource objectResource(Model g, Resource subject, Property predicate) {
        Statement s = g.getProperty(subject, predicate);
        return (s != null && s.getObject().isResource()) ? s.getObject().asResource() : null;
    }

    private BigDecimal rdfValue(Model g, RDFNode node) {
        if (node == null) return null;
        if (node.isLiteral()) return parseDecimal(node.asLiteral().getLexicalForm());
        if (node.isResource()) {
            Statement vs = g.getProperty(node.asResource(), RDF.value);
            if (vs != null && vs.getObject().isLiteral())
                return parseDecimal(vs.getObject().asLiteral().getLexicalForm());
            if (vs != null && vs.getObject().isResource() && !vs.getObject().equals(node))
                return rdfValue(g, vs.getObject());          // nested quantity
        }
        return null;
    }

    private BigDecimal decimalValue(Model g, RDFNode node) { return rdfValue(g, node); }

    private BigDecimal parseDecimal(String s) {
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private boolean compare(int cmp, String sym) {
        return switch (sym) {
            case ">="  -> cmp >= 0;
            case "<="  -> cmp <= 0;
            case ">"   -> cmp > 0;
            case "<"   -> cmp < 0;
            case "=="  -> cmp == 0;
            default    -> false;
        };
    }

    private boolean applyCombinator(Combinator c, List<Boolean> bools) {
        return switch (c) {
            case ALL  -> bools.stream().allMatch(Boolean::booleanValue);
            case ANY  -> bools.stream().anyMatch(Boolean::booleanValue);
            case NONE -> bools.stream().noneMatch(Boolean::booleanValue);
            case ONE  -> bools.stream().filter(Boolean::booleanValue).count() == 1;
        };
    }

    private Instant parseInstant(String s) {
        try { return Instant.parse(s); } catch (DateTimeParseException e) {
            try { return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(s, Instant::from); }
            catch (Exception e2) { return Instant.MIN; }
        }
    }

    private <T> Set<T> intersect(Set<T> a, Set<T> b) {
        Set<T> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    private static String localName(String uri) {
        if (uri == null) return null;
        int hash = uri.lastIndexOf('#');
        int slash = uri.lastIndexOf('/');
        int idx = Math.max(hash, slash);
        return idx >= 0 ? uri.substring(idx + 1) : uri;
    }

    private Map<String, Object> cond(String type, Map<String, Object> extras) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.putAll(extras);
        return c;
    }

    private Map<String, Object> qCond(String type, String sym, BigDecimal obs,
                                       BigDecimal bnd, String error) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type); c.put("operator", sym);
        if (obs != null) c.put("observed", obs);
        if (bnd != null) c.put("bound", bnd);
        if (error != null) c.put("error", error);
        c.put("passed", false);
        return c;
    }

    private String buildFailReason(List<Map<String, Object>> conditions) {
        List<String> labels = conditions.stream()
                .filter(c -> !Boolean.TRUE.equals(c.get("passed")))
                .map(this::failLabel)
                .toList();
        return labels.isEmpty() ? "Conditions not met"
                : "Conditions not met: " + String.join("; ", labels);
    }

    private String failLabel(Map<String, Object> c) {
        if (c.containsKey("error")) return c.get("type") + ": " + c.get("error");
        String t = String.valueOf(c.get("type"));
        String sym = (String) c.get("operator");
        if (sym == null) return t + ": FAIL";
        Object obs = c.get("observed"); Object bnd = c.get("bound");
        if (bnd == null) bnd = c.get("lower") + "…" + c.get("upper");
        String ou = c.get("observedUnit") != null ? " " + c.get("observedUnit") : "";
        String bu = c.get("boundUnit") != null ? " " + c.get("boundUnit") : "";
        return obs + ou + " " + sym + " " + bnd + bu + ": FAIL";
    }

    private String joinTurtle(String... parts) {
        return Arrays.stream(parts).filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    // ── Minimal pair helper ──────────────────────────────────────────────────
    private record Pair<A, B>(A first, B second) {}
}
