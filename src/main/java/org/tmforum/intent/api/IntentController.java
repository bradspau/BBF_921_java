package org.tmforum.intent.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tmforum.intent.service.IntentService;

import java.util.*;

/**
 * TMF921 Intent and ProbeIntent CRUD endpoints.
 *
 * Base: /tmf-api/intentManagement/v5/intent
 */
@RestController
@RequestMapping("/tmf-api/intentManagement/v5/intent")
public class IntentController {

    private static final int MAX_LIMIT = 100;
    private static final String UUID_PAT =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final IntentService intentService;

    public IntentController(IntentService intentService) {
        this.intentService = intentService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(name = "@type", required = false) String type) {

        limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Map<String, Object> filters = buildFilters(lifecycleStatus, type);
        long total = intentService.count(filters);
        List<Map<String, Object>> items = FieldsFilter.apply(
                intentService.findAll(limit, offset, filters), fields);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total))
                .body(items);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(intentService.create(body));
    }

    @GetMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable String id,
            @RequestParam(required = false) String fields) {
        Map<String, Object> intent = intentService.findById(id);
        if (intent == null) throw new NoSuchElementException("Intent not found: " + id);
        return ResponseEntity.ok(FieldsFilter.apply(intent, fields));
    }

    @PatchMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Map<String, Object>> patch(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> updated = intentService.update(id, body);
        if (updated == null) throw new NoSuchElementException("Intent not found: " + id);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!intentService.delete(id)) throw new NoSuchElementException("Intent not found: " + id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> buildFilters(String lifecycleStatus, String type) {
        Map<String, Object> filters = new HashMap<>();
        if (lifecycleStatus != null) filters.put("lifecycleStatus", lifecycleStatus);
        if (type != null) filters.put("@type", type);
        return filters;
    }
}
