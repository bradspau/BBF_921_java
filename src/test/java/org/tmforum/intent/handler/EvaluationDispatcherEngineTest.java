package org.tmforum.intent.handler;

import org.apache.jena.query.Dataset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

    private static final org.apache.jena.rdf.model.Model GRAPH = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();

    private static EvaluationEngine readyEngine(org.apache.jena.rdf.model.Model graph) {
        return new EvaluationEngine() {
            @Override public String name() { return "stub"; }
            @Override public EngineStatus status() { return EngineStatus.ok(); }
            @Override public EvaluationOutcome evaluate(EvaluationInput in) {
                return new EvaluationOutcome(graph, org.tmforum.intent.model.EvaluationResult.fulfilled(List.of()));
            }
        };
    }

    private record Fixture(EvaluationDispatcher dispatcher, IntentReportRepository reports, HandlerStateWriter writer,
                           org.tmforum.intent.engine.InferredGraphStore store) {}

    private Fixture fixture(org.apache.jena.rdf.model.Model graph) {
        return fixture(graph, "@prefix : <x#> .", mock(ReportBuilder.class), mock(NotificationService.class));
    }

    private Fixture fixture(org.apache.jena.rdf.model.Model graph, String expression, ReportBuilder builder,
                            NotificationService notifications) {
        IntentRepository intents = mock(IntentRepository.class);
        IntentReportRepository reports = mock(IntentReportRepository.class);
        ObservationStore obs = mock(ObservationStore.class);
        HandlerStateWriter writer = mock(HandlerStateWriter.class);
        org.tmforum.intent.engine.InferredGraphStore store = mock(org.tmforum.intent.engine.InferredGraphStore.class);
        when(intents.findById("i1")).thenReturn(Map.of(
                "@type", "Intent", "lifecycleStatus", "ACTIVE",
                "expression", Map.of("@type", "TurtleExpression", "expressionValue", expression)));
        when(obs.getObservationsTurtle(anyString())).thenReturn("");
        when(writer.heldResources(anyString())).thenReturn(List.of());
        EvaluationDispatcher d = new EvaluationDispatcher(readyEngine(graph), obs, writer, mock(BestEffortLimits.class),
                intents, reports, notifications, builder, mock(Dataset.class), store);
        return new Fixture(d, reports, writer, store);
    }

    @Test
    void storesTheResultGraphBeforeCreatingTheReport() {
        Fixture f = fixture(GRAPH);
        f.dispatcher().scheduleEvaluation("i1");
        InOrder order = inOrder(f.store(), f.reports());
        order.verify(f.store()).replace("i1", GRAPH);
        order.verify(f.reports()).create(eq("i1"), any());
    }

    @Test
    void aFailedGraphWriteDoesNotDegradeTheResult() {
        Fixture f = fixture(GRAPH);
        doThrow(new RuntimeException("tdb down")).when(f.store()).replace(anyString(), any());
        f.dispatcher().scheduleEvaluation("i1");
        ArgumentCaptor<org.tmforum.intent.model.EvaluationResult> r =
                ArgumentCaptor.forClass(org.tmforum.intent.model.EvaluationResult.class);
        verify(f.writer()).writeHandlerState(eq("i1"), r.capture());
        org.junit.jupiter.api.Assertions.assertEquals("Fulfilled", r.getValue().intentHandlingState());
        verify(f.reports()).create(eq("i1"), any());
    }

    @Test
    void aNullGraphIsNotStored() {
        Fixture f = fixture(null);
        f.dispatcher().scheduleEvaluation("i1");
        verify(f.store(), never()).replace(anyString(), any());
        verify(f.reports()).create(eq("i1"), any());
    }

    private static org.apache.jena.rdf.model.Model nonEmptyGraph() {
        org.apache.jena.rdf.model.Model m = org.apache.jena.rdf.model.ModelFactory.createDefaultModel();
        m.add(m.createResource("http://x/a"), org.apache.jena.vocabulary.RDF.value, m.createTypedLiteral(true));
        return m;
    }

    @Test
    void malformedExpressionTurtleStillCreatesTheReportAndFiresTheEvent() {
        ReportBuilder builder = mock(ReportBuilder.class);
        NotificationService notifications = mock(NotificationService.class);
        Fixture f = fixture(nonEmptyGraph(), "this is { not turtle .", builder, notifications);
        f.dispatcher().scheduleEvaluation("i1");
        verify(f.reports()).create(eq("i1"), any());
        verify(notifications).fire(eq(NotificationService.INTENT_REPORT_CREATE), any());
        verify(builder, never()).build(any(), any(), any());
    }

    @Test
    void anEmptyResultGraphBuildsNoTr290ReportButTheNormalReportIsCreated() {
        ReportBuilder builder = mock(ReportBuilder.class);
        Fixture f = fixture(GRAPH, "@prefix : <x#> .", builder, mock(NotificationService.class));
        f.dispatcher().scheduleEvaluation("i1");
        verify(builder, never()).build(any(), any(), any());
        verify(f.reports()).create(eq("i1"), any());
    }

    @Test
    void aNormalIntentStillBuildsTheTr290Report() {
        ReportBuilder builder = mock(ReportBuilder.class);
        Fixture f = fixture(nonEmptyGraph(), "@prefix : <x#> .", builder, mock(NotificationService.class));
        f.dispatcher().scheduleEvaluation("i1");
        verify(builder).build(any(), any(), any());
        verify(f.reports()).create(eq("i1"), any());
    }
}
