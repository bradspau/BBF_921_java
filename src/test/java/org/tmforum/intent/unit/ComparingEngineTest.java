package org.tmforum.intent.unit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.tmforum.intent.engine.*;
import org.tmforum.intent.model.EvaluationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComparingEngineTest {

    private static final EvaluationInput IN = new EvaluationInput("i1", "", "", "", List.of());
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(ComparingEngine.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() { logger.detachAppender(appender); }

    private static Model graph(boolean... values) {
        Model m = ModelFactory.createDefaultModel();
        for (int i = 0; i < values.length; i++) {
            m.add(m.createResource("urn:e" + i), RDF.value,
                    m.createTypedLiteral(String.valueOf(values[i]), XSDDatatype.XSDboolean));
        }
        return m;
    }

    private static EvaluationOutcome outcome(Model g, String state) {
        return new EvaluationOutcome(g, new EvaluationResult(state, null, List.of()));
    }

    private static EvaluationEngine stub(String name, EngineStatus st, EvaluationOutcome out) {
        return new EvaluationEngine() {
            public String name() { return name; }
            public EngineStatus status() { return st; }
            public EvaluationOutcome evaluate(EvaluationInput in) {
                if (out == null) throw new IllegalStateException("boom");
                return out;
            }
        };
    }

    @Test
    void identicalGraphsLogNothing() {
        var p = outcome(graph(true), "Fulfilled");
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), p),
                stub("jena", EngineStatus.ok(), outcome(graph(true), "Fulfilled")));
        assertSame(p, e.evaluate(IN));
        assertTrue(appender.list.isEmpty());
    }

    private ILoggingEvent onlyEvent() {
        assertEquals(1, appender.list.size(), () -> "log: " + appender.list);
        return appender.list.get(0);
    }

    private static Model withReason(Model m, String element, String label) {
        Resource r = m.createResource();
        r.addProperty(RDF.type, m.createResource("http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/Reason"));
        r.addProperty(RDFS.label, label);
        m.add(m.createResource(element), m.createProperty("http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/reason"), r);
        return m;
    }

    private static final String V = "<" + RDF.value.getURI() + ">";
    private static final String TRUE = "\"true\"^^http://www.w3.org/2001/XMLSchema#boolean";
    private static final String FALSE = "\"false\"^^http://www.w3.org/2001/XMLSchema#boolean";

    @Test
    void differentGraphsLogOneWarningWithCountsAndTheDifferingTriples() {
        var p = outcome(graph(true, true), "Fulfilled");
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), p),
                stub("jena", EngineStatus.ok(), outcome(graph(true, false), "Fulfilled")));
        assertSame(p, e.evaluate(IN));
        ILoggingEvent ev = onlyEvent();
        assertEquals(Level.WARN, ev.getLevel());
        String msg = ev.getFormattedMessage();
        assertTrue(msg.startsWith("compare: 'java' (2 triples) and 'jena' (2 triples) differ for intent i1; "
                + "first differences: "), msg);
        assertTrue(msg.contains("< <urn:e1> " + V + " " + TRUE), msg);
        assertTrue(msg.contains("> <urn:e1> " + V + " " + FALSE), msg);
        assertFalse(msg.contains("urn:e0"), "a triple on both sides is not a difference: " + msg);
    }

    @Test
    void blankNodesWithTheSameContentAreNotReportedAsDifferences() {
        Model a = withReason(graph(true, false), "urn:e1", "5 >= 10 is false");
        Model b = withReason(graph(true, true), "urn:e1", "5 >= 10 is false");
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), outcome(a, "x")),
                stub("jena", EngineStatus.ok(), outcome(b, "x")));
        e.evaluate(IN);
        String msg = onlyEvent().getFormattedMessage();
        assertTrue(msg.endsWith("first differences: [< <urn:e1> " + V + " " + FALSE + ", > <urn:e1> " + V + " " + TRUE
                + "]"), msg);
    }

    @Test
    void differingBlankNodesAreShownByTheirLabel() {
        Model a = withReason(graph(false), "urn:e0", "first reason");
        Model b = withReason(graph(false), "urn:e0", "second reason");
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), outcome(a, "x")),
                stub("jena", EngineStatus.ok(), outcome(b, "x")));
        e.evaluate(IN);
        String msg = onlyEvent().getFormattedMessage();
        assertTrue(msg.contains("[\"first reason\"]"), msg);
        assertTrue(msg.contains("[\"second reason\"]"), msg);
    }

    @Test
    void atMostFiveDifferencesAreShownAcrossBothDirections() {
        Model a = graph(true, true, true);                    // urn:e0..e2 true
        Model b = graph(false, false, false);                 // urn:e0..e2 false
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), outcome(a, "x")),
                stub("jena", EngineStatus.ok(), outcome(b, "x")));
        e.evaluate(IN);
        String msg = onlyEvent().getFormattedMessage();
        long primaryOnly = msg.split("< <urn:e", -1).length - 1;
        long secondaryOnly = msg.split("> <urn:e", -1).length - 1;
        assertEquals(3, primaryOnly, msg);
        assertEquals(2, secondaryOnly, "the cap of five is shared by both directions: " + msg);
    }

    // ── handler result projection ────────────────────────────────────────────

    private static Map<String, Object> cond(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static EvaluationOutcome handler(String state, String reason, Map<String, Object>... conditions) {
        return new EvaluationOutcome(graph(true), new EvaluationResult(state, reason, List.of(conditions)));
    }

    private String compareHandlers(EvaluationOutcome java, EvaluationOutcome jena) {
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), java), stub("jena", EngineStatus.ok(), jena));
        assertSame(java, e.evaluate(IN));
        if (appender.list.isEmpty()) return null;
        ILoggingEvent ev = onlyEvent();
        assertEquals(Level.WARN, ev.getLevel());
        return ev.getFormattedMessage();
    }

    @Test
    void identicalHandlerResultsLogNothing() {
        var c = cond("type", "atLeast", "operator", ">=", "observed", 5, "bound", 10, "passed", false);
        assertNull(compareHandlers(handler("Degraded", "r", c), handler("Degraded", "r", new LinkedHashMap<>(c))));
    }

    @Test
    void keysNoConsumerReadsAreNotCompared() {
        assertNull(compareHandlers(
                handler("Fulfilled", null, cond("type", "atLeast", "passed", true, "metric", "urn:m1")),
                handler("Fulfilled", null, cond("type", "atLeast", "passed", true, "metric", "urn:m2",
                        "element", "urn:x"))));
    }

    @Test
    void differentStateLogsOneWarningWithTheFirstDifference() {
        assertEquals("compare: 'java' and 'jena' handler results differ for intent i1; first difference: "
                        + "state: java=Fulfilled, jena=Degraded",
                compareHandlers(handler("Fulfilled", null), handler("Degraded", null)));
    }

    @Test
    void differentReasonLogsOneWarningWithTheFirstDifference() {
        assertEquals("compare: 'java' and 'jena' handler results differ for intent i1; first difference: "
                        + "reason: java='Conditions not met: a', jena='Conditions not met: b'",
                compareHandlers(handler("Degraded", "Conditions not met: a"), handler("Degraded", "Conditions not met: b")));
    }

    @Test
    void differentConditionEntryLogsTheFirstDifferingEntryWithoutBlankNodes() {
        var same = cond("type", "atLeast", "passed", true);
        String msg = compareHandlers(
                handler("Fulfilled", null, same, cond("type", "DeliveryExpectation", "passed", true,
                        "selected", "http://example.org/UNI-1")),
                handler("Fulfilled", null, same, cond("type", "DeliveryExpectation", "passed", true,
                        "selected", "http://example.org/CTAG-1")));
        assertEquals("compare: 'java' and 'jena' handler results differ for intent i1; first difference: "
                + "condition 2: java={passed=true | selected=http://example.org/UNI-1 | type=DeliveryExpectation}, "
                + "jena={passed=true | selected=http://example.org/CTAG-1 | type=DeliveryExpectation}", msg);
        assertFalse(msg.contains("_:"), msg);
    }

    @Test
    void differentConditionCountIsADifference() {
        String msg = compareHandlers(handler("Fulfilled", null, cond("type", "atLeast", "passed", true)),
                handler("Fulfilled", null));
        assertTrue(msg.endsWith("first difference: condition count: java=1, jena=0"), msg);
    }

    @Test
    void graphAndHandlerDifferencesLogOneWarningEach() {
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), outcome(graph(true), "Fulfilled")),
                stub("jena", EngineStatus.ok(), outcome(graph(false), "Degraded")));
        e.evaluate(IN);
        assertEquals(2, appender.list.size(), () -> "log: " + appender.list);
        assertTrue(appender.list.stream().allMatch(ev -> ev.getLevel() == Level.WARN));
        assertTrue(appender.list.get(0).getFormattedMessage().contains("triples) differ"));
        assertTrue(appender.list.get(1).getFormattedMessage().contains("handler results differ"));
    }

    @Test
    void notReadySecondarySkippedWithOneLogLine() {
        var p = outcome(graph(true), "Fulfilled");
        var sec = new EvaluationEngine() {
            public String name() { return "jena"; }
            public EngineStatus status() { return EngineStatus.notReady("not built"); }
            public EvaluationOutcome evaluate(EvaluationInput in) { fail("must not run"); return null; }
        };
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), p), sec);
        assertSame(p, e.evaluate(IN));
        assertEquals(1, appender.list.size());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("not built"));
    }

    @Test
    void throwingSecondaryIsToleratedWithOneWarning() {
        var p = outcome(graph(true), "Fulfilled");
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), p), stub("jena", EngineStatus.ok(), null));
        assertSame(p, e.evaluate(IN));
        ILoggingEvent ev = onlyEvent();
        assertEquals(Level.WARN, ev.getLevel());
        assertEquals("compare: secondary engine 'jena' failed for intent i1: java.lang.IllegalStateException: boom",
                ev.getFormattedMessage());
    }

    @Test
    void throwingSecondaryStatusIsToleratedWithOneWarning() {
        var p = outcome(graph(true), "Fulfilled");
        var sec = new EvaluationEngine() {
            public String name() { return "jena"; }
            public EngineStatus status() { throw new IllegalStateException("status boom"); }
            public EvaluationOutcome evaluate(EvaluationInput in) { fail("must not run"); return null; }
        };
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), p), sec);
        assertSame(p, e.evaluate(IN));
        ILoggingEvent ev = onlyEvent();
        assertEquals(Level.WARN, ev.getLevel());
        assertTrue(ev.getFormattedMessage().contains("status boom"), ev.getFormattedMessage());
    }

    @Test
    void primaryExceptionPropagatesAndTheSecondaryIsNotRun() {
        var sec = new EvaluationEngine() {
            public String name() { return "jena"; }
            public EngineStatus status() { fail("must not be asked"); return null; }
            public EvaluationOutcome evaluate(EvaluationInput in) { fail("must not run"); return null; }
        };
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), null), sec);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> e.evaluate(IN));
        assertEquals("boom", ex.getMessage());
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void nameAndStatusFollowPrimary() {
        var e = new ComparingEngine(stub("java", EngineStatus.notReady("x"), null),
                stub("jena", EngineStatus.ok(), null));
        assertEquals("compare", e.name());
        assertFalse(e.status().ready());
        assertEquals("x", e.status().reason());
    }
}
