package org.tmforum.intent.unit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.tmforum.intent.engine.*;
import org.tmforum.intent.model.EvaluationResult;

import java.util.List;

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

    private long warns() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    @Test
    void identicalGraphsLogNothing() {
        var p = outcome(graph(true), "Fulfilled");
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), p),
                stub("jena", EngineStatus.ok(), outcome(graph(true), "Fulfilled")));
        assertSame(p, e.evaluate(IN));
        assertTrue(appender.list.isEmpty());
    }

    @Test
    void differentGraphsLogWarningWithCounts() {
        var p = outcome(graph(true, true), "Fulfilled");
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), p),
                stub("jena", EngineStatus.ok(), outcome(graph(true, false), "Degraded")));
        assertSame(p, e.evaluate(IN));
        assertEquals(1, warns());
        String msg = appender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("2"), msg);
        assertTrue(msg.contains("urn:e1"), msg);
    }

    @Test
    void logsAtMostFiveDifferingTriples() {
        boolean[] many = new boolean[10];
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), outcome(graph(many), "x")),
                stub("jena", EngineStatus.ok(), outcome(graph(), "x")));
        e.evaluate(IN);
        String msg = appender.list.get(0).getFormattedMessage();
        assertTrue(msg.contains("urn:e"), msg);
        int shown = msg.split("urn:e", -1).length - 1;
        assertTrue(shown <= 5, msg);
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
    void throwingSecondaryIsTolerated() {
        var p = outcome(graph(true), "Fulfilled");
        var e = new ComparingEngine(stub("java", EngineStatus.ok(), p), stub("jena", EngineStatus.ok(), null));
        assertSame(p, e.evaluate(IN));
        assertEquals(1, appender.list.size());
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
