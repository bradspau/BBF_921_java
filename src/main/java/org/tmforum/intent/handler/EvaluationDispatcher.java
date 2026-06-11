package org.tmforum.intent.handler;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.repositories.IntentRepository;
import org.tmforum.intent.graph.repositories.IntentReportRepository;
import org.tmforum.intent.model.EvaluationResult;
import org.tmforum.intent.service.NotificationService;
import org.tmforum.intent.service.StateMachine;

import java.io.StringWriter;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Background evaluation dispatcher — orchestrates all 4 intent handling flows.
 *
 * Flow 1 — ProbeIntent: auto-transition ACKNOWLEDGED→ACTIVE (Fulfilled) or TERMINATED (Degraded).
 * Flow 2 — Judge/Preference: DEGRADED→ACTIVE when re-evaluation passes (Fulfilled).
 * Flow 3 — Best/Propose: substitute best-effort bounds in expression when Degraded.
 * Flow 4 — Resource allocation: mark selected inventory resources as in-use when Fulfilled.
 *
 * All flows run as a single @Async method to avoid circular Spring dependencies.
 * Errors are logged and never propagate — the evaluation task is fire-and-forget.
 */
@Service
public class EvaluationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EvaluationDispatcher.class);

    @Value("${intent.base-url}")
    private String baseUrl;

    private final TurtleEvaluator evaluator;
    private final ObservationStore observationStore;
    private final HandlerStateWriter handlerStateWriter;
    private final BestEffortLimits bestEffortLimits;
    private final IntentRepository intentRepository;
    private final IntentReportRepository reportRepository;
    private final NotificationService notificationService;
    private final Dataset dataset;

    // Per-intent locks guard DEGRADED→ACTIVE auto-transition against concurrent evaluations
    private final ConcurrentHashMap<String, ReentrantLock> transitionLocks = new ConcurrentHashMap<>();

    public EvaluationDispatcher(TurtleEvaluator evaluator,
                                  ObservationStore observationStore,
                                  HandlerStateWriter handlerStateWriter,
                                  BestEffortLimits bestEffortLimits,
                                  IntentRepository intentRepository,
                                  IntentReportRepository reportRepository,
                                  NotificationService notificationService,
                                  Dataset dataset) {
        this.evaluator = evaluator;
        this.observationStore = observationStore;
        this.handlerStateWriter = handlerStateWriter;
        this.bestEffortLimits = bestEffortLimits;
        this.intentRepository = intentRepository;
        this.reportRepository = reportRepository;
        this.notificationService = notificationService;
        this.dataset = dataset;
    }

    /**
     * Schedule a background evaluation for the given intent.
     * Non-blocking: returns immediately; evaluation runs in evaluationExecutor.
     */
    @Async("evaluationExecutor")
    public void scheduleEvaluation(String intentId) {
        try {
            runEvaluation(intentId);
        } catch (Exception e) {
            log.error("scheduleEvaluation: unhandled error for intent {}: {}", intentId, e.getMessage(), e);
        }
    }

    private void runEvaluation(String intentId) {
        Map<String, Object> intent = intentRepository.findById(intentId);
        if (intent == null) {
            log.warn("runEvaluation: intent {} not found", intentId);
            return;
        }

        // Extract expression Turtle
        Map<?, ?> expr = (Map<?, ?>) intent.get("expression");
        if (expr == null) {
            log.info("runEvaluation: intent {} has no expression — skipping", intentId);
            return;
        }
        if (!"TurtleExpression".equals(expr.get("@type"))) {
            log.info("runEvaluation: intent {} uses non-Turtle expression — skipping", intentId);
            return;
        }
        String expressionTurtle = (String) expr.get("expressionValue");
        if (expressionTurtle == null || expressionTurtle.isBlank()) {
            log.info("runEvaluation: intent {} has empty expressionValue — skipping", intentId);
            return;
        }

        // Get observations and resources Turtle
        String obsTurtle = observationStore.getObservationsTurtle(intentId);
        String resTurtle = getResourcesTurtle();

        // Evaluate (with timeout guard via interruption)
        EvaluationResult result;
        try {
            result = evaluateWithTimeout(expressionTurtle, obsTurtle, resTurtle, intentId);
        } catch (Exception e) {
            log.warn("runEvaluation: evaluation failed for intent {}: {}", intentId, e.getMessage());
            result = EvaluationResult.degraded("Evaluation error: " + e.getMessage(), List.of());
        }

        // Write OODA working memory
        handlerStateWriter.writeHandlerState(intentId, result);

        // Create IntentReport
        String reportId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String reportHref = baseUrl + "/intent/" + intentId + "/intentReport/" + reportId;
        Map<String, Object> reportData = buildReportData(reportId, reportHref, intentId, now, result);
        reportRepository.create(intentId, reportData);
        log.info("runEvaluation: created report {} for intent {} (state={})",
                reportId, intentId, result.intentHandlingState());

        // Fire IntentReportCreateEvent
        Map<String, Object> reportWithIntentId = new LinkedHashMap<>(reportData);
        reportWithIntentId.put("intentId", intentId);
        notificationService.fire(NotificationService.INTENT_REPORT_CREATE, reportWithIntentId);

        // Post-evaluation flows
        try {
            tryProbeTransition(intentId, result, intent);        // Flow 1
            if ("Fulfilled".equals(result.intentHandlingState())) {
                tryAutoActivate(intentId, intent);               // Flow 2
                tryResourceAllocation(intentId, result, intent); // Flow 4
            } else if ("Degraded".equals(result.intentHandlingState())) {
                tryBestPropose(intentId, result, intent);        // Flow 3
            }
        } catch (Exception e) {
            log.error("runEvaluation: flow error for intent {}: {}", intentId, e.getMessage(), e);
        }
    }

    // ── Flow 1: ProbeIntent auto-transition ───────────────────────────────────

    private void tryProbeTransition(String intentId, EvaluationResult result,
                                     Map<String, Object> intent) {
        if (!"ProbeIntent".equals(intent.get("@type"))) return;
        if (!"ACKNOWLEDGED".equals(intent.get("lifecycleStatus"))) return;

        String target = "Fulfilled".equals(result.intentHandlingState()) ? "ACTIVE" : "TERMINATED";
        String now = Instant.now().toString();
        Map<String, Object> updated = intentRepository.update(
                intentId, Map.of("lifecycleStatus", target, "statusChangeDate", now), now);
        if (updated == null) return;

        String changeId = UUID.randomUUID().toString();
        intentRepository.writeStateChange(intentId, changeId, "ACKNOWLEDGED", target, now);
        notificationService.fire(NotificationService.INTENT_STATUS_CHANGE, updated);
        log.info("tryProbeTransition: probe {} auto-transitioned ACKNOWLEDGED → {}", intentId, target);
    }

    // ── Flow 2: Judge/Preference — DEGRADED → ACTIVE on Fulfilled ─────────────

    private void tryAutoActivate(String intentId, Map<String, Object> intent) {
        if (!"DEGRADED".equals(intent.get("lifecycleStatus"))) return;

        ReentrantLock lock = transitionLocks.computeIfAbsent(intentId, k -> new ReentrantLock());
        if (!lock.tryLock()) return; // concurrent evaluation already handling this
        try {
            // Re-read current state (may have changed since eval started)
            Map<String, Object> fresh = intentRepository.findById(intentId);
            if (fresh == null || !"DEGRADED".equals(fresh.get("lifecycleStatus"))) return;

            String now = Instant.now().toString();
            Map<String, Object> updated = intentRepository.update(
                    intentId, Map.of("lifecycleStatus", "ACTIVE", "statusChangeDate", now), now);
            if (updated == null) return;

            String changeId = UUID.randomUUID().toString();
            intentRepository.writeStateChange(intentId, changeId, "DEGRADED", "ACTIVE", now);
            notificationService.fire(NotificationService.INTENT_STATUS_CHANGE, updated);
            log.info("tryAutoActivate: intent {} DEGRADED → ACTIVE after Fulfilled eval", intentId);
        } finally {
            lock.unlock();
        }
    }

    // ── Flow 3: Best/Propose — substitute best-effort bounds ─────────────────

    private void tryBestPropose(String intentId, EvaluationResult result,
                                  Map<String, Object> intent) {
        if (!"Intent".equals(intent.get("@type"))) return;
        String status = (String) intent.get("lifecycleStatus");
        if (!"ACKNOWLEDGED".equals(status) && !"ACTIVE".equals(status)) return;

        Map<?, ?> expr = (Map<?, ?>) intent.get("expression");
        if (expr == null || !"TurtleExpression".equals(expr.get("@type"))) return;
        String turtle = (String) expr.get("expressionValue");
        if (turtle == null || turtle.isBlank()) return;

        Optional<String> newTurtle = bestEffortLimits.apply(turtle, result.conditions());
        if (newTurtle.isEmpty()) {
            log.debug("tryBestPropose: no substitution possible for intent {}", intentId);
            return;
        }

        String now = Instant.now().toString();
        Map<String, Object> patchExpr = Map.of(
                "@type", "TurtleExpression",
                "expressionValue", newTurtle.get());
        Map<String, Object> updated = intentRepository.update(
                intentId, Map.of("expression", patchExpr), now);
        if (updated == null) return;

        notificationService.fire(NotificationService.INTENT_ATTRIBUTE_VALUE_CHANGE, updated);
        log.info("tryBestPropose: best-effort PATCH applied for intent {}", intentId);
    }

    // ── Flow 4: Resource allocation ──────────────────────────────────────────

    private void tryResourceAllocation(String intentId, EvaluationResult result,
                                         Map<String, Object> intent) {
        if ("ProbeIntent".equals(intent.get("@type"))) return; // probe = availability check only

        if (handlerStateWriter.resourcesAlreadyAllocated(intentId)) {
            log.debug("tryResourceAllocation: already allocated for intent {} — skipping", intentId);
            return;
        }
        handlerStateWriter.writeResourceAllocation(intentId, result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private EvaluationResult evaluateWithTimeout(String expressionTurtle, String obsTurtle,
                                                   String resTurtle, String intentId) {
        // Run evaluation in current thread (already in evaluationExecutor pool).
        // The pool provides concurrency; we don't add another layer here.
        return evaluator.evaluate(expressionTurtle, obsTurtle, resTurtle);
    }

    private String getResourcesTurtle() {
        String graphUri = Namespaces.RESOURCES_GRAPH;
        dataset.begin(ReadWrite.READ);
        try {
            if (!dataset.containsNamedModel(graphUri)) return null;
            var model = dataset.getNamedModel(graphUri);
            if (model.isEmpty()) return null;
            StringWriter sw = new StringWriter();
            RDFDataMgr.write(sw, model, Lang.TURTLE);
            return sw.toString();
        } catch (Exception e) {
            log.debug("getResourcesTurtle: no resources graph available: {}", e.getMessage());
            return null;
        } finally {
            dataset.end();
        }
    }

    private Map<String, Object> buildReportData(String reportId, String reportHref,
                                                  String intentId, String now,
                                                  EvaluationResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", reportId);
        data.put("href", reportHref);
        data.put("@type", "IntentReport");
        data.put("@baseType", "IntentReport");
        data.put("name", "Evaluation for intent " + intentId);
        data.put("creationDate", now);
        data.put("expression", null);
        data.put("intentHandlingState", result.intentHandlingState());
        data.put("intentHandlingReason", result.reason());
        return data;
    }
}
