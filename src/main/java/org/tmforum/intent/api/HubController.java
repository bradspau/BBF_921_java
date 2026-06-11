package org.tmforum.intent.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tmforum.intent.graph.repositories.HubRepository;

import java.util.*;

/**
 * TMF921 Hub (notification subscriber) CRUD endpoints.
 *
 * Base: /tmf-api/intentManagement/v5/hub
 *
 * POST /hub      — register a callback URL
 * GET  /hub      — list all registrations
 * GET  /hub/{id} — get single registration
 * DELETE /hub/{id} — unregister
 */
@RestController
@RequestMapping("/tmf-api/intentManagement/v5/hub")
public class HubController {

    private static final String UUID_PAT =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final HubRepository hubRepository;

    @Value("${intent.base-url}")
    private String baseUrl;

    public HubController(HubRepository hubRepository) {
        this.hubRepository = hubRepository;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(hubRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String id = UUID.randomUUID().toString();
        String callback = (String) body.get("callback");
        if (callback == null || callback.isBlank())
            throw new IllegalArgumentException("callback is required");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("href", baseUrl + "/hub/" + id);
        data.put("callback", callback);
        if (body.get("query") != null) data.put("query", body.get("query"));

        hubRepository.create(data);
        return ResponseEntity.status(201).body(data);
    }

    @GetMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable String id) {
        Map<String, Object> hub = hubRepository.findById(id);
        if (hub == null) throw new NoSuchElementException("Hub not found: " + id);
        return ResponseEntity.ok(hub);
    }

    @DeleteMapping("/{id:" + UUID_PAT + "}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!hubRepository.delete(id)) throw new NoSuchElementException("Hub not found: " + id);
        return ResponseEntity.noContent().build();
    }
}
