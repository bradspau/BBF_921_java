package org.tmforum.intent.handler;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.springframework.stereotype.Component;
import org.tmforum.intent.graph.GraphNodes;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.TioNamespaces;
import org.tmforum.intent.model.ElementResult;
import org.tmforum.intent.model.ReportTree;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Builds a TR290 (Intent Common Model, §4) intent report from an evaluation's {@link ReportTree}.
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

    private static Property icm(String n) { return org.apache.jena.rdf.model.ResourceFactory.createProperty(ICM + n); }

    /** What the builder needs to know about the report being created. */
    public record ReportContext(String reportUri, String intentHandlingState, long reportNumber, Instant generated) {}

    /**
     * @return the report triples, or null if the evaluation has no icm:Intent to report on
     */
    public Model build(ReportTree tree, ReportContext ctx) {
        if (tree == null || tree.elements().isEmpty()) return null;
        ElementResult intent = tree.elements().stream().filter(e -> "Intent".equals(e.kind())).findFirst().orElse(null);
        if (intent == null) return null;

        Model m = ModelFactory.createDefaultModel();
        Build b = new Build(m, tree, ctx.reportUri() + "/tio/");

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

    /** Per-build state. */
    private static final class Build {
        final Model m;
        final ReportTree tree;
        final String base;
        final Set<String> usedIris = new HashSet<>();
        final Map<String, Resource> targetReports = new HashMap<>();
        int reasons = 0;

        Build(Model m, ReportTree tree, String base) {
            this.m = m;
            this.tree = tree;
            this.base = base;
        }

        /** Report resources this element contributes: its own report if in scope, else its descendants'. */
        List<Resource> report(ElementResult e) {
            List<Resource> fromChildren = new ArrayList<>();
            for (ElementResult child : e.children()) fromChildren.addAll(report(child));

            boolean isIntent = "Intent".equals(e.kind());
            if (!isIntent && !tree.scope().contains(e.uri())) return fromChildren;

            Resource r = m.createResource(iri(e));
            r.addProperty(RDF.type, m.createResource(ICM + reportClass(e.kind())));
            r.addProperty(icm("about"), m.createResource(e.uri()));
            r.addProperty(icm("result"), m.createTypedLiteral(e.passed()));
            for (Resource c : fromChildren) r.addProperty(icm("resultFrom"), c);

            for (Map<String, Object> d : e.details()) {
                if (!Boolean.TRUE.equals(d.get("passed"))) {
                    String why = reason(d);
                    if (why != null) {
                        Resource reason = m.createResource(base + "reason/" + (++reasons));
                        reason.addProperty(RDF.type, m.createResource(ICM + "Reason"));
                        reason.addProperty(RDFS.label, why);
                        r.addProperty(icm("reason"), reason);
                    }
                }
                if (tree.includeObservations() && d.get("metric") != null && d.get("observed") != null) {
                    Resource obs = m.createResource();
                    obs.addProperty(RDF.type, m.createResource(MET + "Observation"));
                    obs.addProperty(m.createProperty(MET + "observedMetric"), m.createResource(d.get("metric").toString()));
                    obs.addProperty(RDF.value, m.createTypedLiteral(plain(d.get("observed")), XSDDatatype.XSDdecimal));
                    r.addProperty(m.createProperty(MET + "observed"), obs);
                }
            }

            if (e.targetUri() != null && tree.scope().contains(e.targetUri())) {
                r.addProperty(icm("targetReport"), targetReport(e));
            }
            return List.of(r);
        }

        Resource targetReport(ElementResult e) {
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

        String iri(ElementResult e) {
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

    /** Human-readable explanation of why a leaf element is false. */
    private static String reason(Map<String, Object> d) {
        if (d.get("error") != null) return d.get("error").toString();
        if (d.get("observed") != null && d.get("operator") != null && d.get("bound") != null) {
            return plain(d.get("observed")) + " " + d.get("operator") + " " + plain(d.get("bound")) + " is false";
        }
        return null;
    }

    private static String plain(Object v) {
        return v instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(v);
    }

    private static String safe(String uri) {
        return GraphNodes.localName(uri).replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
