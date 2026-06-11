package org.tmforum.intent.api;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Simple liveness/readiness probe. */
@RestController
public class HealthController {

    private final Dataset dataset;

    public HealthController(Dataset dataset) {
        this.dataset = dataset;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        String graphStatus = checkDataset();
        status.put("status", "UP".equals(graphStatus) ? "UP" : "DEGRADED");
        status.put("graph", graphStatus);
        int httpStatus = "UP".equals(graphStatus) ? 200 : 503;
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
