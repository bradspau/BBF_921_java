package org.tmforum.intent.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.handler.BestEffortLimits;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BestEffortLimitsTest {

    private BestEffortLimits limits;

    private static final String PREFIXES = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
            """;

    @BeforeEach
    void setUp() {
        limits = new BestEffortLimits();
    }

    @Test
    void noFailedConditions_returnsEmpty() {
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first _:val ; rdf:rest [ rdf:first _:bound ] .
                _:val   rdf:value "60"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                """;
        List<Map<String, Object>> conditions = List.of(
                Map.of("type", "quanatLeast", "operator", ">=",
                       "observed", new BigDecimal("60"), "bound", new BigDecimal("50"),
                       "passed", true)
        );
        Optional<String> result = limits.apply(turtle, conditions);
        assertFalse(result.isPresent());
    }

    @Test
    void failedCondition_substitutesBound_typeForm() {
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first _:val ; rdf:rest [ rdf:first _:bound ] .
                _:val   rdf:value "30"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                """;
        List<Map<String, Object>> conditions = List.of(
                Map.of("type", "quanatLeast", "operator", ">=",
                       "observed", new BigDecimal("30"), "bound", new BigDecimal("50"),
                       "passed", false)
        );
        Optional<String> result = limits.apply(turtle, conditions);
        assertTrue(result.isPresent());
        String newTurtle = result.get();
        // New bound should be "30" (the observed value)
        assertTrue(newTurtle.contains("30"), "New turtle should contain observed value as bound");
        // Old bound "50" should no longer be the bound value
        assertFalse(newTurtle.contains("\"50\"") && newTurtle.contains("_:bound"),
                "Old bound should have been replaced");
    }

    @Test
    void sameObservedAndBound_noChange() {
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first _:val ; rdf:rest [ rdf:first _:bound ] .
                _:val   rdf:value "40"^^xsd:decimal .
                _:bound rdf:value "40"^^xsd:decimal .
                """;
        List<Map<String, Object>> conditions = List.of(
                Map.of("type", "quanatLeast", "operator", ">=",
                       "observed", new BigDecimal("40"), "bound", new BigDecimal("40"),
                       "passed", false)
        );
        Optional<String> result = limits.apply(turtle, conditions);
        // Bound == observed value → no change
        assertFalse(result.isPresent());
    }

    @Test
    void nullTurtle_returnsEmpty() {
        Optional<String> result = limits.apply(null, List.of());
        assertFalse(result.isPresent());
    }

    @Test
    void emptyConditions_returnsEmpty() {
        String turtle = PREFIXES + "_:x a <http://example.org/T> .\n";
        Optional<String> result = limits.apply(turtle, List.of());
        assertFalse(result.isPresent());
    }

    @Test
    void invalidTurtle_returnsEmpty() {
        Optional<String> result = limits.apply("not turtle !!!", List.of(
                Map.of("type", "quanatLeast", "observed", new BigDecimal("30"), "passed", false)
        ));
        assertFalse(result.isPresent());
    }

    @Test
    void predicateForm_substitutesBound() {
        // Predicate form: ?cond quan:atLeast (?val ?bound)
        String turtle = PREFIXES + """
                _:cond quan:atLeast ( _:val _:bound ) .
                _:val   rdf:value "25"^^xsd:decimal .
                _:bound rdf:value "60"^^xsd:decimal .
                """;
        List<Map<String, Object>> conditions = List.of(
                Map.of("type", "atLeast", "operator", ">=",
                       "observed", new BigDecimal("25"), "bound", new BigDecimal("60"),
                       "passed", false)
        );
        Optional<String> result = limits.apply(turtle, conditions);
        assertTrue(result.isPresent());
    }

    @Test
    void nonQuantityType_ignored() {
        String turtle = PREFIXES + "_:x a <http://example.org/T> .\n";
        List<Map<String, Object>> conditions = List.of(
                Map.of("type", "DeliveryExpectation", "passed", false)
        );
        Optional<String> result = limits.apply(turtle, conditions);
        assertFalse(result.isPresent());
    }

    // ── Several conditions of the same function type ─────────────────────────────

    private static final String TWO_AT_LEAST = PREFIXES + """
            @prefix ex: <http://example.org/> .
            ex:DlCond quan:atLeast ( ex:Downstream [ rdf:value "500"^^xsd:decimal ; quan:unit "Mbps" ] ) .
            ex:UlCond quan:atLeast ( ex:Upstream   [ rdf:value "20"^^xsd:decimal  ; quan:unit "Mbps" ] ) .
            """;

    private static Map<String, Object> failedCondition(String metric, String observed, String bound) {
        return Map.of("type", "atLeast", "operator", ">=", "metric", "http://example.org/" + metric,
                "observed", new BigDecimal(observed), "bound", new BigDecimal(bound), "passed", false);
    }

    private static Map<String, Object> passedCondition(String metric, String observed, String bound) {
        return Map.of("type", "atLeast", "operator", ">=", "metric", "http://example.org/" + metric,
                "observed", new BigDecimal(observed), "bound", new BigDecimal(bound), "passed", true);
    }

    /** Reads back the bound of the condition on the given metric from the rewritten Turtle. */
    private static String boundOf(String turtle, String condition) {
        org.apache.jena.rdf.model.Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        m.read(new java.io.StringReader(turtle), null, "TURTLE");
        org.apache.jena.rdf.model.Resource list = m.getResource("http://example.org/" + condition)
                .getProperty(m.getProperty("http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/atLeast"))
                .getObject().asResource();
        org.apache.jena.rdf.model.Resource bound = list
                .getProperty(org.apache.jena.vocabulary.RDF.rest).getObject().asResource()
                .getProperty(org.apache.jena.vocabulary.RDF.first).getObject().asResource();
        return bound.getProperty(org.apache.jena.vocabulary.RDF.value).getObject().asLiteral().getLexicalForm();
    }

    @Test
    void aPassingConditionOfTheSameTypeKeepsItsBound() {
        Optional<String> result = limits.apply(TWO_AT_LEAST, List.of(
                failedCondition("Downstream", "150", "500"),
                passedCondition("Upstream", "30", "20")));
        assertTrue(result.isPresent());
        assertEquals("150", boundOf(result.get(), "DlCond"), "the failed condition takes the observed value");
        assertEquals("20", boundOf(result.get(), "UlCond"), "the passing condition keeps its bound");
    }

    @Test
    void eachFailedConditionOfTheSameTypeTakesItsOwnObservedValue() {
        Optional<String> result = limits.apply(TWO_AT_LEAST, List.of(
                failedCondition("Downstream", "150", "500"),
                failedCondition("Upstream", "12", "20")));
        assertTrue(result.isPresent());
        assertEquals("150", boundOf(result.get(), "DlCond"));
        assertEquals("12", boundOf(result.get(), "UlCond"));
    }

    @Test
    void aFailedConditionWithoutAMetricIsLeftAloneWhenSeveralConditionsHaveItsType() {
        Optional<String> result = limits.apply(TWO_AT_LEAST, List.of(
                Map.of("type", "atLeast", "operator", ">=", "observed", new BigDecimal("150"),
                       "bound", new BigDecimal("500"), "passed", false)));
        assertFalse(result.isPresent(), "two atLeast conditions: the type alone cannot say which one failed");
    }

    @Test
    void aFailedConditionWithoutAMetricIsRewrittenWhenItIsTheOnlyOneOfItsType() {
        String turtle = PREFIXES + """
                @prefix ex: <http://example.org/> .
                ex:DlCond quan:atLeast ( ex:Downstream [ rdf:value "500"^^xsd:decimal ] ) .
                ex:LatCond quan:smaller ( ex:Latency [ rdf:value "25"^^xsd:decimal ] ) .
                """;
        Optional<String> result = limits.apply(turtle, List.of(
                Map.of("type", "atLeast", "operator", ">=", "observed", new BigDecimal("150"),
                       "bound", new BigDecimal("500"), "passed", false)));
        assertTrue(result.isPresent());
        assertEquals("150", boundOf(result.get(), "DlCond"));
    }
}
