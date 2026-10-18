package org.tmforum.intent.engine;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.tmforum.intent.graph.TioNamespaces;

/**
 * The shared result graph contract (spec 5.2). Both engines produce a graph that, after {@link #filter},
 * holds only:
 * <ul>
 *   <li>{@code ?e rdf:value "true"|"false"^^xsd:boolean} for intent elements,</li>
 *   <li>{@code ?target rdfs:member ?resource} for chosen delivery resources,</li>
 *   <li>{@code ?e icm:reason ?r . ?r a icm:Reason ; rdfs:label "..."},</li>
 *   <li>{@code ?condition met:observed ?o} with the observation's type, metric, value and unit.</li>
 * </ul>
 * Never {@code icm:result}: that belongs to reports (TR290 section 4).
 *
 * <p>{@link #filter} and {@link #sameResults} apply to an <em>already extracted</em> result graph. The contract
 * carries no rdf:type, so they cannot tell an intent element from a function node, nor a chosen target member
 * from a seed or pool member; the extractor that builds the graph (the rule engine's ContractExtractor) owns
 * restricting {@code rdf:value} to intent elements and {@code rdfs:member} to chosen members. Within that scope
 * the filter strips everything outside the four predicate families: {@code icm:result}, non-boolean
 * {@code rdf:value} on non-observation subjects, {@code urn:tmf921:evaluation#...} helper triples, types, etc.
 *
 * <p>Observations have one shape, flattened exactly as {@link ResultGraphWriter} writes them: the observation
 * individual has {@code a met:Observation}, {@code met:observedMetric}, {@code rdf:value <number>} and
 * {@code quan:unit "<unit>"}, never a nested blank-node quantity. {@link #filter} flattens a nested quantity
 * into that shape.
 */
public final class ResultContract {

    private ResultContract() {}

    public static final Property ICM_REASON = ResourceFactory.createProperty(TioNamespaces.ICM + "reason");
    public static final Resource ICM_REASON_CLASS = ResourceFactory.createResource(TioNamespaces.ICM + "Reason");
    public static final Property MET_OBSERVED = ResourceFactory.createProperty(TioNamespaces.MET + "observed");
    public static final Resource MET_OBSERVATION = ResourceFactory.createResource(TioNamespaces.MET + "Observation");
    public static final Property MET_OBSERVED_METRIC = ResourceFactory.createProperty(TioNamespaces.MET + "observedMetric");
    public static final Property QUAN_UNIT = ResourceFactory.createProperty(TioNamespaces.QUAN + "unit");

    /** @return a new model holding only the contract triples of {@code m} */
    public static Model filter(Model m) {
        Model out = ModelFactory.createDefaultModel();
        for (StmtIterator it = m.listStatements(); it.hasNext(); ) {
            Statement st = it.next();
            Property p = st.getPredicate();
            RDFNode o = st.getObject();
            if (p.equals(RDF.value) && o.isLiteral() && XSDDatatype.XSDboolean.getURI().equals(o.asLiteral().getDatatypeURI())) {
                out.add(st);
            } else if (p.equals(RDFS.member) && st.getSubject().isURIResource() && o.isURIResource()) {
                out.add(st);
            } else if (p.equals(ICM_REASON) && o.isResource()) {
                out.add(st);
                copy(m, out, o.asResource(), RDF.type, ICM_REASON_CLASS);
                copy(m, out, o.asResource(), RDFS.label, null);
            } else if (p.equals(MET_OBSERVED) && o.isResource()) {
                out.add(st);
                Resource obs = o.asResource();
                copyObservation(m, out, obs);
            }
        }
        out.setNsPrefixes(m.getNsPrefixMap());
        return out;
    }

    /** Whether two result graphs agree on every contract triple (blank nodes compared by isomorphism). */
    public static boolean sameResults(Model a, Model b) {
        return filter(a).isIsomorphicWith(filter(b));
    }

    /** Copies an observation individual in the flattened contract shape (see class javadoc). */
    static void copyObservation(Model from, Model to, Resource obs) {
        to.add(obs, RDF.type, MET_OBSERVATION);
        copy(from, to, obs, MET_OBSERVED_METRIC, null);
        Statement vs = from.getProperty(obs, RDF.value);
        Statement us = from.getProperty(obs, QUAN_UNIT);
        RDFNode value = vs == null ? null : vs.getObject();
        RDFNode unit = us == null ? null : us.getObject();
        if (value != null && value.isResource()) {                 // nested quantity
            Resource q = value.asResource();
            Statement inner = from.getProperty(q, RDF.value);
            Statement qu = from.getProperty(q, QUAN_UNIT);
            value = inner == null ? null : inner.getObject();
            if (qu != null) unit = qu.getObject();
        }
        if (value != null && value.isLiteral()) to.add(obs, RDF.value, value);
        if (unit != null && unit.isLiteral()) to.add(obs, QUAN_UNIT, unit);
    }

    private static void copy(Model from, Model to, Resource s, Property p, RDFNode only) {
        for (StmtIterator it = from.listStatements(s, p, (RDFNode) null); it.hasNext(); ) {
            Statement st = it.next();
            if (only == null || only.equals(st.getObject())) to.add(st);
        }
    }
}
