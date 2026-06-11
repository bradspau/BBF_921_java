package org.tmforum.intent.unit;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.graph.repositories.IntentRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IntentRepositoryTest {

    private Dataset dataset;
    private IntentRepository repo;

    @BeforeEach
    void setUp() {
        // In-memory dataset for unit tests
        dataset = DatasetFactory.createTxnMem();
        repo = new IntentRepository(dataset);
    }

    @AfterEach
    void tearDown() {
        dataset.close();
    }

    private Map<String, Object> sampleIntent(String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("href", "http://localhost:8000/tmf-api/intentManagement/v5/intent/" + id);
        data.put("@type", "Intent");
        data.put("name", "Test Intent");
        data.put("lifecycleStatus", "ACKNOWLEDGED");
        data.put("creationDate", "2026-06-11T10:00:00Z");
        data.put("lastUpdate", "2026-06-11T10:00:00Z");

        Map<String, Object> expr = new LinkedHashMap<>();
        expr.put("@type", "TurtleExpression");
        expr.put("expressionValue", "@prefix log: <http://example.org/log#> .");
        data.put("expression", expr);
        return data;
    }

    @Test
    void createAndFindById() {
        String id = "550e8400-e29b-41d4-a716-446655440001";
        Map<String, Object> intent = sampleIntent(id);
        repo.create(intent);

        Map<String, Object> found = repo.findById(id);
        assertNotNull(found);
        assertEquals(id, found.get("id"));
        assertEquals("Intent", found.get("@type"));
        assertEquals("Test Intent", found.get("name"));
        assertEquals("ACKNOWLEDGED", found.get("lifecycleStatus"));
    }

    @Test
    void findById_notFound() {
        assertNull(repo.findById("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void createAndList() {
        String id1 = "550e8400-e29b-41d4-a716-446655440002";
        String id2 = "550e8400-e29b-41d4-a716-446655440003";
        repo.create(sampleIntent(id1));
        repo.create(sampleIntent(id2));

        long count = repo.count(Map.of());
        assertEquals(2, count);

        List<Map<String, Object>> items = repo.findAll(20, 0, Map.of());
        assertEquals(2, items.size());
    }

    @Test
    void update_lifecycleStatus() {
        String id = "550e8400-e29b-41d4-a716-446655440004";
        repo.create(sampleIntent(id));

        Map<String, Object> updated = repo.update(id, Map.of("lifecycleStatus", "ACTIVE"), "2026-06-11T11:00:00Z");
        assertNotNull(updated);
        assertEquals("ACTIVE", updated.get("lifecycleStatus"));
    }

    @Test
    void deleteExisting() {
        String id = "550e8400-e29b-41d4-a716-446655440005";
        repo.create(sampleIntent(id));
        assertTrue(repo.delete(id));
        assertNull(repo.findById(id));
    }

    @Test
    void deleteNonExistent() {
        assertFalse(repo.delete("00000000-0000-0000-0000-000000000099"));
    }

    @Test
    void filterByLifecycleStatus() {
        String id1 = "550e8400-e29b-41d4-a716-446655440006";
        String id2 = "550e8400-e29b-41d4-a716-446655440007";
        repo.create(sampleIntent(id1));
        Map<String, Object> active = sampleIntent(id2);
        active.put("lifecycleStatus", "ACTIVE");
        repo.create(active);

        long ackCount = repo.count(Map.of("lifecycleStatus", "ACKNOWLEDGED"));
        assertEquals(1, ackCount);

        List<Map<String, Object>> activeItems = repo.findAll(20, 0, Map.of("lifecycleStatus", "ACTIVE"));
        assertEquals(1, activeItems.size());
        assertEquals(id2, activeItems.get(0).get("id"));
    }

    @Test
    void expressionRoundTrip_turtle() {
        String id = "550e8400-e29b-41d4-a716-446655440008";
        String turtle = "@prefix log: <http://example.org/log#> .\n<x> a log:Intent .";
        Map<String, Object> intent = sampleIntent(id);
        ((Map<?, ?>) intent.get("expression")).clear();
        ((Map<String, Object>) intent.get("expression")).put("@type", "TurtleExpression");
        ((Map<String, Object>) intent.get("expression")).put("expressionValue", turtle);
        repo.create(intent);

        Map<String, Object> found = repo.findById(id);
        assertNotNull(found.get("expression"));
        assertEquals(turtle, ((Map<?, ?>) found.get("expression")).get("expressionValue"));
    }

    @Test
    void writeStateChange_doesNotThrow() {
        assertDoesNotThrow(() ->
                repo.writeStateChange("intent-1", "change-1", "ACKNOWLEDGED", "ACTIVE", "2026-06-11T10:00:00Z"));
    }
}
