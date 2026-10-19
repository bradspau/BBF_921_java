package org.tmforum.intent.model;

import java.util.List;
import java.util.Set;

/**
 * Private trace of the Java engine: read only by {@code ResultGraphWriter}, which turns it into the shared
 * result graph. Reports are built from that graph, not from this type.
 *
 * The tree of element results from one evaluation, with the reporting scope, from which the Java engine writes the
 * result graph.
 *
 * @param elements            top-level element results (the intent, when the expression has one)
 * @param scope               IRIs of the intent elements the reporting expectation(s) ask for (TR290 §4.2);
 *                            the intent itself is always reported
 * @param includeObservations true if a reporting expectation is an icm:ObservationReportingExpectation
 */
public record ReportTree(
        List<ElementResult> elements,
        Set<String> scope,
        boolean includeObservations
) {}
