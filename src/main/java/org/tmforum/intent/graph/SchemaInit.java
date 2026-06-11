package org.tmforum.intent.graph;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SchemaInit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInit.class);

    private final Dataset dataset;

    @Value("${intent.resource-inventory:}")
    private String resourceInventory;

    @Value("${intent.resource-ontology:}")
    private String resourceOntology;

    public SchemaInit(Dataset dataset) {
        this.dataset = dataset;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadOntology();
        loadResources();
        log.info("Schema initialisation complete");
    }

    private void loadOntology() {
        Path ontologyDir = Path.of("ontology");
        if (!Files.exists(ontologyDir)) {
            log.warn("Ontology directory not found: {} — skipping", ontologyDir);
            return;
        }

        List<Path> ttlFiles;
        try {
            ttlFiles = Files.list(ontologyDir)
                    .filter(p -> p.toString().endsWith(".ttl"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("Could not list ontology directory: {}", e.getMessage());
            return;
        }

        if (ttlFiles.isEmpty()) {
            log.warn("No .ttl files in {} — skipping ontology load", ontologyDir);
            return;
        }

        dataset.begin(ReadWrite.WRITE);
        try {
            Model ontModel = dataset.containsNamedModel(Namespaces.ONTOLOGY_GRAPH)
                    ? dataset.getNamedModel(Namespaces.ONTOLOGY_GRAPH)
                    : ModelFactory.createDefaultModel();

            for (Path ttlFile : ttlFiles) {
                try (InputStream in = new FileInputStream(ttlFile.toFile())) {
                    RDFDataMgr.read(ontModel, in, Lang.TURTLE);
                    log.info("Loaded {} into ontology graph", ttlFile.getFileName());
                } catch (IOException e) {
                    log.warn("Skipping {}: {}", ttlFile.getFileName(), e.getMessage());
                }
            }
            dataset.addNamedModel(Namespaces.ONTOLOGY_GRAPH, ontModel);
            dataset.commit();
        } catch (Exception e) {
            dataset.abort();
            log.error("Ontology load failed: {}", e.getMessage(), e);
        } finally {
            dataset.end();
        }
    }

    private void loadResources() {
        if (resourceInventory == null || resourceInventory.isBlank()) {
            log.info("No resource inventory configured — skipping resource load");
            return;
        }

        dataset.begin(ReadWrite.WRITE);
        try {
            Model resourceModel = dataset.containsNamedModel(Namespaces.RESOURCES_GRAPH)
                    ? dataset.getNamedModel(Namespaces.RESOURCES_GRAPH)
                    : ModelFactory.createDefaultModel();

            for (String filePath : new String[]{resourceOntology, resourceInventory}) {
                if (filePath == null || filePath.isBlank()) continue;
                Path p = Path.of(filePath);
                if (!Files.exists(p)) {
                    log.warn("Resource file not found: {} — skipping", p);
                    continue;
                }
                try (InputStream in = new FileInputStream(p.toFile())) {
                    RDFDataMgr.read(resourceModel, in, Lang.TURTLE);
                    log.info("Loaded resource file {} into resources graph", p.getFileName());
                } catch (IOException e) {
                    log.warn("Skipping resource file {}: {}", p.getFileName(), e.getMessage());
                }
            }
            dataset.addNamedModel(Namespaces.RESOURCES_GRAPH, resourceModel);
            dataset.commit();
            log.info("Resource inventory loaded");
        } catch (Exception e) {
            dataset.abort();
            log.error("Resource load failed: {}", e.getMessage(), e);
        } finally {
            dataset.end();
        }
    }
}
