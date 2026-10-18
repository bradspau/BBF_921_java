package org.tmforum.intent.handler;

import org.apache.jena.query.Dataset;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.engine.EngineStatus;
import org.tmforum.intent.engine.EvaluationEngine;
import org.tmforum.intent.engine.EvaluationInput;
import org.tmforum.intent.engine.EvaluationOutcome;
import org.tmforum.intent.graph.repositories.IntentReportRepository;
import org.tmforum.intent.graph.repositories.IntentRepository;
import org.tmforum.intent.service.NotificationService;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EvaluationDispatcherEngineTest {

    @Test
    void notReadyEngineCreatesNoReport() {
        EvaluationEngine engine = new EvaluationEngine() {
            @Override public String name() { return "stub"; }
            @Override public EngineStatus status() { return EngineStatus.notReady("down"); }
            @Override public EvaluationOutcome evaluate(EvaluationInput in) {
                throw new AssertionError("evaluate must not be called");
            }
        };
        IntentRepository intents = mock(IntentRepository.class);
        IntentReportRepository reports = mock(IntentReportRepository.class);
        ObservationStore obs = mock(ObservationStore.class);
        HandlerStateWriter writer = mock(HandlerStateWriter.class);
        when(intents.findById("i1")).thenReturn(Map.of(
                "@type", "Intent", "lifecycleStatus", "ACTIVE",
                "expression", Map.of("@type", "TurtleExpression", "expressionValue", "@prefix : <x#> .")));
        when(obs.getObservationsTurtle(anyString())).thenReturn("");
        when(writer.heldResources(anyString())).thenReturn(List.of());

        EvaluationDispatcher d = new EvaluationDispatcher(engine, obs, writer, mock(BestEffortLimits.class),
                intents, reports, mock(NotificationService.class), mock(ReportBuilder.class), mock(Dataset.class),
                mock(org.tmforum.intent.engine.InferredGraphStore.class));
        d.scheduleEvaluation("i1");

        verify(reports, never()).create(anyString(), any());
        verify(writer, never()).writeHandlerState(anyString(), any());
    }
}
