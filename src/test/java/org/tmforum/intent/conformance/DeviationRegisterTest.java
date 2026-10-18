package org.tmforum.intent.conformance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DeviationRegisterTest {

    private static Path root() throws URISyntaxException {
        return Path.of(DeviationRegisterTest.class.getClassLoader().getResource(ConformanceLoader.ROOT).toURI());
    }

    private static Set<String> caseDirs(boolean onlyWithJenaExpectation) throws IOException, URISyntaxException {
        try (Stream<Path> dirs = Files.list(root())) {
            Set<String> out = new TreeSet<>();
            dirs.filter(Files::isDirectory)
                    .filter(d -> !onlyWithJenaExpectation || Files.isRegularFile(d.resolve("expected.jena.ttl")))
                    .forEach(d -> out.add(d.getFileName().toString()));
            return out;
        }
    }

    @Test
    void conventionIsOneCaseLinePerCase() {
        String md = "# Register\n## 1. x\ncase: a-b\n- case: c_d\nnot a case: line\ntext case: e\n  case:  f.g  \n";
        assertEquals(Set.of("a-b", "c_d", "f.g"), DeviationRegister.caseNames(md));
    }

    @Test
    void theRegisterExists() throws Exception {
        assertTrue(Files.isRegularFile(root().resolve("DEVIATIONS.md")), "conformance/DEVIATIONS.md is missing");
    }

    @Test
    void everyJenaExpectationIsNamedInTheRegister() throws Exception {
        Set<String> named = DeviationRegister.caseNames(root());
        Set<String> missing = caseDirs(true);
        missing.removeAll(named);
        assertTrue(missing.isEmpty(), "cases with expected.jena.ttl but no register entry: " + missing);
    }

    @Test
    void everyCaseNamedInTheRegisterExists() throws Exception {
        Set<String> unknown = new TreeSet<>(DeviationRegister.caseNames(root()));
        unknown.removeAll(caseDirs(false));
        assertTrue(unknown.isEmpty(), "register names cases that do not exist: " + unknown);
    }

    @Test
    void theLegacyMultiFunctionCaseCarriesBothExpectations() throws Exception {
        Path dir = root().resolve("pool-legacy-multifunction");
        assertTrue(Files.isRegularFile(dir.resolve("expected.ttl")));
        assertTrue(Files.isRegularFile(dir.resolve("expected.jena.ttl")));
    }
}
