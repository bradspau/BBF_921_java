package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.graph.GraphReadOnly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The TIO ontology TTL files the rule engine reasons over (subclasses, function declarations). The files are
 * licensed and not in the repository: they live in a local {@code ontology/} directory.
 *
 * <p>Every {@code *.ttl} directly in the directory is parsed except {@code intent.ttl}, which duplicates the
 * others. The parsed model is cached per directory (loaded once) and returned read-only, so callers union it
 * with per-evaluation data instead of copying it. A missing directory, or one without TTL files, gives
 * {@link Optional#empty()} and is not cached, so files that appear later are picked up.
 */
public final class OntologyModels {

    private OntologyModels() {}

    static final String EXCLUDED = "intent.ttl";

    private static final Map<Path, Model> CACHE = new ConcurrentHashMap<>();

    /** @return the read-only ontology model for {@code dir}, or empty when it has no TTL files */
    public static Optional<Model> load(Path dir) {
        Path key = dir.toAbsolutePath().normalize();
        Model cached = CACHE.get(key);
        if (cached != null) return Optional.of(cached);
        List<Path> files = sources(key);
        if (files.isEmpty()) return Optional.empty();
        return Optional.of(CACHE.computeIfAbsent(key, k -> parse(files)));
    }

    /** The TTL files {@link #load} parses, sorted by name; empty when the directory is missing. */
    public static List<Path> sources(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".ttl"))
                    .filter(p -> !p.getFileName().toString().equals(EXCLUDED))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Model parse(List<Path> files) {
        Model m = ModelFactory.createDefaultModel();
        for (Path f : files) RDFDataMgr.read(m, f.toUri().toString());
        return ModelFactory.createModelForGraph(new GraphReadOnly(m.getGraph()));
    }
}
