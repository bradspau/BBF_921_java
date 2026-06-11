package org.tmforum.intent.unit;

import org.junit.jupiter.api.Test;
import org.tmforum.intent.graph.GraphNodes;
import org.tmforum.intent.graph.Namespaces;

import static org.junit.jupiter.api.Assertions.*;

class GraphNodesTest {

    private static final String UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String BASE = "http://tmforum.org/api/v5";

    @Test
    void intentGraphUri() {
        assertEquals(BASE + "/intents/" + UUID, GraphNodes.intentGraphUri(UUID));
    }

    @Test
    void reportGraphUri() {
        assertEquals(BASE + "/reports/" + UUID, GraphNodes.reportGraphUri(UUID));
    }

    @Test
    void specGraphUri() {
        assertEquals(BASE + "/intentSpecifications/" + UUID, GraphNodes.specGraphUri(UUID));
    }

    @Test
    void handlerStateGraphUri() {
        assertEquals(BASE + "/intents/" + UUID + "/handlerState", GraphNodes.handlerStateGraphUri(UUID));
    }

    @Test
    void observationsGraphUri() {
        assertEquals(BASE + "/intents/" + UUID + "/observations", GraphNodes.observationsGraphUri(UUID));
    }

    @Test
    void intentNodeSameAsGraphUri() {
        assertEquals(GraphNodes.intentGraphUri(UUID), GraphNodes.intentNode(UUID));
    }

    @Test
    void localName_extractsFragment() {
        assertEquals("Intent", GraphNodes.localName("http://tmforum.org/api/v5/Intent"));
    }

    @Test
    void localName_extractsHashFragment() {
        assertEquals("Intent", GraphNodes.localName("http://example.org/onto#Intent"));
    }

    @Test
    void localName_nullSafe() {
        assertNull(GraphNodes.localName(null));
    }

    @Test
    void namespacesHubsGraph() {
        assertEquals("http://tmforum.org/api/v5/hubs", Namespaces.HUBS_GRAPH);
    }

    @Test
    void namespacesResourcesGraph() {
        assertEquals("http://tmforum.org/api/v5/resources", Namespaces.RESOURCES_GRAPH);
    }

    @Test
    void namespacesOntologyGraph() {
        assertEquals("http://tmforum.org/api/v5/ontology", Namespaces.ONTOLOGY_GRAPH);
    }

    @Test
    void sparqlPrefixesContainRequiredPrefixes() {
        String prefixes = Namespaces.SPARQL_PREFIXES;
        assertTrue(prefixes.contains("PREFIX rdf:"));
        assertTrue(prefixes.contains("PREFIX xsd:"));
        assertTrue(prefixes.contains("PREFIX tmf:"));
        assertTrue(prefixes.contains("PREFIX dcterms:"));
    }
}
