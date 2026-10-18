package org.tmforum.intent.model;

import java.util.List;
import java.util.Set;

/**
 * Everything needed to build a TR290 intent report from one evaluation.
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
