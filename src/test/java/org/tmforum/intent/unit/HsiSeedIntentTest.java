package org.tmforum.intent.unit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.handler.TurtleEvaluator;
import org.tmforum.intent.model.EvaluationResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Evaluates the real seed intent against the real PON inventory (the Access HSI demo data). */
class HsiSeedIntentTest {

    private static final String PON = "http://broadband-forum.org/ont/pon-resource#";
    private static final String BBF = "http://broadband-forum.org/Intent#";

    private TurtleEvaluator evaluator;
    private String intent;
    private String inventory;

    @BeforeEach
    void setUp() throws Exception {
        evaluator = new TurtleEvaluator();
        intent = Files.readString(Path.of("seed_data/hsionlyintent_v0.5.ttl"));
        inventory = Files.readString(Path.of("BBF_access/pon_resource_onto.ttl"))
                + "\n" + Files.readString(Path.of("BBF_access/pon_resource_data.ttl"));
    }

    private static String observations(String... metricAndValue) {
        StringBuilder sb = new StringBuilder("""
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                @prefix met: <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
                """);
        for (int i = 0; i < metricAndValue.length; i += 2) {
            sb.append("<http://example.org/obs/").append(i).append("> a met:Observation ;\n")
              .append("  met:observedMetric <").append(BBF).append(metricAndValue[i]).append("> ;\n")
              .append("  rdf:value \"").append(metricAndValue[i + 1]).append("\"^^xsd:decimal ;\n")
              .append("  met:obtainedAt \"2026-06-11T10:00:0").append(i / 2).append("Z\"^^xsd:dateTime .\n");
        }
        return sb.toString();
    }

    private static final String[] ALL_METRICS_OK = {
            "DownstreamBandwidthMetric", "150", "UpstreamBandwidthMetric", "30",
            "LatencyMetric", "8", "JitterMetric", "1.2", "PacketLossMetric", "0.02"};

    private List<Map<String, Object>> deliveries(EvaluationResult r) {
        return r.conditions().stream().filter(c -> "DeliveryExpectation".equals(c.get("type"))).toList();
    }

    @Test
    void allMetricsOk_fulfilled_withUniStagAndCtagSelected() {
        EvaluationResult r = evaluator.evaluate(intent, observations(ALL_METRICS_OK), inventory);

        assertEquals("Fulfilled", r.intentHandlingState(), r.reason());
        assertEquals(8, r.conditions().size(), "3 delivery + 5 metric conditions");

        Map<String, String> selectedByType = new java.util.HashMap<>();
        for (Map<String, Object> d : deliveries(r)) {
            assertTrue(Boolean.TRUE.equals(d.get("passed")));
            selectedByType.put((String) d.get("deliveryType"), (String) d.get("selected"));
        }
        assertEquals(PON + "UNI-001-1", selectedByType.get(PON + "UNIPort"));
        assertEquals(PON + "SVLAN-N-HSI", selectedByType.get(PON + "SVLANAllocation"));
        assertEquals(PON + "CTAG-N-001", selectedByType.get(PON + "CTAGAllocation"));
    }

    @Test
    void noObservations_degraded_butDeliveriesStillSelect() {
        EvaluationResult r = evaluator.evaluate(intent, null, inventory);

        assertEquals("Degraded", r.intentHandlingState());
        assertEquals(3, deliveries(r).stream().filter(d -> Boolean.TRUE.equals(d.get("passed"))).count());
        assertTrue(r.reason().contains("no observation: DownstreamBandwidthMetric"), r.reason());
    }

    @Test
    void noHsiSVlans_stagAndCtagCannotBeDelivered() {
        // Same inventory but with the HSI service type removed from every S-VLAN
        String noHsi = inventory.replace("pon:serviceType   pon:HSIService ;", "pon:serviceType   pon:VoIPService ;");
        EvaluationResult r = evaluator.evaluate(intent, observations(ALL_METRICS_OK), noHsi);

        assertEquals("Degraded", r.intentHandlingState());
        assertEquals(1, deliveries(r).stream().filter(d -> Boolean.TRUE.equals(d.get("passed"))).count(),
                "only the UNI delivery still passes");
    }

    @Test
    void ctag_comesFromTheSelectedStagVlan_evenWhenOtherVlansHaveLowerNamedTags() {
        // Rename the South C-tags so they sort before every North one; the S-tag chosen is still SVLAN-N-HSI,
        // so the C-tag must still come from it
        String renamed = inventory.replace("pon:CTAG-S-", "pon:CTAG-A-");
        EvaluationResult r = evaluator.evaluate(intent, observations(ALL_METRICS_OK), renamed);

        assertEquals("Fulfilled", r.intentHandlingState(), r.reason());
        String ctag = deliveries(r).stream()
                .filter(d -> (PON + "CTAGAllocation").equals(d.get("deliveryType")))
                .map(d -> (String) d.get("selected")).findFirst().orElseThrow();
        assertEquals(PON + "CTAG-N-001", ctag);
    }

    /** Inventory as the handler leaves it after allocating UNI-001-1 and CTAG-N-001 to "intent-a". */
    private String allocatedToIntentA() {
        return inventory
                .replaceFirst("(pon:UNI-001-1\\s[^.]*?)pon:inUse\\s+false", "$1pon:inUse true ;\n pon:assignedToService \"intent-a\"")
                .replaceFirst("(pon:CTAG-N-001\\s[^.]*?)pon:inUse\\s+false", "$1pon:inUse true ;\n pon:assignedToService \"intent-a\"");
    }

    private static final List<String> HELD_BY_A = List.of(PON + "UNI-001-1", PON + "CTAG-N-001");

    @Test
    void reevaluationAfterAllocation_keepsTheAllocatedResources() {
        EvaluationResult r = evaluator.evaluate(intent, observations(ALL_METRICS_OK), allocatedToIntentA(), HELD_BY_A);

        assertEquals("Fulfilled", r.intentHandlingState(), r.reason());
        Map<String, String> sel = new java.util.HashMap<>();
        deliveries(r).forEach(d -> sel.put((String) d.get("deliveryType"), (String) d.get("selected")));
        assertEquals(PON + "UNI-001-1", sel.get(PON + "UNIPort"));
        assertEquals(PON + "CTAG-N-001", sel.get(PON + "CTAGAllocation"));
    }

    @Test
    void withoutHoldings_reevaluationAfterAllocationPicksDifferentResources() {
        // Documents the problem the holdings solve
        EvaluationResult r = evaluator.evaluate(intent, observations(ALL_METRICS_OK), allocatedToIntentA());
        String uni = deliveries(r).stream().filter(d -> (PON + "UNIPort").equals(d.get("deliveryType")))
                .map(d -> (String) d.get("selected")).findFirst().orElseThrow();
        assertEquals(PON + "UNI-001-2", uni);
    }

    @Test
    void reevaluation_staysFulfilledWhenOtherIntentsTookEveryOtherPort() {
        String othersTookTheRest = allocatedToIntentA().replaceAll(
                "(pon:UNI-(?!001-1)\\d+-\\d\\s[^.]*?)pon:inUse\\s+false", "$1pon:inUse true");

        assertEquals("Degraded", evaluator.evaluate(intent, observations(ALL_METRICS_OK), othersTookTheRest)
                .intentHandlingState());
        assertEquals("Fulfilled", evaluator.evaluate(intent, observations(ALL_METRICS_OK), othersTookTheRest, HELD_BY_A)
                .intentHandlingState());
    }

    @Test
    void everyUniInUse_uniDeliveryFails() {
        String allUsed = inventory.replace("pon:inUse         false ;", "pon:inUse         true ;");
        EvaluationResult r = evaluator.evaluate(intent, observations(ALL_METRICS_OK), allUsed);

        assertEquals("Degraded", r.intentHandlingState());
        assertTrue(r.reason().contains("no candidate of type UNIPort"), r.reason());
    }
}
