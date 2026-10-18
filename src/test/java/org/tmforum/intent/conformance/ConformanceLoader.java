package org.tmforum.intent.conformance;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads the conformance corpus from {@code conformance/<case>/} on the test classpath. Entries that are not
 * directories (such as a deviation register) are skipped.
 */
public final class ConformanceLoader {

    /** The corpus root on the classpath. */
    public static final String ROOT = "conformance";

    private ConformanceLoader() {}

    /** @return every case, sorted by name */
    public static List<ConformanceCase> loadAll() {
        Path root = root();
        Set<String> registered = DeviationRegister.caseNames(root);
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).sorted().map(d -> load(d, registered)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the case in {@code dir} */
    public static ConformanceCase load(Path dir) {
        return load(dir, DeviationRegister.caseNames(dir.getParent()));
    }

    private static ConformanceCase load(Path dir, Set<String> registered) {
        if (Files.isRegularFile(dir.resolve("expected.jena.ttl")) && !registered.contains(dir.getFileName().toString()))
            throw new IllegalStateException(dir.getFileName()
                    + ": expected.jena.ttl has no entry in DEVIATIONS.md (add a 'case: " + dir.getFileName() + "' line)");
        String expression = read(dir.resolve("expression.ttl"));
        if (expression == null) throw new IllegalStateException(dir.getFileName() + ": expression.ttl is missing");
        Properties props = new Properties();
        String p = read(dir.resolve("case.properties"));
        if (p != null) {
            try (Reader r = new StringReader(p)) {
                props.load(r);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new ConformanceCase(dir.getFileName().toString(), expression,
                read(dir.resolve("observations.ttl")), read(dir.resolve("resources.ttl")),
                held(read(dir.resolve("held.txt"))), model(dir.resolve("expected.ttl")),
                model(dir.resolve("expected.jena.ttl")),
                Boolean.parseBoolean(props.getProperty("needsOntology", "false").trim()));
    }

    private static Path root() {
        URL url = ConformanceLoader.class.getClassLoader().getResource(ROOT);
        if (url == null) throw new IllegalStateException("no '" + ROOT + "' directory on the test classpath");
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> held(String text) {
        if (text == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
        }
        return Collections.unmodifiableSet(out);
    }

    private static Model model(Path file) {
        String ttl = read(file);
        if (ttl == null) return null;
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(ttl), "http://tmforum.org/", "TURTLE");
        return m;
    }

    private static String read(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
