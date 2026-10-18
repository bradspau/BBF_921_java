package org.tmforum.intent.model;

import java.util.List;
import java.util.Map;

/**
 * Private trace of the Java engine: read only by {@code ResultGraphWriter}, which turns it into the shared
 * result graph. Reports are built from that graph, not from this type.
 *
 * Evaluation outcome for one intent element (intent, expectation or condition), kept as a tree that
 * mirrors the intent so a TR290 report can be built from it.
 *
 * @param uri           IRI of the intent element (only IRIs can be reported on, TR290 §4.2)
 * @param kind          Intent | DeliveryExpectation | PropertyExpectation | ReportingExpectation | Expectation | Condition
 * @param passed        truth value of the element (compliance for intents and expectations)
 * @param children      elements this result was derived from (icm:resultFrom)
 * @param details       leaf detail maps (observed/bound/selected/error ...); empty for composite elements
 * @param targetUri     icm:target of an expectation, or null
 * @param targetCount   number of members the target had when evaluated
 * @param targetMembers up to {@link #MAX_TARGET_MEMBERS} member IRIs of that target
 */
public record ElementResult(
        String uri,
        String kind,
        boolean passed,
        List<ElementResult> children,
        List<Map<String, Object>> details,
        String targetUri,
        int targetCount,
        List<String> targetMembers
) {
    public static final int MAX_TARGET_MEMBERS = 50;
}
