package org.tmforum.intent.handler;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.stereotype.Component;
import org.tmforum.intent.engine.Reasons;
import org.tmforum.intent.engine.ResultContract;
import org.tmforum.intent.graph.GraphNodes;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.TioNamespaces;
import org.tmforum.intent.model.ElementResult;
import org.tmforum.intent.model.ReportTree;

import java.io.StringWriter;
import java.time.Instant;
import java.util.*;

/**
 * Builds a TR290 (Intent Common Model, §4) intent report, either from an evaluation's {@link ReportTree} or
 * from the expression plus the shared result graph (spec 5.3). Both inputs are first turned into the same
 * {@link Element} tree, which one renderer turns into report triples, so the two paths cannot diverge.
 *
 * <p>From the result graph the builder only takes what the engine decided: each element's truth value
 * ({@code rdf:value}), chosen target members, reasons and observations. From the expression it takes what to
 * report: the reporting scope, whether observations are reported, each element's kind and structure, and its
 * target. It never decides whether an element is true.
 *
 * <ul>
 *   <li>One report node per reported intent element, linked to it with {@code icm:about}.</li>
 *   <li>{@code icm:result} holds the element's truth value (compliance for intents and expectations).</li>
 *   <li>{@code icm:resultFrom} links a report to the reports of the elements its result was derived from.</li>
 *   <li>Only elements named in the reporting expectation's target are reported (§4.2); the intent always is.
 *       If an element is out of scope, its in-scope descendants are linked from the nearest reported ancestor.</li>
 *   <li>Observed values ({@code met:observed}) are included only for an icm:ObservationReportingExpectation (§4.6).</li>
 * </ul>
 */
@Component
public class ReportBuilder {

    private static final String ICM = TioNamespaces.ICM;
    private static final String IMO = TioNamespaces.IMO;
    private static final String MET = TioNamespaces.MET;
    private static final String TIME = "http://www.w3.org/2006/time#";

    private static Property icm(String n) { return ResourceFactory.createProperty(ICM + n); }

    /** What the builder needs to know about the report being created. */
    public record ReportContext(String reportUri, String intentHandlingState, long reportNumber, Instant generated) {}

    /**
     * @return the report triples, or null if the evaluation has no icm:Intent to report on
     */
    public Model build(ReportTree tree, ReportContext ctx) {
        if (tree == null || tree.elements().isEmpty()) return null;
        ElementResult intent = tree.elements().stream().filter(e -> "Intent".equals(e.kind())).findFirst().orElse(null);
        if (intent == null) return null;
        return render(fromTree(intent), tree.scope(), tree.includeObservations(), ctx);
    }

    /**
     * Builds the report from the parsed expression and the result graph of its evaluation (spec 5.2).
     * An element without an {@code rdf:value} in the graph is reported false (TR292A §4: default false).
     *
     * @return the report triples, or null if the expression has no IRI-identified icm:Intent
     */
    public Model build(Model expression, Model resultGraph, ReportContext ctx) {
        if (expression == null) return null;
        Model graph = resultGraph == null ? ModelFactory.createDefaultModel() : resultGraph;
        Resource intent = expression.listSubjectsWithProperty(RDF.type, expression.createResource(ICM + "Intent"))
                .toList().stream().filter(Resource::isURIResource)
                .min(Comparator.comparing(Resource::getURI)).orElse(null);
        if (intent == null) return null;

        Set<String> scope = new TreeSet<>();
        boolean observations = false;
        for (Resource re : reportingExpectations(expression)) {
            if (expression.contains(re, RDF.type, expression.createResource(ICM + "ObservationReportingExpectation"))) {
                observations = true;
            }
            Resource target = objectResource(expression, re, icm("target"));
            if (target == null) continue;
            expression.listObjectsOfProperty(target, RDFS.member).forEachRemaining(n -> {
                if (n.isURIResource()) scope.add(n.asResource().getURI());
            });
        }
        return render(new GraphReader(expression, graph).element(intent), scope, observations, ctx);
    }

    private Model render(Element intent, Set<String> scope, boolean includeObservations, ReportContext ctx) {
        Model m = ModelFactory.createDefaultModel();
        Build b = new Build(m, scope, includeObservations, ctx.reportUri() + "/tio/");

        List<Resource> reports = b.report(intent);
        Resource intentReport = reports.get(0);

        intentReport.addProperty(icm("reportNumber"),
                m.createTypedLiteral(Long.toString(ctx.reportNumber()), XSDDatatype.XSDpositiveInteger));
        Resource generated = m.createResource();
        generated.addProperty(RDF.type, m.createResource(TIME + "Instant"));
        generated.addProperty(m.createProperty(TIME + "inXSDDateTimeStamp"),
                m.createTypedLiteral(ctx.generated().toString(), XSDDatatype.XSDdateTime));
        intentReport.addProperty(icm("reportGenerated"), generated);
        intentReport.addProperty(m.createProperty(IMO + "handlingState"), m.createResource(IMO
                + ("Fulfilled".equals(ctx.intentHandlingState()) ? "StateCompliant" : "StateDegraded")));

        // Tie the TR290 report to the TMF921 IntentReport resource it belongs to
        m.createResource(ctx.reportUri()).addProperty(m.createProperty(Namespaces.TMF + "icmReport"), intentReport);
        return m;
    }

    public static String toTurtle(Model m) {
        m.setNsPrefix("icm", ICM);
        m.setNsPrefix("imo", IMO);
        m.setNsPrefix("met", MET);
        m.setNsPrefix("t", TIME);
        m.setNsPrefix("rdfs", RDFS.getURI());
        m.setNsPrefix("xsd", XSDDatatype.XSD + "#");
        m.setNsPrefix("tmf", Namespaces.TMF);
        StringWriter sw = new StringWriter();
        RDFDataMgr.write(sw, m, Lang.TURTLE);
        return sw.toString();
    }

    // ── The element tree both inputs are turned into ─────────────────────────

    /**
     * One reportable intent element, as the renderer needs it.
     *
     * @param reasons       explanations of why it is false (icm:Reason labels)
     * @param observations  observations its result used (reported only for an ObservationReportingExpectation)
     */
    private record Element(String uri, String kind, boolean result, List<Element> children, List<String> reasons,
                           List<Observation> observations, String targetUri, int targetCount, List<String> targetMembers) {}

    /** An observation used by a condition: metric IRI, value literal and unit (either may be null). */
    private record Observation(String metric, Literal value, String unit) {}

    private static Element fromTree(ElementResult e) {
        List<String> reasons = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        for (Map<String, Object> d : e.details()) {
            if (!Boolean.TRUE.equals(d.get("passed"))) {
                String why = Reasons.of(d);
                if (why != null) reasons.add(why);
            }
            if (d.get("metric") != null && d.get("observed") != null) {
                observations.add(new Observation(d.get("metric").toString(),
                        ResourceFactory.createTypedLiteral(Reasons.plain(d.get("observed")), XSDDatatype.XSDdecimal),
                        d.get("observedUnit") == null ? null : d.get("observedUnit").toString()));
            }
        }
        return new Element(e.uri(), e.kind(), e.passed(), e.children().stream().map(ReportBuilder::fromTree).toList(),
                reasons, observations, e.targetUri(), e.targetCount(), e.targetMembers());
    }

    /** Reads elements from the expression (structure) and the result graph (truth, choices, reasons, observations). */
    private static final class GraphReader {
        final Model expr;
        final Model graph;

        GraphReader(Model expr, Model graph) {
            this.expr = expr;
            this.graph = graph;
        }

        Element element(Resource node) {
            String kind = kindOf(expr, node);
            List<Element> children = new ArrayList<>();
            List<Resource> items = combinatorItems(node);
            if (items != null) {
                for (Resource item : items) collect(item, children, new HashSet<>());
            } else if ("Intent".equals(kind)) {
                // the intent links to its expectations in another way: report the top-level elements under it
                for (Resource root : roots()) {
                    if (!root.equals(node)) collect(root, children, new HashSet<>());
                }
            }

            Statement vs = graph.getProperty(graph.createResource(node.getURI()), RDF.value);
            boolean result = vs != null && vs.getObject().isLiteral() && vs.getObject().asLiteral().getBoolean();

            List<String> reasons = new ArrayList<>();
            graph.listObjectsOfProperty(graph.createResource(node.getURI()), ResultContract.ICM_REASON).forEachRemaining(r -> {
                if (r.isResource()) {
                    Statement label = graph.getProperty(r.asResource(), RDFS.label);
                    if (label != null && label.getObject().isLiteral()) reasons.add(label.getString());
                }
            });
            Collections.sort(reasons);

            List<Observation> observations = new ArrayList<>();
            graph.listObjectsOfProperty(graph.createResource(node.getURI()), ResultContract.MET_OBSERVED).forEachRemaining(o -> {
                if (!o.isResource()) return;
                Resource obs = o.asResource();
                Resource metric = objectResource(graph, obs, ResultContract.MET_OBSERVED_METRIC);
                Statement value = graph.getProperty(obs, RDF.value);
                Statement unit = graph.getProperty(obs, ResultContract.QUAN_UNIT);
                if (metric == null || !metric.isURIResource() || value == null || !value.getObject().isLiteral()) return;
                observations.add(new Observation(metric.getURI(), value.getObject().asLiteral(),
                        unit != null && unit.getObject().isLiteral() ? unit.getString() : null));
            });

            String targetUri = null;
            int targetCount = 0;
            List<String> targetMembers = List.of();
            Resource target = kind.endsWith("Expectation") ? objectResource(expr, node, icm("target")) : null;
            if (target != null && target.isURIResource()) {
                targetUri = target.getURI();
                // members the expression names, plus those the engine chose (spec 5.2: chosen become members)
                Set<RDFNode> members = new HashSet<>(expr.listObjectsOfProperty(target, RDFS.member).toList());
                members.addAll(graph.listObjectsOfProperty(graph.createResource(targetUri), RDFS.member).toList());
                targetCount = members.size();
                targetMembers = members.stream().filter(RDFNode::isURIResource).map(n -> n.asResource().getURI())
                        .sorted().limit(ElementResult.MAX_TARGET_MEMBERS).toList();
            }
            return new Element(node.getURI(), kind, result, children, reasons, observations,
                    targetUri, targetCount, targetMembers);
        }

        /** Adds the reportable elements a list item stands for: itself, or those reachable through it. */
        private void collect(Resource item, List<Element> out, Set<Resource> visiting) {
            if (item.isURIResource() && kindOf(expr, item) != null) {
                out.add(element(item));
                return;
            }
            if (!visiting.add(item)) return;                      // cyclic structure: nothing more to find
            List<Resource> items = combinatorItems(item);
            if (items != null) for (Resource i : items) collect(i, out, visiting);
        }

        /** The items of a node's first log combinator list (allOf, anyOf, noneOf, oneOf), or null if it has none. */
        private List<Resource> combinatorItems(Resource node) {
            for (Property p : COMBINATORS) {
                Resource list = objectResource(expr, node, p);
                if (list == null) continue;
                return list.as(org.apache.jena.rdf.model.RDFList.class).iterator().toList().stream()
                        .filter(RDFNode::isResource).map(RDFNode::asResource).toList();
            }
            return null;
        }

        /** Nodes with a combinator list that are not themselves inside one, in URI order. */
        private List<Resource> roots() {
            Set<Resource> inner = new HashSet<>();
            Set<Resource> withList = new HashSet<>();
            for (Property p : COMBINATORS) {
                expr.listSubjectsWithProperty(p).forEachRemaining(s -> {
                    withList.add(s);
                    List<Resource> items = combinatorItems(s);
                    if (items != null) inner.addAll(items);
                });
            }
            return withList.stream().filter(r -> !inner.contains(r))
                    .sorted(Comparator.comparing(r -> r.isURIResource() ? r.getURI() : "~" + r)).toList();
        }
    }

    private static final Property[] COMBINATORS = {
            TioNamespaces.LOG_allOf, TioNamespaces.LOG_anyOf, TioNamespaces.LOG_noneOf, TioNamespaces.LOG_oneOf};

    /** Reporting expectations of the expression (icm:ReportingExpectation or icm:ObservationReportingExpectation). */
    private static Set<Resource> reportingExpectations(Model expr) {
        Set<Resource> out = new HashSet<>();
        expr.listSubjectsWithProperty(RDF.type, expr.createResource(ICM + "ReportingExpectation")).forEachRemaining(out::add);
        expr.listSubjectsWithProperty(RDF.type, expr.createResource(ICM + "ObservationReportingExpectation"))
                .forEachRemaining(out::add);
        return out;
    }

    /**
     * The kind of intent element an expression node is, or null if it is not one TR290 reports on. Mirrors the
     * evaluator's classification: a node with a quantity-function type or predicate is a condition.
     */
    private static String kindOf(Model g, Resource n) {
        if (g.contains(n, RDF.type, g.createResource(ICM + "Intent"))) return "Intent";
        if (g.contains(n, RDF.type, g.createResource(ICM + "DeliveryExpectation"))) return "DeliveryExpectation";
        if (g.contains(n, RDF.type, g.createResource(ICM + "PropertyExpectation"))) return "PropertyExpectation";
        if (g.contains(n, RDF.type, g.createResource(ICM + "ReportingExpectation"))
                || g.contains(n, RDF.type, g.createResource(ICM + "ObservationReportingExpectation"))) {
            return "ReportingExpectation";
        }
        if (g.contains(n, RDF.type, g.createResource(ICM + "Expectation"))) return "Expectation";
        if (g.contains(n, RDF.type, g.createResource(ICM + "Condition"))
                || g.contains(n, RDF.type, g.createResource(TioNamespaces.LOG + "Condition"))) return "Condition";
        for (RDFNode t : g.listObjectsOfProperty(n, RDF.type).toList()) {
            if (t.isURIResource() && t.asResource().getURI().startsWith(TioNamespaces.QUAN)) return "Condition";
        }
        for (Property p : TioNamespaces.ALL_QUAN_PREDICATES) {
            if (g.contains(n, p)) return "Condition";
        }
        return null;
    }

    private static Resource objectResource(Model g, Resource s, Property p) {
        Statement st = g.getProperty(s, p);
        return st != null && st.getObject().isResource() ? st.getObject().asResource() : null;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    /** Per-build state. */
    private static final class Build {
        final Model m;
        final Set<String> scope;
        final boolean includeObservations;
        final String base;
        final Set<String> usedIris = new HashSet<>();
        final Map<String, Resource> targetReports = new HashMap<>();
        int reasons = 0;

        Build(Model m, Set<String> scope, boolean includeObservations, String base) {
            this.m = m;
            this.scope = scope;
            this.includeObservations = includeObservations;
            this.base = base;
        }

        /** Report resources this element contributes: its own report if in scope, else its descendants'. */
        List<Resource> report(Element e) {
            List<Resource> fromChildren = new ArrayList<>();
            for (Element child : e.children()) fromChildren.addAll(report(child));

            boolean isIntent = "Intent".equals(e.kind());
            if (!isIntent && !scope.contains(e.uri())) return fromChildren;

            Resource r = m.createResource(iri(e));
            r.addProperty(RDF.type, m.createResource(ICM + reportClass(e.kind())));
            r.addProperty(icm("about"), m.createResource(e.uri()));
            r.addProperty(icm("result"), m.createTypedLiteral(e.result()));
            for (Resource c : fromChildren) r.addProperty(icm("resultFrom"), c);

            for (String why : e.reasons()) {
                Resource reason = m.createResource(base + "reason/" + (++reasons));
                reason.addProperty(RDF.type, m.createResource(ICM + "Reason"));
                reason.addProperty(RDFS.label, why);
                r.addProperty(icm("reason"), reason);
            }
            if (includeObservations) {
                for (Observation o : e.observations()) {
                    Resource obs = m.createResource();
                    obs.addProperty(RDF.type, m.createResource(MET + "Observation"));
                    obs.addProperty(m.createProperty(MET + "observedMetric"), m.createResource(o.metric()));
                    obs.addProperty(RDF.value, o.value());
                    if (o.unit() != null) obs.addProperty(m.createProperty(TioNamespaces.QUAN + "unit"), o.unit());
                    r.addProperty(m.createProperty(MET + "observed"), obs);
                }
            }

            if (e.targetUri() != null && scope.contains(e.targetUri())) {
                r.addProperty(icm("targetReport"), targetReport(e));
            }
            return List.of(r);
        }

        Resource targetReport(Element e) {
            return targetReports.computeIfAbsent(e.targetUri(), uri -> {
                Resource tr = m.createResource(unique(base + "target/" + safe(uri)));
                tr.addProperty(RDF.type, m.createResource(ICM + "TargetReport"));
                tr.addProperty(RDF.type, RDFS.Container);
                tr.addProperty(icm("about"), m.createResource(uri));
                tr.addProperty(icm("targetCount"),
                        m.createTypedLiteral(Integer.toString(e.targetCount()), XSDDatatype.XSDnonNegativeInteger));
                for (String member : e.targetMembers()) tr.addProperty(RDFS.member, m.createResource(member));
                return tr;
            });
        }

        String iri(Element e) {
            String segment = switch (e.kind()) {
                case "Intent" -> "intent";
                case "Condition" -> "condition/" + safe(e.uri());
                default -> "expectation/" + safe(e.uri());
            };
            return unique(base + segment);
        }

        String unique(String candidate) {
            String iri = candidate;
            for (int i = 2; !usedIris.add(iri); i++) iri = candidate + "-" + i;
            return iri;
        }
    }

    private static String reportClass(String kind) {
        return switch (kind) {
            case "Intent" -> "IntentReport";
            case "Condition" -> "ConditionReport";
            case "DeliveryExpectation" -> "DeliveryExpectationReport";
            case "PropertyExpectation" -> "PropertyExpectationReport";
            case "ReportingExpectation" -> "ReportingExpectationReport";
            default -> "ExpectationReport";
        };
    }

    private static String safe(String uri) {
        return GraphNodes.localName(uri).replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
