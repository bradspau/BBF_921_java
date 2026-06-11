package org.tmforum.intent.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tmforum.intent.service.IntentReportService;
import org.tmforum.intent.service.IntentService;

import java.util.*;

/**
 * TMF921 IntentReport read-only endpoints (reports are system-generated).
 *
 * Base: /tmf-api/intentManagement/v5/intent/{intentId}/intentReport
 */
@RestController
@RequestMapping("/tmf-api/intentManagement/v5/intent/{intentId:" +
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}}/intentReport")
public class IntentReportController {

    private static final int MAX_LIMIT = 100;
    private static final String UUID_PAT =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final IntentReportService reportService;
    private final IntentService intentService;

    public IntentReportController(IntentReportService reportService, IntentService intentService) {
        this.reportService = reportService;
        this.intentService = intentService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @PathVariable String intentId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String fields) {

        if (intentService.findById(intentId) == null)
            throw new NoSuchElementException("Intent not found: " + intentId);

        limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        long total = reportService.count(intentId);
        List<Map<String, Object>> items = FieldsFilter.apply(
                reportService.findAll(intentId, limit, offset), fields);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total))
                .body(items);
    }

    @GetMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable String intentId,
            @PathVariable String id,
            @RequestParam(required = false) String fields) {
        if (intentService.findById(intentId) == null)
            throw new NoSuchElementException("Intent not found: " + intentId);
        // findById throws NoSuchElementException if not found
        Map<String, Object> report = reportService.findById(intentId, id);
        return ResponseEntity.ok(FieldsFilter.apply(report, fields));
    }
}
