package org.tmforum.intent.unit;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.update.UpdateExecutionFactory;
import org.apache.jena.update.UpdateFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.SchemaInit;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchemaInitTest {

    private static final String PON = "http://broadband-forum.org/ont/pon-resource#";

    @TempDir
    Path tmp;

    private Dataset dataset;
    private SchemaInit schemaInit;

    @BeforeEach
    void setUp() throws Exception {
        dataset = DatasetFactory.createTxnMem();
        Path inventory = tmp.resolve("inventory.ttl");
        Files.writeString(inventory, """
                @prefix pon: <%s> .
                pon:UNI-1 a pon:UNIPort ; pon:inUse false .
                """.formatted(PON));
        schemaInit = new SchemaInit(dataset);
        ReflectionTestUtils.setField(schemaInit, "resourceInventory", inventory.toString());
    }

    @AfterEach
    void tearDown() {
        dataset.close();
    }

    private boolean ask(String body) {
        String sparql = "PREFIX pon: <" + PON + "> ASK { GRAPH <" + Namespaces.RESOURCES_GRAPH + "> { " + body + " } }";
        dataset.begin(ReadWrite.READ);
        try {
            return QueryExecutionFactory.create(sparql, dataset).execAsk();
        } finally {
            dataset.end();
        }
    }

    @Test
    void restartDoesNotResetAllocatedResources() {
        schemaInit.run(null);
        assertEquals(true, ask("pon:UNI-1 pon:inUse false"));

        // Handler write-back: allocate the UNI to an intent
        dataset.begin(ReadWrite.WRITE);
        try {
            UpdateExecutionFactory.create(UpdateFactory.create("""
                    PREFIX pon: <%s>
                    WITH <%s>
                    DELETE { pon:UNI-1 pon:inUse false }
                    INSERT { pon:UNI-1 pon:inUse true ; pon:assignedToService "intent-a" }
                    WHERE { pon:UNI-1 pon:inUse false }
                    """.formatted(PON, Namespaces.RESOURCES_GRAPH)), dataset).execute();
            dataset.commit();
        } finally {
            dataset.end();
        }

        // Application restart on the same store
        schemaInit.run(null);

        assertFalse(ask("pon:UNI-1 pon:inUse false"), "allocated UNI must not be marked free again");
        assertEquals(true, ask("pon:UNI-1 pon:inUse true ; pon:assignedToService \"intent-a\""));
    }
}
