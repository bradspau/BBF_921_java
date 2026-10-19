package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.TioNamespaces;

import java.util.*;

/**
 * Extracts the result graph (spec 5.2) from the rule engine's final (stage B) inference model.
 *
 * <p>{@link ResultContract#filter} alone cannot scope a full reasoner model (it carries no types, so it cannot
 * tell intent elements from function nodes or chosen members from pool members). This extractor owns that:
 * <ul>
 *   <li><b>Elements</b> are the IRI-identified instances of {@code icm:Intent}, {@code icm:Expectation},
 *       {@code icm:Condition} and {@code log:Condition}, including instances of their subclasses as far as the
 *       model holds {@code rdfs:subClassOf} statements (the ontology). Blank-node elements are not reported
 *       (TR290 section 4.2).</li>
 *   <li>{@code rdf:value} booleans, {@code icm:reason} (with the reason's type and label) and
 *       {@code met:observed} are copied for elements only. The value of an element with a contributor is the rules'
 *       conjunction ({@code tmf:result}), never an {@code rdf:value} the input gave it (see {@link #copyValue}).</li>
 *   <li>{@code ?target rdfs:member ?r} is copied only for a <em>chosen</em> member, marked by the rules with
 *       {@code ?r tmf:chosenFor ?target} ({@link #CHOSEN_FOR}); seed and pool members are never emitted.</li>
 *   <li>Observations are flattened exactly as {@link ResultContract#copyObservation} does.</li>
 *   <li>A {@code set:forAll} condition template (marked {@code tmf:forAllTemplate}) is not reported: it is evaluated
 *       once per member through copies, and its own value (with the variable unbound) means nothing.</li>
 *   <li>{@code icm:result} is never emitted. The output is passed through {@link ResultContract#filter}.</li>
 * </ul>
 */
public final class ContractExtractor {

    private ContractExtractor() {}

    /** Rule-engine marker: {@code ?resource tmf:chosenFor ?target}, the resource was chosen for the target. */
    public static final Property CHOSEN_FOR = ResourceFactory.createProperty(Namespaces.TMF + "chosenFor");

    /** Rule-engine marker: a condition template of {@code set:forAll} (tio_a_sets.rules); not reported itself. */
    static final Property FORALL_TEMPLATE = ResourceFactory.createProperty(Namespaces.TMF + "forAllTemplate");

    static final List<Resource> ELEMENT_CLASSES = List.of(
            ResourceFactory.createResource(TioNamespaces.ICM + "Intent"),
            ResourceFactory.createResource(TioNamespaces.ICM + "Expectation"),
            ResourceFactory.createResource(TioNamespaces.ICM + "Condition"),
            ResourceFactory.createResource(TioNamespaces.LOG + "Condition"));

    public static Model extract(InfModel stageB) {
        Model out = ModelFactory.createDefaultModel();
        for (Resource e : elements(stageB, ELEMENT_CLASSES)) {
            if (stageB.contains(e, FORALL_TEMPLATE)) continue;   // evaluated per member through copies, not itself
            copyValue(stageB, out, e);
            for (Statement st : stageB.listStatements(e, ResultContract.ICM_REASON, (RDFNode) null).toList()) {
                if (!st.getObject().isResource()) continue;
                Resource r = st.getObject().asResource();
                out.add(st);
                out.add(stageB.listStatements(r, RDF.type, ResultContract.ICM_REASON_CLASS));
                out.add(stageB.listStatements(r, RDFS.label, (RDFNode) null));
            }
            for (Statement st : stageB.listStatements(e, ResultContract.MET_OBSERVED, (RDFNode) null).toList()) {
                if (!st.getObject().isResource()) continue;
                out.add(st);
                ResultContract.copyObservation(stageB, out, st.getObject().asResource());
            }
        }
        for (Statement chosen : stageB.listStatements(null, CHOSEN_FOR, (RDFNode) null).toList()) {
            Resource member = chosen.getSubject();
            if (!chosen.getObject().isURIResource() || !member.isURIResource()) continue;
            Resource target = chosen.getObject().asResource();
            if (stageB.contains(target, RDFS.member, member)) out.add(target, RDFS.member, member);
        }
        out.setNsPrefixes(stageB.getNsPrefixMap());
        return ResultContract.filter(out);
    }

    /**
     * The element's value. An element with a contributor (stage A {@code tmf:hasContributor}) is valued by the rules
     * as the conjunction of its contributions, recorded as {@code tmf:result tmf:T|tmf:F}; that is its value, and an
     * {@code rdf:value} the input also gave it is ignored (otherwise contradictory input would show two values). An
     * element without a contributor keeps its input value, or the default false.
     */
    private static void copyValue(Model m, Model out, Resource e) {
        if (!m.contains(e, HAS_CONTRIBUTOR)) {
            out.add(m.listStatements(e, RDF.value, (RDFNode) null));
            return;
        }
        if (m.contains(e, RESULT, RESULT_T)) out.addLiteral(e, RDF.value, true);
        if (m.contains(e, RESULT, RESULT_F)) out.addLiteral(e, RDF.value, false);
    }

    static final Property HAS_CONTRIBUTOR = ResourceFactory.createProperty(Namespaces.TMF + "hasContributor");
    static final Property RESULT = ResourceFactory.createProperty(Namespaces.TMF + "result");
    static final Resource RESULT_T = ResourceFactory.createResource(Namespaces.TMF + "T");
    static final Resource RESULT_F = ResourceFactory.createResource(Namespaces.TMF + "F");

    /** IRI-identified instances of the classes or of any of their subclasses in {@code m}, in IRI order. */
    static SortedSet<Resource> elements(Model m, List<Resource> classes) {
        SortedSet<Resource> out = new TreeSet<>(Comparator.comparing(Resource::getURI));
        for (Resource cls : withSubclasses(m, classes)) {
            m.listSubjectsWithProperty(RDF.type, cls).forEachRemaining(s -> {
                if (s.isURIResource()) out.add(s);
            });
        }
        return out;
    }

    /** The classes and every class below them (rdfs:subClassOf), when the model holds the statements. */
    static Set<Resource> withSubclasses(Model m, List<Resource> classes) {
        Set<Resource> all = new LinkedHashSet<>();
        Deque<Resource> todo = new ArrayDeque<>(classes);
        while (!todo.isEmpty()) {
            Resource c = todo.pop();
            if (!all.add(c)) continue;
            m.listSubjectsWithProperty(RDFS.subClassOf, c).forEachRemaining(todo::push);
        }
        return all;
    }
}
