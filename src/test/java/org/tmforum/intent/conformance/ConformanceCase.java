package org.tmforum.intent.conformance;

import org.apache.jena.rdf.model.Model;

import java.util.Set;

/**
 * One conformance case (spec 5.6): an intent expression with its observations, resources and held resources,
 * and the result graph (spec 5.2) every engine must produce for it.
 *
 * @param name          the case directory name
 * @param expression    expression Turtle ({@code expression.ttl})
 * @param observations  observation Turtle ({@code observations.ttl}), or null
 * @param resources     resource inventory Turtle ({@code resources.ttl}), or null
 * @param held          resources the intent already holds ({@code held.txt}, one IRI per line)
 * @param expected      the contract graph ({@code expected.ttl}), or null when not yet recorded
 * @param expectedJena  the specification-based expectation where the Java engine deviates
 *                      ({@code expected.jena.ttl}), or null
 * @param needsOntology whether the case needs the licensed ontology files under {@code ontology/}
 */
public record ConformanceCase(String name, String expression, String observations, String resources,
                              Set<String> held, Model expected, Model expectedJena, boolean needsOntology) {

    @Override
    public String toString() {
        return name;
    }
}
