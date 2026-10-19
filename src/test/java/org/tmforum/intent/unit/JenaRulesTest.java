package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.rulesys.BuiltinRegistry;
import org.apache.jena.reasoner.rulesys.ClauseEntry;
import org.apache.jena.reasoner.rulesys.Functor;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.handler.JenaBuiltins;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TIO Jena rules themselves: every functor they use must exist, and the rules must derive what the
 * ontology says they should (the legacy tio_all.rules; the engine's staged rules have their own tests).
 */
class JenaRulesTest {

    private static final String PFX = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix icm:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
            @prefix tmf:  <http://tmforum.org/api/v5/> .
            @prefix ex:   <http://example.org/> .
            """;

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

    // ── Quantity comparisons ──────────────────────────────────────────────────

    private InfModel stageA(String turtle) throws Exception {
        return infer(turtle, RUNTIME_RULES.resolve("tio_all.rules"));
    }

    private InfModel infer(String turtle, Path rules) throws Exception {
        Model base = ModelFactory.createDefaultModel();
        base.read(new StringReader(PFX + turtle), null, "TURTLE");
        return infer(base, rules);
    }

    private InfModel infer(Model base, Path rules) throws Exception {
        GenericRuleReasoner reasoner = new GenericRuleReasoner(Rule.parseRules(Files.readString(rules)));
        reasoner.setMode(GenericRuleReasoner.FORWARD_RETE);
        InfModel inf = ModelFactory.createInfModel(reasoner, base);
        inf.prepare();
        return inf;
    }

    /** A comparison function node in the typed-node form the rules read: type, rdf:first, rdf:rest. */
    private static String comparison(String type, String value, String bound) {
        return "ex:f a quan:" + type + " ; rdf:first ex:v ; rdf:rest [ rdf:first ex:b ] .\n"
                + "ex:v rdf:value " + value + " .\nex:b rdf:value " + bound + " .\n";
    }

    private boolean holds(InfModel m) {
        return m.contains(m.getResource("http://example.org/f"), org.apache.jena.vocabulary.RDF.value,
                m.createTypedLiteral(true));
    }

    @Test
    void quanSmaller_isTrueOnlyWhenTheValueIsBelowTheBound() throws Exception {
        assertTrue(holds(stageA(comparison("quansmaller", "8.0", "25.0"))));
        assertFalse(holds(stageA(comparison("quansmaller", "40.0", "25.0"))));
        assertFalse(holds(stageA(comparison("quansmaller", "25.0", "25.0"))), "smaller is strict");
    }

    @Test
    void quanGreater_isTrueOnlyWhenTheValueIsAboveTheBound() throws Exception {
        assertTrue(holds(stageA(comparison("quangreater", "150.0", "100.0"))));
        assertFalse(holds(stageA(comparison("quangreater", "50.0", "100.0"))));
        assertFalse(holds(stageA(comparison("quangreater", "100.0", "100.0"))), "greater is strict");
    }

    @Test
    void quanAtLeastAndAtMost_includeTheBound() throws Exception {
        assertTrue(holds(stageA(comparison("quanatLeast", "100.0", "100.0"))));
        assertFalse(holds(stageA(comparison("quanatLeast", "99.0", "100.0"))));
        assertTrue(holds(stageA(comparison("quanatMost", "25.0", "25.0"))));
        assertFalse(holds(stageA(comparison("quanatMost", "26.0", "25.0"))));
    }

    // The icm:chooseFrom cases moved to JenaDeliveryRulesTest (Task 15), with tio_choose.rules' successor
    // tio_c_choose.rules (choice strata).
}
