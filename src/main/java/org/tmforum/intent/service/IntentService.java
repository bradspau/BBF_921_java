package org.tmforum.intent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tmforum.intent.graph.repositories.IntentRepository;
import org.tmforum.intent.handler.EvaluationDispatcher;

import java.time.Instant;
import java.util.*;

/**
 * Business-logic layer for Intent and ProbeIntent resources.
 *
 * Responsibilities:
 * - Set server-side fields (id, href, creationDate, lastUpdate)
 * - Strip non-patchable fields from POST/PATCH
 * - Validate and apply FSM transitions
 * - Write StateChange audit records
 * - Fire notifications
 * - Schedule evaluations on state-activate triggers
 */
@Service
public class IntentService {

    private static final Set<String> SERVER_FIELDS =
            Set.of("id", "href", "creationDate", "lastUpdate");

    private final IntentRepository intentRepository;
    private final NotificationService notificationService;
    private final EvaluationDispatcher evaluationDispatcher;

    @Value("${intent.base-url}")
    private String baseUrl;

    public IntentService(IntentRepository intentRepository,
                          NotificationService notificationService,
                          EvaluationDispatcher evaluationDispatcher) {
        this.intentRepository = intentRepository;
        this.notificationService = notificationService;
        this.evaluationDispatcher = evaluationDispatcher;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    public Map<String, Object> create(Map<String, Object> body) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> data = new LinkedHashMap<>(body);
        SERVER_FIELDS.forEach(data::remove); // strip any supplied server-only fields

        data.put("id", id);
        data.put("href", baseUrl + "/intent/" + id);
        data.put("creationDate", now);
        data.put("lastUpdate", now);

        // Default type and lifecycleStatus
        data.putIfAbsent("@type", "Intent");
        data.putIfAbsent("@baseType", "Intent");
        data.put("lifecycleStatus", "ACKNOWLEDGED"); // always initialise to ACKNOWLEDGED

        intentRepository.create(data);

        String eventType = "ProbeIntent".equals(data.get("@type"))
                ? NotificationService.INTENT_CREATE
                : NotificationService.INTENT_CREATE;
        notificationService.fire(eventType, data);

        // Schedule background evaluation for new intents
        evaluationDispatcher.scheduleEvaluation(id);

        return data;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Map<String, Object> findById(String id) {
        return intentRepository.findById(id);
    }

    public List<Map<String, Object>> findAll(int limit, int offset, Map<String, String> filters) {
        return intentRepository.findAll(limit, offset, filters);
    }

    public long count(Map<String, String> filters) {
        return intentRepository.count(filters);
    }

    // ── Update (PATCH) ────────────────────────────────────────────────────────

    /**
     * Apply a partial update (RFC 7386 merge-patch semantics).
     * Server-side fields are stripped from the patch body before applying.
     * If lifecycleStatus changes, the FSM is validated and a StateChange record written.
     */
    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Map<String, Object> existing = intentRepository.findById(id);
        if (existing == null) return null;

        Map<String, Object> cleanPatch = new LinkedHashMap<>(patch);
        SERVER_FIELDS.forEach(cleanPatch::remove);

        String now = Instant.now().toString();
        String newStatus = (String) cleanPatch.get("lifecycleStatus");
        String oldStatus = (String) existing.get("lifecycleStatus");

        if (newStatus != null && !newStatus.equals(oldStatus)) {
            StateMachine.validateTransition(oldStatus, newStatus); // throws on invalid
            cleanPatch.put("statusChangeDate", now);
        }

        Map<String, Object> updated = intentRepository.update(id, cleanPatch, now);
        if (updated == null) return null;

        if (newStatus != null && !newStatus.equals(oldStatus)) {
            String changeId = UUID.randomUUID().toString();
            intentRepository.writeStateChange(id, changeId, oldStatus, newStatus, now);
            notificationService.fire(NotificationService.INTENT_STATUS_CHANGE, updated);

            // Re-evaluate when intent is activated or re-activated
            if ("ACTIVE".equals(newStatus)) {
                evaluationDispatcher.scheduleEvaluation(id);
            }
        } else if (cleanPatch.containsKey("expression")) {
            notificationService.fire(NotificationService.INTENT_ATTRIBUTE_VALUE_CHANGE, updated);
            // Re-evaluate when expression is patched and intent is active
            if ("ACTIVE".equals(updated.get("lifecycleStatus"))) {
                evaluationDispatcher.scheduleEvaluation(id);
            }
        } else {
            notificationService.fire(NotificationService.INTENT_ATTRIBUTE_VALUE_CHANGE, updated);
        }

        return updated;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public boolean delete(String id) {
        Map<String, Object> existing = intentRepository.findById(id);
        if (existing == null) return false;
        boolean deleted = intentRepository.delete(id);
        if (deleted) {
            notificationService.fire(NotificationService.INTENT_DELETE,
                    Map.of("id", id, "@type", existing.getOrDefault("@type", "Intent")));
        }
        return deleted;
    }
}
