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

    // ── DeliveryExpectation: deliveryType, chooseFrom, nested pools (TR290 §2.5) ─

    private static final String EX = """
            @prefix ex: <http://example.org/> .
            """;

    private Map<String, Object> deliveryCondition(EvaluationResult r) {
        return r.conditions().stream()
                .filter(c -> "DeliveryExpectation".equals(c.get("type")))
                .findFirst().orElseThrow();
    }

    @Test
    void delivery_choosesFromPool_addsChoiceToTarget_andPasses() {
        String resources = PREFIXES + EX + """
                ex:a2 a ex:Widget .
                ex:a1 a ex:Widget .
                """;
        String turtle = PREFIXES + EX + """
                ex:E a icm:DeliveryExpectation ;
                    icm:target ex:T ;
                    icm:deliveryType ex:Widget ;
                    icm:chooseFrom ex:Pool .
                ex:T a icm:Target .
                ex:Pool a icm:Target ; set:resourcesOfType ex:Widget .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, resources);
        assertEquals("Fulfilled", r.intentHandlingState());
        // deterministic: lowest URI wins
        assertEquals("http://example.org/a1", deliveryCondition(r).get("selected"));
    }

    @Test
    void delivery_poolMembersOfWrongType_fail() {
        // The pool holds things, but none is an instance of the required deliveryType
        String resources = PREFIXES + EX + """
                ex:gadget1 a ex:Gadget .
                """;
        String turtle = PREFIXES + EX + """
                ex:E a icm:DeliveryExpectation ;
                    icm:target ex:T ;
                    icm:deliveryType ex:Widget ;
                    icm:chooseFrom ex:Pool .
                ex:T a icm:Target .
                ex:Pool a icm:Target ; set:resourcesOfType ex:Gadget .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, resources);
        assertEquals("Degraded", r.intentHandlingState());
        assertTrue(r.reason().contains("no candidate of type Widget"), r.reason());
    }

    @Test
    void delivery_deliveryTypeIsIndividualNotClass_fails() {
        // pon:HSIService-style value: an individual, not a class any resource is an instance of
        String resources = PREFIXES + EX + """
                ex:u1 a ex:Port .
                ex:HSI a ex:ServiceType .
                """;
        String turtle = PREFIXES + EX + """
                ex:E a icm:DeliveryExpectation ;
                    icm:target ex:T ;
                    icm:deliveryType ex:HSI ;
                    icm:chooseFrom ex:Pool .
                ex:T a icm:Target .
                ex:Pool a icm:Target ; set:resourcesOfType ex:Port .
                """;
        assertEquals("Degraded", evaluator.evaluate(turtle, null, resources).intentHandlingState());
    }

    @Test
    void delivery_withoutDeliveryType_anyChosenResourceSatisfies() {
        String resources = PREFIXES + EX + "ex:u1 a ex:Port .\n";
        String turtle = PREFIXES + EX + """
                ex:E a icm:DeliveryExpectation ;
                    icm:target ex:T ;
                    icm:chooseFrom ex:Pool .
                ex:T a icm:Target .
                ex:Pool a icm:Target ; set:resourcesOfType ex:Port .
                """;
        assertEquals("Fulfilled", evaluator.evaluate(turtle, null, resources).intentHandlingState());
    }

    @Test
    void delivery_assertedIcmResultIsIgnored() {
        // icm:result belongs in reports; asserting it on an expectation must not force compliance
        String turtle = PREFIXES + EX + """
                ex:E a icm:DeliveryExpectation ;
                    icm:target ex:T ;
                    icm:deliveryType ex:Widget ;
                    icm:result "true"^^xsd:boolean .
                ex:T a icm:Target .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, null);
        assertEquals("Degraded", r.intentHandlingState());
    }

    @Test
    void pool_filteredByPropertyOfAnotherPool() {
        // Only tags whose svlan has serviceType ex:HSI are eligible; the lowest free one is chosen
        String resources = PREFIXES + EX + """
                ex:svHsi  a ex:SVLAN ; ex:serviceType ex:HSI .
                ex:svVoip a ex:SVLAN ; ex:serviceType ex:VoIP .
                ex:t1 a ex:CTag ; ex:fromSVLAN ex:svVoip ; ex:inUse false .
                ex:t2 a ex:CTag ; ex:fromSVLAN ex:svHsi  ; ex:inUse false .
                ex:t3 a ex:CTag ; ex:fromSVLAN ex:svHsi  ; ex:inUse true .
                """;
        String turtle = PREFIXES + EX + """
                ex:E a icm:DeliveryExpectation ;
                    icm:target ex:T ;
                    icm:deliveryType ex:CTag ;
                    icm:chooseFrom ex:TagPool .
                ex:T a icm:Target .
                ex:HsiVlans a icm:Target ;
                    set:resourcesOfType ex:SVLAN ;
                    set:resourcesWithPropertyObject ( ex:serviceType ex:HSI ) .
                ex:TagPool a icm:Target ;
                    set:resourcesOfType ex:CTag ;
                    set:resourcesWithPropertyObject ( ex:inUse false ) ;
                    set:resourcesWithPropertyObject ( ex:fromSVLAN ex:HsiVlans ) .
                """;
        EvaluationResult r = evaluator.evaluate(turtle, null, resources);
        assertEquals("Fulfilled", r.intentHandlingState());
        assertEquals("http://example.org/t2", deliveryCondition(r).get("selected"));
    }

    @Test
    void pool_filteredByEmptyOtherPool_hasNoCandidates() {
        String resources = PREFIXES + EX + """
                ex:svVoip a ex:SVLAN ; ex:serviceType ex:VoIP .
                ex:t1 a ex:CTag ; ex:fromSVLAN ex:svVoip ; ex:inUse false .
                """;
        String turtle = PREFIXES + EX + """
                ex:E a icm:DeliveryExpectation ;
                    icm:target ex:T ;
                    icm:deliveryType ex:CTag ;
                    icm:chooseFrom ex:TagPool .
                ex:T a icm:Target .
                ex:HsiVlans a icm:Target ;
                    set:resourcesOfType ex:SVLAN ;
                    set:resourcesWithPropertyObject ( ex:serviceType ex:HSI ) .
                ex:TagPool a icm:Target ;
                    set:resourcesOfType ex:CTag ;
                    set:resourcesWithPropertyObject ( ex:fromSVLAN ex:HsiVlans ) .
                """;
        assertEquals("Degraded", evaluator.evaluate(turtle, null, resources).intentHandlingState());
    }

    // ── Set functions in their TR292F form: lists, and explicit intersection ───

    private String chosenFrom(String poolTurtle, String resources) {
        String turtle = PREFIXES + EX + """
                ex:E a icm:DeliveryExpectation ;
                    icm:target ex:T ; icm:deliveryType ex:Widget ; icm:chooseFrom ex:Pool .
                ex:T a icm:Target .
                """ + poolTurtle;
        EvaluationResult r = evaluator.evaluate(turtle, null, PREFIXES + EX + resources);
        return r.isFulfilled() ? (String) deliveryCondition(r).get("selected") : "none";
    }

    @Test
    void setResourcesOfType_takesAListOfClasses() {
        assertEquals("http://example.org/w1", chosenFrom(
                "ex:Pool a icm:Target ; set:resourcesOfType ( ex:Widget ) .",
                "ex:w2 a ex:Widget . ex:w1 a ex:Widget ."));
    }

    @Test
    void setResourcesOfType_includesInstancesOfSubclasses() {
        assertEquals("http://example.org/a1", chosenFrom(
                "ex:Pool a icm:Target ; set:resourcesOfType ( ex:Widget ) .",
                "ex:BigWidget rdfs:subClassOf ex:Widget . ex:a1 a ex:BigWidget . ex:a1 a ex:Widget . ex:w2 a ex:Widget ."));
    }

    @Test
    void setIntersection_combinesFunctionNodesGivenAsAList() {
        // free widgets only: w1 is in use, w2 is free
        assertEquals("http://example.org/w2", chosenFrom("""
                ex:Pool a icm:Target ;
                    set:intersection ( [ set:resourcesOfType ( ex:Widget ) ]
                                       [ set:resourcesWithPropertyObject ( ex:inUse false ) ] ) .
                """, "ex:w1 a ex:Widget ; ex:inUse true . ex:w2 a ex:Widget ; ex:inUse false . ex:g0 a ex:Gadget ; ex:inUse false ."));
    }

    @Test
    void setUnion_andDifference() {
        String resources = "ex:w1 a ex:Widget . ex:w2 a ex:Widget . ex:g1 a ex:Gadget .";
        // widgets minus w1 -> w2
        assertEquals("http://example.org/w2", chosenFrom("""
                ex:Pool a icm:Target ;
                    set:difference ( [ set:resourcesOfType ( ex:Widget ) ]
                                     [ set:resourcesWithPropertyObject ( ex:marker ex:Skip ) ] ) .
                """, resources + " ex:w1 ex:marker ex:Skip ."));
        // union of an empty set and the widgets -> w1
        assertEquals("http://example.org/w1", chosenFrom("""
                ex:Pool a icm:Target ;
                    set:union ( [ set:resourcesOfType ( ex:Nothing ) ] [ set:resourcesOfType ( ex:Widget ) ] ) .
                """, resources));
    }

    @Test
    void ctagPool_isFilteredByTheSelectedStag_whenWrittenAsAnExplicitIntersection() {
        String resources = STAG_CTAG_RESOURCES;
        String expression = PREFIXES + EX + """
                ex:Top a icm:Expectation ; log:allOf ( ex:STagE ex:CTagE ) .

                ex:STagE a icm:DeliveryExpectation ;
                    icm:target ex:SelectedSVLAN ; icm:deliveryType ex:SVLAN ; icm:chooseFrom ex:SVLANs .
                ex:SelectedSVLAN a icm:Target .
                ex:SVLANs a icm:Target ; set:resourcesOfType ( ex:SVLAN ) .

                ex:CTagE a icm:DeliveryExpectation ;
                    icm:target ex:SelectedCTag ; icm:deliveryType ex:CTag ; icm:chooseFrom ex:CTags .
                ex:SelectedCTag a icm:Target .
                ex:CTags a icm:Target ;
                    set:intersection ( [ set:resourcesOfType ( ex:CTag ) ]
                                       [ set:resourcesWithPropertyObject ( ex:inUse false ) ]
                                       [ set:resourcesWithPropertyObject ( ex:fromSVLAN ex:SelectedSVLAN ) ] ) .
                """;
        EvaluationResult r = evaluator.evaluate(expression, null, resources);

        assertEquals("Fulfilled", r.intentHandlingState(), r.reason());
        Map<String, Object> ctag = r.conditions().stream()
                .filter(c -> CTAG_TYPE.equals(c.get("deliveryType"))).findFirst().orElseThrow();
        assertEquals("http://example.org/b1", ctag.get("selected"),
                "a0 is lowest overall but belongs to the other S-VLAN");
    }

    // ── Resources the intent already holds ────────────────────────────────────

    private static final String HELD_EXPRESSION = PREFIXES + EX + """
            ex:E a icm:DeliveryExpectation ;
                icm:target ex:T ; icm:deliveryType ex:Widget ; icm:chooseFrom ex:Pool .
            ex:T a icm:Target .
            ex:Pool a icm:Target ;
                set:resourcesOfType ex:Widget ;
                set:resourcesWithPropertyObject ( ex:inUse false ) .
            """;

    @Test
    void heldResource_isKeptEvenThoughItIsNoLongerInTheFreePool() {
        // w1 is allocated to this intent (in use); w2 is free and sorts after it
        String resources = PREFIXES + EX + """
                ex:w1 a ex:Widget ; ex:inUse true .
                ex:w2 a ex:Widget ; ex:inUse false .
                """;
        EvaluationResult r = evaluator.evaluate(HELD_EXPRESSION, null, resources, List.of("http://example.org/w1"));

        assertEquals("Fulfilled", r.intentHandlingState());
        Map<String, Object> d = deliveryCondition(r);
        assertEquals("http://example.org/w1", d.get("selected"));
        assertEquals(true, d.get("held"));
    }

    @Test
    void heldResource_keepsTheIntentFulfilledWhenThePoolIsExhausted() {
        String resources = PREFIXES + EX + """
                ex:w1 a ex:Widget ; ex:inUse true .
                ex:w2 a ex:Widget ; ex:inUse true .
                """;
        assertEquals("Degraded", evaluator.evaluate(HELD_EXPRESSION, null, resources).intentHandlingState());
        assertEquals("Fulfilled", evaluator.evaluate(HELD_EXPRESSION, null, resources,
                List.of("http://example.org/w1")).intentHandlingState());
    }

    @Test
    void heldResource_ofAnotherTypeIsNotUsed() {
        String resources = PREFIXES + EX + """
                ex:g1 a ex:Gadget ; ex:inUse true .
                ex:w2 a ex:Widget ; ex:inUse false .
                """;
        EvaluationResult r = evaluator.evaluate(HELD_EXPRESSION, null, resources, List.of("http://example.org/g1"));

        assertEquals("http://example.org/w2", deliveryCondition(r).get("selected"));
        assertNull(deliveryCondition(r).get("held"));
    }

    // ── C-tags constrained by the selected S-tag (802.1ad) ────────────────────

    private static final String STAG_CTAG_RESOURCES = PREFIXES + EX + """
            ex:svA a ex:SVLAN .
            ex:svB a ex:SVLAN .
            ex:a0 a ex:CTag ; ex:fromSVLAN ex:svB ; ex:inUse false .   # sorts first, but belongs to svB
            ex:b1 a ex:CTag ; ex:fromSVLAN ex:svA ; ex:inUse false .
            ex:b2 a ex:CTag ; ex:fromSVLAN ex:svA ; ex:inUse true .
            """;

    private static String stagThenCtag(String first, String second) {
        return PREFIXES + EX + """
                ex:Top a icm:Expectation ; log:allOf ( ex:%s ex:%s ) .

                ex:STagE a icm:DeliveryExpectation ;
                    icm:target ex:SelectedSVLAN ; icm:deliveryType ex:SVLAN ; icm:chooseFrom ex:SVLANs .
                ex:SelectedSVLAN a icm:Target .
                ex:SVLANs a icm:Target ; set:resourcesOfType ex:SVLAN .

                ex:CTagE a icm:DeliveryExpectation ;
                    icm:target ex:SelectedCTag ; icm:deliveryType ex:CTag ; icm:chooseFrom ex:CTags .
                ex:SelectedCTag a icm:Target .
                ex:CTags a icm:Target ;
                    set:resourcesOfType ex:CTag ;
                    set:resourcesWithPropertyObject ( ex:inUse false ) ;
                    set:resourcesWithPropertyObject ( ex:fromSVLAN ex:SelectedSVLAN ) .
                """.formatted(first, second);
    }

    @Test
    void ctag_isChosenFromTheSelectedStag_notJustAnyFreeCtag() {
        EvaluationResult r = evaluator.evaluate(stagThenCtag("STagE", "CTagE"), null, STAG_CTAG_RESOURCES);

        assertEquals("Fulfilled", r.intentHandlingState(), r.reason());
        Map<String, Object> stag = r.conditions().stream()
                .filter(c -> SVLAN_TYPE.equals(c.get("deliveryType"))).findFirst().orElseThrow();
        Map<String, Object> ctag = r.conditions().stream()
                .filter(c -> CTAG_TYPE.equals(c.get("deliveryType"))).findFirst().orElseThrow();
        assertEquals("http://example.org/svA", stag.get("selected"));
        // ex:a0 is the lowest free C-tag overall but belongs to svB, so it must not be chosen
        assertEquals("http://example.org/b1", ctag.get("selected"));
    }

    @Test
    void ctag_stagWithNoFreeCtags_failsEvenIfOtherStagsHaveSome() {
        // Only svB has a free C-tag; svA (chosen first, lowest name) has none
        String resources = PREFIXES + EX + """
                ex:svA a ex:SVLAN .
                ex:svB a ex:SVLAN .
                ex:a1 a ex:CTag ; ex:fromSVLAN ex:svA ; ex:inUse true .
                ex:b1 a ex:CTag ; ex:fromSVLAN ex:svB ; ex:inUse false .
                """;
        EvaluationResult r = evaluator.evaluate(stagThenCtag("STagE", "CTagE"), null, resources);

        assertEquals("Degraded", r.intentHandlingState());
        assertTrue(r.reason().contains("no candidate of type CTag"), r.reason());
    }

    @Test
    void ctag_evaluatedBeforeStag_hasNoSelectionToConstrainBy() {
        // The S-tag choice does not exist yet, so the C-tag pool is empty: order in the list matters
        EvaluationResult r = evaluator.evaluate(stagThenCtag("CTagE", "STagE"), null, STAG_CTAG_RESOURCES);
        assertEquals("Degraded", r.intentHandlingState());
    }

    private static final String SVLAN_TYPE = "http://example.org/SVLAN";
    private static final String CTAG_TYPE = "http://example.org/CTag";

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
