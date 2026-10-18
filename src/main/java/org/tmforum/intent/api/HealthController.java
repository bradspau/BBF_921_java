package org.tmforum.intent.api;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.tmforum.intent.engine.EngineStatus;
import org.tmforum.intent.engine.EvaluationEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Simple liveness/readiness probe. */
@RestController
public class HealthController {

    private final Dataset dataset;

    private final EvaluationEngine engine;

    public HealthController(Dataset dataset, EvaluationEngine engine) {
        this.dataset = dataset;
        this.engine = engine;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        String graphStatus = checkDataset();
        EngineStatus es = engine.status();
        Map<String, Object> evaluator = new LinkedHashMap<>();
        evaluator.put("engine", engine.name());
        evaluator.put("ready", es.ready());
        if (es.reason() != null) evaluator.put("reason", es.reason());
        boolean up = "UP".equals(graphStatus) && es.ready();
        status.put("status", up ? "UP" : "DEGRADED");
        status.put("graph", graphStatus);
        status.put("evaluator", evaluator);
        int httpStatus = up ? 200 : 503;
        return ResponseEntity.status(httpStatus).body(status);
    }

    private String checkDataset() {
        try {
            dataset.begin(ReadWrite.READ);
            dataset.end();
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
