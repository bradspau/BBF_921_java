package org.tmforum.intent.unit;

import org.apache.jena.reasoner.rulesys.BuiltinRegistry;
import org.apache.jena.reasoner.rulesys.ClauseEntry;
import org.apache.jena.reasoner.rulesys.Functor;
import org.apache.jena.reasoner.rulesys.Rule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.handler.JenaBuiltins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TIO Jena rule files: every functor they use must exist. (The quantity comparison cases that used to be here
 * ran on the removed legacy tio_all.rules; the staged rules are covered by JenaQuantityRulesTest and friends.)
 */
class JenaRulesTest {

    private static final Path RUNTIME_RULES = Path.of("src/main/resources/rules");
    private static final Path ONTOLOGY_RULES = Path.of("ontology/jena-rules");

    @BeforeAll
    static void registerBuiltins() {
        JenaBuiltins.register();
    }

    // ── Every functor must be a real Jena (or TIO) builtin ────────────────────

    @Test
    void everyFunctorUsedByTheRuleFilesIsAKnownBuiltin() throws Exception {
        List<String> problems = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(RUNTIME_RULES, ONTOLOGY_RULES)) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".rules")).sorted().forEach(files::add);
            }
        }
        assertFalse(files.isEmpty());
        for (Path f : files) {
            for (Rule r : Rule.parseRules(Files.readString(f))) {
                List<ClauseEntry> clauses = new ArrayList<>(Arrays.asList(r.getBody()));
                clauses.addAll(Arrays.asList(r.getHead()));
                for (ClauseEntry c : clauses) {
                    if (c instanceof Functor fn && BuiltinRegistry.theRegistry.getImplementation(fn.getName()) == null) {
                        problems.add(f.getFileName() + ": rule " + r.getName() + " uses unknown functor " + fn.getName());
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
