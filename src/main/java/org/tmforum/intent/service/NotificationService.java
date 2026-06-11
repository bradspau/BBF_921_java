package org.tmforum.intent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.tmforum.intent.graph.repositories.HubRepository;

import java.time.Instant;
import java.util.*;

/**
 * Async notification fan-out for TMF921 hub subscriptions.
 *
 * Notifications are fire-and-forget: delivery is attempted after the originating
 * API write succeeds, and failures are logged without failing the operation.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    // All supported TMF921A event types
    public static final String INTENT_CREATE                      = "IntentCreateEvent";
    public static final String INTENT_DELETE                      = "IntentDeleteEvent";
    public static final String INTENT_STATUS_CHANGE               = "IntentStatusChangeEvent";
    public static final String INTENT_ATTRIBUTE_VALUE_CHANGE      = "IntentAttributeValueChangeEvent";
    public static final String INTENT_REPORT_CREATE               = "IntentReportCreateEvent";
    public static final String INTENT_REPORT_DELETE               = "IntentReportDeleteEvent";
    public static final String INTENT_SPEC_CREATE                 = "IntentSpecificationCreateEvent";
    public static final String INTENT_SPEC_DELETE                 = "IntentSpecificationDeleteEvent";
    public static final String INTENT_SPEC_ATTRIBUTE_VALUE_CHANGE = "IntentSpecificationAttributeValueChangeEvent";
    public static final String INTENT_SPEC_STATUS_CHANGE          = "IntentSpecificationStatusChangeEvent";

    // Resource key nested inside "event" per TMF921 OAS payload shape
    private static final Map<String, String> EVENT_RESOURCE_KEY = Map.ofEntries(
            Map.entry(INTENT_CREATE,                      "intent"),
            Map.entry(INTENT_DELETE,                      "intent"),
            Map.entry(INTENT_STATUS_CHANGE,               "intent"),
            Map.entry(INTENT_ATTRIBUTE_VALUE_CHANGE,      "intent"),
            Map.entry(INTENT_REPORT_CREATE,               "intentReport"),
            Map.entry(INTENT_REPORT_DELETE,               "intentReport"),
            Map.entry(INTENT_SPEC_CREATE,                 "intentSpecification"),
            Map.entry(INTENT_SPEC_DELETE,                 "intentSpecification"),
            Map.entry(INTENT_SPEC_ATTRIBUTE_VALUE_CHANGE, "intentSpecification"),
            Map.entry(INTENT_SPEC_STATUS_CHANGE,          "intentSpecification")
    );

    private final HubRepository hubRepository;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public NotificationService(HubRepository hubRepository, ObjectMapper objectMapper) {
        this.hubRepository = hubRepository;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Schedule async fan-out to all registered hubs.
     * Non-blocking: returns immediately; delivery runs in evaluationExecutor.
     */
    @Async("evaluationExecutor")
    public void fire(String eventType, Map<String, Object> resource) {
        List<Map<String, Object>> hubs = hubRepository.findAll();
        if (hubs.isEmpty()) return;

        Map<String, Object> payload = buildPayload(eventType, resource);

        for (Map<String, Object> hub : hubs) {
            String callback = (String) hub.get("callback");
            if (callback == null || callback.isBlank()) continue;
            try {
                webClient.post()
                        .uri(callback)
                        .header("Content-Type", "application/json")
                        .bodyValue(objectMapper.writeValueAsString(payload))
                        .retrieve()
                        .toBodilessEntity()
                        .block(java.time.Duration.ofSeconds(10));
            } catch (Exception e) {
                log.warn("Notification delivery failed to {}: {}", callback, e.getMessage());
            }
        }
    }

    private Map<String, Object> buildPayload(String eventType, Map<String, Object> resource) {
        String resourceKey = EVENT_RESOURCE_KEY.getOrDefault(eventType, "resource");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId",       UUID.randomUUID().toString());
        payload.put("correlationId", UUID.randomUUID().toString());
        payload.put("eventTime",     Instant.now().toString());
        payload.put("eventType",     eventType);
        payload.put("@type",         eventType);
        payload.put("@baseType",     "Event");
        payload.put("event",         Map.of(resourceKey, resource));
        return payload;
    }
}
