package org.tmforum.intent.conformance;

import org.apache.jena.rdf.model.*;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.sparql.util.FmtUtils;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tmforum.intent.engine.*;
import org.tmforum.intent.graph.TioNamespaces;
import org.tmforum.intent.handler.TurtleEvaluator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs every conformance case against every engine (spec 5.6) and compares the result graph with the expected
 * contract graph. A Jena run is skipped while that engine reports itself not ready; a case that needs the licensed
 * ontology is skipped when {@code ontology/IntentCommonModel.ttl} is absent.
 *
 * <p>Record mode, {@code -Dconformance.record=true}: writes each case's {@code expected.ttl} from the Java engine
 * into {@code src/test/resources/conformance/<case>/} instead of asserting. Recorded files must be reviewed by
 * hand against the specification before they are committed.
 *
 * <p>Development mode, {@code -Dconformance.forceJena=true}: runs the Jena engine's rules against every case even
 * while the engine is not ready ({@link JenaEvaluationEngine#READY}), as long as the ontology is present. It is for
 * seeing which cases the rules written so far already pass; it does not change the engine's declared status.
 */
class ConformanceTest {

    static final boolean RECORD = Boolean.getBoolean("conformance.record");
    static final boolean FORCE_JENA = Boolean.getBoolean("conformance.forceJena");
    static final Path SOURCE_ROOT = Path.of("src/test/resources", ConformanceLoader.ROOT);
    static final Path ONTOLOGY = Path.of("ontology/IntentCommonModel.ttl");

    /** The engines, constructed as production constructs them (without Spring). */
    static List<EvaluationEngine> engines() {
        return List.of(new JavaEvaluationEngine(new TurtleEvaluator()), new JenaEvaluationEngine());
    }

    static Stream<Arguments> runs() {
        List<ConformanceCase> cases = ConformanceLoader.loadAll();
        return engines().stream().flatMap(e -> cases.stream().map(c -> Arguments.of(e.name(), c.name(), e, c)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("runs")
    void engineProducesTheExpectedResultGraph(String engineName, String caseName, EvaluationEngine engine,
                                              ConformanceCase c) throws IOException {
        EngineStatus status = engine.status();
        boolean forced = FORCE_JENA && "jena".equals(engineName) && Files.isRegularFile(ONTOLOGY);
        assumeTrue(status.ready() || forced, () -> engineName + " engine not ready: " + status.reason());
        assumeFalse(c.needsOntology() && !Files.isRegularFile(ONTOLOGY),
                () -> caseName + " needs the licensed ontology (" + ONTOLOGY + " is absent)");
        if (RECORD) assumeTrue("java".equals(engineName), "record mode records from the java engine only");

        EvaluationOutcome outcome = engine.evaluate(new EvaluationInput(
                "conformance-" + caseName, c.expression(), c.observations(), c.resources(), c.held()));
        Model actual = ResultContract.filter(outcome.resultGraph());

        if (RECORD) {
            record(caseName, actual);
            return;
        }
        Model expected = expectedFor(engineName, c);
        assertNotNull(expected, caseName + ": no expected.ttl; record it with -Dconformance.record=true");
        assertTrue(ResultContract.sameResults(actual, expected),
                () -> caseName + " on " + engineName + " differs from the expected result graph:\n"
                        + diff(actual, ResultContract.filter(expected)));
    }

    /** Review Focus 3: holding the allocated resources, a re-evaluation gives the same graph as the first one. */
    @Test
    void reevaluationWhileHoldingExpectsTheSameGraphAsTheFirstEvaluation() {
        Map<String, ConformanceCase> byName = new HashMap<>();
        ConformanceLoader.loadAll().forEach(c -> byName.put(c.name(), c));
        ConformanceCase firstCase = byName.get("seed-fulfilled");
        ConformanceCase againCase = byName.get("seed-reevaluation-held");
        assertNotNull(firstCase, "case seed-fulfilled is missing from the corpus");
        assertNotNull(againCase, "case seed-reevaluation-held is missing from the corpus");
        Model first = firstCase.expected();
        Model again = againCase.expected();
        assertNotNull(first, "seed-fulfilled: no expected.ttl");
        assertNotNull(again, "seed-reevaluation-held: no expected.ttl");
        assumeFalse(RECORD, "record mode");
        assertTrue(ResultContract.sameResults(first, again), () -> diff(again, first));
    }

    /** {@code expected.ttl} for java; for jena {@code expected.jena.ttl} where present, else {@code expected.ttl}. */
    static Model expectedFor(String engineName, ConformanceCase c) {
        if ("jena".equals(engineName) && c.expectedJena() != null) return c.expectedJena();
        return c.expected();
    }

    private static void record(String caseName, Model actual) throws IOException {
        Path file = SOURCE_ROOT.resolve(caseName).resolve("expected.ttl");
        Files.writeString(file, stableTurtle(actual));
    }

    /** Turtle with subjects and predicates in a fixed order, so re-recording gives a readable diff. */
    static String stableTurtle(Model m) {
        StringBuilder sb = new StringBuilder("# Recorded from the java engine with -Dconformance.record=true, then reviewed by hand.\n");
        PREFIXES.getNsPrefixMap().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("@prefix ").append(e.getKey()).append(": <").append(e.getValue()).append("> .\n"));
        Map<String, List<String>> bySubject = new TreeMap<>();
        for (StmtIterator it = m.listStatements(); it.hasNext(); ) {
            Statement st = it.next();
            if (st.getSubject().isAnon()) continue;              // reasons are written inline
            RDFNode o = st.getObject();
            String obj = o.isAnon() ? inline(m, o.asResource()) : FmtUtils.stringForNode(o.asNode(), PREFIXES);
            bySubject.computeIfAbsent(FmtUtils.stringForNode(st.getSubject().asNode(), PREFIXES), k -> new ArrayList<>())
                    .add(FmtUtils.stringForNode(st.getPredicate().asNode(), PREFIXES) + " " + obj);
        }
        bySubject.forEach((s, pos) -> {
            sb.append('\n').append(s);
            List<String> sorted = pos.stream().sorted().toList();
            for (int i = 0; i < sorted.size(); i++) {
                sb.append("\n    ").append(sorted.get(i)).append(i == sorted.size() - 1 ? " ." : " ;");
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    private static String inline(Model m, Resource blank) {
        List<String> pos = new ArrayList<>();
        for (StmtIterator it = m.listStatements(blank, null, (RDFNode) null); it.hasNext(); ) {
            Statement st = it.next();
            pos.add(FmtUtils.stringForNode(st.getPredicate().asNode(), PREFIXES) + " "
                    + FmtUtils.stringForNode(st.getObject().asNode(), PREFIXES));
        }
        return "[ " + String.join(" ; ", pos.stream().sorted().toList()) + " ]";
    }

    // ── Readable diff ─────────────────────────────────────────────────────────

    static String diff(Model actual, Model expected) {
        Set<String> a = lines(actual);
        Set<String> e = lines(expected);
        StringBuilder sb = new StringBuilder();
        sb.append("  only in actual:\n");
        a.stream().filter(l -> !e.contains(l)).forEach(l -> sb.append("    + ").append(l).append('\n'));
        sb.append("  only in expected:\n");
        e.stream().filter(l -> !a.contains(l)).forEach(l -> sb.append("    - ").append(l).append('\n'));
        return sb.toString();
    }

    /** Statements as text; a blank node is shown by its rdfs:label (reasons) instead of its id. */
    private static Set<String> lines(Model m) {
        Set<String> out = new TreeSet<>();
        for (StmtIterator it = m.listStatements(); it.hasNext(); ) {
            Statement st = it.next();
            if (st.getSubject().isAnon()) continue;              // folded into the line that points to it
            out.add(text(m, st.getSubject()) + " " + PREFIXES.shortForm(st.getPredicate().getURI()) + " "
                    + text(m, st.getObject()));
        }
        return out;
    }

    private static String text(Model m, RDFNode n) {
        if (n.isURIResource()) return PREFIXES.shortForm(n.asResource().getURI());
        if (n.isLiteral()) return n.asLiteral().getLexicalForm()
                + (n.asLiteral().getDatatypeURI() == null ? "" : "^^" + PREFIXES.shortForm(n.asLiteral().getDatatypeURI()));
        Statement label = m.getProperty(n.asResource(), RDFS.label);
        return "[" + (label == null ? "_" : "\"" + label.getString() + "\"") + "]";
    }

    static final PrefixMapping PREFIXES = PrefixMapping.Factory.create()
            .setNsPrefix("rdf", RDF.getURI())
            .setNsPrefix("rdfs", RDFS.getURI())
            .setNsPrefix("xsd", XSD.getURI())
            .setNsPrefix("icm", TioNamespaces.ICM)
            .setNsPrefix("met", TioNamespaces.MET)
            .setNsPrefix("quan", TioNamespaces.QUAN)
            .setNsPrefix("bbf", "http://broadband-forum.org/Intent#")
            .setNsPrefix("pon", "http://broadband-forum.org/ont/pon-resource#")
            .setNsPrefix("ex", "http://example.org/")
            .lock();
}
