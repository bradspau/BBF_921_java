package org.tmforum.intent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tmforum.intent.graph.repositories.IntentSpecRepository;

import java.time.Instant;
import java.util.*;

/**
 * Business-logic layer for IntentSpecification resources.
 * Sets server-side fields, strips non-patchable fields, delegates to repository.
 */
@Service
public class IntentSpecService {

    private static final Set<String> SERVER_FIELDS = Set.of("id", "href", "lastUpdate");

    private final IntentSpecRepository specRepository;

    @Value("${intent.base-url}")
    private String baseUrl;

    public IntentSpecService(IntentSpecRepository specRepository) {
        this.specRepository = specRepository;
    }

    public Map<String, Object> create(Map<String, Object> body) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> data = new LinkedHashMap<>(body);
        SERVER_FIELDS.forEach(data::remove);

        data.put("id", id);
        data.put("href", baseUrl + "/intentSpecification/" + id);
        data.put("lastUpdate", now);

        data.putIfAbsent("@type", "IntentSpecification");
        data.putIfAbsent("@baseType", "IntentSpecification");

        specRepository.create(data);
        return data;
    }

    public Map<String, Object> findById(String id) {
        return specRepository.findById(id);
    }

    public List<Map<String, Object>> findAll(int limit, int offset, Map<String, Object> filters) {
        return specRepository.findAll(limit, offset, filters);
    }

    public long count(Map<String, Object> filters) {
        return specRepository.count(filters);
    }

    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Map<String, Object> existing = specRepository.findById(id);
        if (existing == null) return null;

        Map<String, Object> cleanPatch = new LinkedHashMap<>(patch);
        SERVER_FIELDS.forEach(cleanPatch::remove);

        String now = Instant.now().toString();
        return specRepository.update(id, cleanPatch, now);
    }

    public boolean delete(String id) {
        return specRepository.delete(id);
    }
}
