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
}
