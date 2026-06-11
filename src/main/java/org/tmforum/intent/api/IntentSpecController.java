package org.tmforum.intent.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tmforum.intent.service.IntentSpecService;

import java.util.*;

/**
 * TMF921 IntentSpecification CRUD endpoints.
 *
 * Base: /tmf-api/intentManagement/v5/intentSpecification
 */
@RestController
@RequestMapping("/tmf-api/intentManagement/v5/intentSpecification")
public class IntentSpecController {

    private static final int MAX_LIMIT = 100;
    private static final String UUID_PAT =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final IntentSpecService specService;

    public IntentSpecController(IntentSpecService specService) {
        this.specService = specService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String fields,
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(required = false) String name) {

        limit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Map<String, Object> filters = buildFilters(lifecycleStatus, name);
        long total = specService.count(filters);
        List<Map<String, Object>> items = FieldsFilter.apply(
                specService.findAll(limit, offset, filters), fields);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total))
                .body(items);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(specService.create(body));
    }

    @GetMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Map<String, Object>> getById(
            @PathVariable String id,
            @RequestParam(required = false) String fields) {
        Map<String, Object> spec = specService.findById(id);
        if (spec == null) throw new NoSuchElementException("IntentSpecification not found: " + id);
        return ResponseEntity.ok(FieldsFilter.apply(spec, fields));
    }

    @PatchMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Map<String, Object>> patch(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> updated = specService.update(id, body);
        if (updated == null) throw new NoSuchElementException("IntentSpecification not found: " + id);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!specService.delete(id)) throw new NoSuchElementException("IntentSpecification not found: " + id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> buildFilters(String lifecycleStatus, String name) {
        Map<String, Object> filters = new HashMap<>();
        if (lifecycleStatus != null) filters.put("lifecycleStatus", lifecycleStatus);
        if (name != null) filters.put("name", name);
        return filters;
    }
}
