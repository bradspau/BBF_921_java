package org.tmforum.intent.unit;

import org.apache.jena.query.Dataset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tmforum.intent.api.HealthController;
import org.tmforum.intent.engine.EngineStatus;
import org.tmforum.intent.engine.EvaluationEngine;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired MockMvc mvc;
    @MockBean Dataset dataset;
    @MockBean EvaluationEngine engine;

    @Test
    void reportsReadyEngine() throws Exception {
        when(engine.name()).thenReturn("java");
        when(engine.status()).thenReturn(EngineStatus.ok());
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.evaluator.engine").value("java"))
                .andExpect(jsonPath("$.evaluator.ready").value(true));
    }

    @Test
    void notReadyEngineDegrades() throws Exception {
        when(engine.name()).thenReturn("jena");
        when(engine.status()).thenReturn(EngineStatus.notReady("rule engine not implemented"));
        mvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.evaluator.ready").value(false))
                .andExpect(jsonPath("$.evaluator.reason").value("rule engine not implemented"));
    }
}
