package org.tmforum.intent.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tmforum.intent.handler.EvaluationDispatcher;
import org.tmforum.intent.handler.ObservationStore;
import org.tmforum.intent.service.IntentService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Extension endpoint: inject metric observations for an intent.
 *
 * POST /intent/{intentId}/observation
 * Body: { "metricUri": "http://...", "value": 42.5, "obtainedAt": "2026-..." }
 *
 * Persists the observation and triggers re-evaluation when the intent is ACTIVE.
 * This endpoint is not part of the TMF921 standard; it is used by the HSI and Access demos.
 */
@RestController
@RequestMapping("/tmf-api/intentManagement/v5/intent/{intentId:" +
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/observation")
public class ObservationController {

    private final ObservationStore observationStore;
    private final IntentService intentService;
    private final EvaluationDispatcher evaluationDispatcher;

    public ObservationController(ObservationStore observationStore,
                                  IntentService intentService,
                                  EvaluationDispatcher evaluationDispatcher) {
        this.observationStore = observationStore;
        this.intentService = intentService;
        this.evaluationDispatcher = evaluationDispatcher;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> addObservation(
            @PathVariable String intentId,
            @RequestBody Map<String, Object> body) {

        Map<String, Object> intent = intentService.findById(intentId);
        if (intent == null) throw new NoSuchElementException("Intent not found: " + intentId);

        String metricUri = (String) body.get("metricUri");
        Object valueObj = body.get("value");
        if (metricUri == null || metricUri.isBlank())
            throw new IllegalArgumentException("metricUri is required");
        if (valueObj == null)
            throw new IllegalArgumentException("value is required");

        BigDecimal value;
        try {
            value = new BigDecimal(valueObj.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("value must be numeric, got: " + valueObj);
        }

        String obtainedAt = body.containsKey("obtainedAt")
                ? (String) body.get("obtainedAt")
                : Instant.now().toString();

        String obsUri = observationStore.writeObservation(intentId, metricUri, value, obtainedAt);

        // Re-evaluate immediately when intent is ACTIVE
        if ("ACTIVE".equals(intent.get("lifecycleStatus"))) {
            evaluationDispatcher.scheduleEvaluation(intentId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", obsUri);
        response.put("intentId", intentId);
        response.put("metricUri", metricUri);
        response.put("value", value);
        response.put("obtainedAt", obtainedAt);
        return ResponseEntity.status(201).body(response);
    }
}
