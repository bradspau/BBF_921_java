package org.tmforum.intent.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.handler.TurtleEvaluator;
import org.tmforum.intent.model.EvaluationResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TurtleEvaluatorTest {

    private TurtleEvaluator evaluator;

    // Common TIO prefixes used across test Turtle snippets
    private static final String PREFIXES = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
            @prefix log:  <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
            @prefix set:  <http://tio.models.tmforum.org/tio/v3.6.0/SetOperators/> .
            @prefix icm:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/> .
            @prefix met:  <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
            @prefix iv:   <http://tio.models.tmforum.org/tio/v3.6.0/IntentValidityOntology/> .
            """;

    @BeforeEach
    void setUp() {
        evaluator = new TurtleEvaluator();
    }

    // ── Empty / error cases ───────────────────────────────────────────────────

    @Test
    void emptyExpression_returnsDegraded() {
        EvaluationResult r = evaluator.evaluate("", null, null);
        assertEquals("Degraded", r.intentHandlingState());
    }

    @Test
    void nullExpression_returnsDegraded() {
        EvaluationResult r = evaluator.evaluate(null, null, null);
        assertEquals("Degraded", r.intentHandlingState());
    }

    @Test
    void invalidTurtle_returnsDegraded() {
        EvaluationResult r = evaluator.evaluate("this is not turtle", null, null);
        assertEquals("Degraded", r.intentHandlingState());
        assertNotNull(r.reason());
        assertTrue(r.reason().contains("parse error") || r.reason().contains("Turtle"));
    }

    @Test
    void noConditions_returnsDegraded() {
        String turtle = PREFIXES + "<http://ex.org/intent> a <http://ex.org/Intent> .\n";
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
        assertNotNull(r.reason());
        assertTrue(r.reason().contains("No quantity conditions"));
    }

    // ── Quantity: type-form, direct literal value ──────────────────────────────

    @Test
    void atLeast_typeForm_passes() {
        // _:cond a quan:quanatLeast ; rdf:first _:val ; rdf:rest [ rdf:first _:bound ]
        // _:val rdf:value "50"^^xsd:decimal ; _:bound rdf:value "40"^^xsd:decimal
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first _:val ;
                    rdf:rest [ rdf:first _:bound ] .
                _:val  rdf:value "50"^^xsd:decimal .
                _:bound rdf:value "40"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
        assertFalse(r.conditions().isEmpty());
        assertEquals(true, r.conditions().get(0).get("passed"));
    }

    @Test
    void atLeast_typeForm_fails() {
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first _:val ;
                    rdf:rest [ rdf:first _:bound ] .
                _:val  rdf:value "30"^^xsd:decimal .
                _:bound rdf:value "40"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
        assertEquals(false, r.conditions().get(0).get("passed"));
    }

    @Test
    void smaller_typeForm_passes() {
        String turtle = PREFIXES + """
                _:cond a quan:quansmaller ;
                    rdf:first _:val ;
                    rdf:rest [ rdf:first _:bound ] .
                _:val  rdf:value "20"^^xsd:decimal .
                _:bound rdf:value "30"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    // ── Quantity: predicate-form normalization ────────────────────────────────

    @Test
    void atLeast_predicateForm_passes() {
        // Predicate form: _:cond quan:atLeast ( _:val _:bound )
        String turtle = PREFIXES + """
                _:cond quan:atLeast ( _:val _:bound ) .
                _:val  rdf:value "55"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    @Test
    void atLeast_predicateForm_fails() {
        String turtle = PREFIXES + """
                _:cond quan:atLeast ( _:val _:bound ) .
                _:val  rdf:value "30"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
    }

    // ── Quantity: metric injection via observations ───────────────────────────

    @Test
    void metricInjection_patternA_passes() {
        // Pattern A: direct metric URI as rdf:first
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first <http://example.org/metrics/latency> ;
                    rdf:rest [ rdf:first _:bound ] .
                _:bound rdf:value "10"^^xsd:decimal .
                """;
        // Observation Turtle
        String obs = PREFIXES + """
                <http://example.org/obs/1> a met:Observation ;
                    met:observedMetric <http://example.org/metrics/latency> ;
                    rdf:value "50"^^xsd:decimal ;
                    met:obtainedAt "2026-06-11T10:00:00Z"^^xsd:dateTime .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, obs, null);
        assertEquals("Fulfilled", r.intentHandlingState());
        assertEquals(new java.math.BigDecimal("50"), r.conditions().get(0).get("observed"));
    }

    @Test
    void metricInjection_noObservation_degraded() {
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first <http://example.org/metrics/latency> ;
                    rdf:rest [ rdf:first _:bound ] .
                _:bound rdf:value "100"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
        assertTrue(r.reason().contains("no observation"));
    }

    @Test
    void metricInjection_latestObservationUsed() {
        // Two observations; the later one (80) should be used
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first <http://example.org/metrics/bandwidth> ;
                    rdf:rest [ rdf:first _:bound ] .
                _:bound rdf:value "70"^^xsd:decimal .
                """;
        String obs = PREFIXES + """
                <http://example.org/obs/old> a met:Observation ;
                    met:observedMetric <http://example.org/metrics/bandwidth> ;
                    rdf:value "60"^^xsd:decimal ;
                    met:obtainedAt "2026-06-10T08:00:00Z"^^xsd:dateTime .
                <http://example.org/obs/new> a met:Observation ;
                    met:observedMetric <http://example.org/metrics/bandwidth> ;
                    rdf:value "80"^^xsd:decimal ;
                    met:obtainedAt "2026-06-11T10:00:00Z"^^xsd:dateTime .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, obs, null);
        assertEquals("Fulfilled", r.intentHandlingState());
        assertEquals(new java.math.BigDecimal("80"), r.conditions().get(0).get("observed"));
    }

    // ── Logical combinators ───────────────────────────────────────────────────

    @Test
    void allOf_bothPass() {
        String turtle = PREFIXES + """
                _:root log:allOf (
                    _:c1
                    _:c2
                ) .
                _:c1 a quan:quanatLeast ; rdf:first _:v1 ; rdf:rest [ rdf:first _:b1 ] .
                _:v1 rdf:value "50"^^xsd:decimal .
                _:b1 rdf:value "40"^^xsd:decimal .
                _:c2 a quan:quansmaller ; rdf:first _:v2 ; rdf:rest [ rdf:first _:b2 ] .
                _:v2 rdf:value "20"^^xsd:decimal .
                _:b2 rdf:value "30"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
        assertEquals(2, r.conditions().size());
    }

    @Test
    void allOf_oneFails() {
        String turtle = PREFIXES + """
                _:root log:allOf (
                    _:c1
                    _:c2
                ) .
                _:c1 a quan:quanatLeast ; rdf:first _:v1 ; rdf:rest [ rdf:first _:b1 ] .
                _:v1 rdf:value "50"^^xsd:decimal .
                _:b1 rdf:value "40"^^xsd:decimal .
                _:c2 a quan:quanatLeast ; rdf:first _:v2 ; rdf:rest [ rdf:first _:b2 ] .
                _:v2 rdf:value "10"^^xsd:decimal .
                _:b2 rdf:value "40"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
        long failCount = r.conditions().stream()
                .filter(c -> !Boolean.TRUE.equals(c.get("passed"))).count();
        assertEquals(1, failCount);
    }

    @Test
    void anyOf_onePassSuffices() {
        String turtle = PREFIXES + """
                _:root log:anyOf (
                    _:c1
                    _:c2
                ) .
                _:c1 a quan:quanatLeast ; rdf:first _:v1 ; rdf:rest [ rdf:first _:b1 ] .
                _:v1 rdf:value "10"^^xsd:decimal .
                _:b1 rdf:value "40"^^xsd:decimal .
                _:c2 a quan:quanatLeast ; rdf:first _:v2 ; rdf:rest [ rdf:first _:b2 ] .
                _:v2 rdf:value "50"^^xsd:decimal .
                _:b2 rdf:value "40"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    @Test
    void noneOf_bothFail_passes() {
        String turtle = PREFIXES + """
                _:root log:noneOf (
                    _:c1
                    _:c2
                ) .
                _:c1 a quan:quanatLeast ; rdf:first _:v1 ; rdf:rest [ rdf:first _:b1 ] .
                _:v1 rdf:value "10"^^xsd:decimal .
                _:b1 rdf:value "40"^^xsd:decimal .
                _:c2 a quan:quanatLeast ; rdf:first _:v2 ; rdf:rest [ rdf:first _:b2 ] .
                _:v2 rdf:value "5"^^xsd:decimal .
                _:b2 rdf:value "40"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    // ── inRange ───────────────────────────────────────────────────────────────

    @Test
    void inRange_passes() {
        String turtle = PREFIXES + """
                _:cond a quan:quaninRange ;
                    rdf:first _:val ;
                    rdf:rest [ rdf:first _:lo ; rdf:rest [ rdf:first _:hi ] ] .
                _:val rdf:value "50"^^xsd:decimal .
                _:lo  rdf:value "40"^^xsd:decimal .
                _:hi  rdf:value "60"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    @Test
    void inRange_fails() {
        String turtle = PREFIXES + """
                _:cond a quan:quaninRange ;
                    rdf:first _:val ;
                    rdf:rest [ rdf:first _:lo ; rdf:rest [ rdf:first _:hi ] ] .
                _:val rdf:value "80"^^xsd:decimal .
                _:lo  rdf:value "40"^^xsd:decimal .
                _:hi  rdf:value "60"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
    }

    // ── set:resourcesOfType + DeliveryExpectation ────────────────────────────

    @Test
    void deliveryExpectation_withMatchingResources_passes() {
        // Resource inventory: two resources typed as ex:Widget
        String resources = PREFIXES + """
                @prefix ex: <http://example.org/> .
                ex:res1 a ex:Widget .
                ex:res2 a ex:Widget .
                """;
        // Expression: target set via set:resourcesOfType, expect at least 1 ex:Widget
        String turtle = PREFIXES + """
                @prefix ex: <http://example.org/> .
                _:exp a icm:DeliveryExpectation ;
                    icm:target _:targets ;
                    icm:deliveryType ex:Widget .
                _:targets set:resourcesOfType ex:Widget .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, resources);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    @Test
    void deliveryExpectation_noMatchingResources_fails() {
        String turtle = PREFIXES + """
                @prefix ex: <http://example.org/> .
                _:exp a icm:DeliveryExpectation ;
                    icm:target _:targets ;
                    icm:deliveryType ex:Widget .
                _:targets set:resourcesOfType ex:Widget .
                """;
        // No resources Turtle provided → no matching members
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
    }

    // ── set:setforAll ─────────────────────────────────────────────────────────

    @Test
    void setForAll_emptyContainer_vacuouslyTrue() {
        String turtle = PREFIXES + """
                _:fa a set:setforAll ;
                    rdf:first <http://example.org/var> ;
                    rdf:rest [ rdf:first _:container ;
                               rdf:rest [ rdf:first _:cond ] ] .
                _:cond a quan:quanatLeast ;
                    rdf:first <http://example.org/var> ;
                    rdf:rest [ rdf:first _:bound ] .
                _:bound rdf:value "50"^^xsd:decimal .
                """;
        // No rdfs:member on _:container → vacuously true
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    // ── log:match ─────────────────────────────────────────────────────────────

    @Test
    void logMatch_tripleExists_passes() {
        String turtle = PREFIXES + """
                @prefix ex: <http://example.org/> .
                _:root log:match ( ex:res1  rdf:type  ex:Widget ) .
                ex:res1 a ex:Widget .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    @Test
    void logMatch_tripleAbsent_fails() {
        String turtle = PREFIXES + """
                @prefix ex: <http://example.org/> .
                _:root log:match ( ex:res1  rdf:type  ex:Widget ) .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
    }

    // ── Validity gate ─────────────────────────────────────────────────────────

    @Test
    void validityGate_invalid_fails() {
        String turtle = PREFIXES + """
                _:root log:allOf ( _:c1 ) .
                _:c1 iv:ivvalidIf _:ctx ;
                     a quan:quanatLeast ;
                     rdf:first _:val ;
                     rdf:rest [ rdf:first _:bound ] .
                _:val   rdf:value "99"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                _:ctx iv:ivisValid "false"^^xsd:boolean .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
    }

    @Test
    void validityGate_valid_passes() {
        String turtle = PREFIXES + """
                _:root log:allOf ( _:c1 ) .
                _:c1 iv:ivvalidIf _:ctx ;
                     a quan:quanatLeast ;
                     rdf:first _:val ;
                     rdf:rest [ rdf:first _:bound ] .
                _:val   rdf:value "99"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                _:ctx iv:ivisValid "true"^^xsd:boolean .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Fulfilled", r.intentHandlingState());
    }

    // ── Diagnostics shape ─────────────────────────────────────────────────────

    @Test
    void conditionDiagnostics_includeObservedAndBound() {
        String turtle = PREFIXES + """
                _:cond a quan:quanatLeast ;
                    rdf:first _:val ;
                    rdf:rest [ rdf:first _:bound ] .
                _:val  rdf:value "30"^^xsd:decimal .
                _:bound rdf:value "50"^^xsd:decimal .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
        Map<String, Object> c = r.conditions().get(0);
        assertEquals(">=", c.get("operator"));
        assertNotNull(c.get("observed"));
        assertNotNull(c.get("bound"));
        assertEquals(false, c.get("passed"));
    }
}
