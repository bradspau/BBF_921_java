package org.tmforum.intent.unit;

import org.apache.jena.graph.compose.Union;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tmforum.intent.engine.EvaluationInput;
import org.tmforum.intent.engine.EvaluationInputs;
import org.tmforum.intent.engine.EvaluationOutcome;
import org.tmforum.intent.engine.JenaEvaluationEngine;
import org.tmforum.intent.engine.OntologyModels;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.graph.TioNamespaces;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stage B logic ({@code rules/tio_b_logic.rules}) with stage A ({@code tio_a_core}, {@code tio_a_quantity},
 * {@code tio_a_logic}): boolean defaults (TR292A §4, TR292E §4 p.13), conjunction of several contributing functions,
 * {@code log:allOf/anyOf/noneOf/oneOf} over RDF lists (TR292E §2, §6) and the statement evaluation functions
 * {@code log:match*} (TR292E §3, §6).
 *
 * <p>Every case runs through {@link JenaEvaluationEngine#infer} (the engine's real two-stage chain over the real rule
 * files), with the input triples and the rules of each stage shuffled by several fixed seeds, and, when the licensed
 * ontology is present, both with and without it in the model. Every run asserts on the whole model that each
 * IRI-identified intent element has exactly one boolean {@code rdf:value} (the contract of design §5.2).
 */
class JenaLogicRulesTest {

    static final String EX = "http://example.org/";
    static final String TMF = Namespaces.TMF;
    static final Path ONTOLOGY_DIR = Path.of("ontology");

    static final String PREFIXES = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix icm:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/> .
            @prefix log:  <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
            @prefix met:  <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
            @prefix mf:   <http://tio.models.tmforum.org/tio/v3.6.0/MathFunctions/> .
            @prefix ex:   <http://example.org/> .
            """;

    static final long[] SEEDS = {0, 3, 17, 4242};

    /** A leaf condition whose value comes from a stage A quantity function: atLeast(5, 3) / atLeast(3, 5). */
    static String leaf(String name, boolean value) {
        return "ex:" + name + " a icm:Condition ; quan:atLeast ( " + (value ? "5 3" : "3 5") + " ) .\n";
    }

    // ── running ──────────────────────────────────────────────────────────────────

    static Optional<Model> ontology() {
        return OntologyModels.load(ONTOLOGY_DIR);
    }

    /** The prepared stage B model for the input, with input and rule order shuffled by {@code seed} (0 = as is). */
    static InfModel run(String turtle, long seed, boolean withOntology) {
        Model parsed = EvaluationInputs.parse(PREFIXES + turtle);
        List<Statement> stmts = new ArrayList<>(parsed.listStatements().toList());
        if (seed != 0) Collections.shuffle(stmts, new Random(seed));
        Model input = ModelFactory.createDefaultModel();
        stmts.forEach(input::add);
        Model base = input;
        if (withOntology) {
            base = ModelFactory.createModelForGraph(new Union(input.getGraph(), ontology().orElseThrow().getGraph()));
        }
        return JenaEvaluationEngine.infer(base, rules -> {
            List<Rule> r = new ArrayList<>(rules);
            if (seed != 0) Collections.shuffle(r, new Random(seed * 31 + 7));
            return r;
        });
    }

    static List<Boolean> ontologyModes() {
        return ontology().isPresent() ? List.of(false, true) : List.of(false);
    }

    /**
     * Runs every seed and ontology mode; in each run asserts the whole-model invariants and that every named element
     * has exactly the expected single value. Returns the last model for further checks.
     */
    static InfModel assertAllRuns(String turtle, Map<String, Boolean> expected) {
        InfModel last = null;
        for (boolean withOntology : ontologyModes()) {
            for (long seed : SEEDS) {
                InfModel m = run(turtle, seed, withOntology);
                String where = " (seed " + seed + ", ontology " + withOntology + ")\n" + turtle;
                assertEveryElementHasExactlyOneBooleanValue(m, where);
                assertStageBWritesOnlyOnContributorsOrDefaults(m, where);
                for (var e : expected.entrySet()) {
                    assertEquals(Set.of(e.getValue().toString()), values(m, e.getKey()), () -> e.getKey() + where);
                }
                last = m;
            }
        }
        return last;
    }

    static Set<String> values(Model m, String local) {
        Set<String> out = new TreeSet<>();
        m.listObjectsOfProperty(m.getResource(EX + local), RDF.value).forEachRemaining(n -> out.add(lex(n)));
        return out;
    }

    static String lex(RDFNode n) {
        return n.isLiteral() ? ((Literal) n).getLexicalForm() : n.toString();
    }

    // ── whole-model invariants ───────────────────────────────────────────────────

    static final List<Resource> ELEMENT_CLASSES = List.of(
            ResourceFactory.createResource(TioNamespaces.ICM + "Intent"),
            ResourceFactory.createResource(TioNamespaces.ICM + "Expectation"),
            ResourceFactory.createResource(TioNamespaces.ICM + "Condition"),
            ResourceFactory.createResource(TioNamespaces.LOG + "Condition"));

    /**
     * IRI instances of the element classes and their subclasses (as far as the model knows them), and the IRI subjects
     * the rules type {@code tmf:Element} themselves (untyped subjects of a function, such as the demo shape
     * {@code bbf:DL_Check quan:atLeast ( ... )}).
     */
    static Set<Resource> iriElements(Model m) {
        Set<Resource> classes = new HashSet<>();
        classes.add(ResourceFactory.createResource(TMF + "Element"));
        Deque<Resource> todo = new ArrayDeque<>(ELEMENT_CLASSES);
        while (!todo.isEmpty()) {
            Resource c = todo.pop();
            if (classes.add(c)) m.listSubjectsWithProperty(RDFS.subClassOf, c).forEachRemaining(todo::push);
        }
        Set<Resource> out = new HashSet<>();
        for (Resource c : classes) {
            m.listSubjectsWithProperty(RDF.type, c).forEachRemaining(s -> { if (s.isURIResource()) out.add(s); });
        }
        return out;
    }

    static void assertEveryElementHasExactlyOneBooleanValue(Model m, String where) {
        Set<Resource> elements = iriElements(m);
        List<String> bad = new ArrayList<>();
        for (Resource e : elements) {
            List<RDFNode> v = m.listObjectsOfProperty(e, RDF.value).toList();
            if (v.size() != 1 || !v.get(0).isLiteral()
                    || !"http://www.w3.org/2001/XMLSchema#boolean".equals(v.get(0).asLiteral().getDatatypeURI())) {
                bad.add(e.getLocalName() + "=" + v);
            }
        }
        assertEquals(List.of(), bad, () -> elements.size() + " elements, not exactly one boolean value:" + where);
    }

    static final Property HAS_CONTRIBUTOR = ResourceFactory.createProperty(TMF + "hasContributor");
    static final Resource ELEMENT = ResourceFactory.createResource(TMF + "Element");

    /**
     * Design §5.5.1 precondition (b): a stage B rule writes {@code rdf:value} only on a subject with a stage-A-derived
     * {@code tmf:hasContributor}; the one exception is the default {@code false} on a typed element with none.
     */
    static void assertStageBWritesOnlyOnContributorsOrDefaults(InfModel stageB, String where) {
        Model stageA = stageB.getRawModel();
        for (Statement st : stageB.getDeductionsModel().listStatements(null, RDF.value, (RDFNode) null).toList()) {
            Resource s = st.getSubject();
            if (stageA.contains(s, HAS_CONTRIBUTOR)) continue;
            assertTrue(stageA.contains(s, RDF.type, ELEMENT) && st.getObject().isLiteral()
                            && !st.getObject().asLiteral().getBoolean(),
                    () -> "stage B wrote " + st + " on a subject without a contributor" + where);
        }
    }

    // ── truth tables: 0 to 3 members, every combination, every operator ─────────

    static final Map<String, Function<boolean[], Boolean>> OPERATORS = new LinkedHashMap<>();
    static {
        OPERATORS.put("allOf", v -> v.length > 0 && count(v) == v.length);   // TR292E §6.1: empty is false
        OPERATORS.put("anyOf", v -> count(v) > 0);                           // §6.2
        OPERATORS.put("noneOf", v -> count(v) == 0);                         // §6.10: empty is true
        OPERATORS.put("oneOf", v -> count(v) == 1);                          // §6.11
    }

    static int count(boolean[] v) {
        int n = 0;
        for (boolean b : v) if (b) n++;
        return n;
    }

    static Stream<Arguments> truthTable() {
        List<Arguments> out = new ArrayList<>();
        for (String op : OPERATORS.keySet())
            for (int n = 0; n <= 3; n++)
                for (int bits = 0; bits < (1 << n); bits++)
                    out.add(Arguments.of(op, n, bits));
        return out.stream();
    }

    @ParameterizedTest(name = "{0} over {1} members, bits={2}")
    @MethodSource("truthTable")
    void truthTables(String op, int n, int bits) {
        boolean[] v = new boolean[n];
        StringBuilder t = new StringBuilder("ex:c a icm:Condition ; log:" + op + " ( ");
        StringBuilder leaves = new StringBuilder();
        Map<String, Boolean> expected = new HashMap<>();
        for (int i = 0; i < n; i++) {
            v[i] = (bits & (1 << i)) != 0;
            t.append("ex:m").append(i).append(' ');
            leaves.append(leaf("m" + i, v[i]));
            expected.put("m" + i, v[i]);
        }
        t.append(") .\n").append(leaves);
        expected.put("c", OPERATORS.get(op).apply(v));
        assertAllRuns(t.toString(), expected);
    }

    static Stream<Arguments> operators() {
        return OPERATORS.keySet().stream().map(Arguments::of);
    }

    // ── TR292E §2: non-boolean arguments are removed ─────────────────────────────

    @ParameterizedTest(name = "{0} drops non-boolean members")
    @MethodSource("operators")
    void nonBooleanMembersAreDropped(String op) {
        // one true member among: a decimal-valued node, a string literal, an untyped valueless node, an integer
        String t = "ex:c a icm:Condition ; log:" + op + " ( ex:n ex:t \"x\" ex:u 5 [ rdf:value 7 ] ) .\n"
                + "ex:n rdf:value 3.5 .\n" + leaf("t", true);
        assertAllRuns(t, Map.of("c", OPERATORS.get(op).apply(new boolean[]{true}), "t", true));
    }

    @ParameterizedTest(name = "{0} over only non-boolean members behaves as empty")
    @MethodSource("operators")
    void onlyNonBooleanMembers(String op) {
        String t = "ex:c a icm:Condition ; log:" + op + " ( ex:n ex:u \"s\" ) .\nex:n rdf:value 4 .\n";
        assertAllRuns(t, Map.of("c", OPERATORS.get(op).apply(new boolean[0])));
    }

    @ParameterizedTest(name = "{0} counts literal boolean members")
    @MethodSource("operators")
    void literalBooleanMembers(String op) {
        String t = "ex:c a icm:Condition ; log:" + op + " ( true false ) .\n";
        assertAllRuns(t, Map.of("c", OPERATORS.get(op).apply(new boolean[]{true, false})));
    }

    @ParameterizedTest(name = "{0} reads input boolean values of untyped members")
    @MethodSource("operators")
    void inputBooleanValuesOfUntypedMembers(String op) {
        String t = "ex:c a icm:Condition ; log:" + op + " ( ex:a ex:b ) .\n"
                + "ex:a rdf:value true .\nex:b rdf:value false .\n";
        assertAllRuns(t, Map.of("c", OPERATORS.get(op).apply(new boolean[]{true, false})));
    }

    @Test
    void aMemberWithBothValuesCountsAsFalse() {
        // degenerate input: any-false wins, so the operator still gets exactly one value
        String t = """
                ex:c a icm:Condition ; log:allOf ( ex:a ex:t ) .
                ex:d a icm:Condition ; log:anyOf ( ex:a ) .
                ex:a rdf:value true, false .
                """ + leaf("t", true);
        assertAllRuns(t, Map.of("c", false, "d", false));
    }

    @Test
    void anArgumentThatIsNotAListIsFalse() {
        String t = """
                ex:c a icm:Condition ; log:anyOf ex:notAList .
                ex:d a icm:Condition ; log:noneOf "text" .
                ex:notAList ex:p ex:q .
                """;
        assertAllRuns(t, Map.of("c", false, "d", false));
    }

    // ── TR292A §4 / TR292E §4: conjunction of contributions, default false ───────

    @ParameterizedTest(name = "two functions on one condition: {0} and {1}")
    @MethodSource("pairs")
    void severalFunctionsOnOneConditionCombineByConjunction(boolean a, boolean b) {
        String t = "ex:c a icm:Condition ; quan:atLeast ( " + (a ? "5 3" : "3 5") + " ) ; quan:smaller ( "
                + (b ? "1 2" : "2 1") + " ) .\n";
        assertAllRuns(t, Map.of("c", a && b));
    }

    static Stream<Arguments> pairs() {
        return Stream.of(Arguments.of(true, true), Arguments.of(true, false), Arguments.of(false, true),
                Arguments.of(false, false));
    }

    @Test
    void theSameFunctionTwiceAndAnOperatorAlsoCombineByConjunction() {
        String t = """
                ex:c1 a icm:Condition ; quan:atLeast ( 5 3 ), ( 6 1 ) .
                ex:c2 a icm:Condition ; quan:atLeast ( 5 3 ), ( 1 6 ) .
                ex:c3 a icm:Condition ; quan:atLeast ( 5 3 ) ; log:anyOf ( ex:t ex:f ) .
                ex:c4 a icm:Condition ; quan:atLeast ( 5 3 ) ; log:allOf ( ex:t ex:f ) .
                ex:c5 a icm:Condition ; log:allOf ( ex:t ) ; log:noneOf ( ex:f ) ; log:oneOf ( ex:t ex:f ) .
                """ + leaf("t", true) + leaf("f", false);
        assertAllRuns(t, Map.of("c1", true, "c2", false, "c3", true, "c4", false, "c5", true));
    }

    @Test
    void aContributionWithBothValuesCountsAsFalse() {
        // degenerate input: an argument with two values makes the quantity contribution both true and false
        String t = """
                ex:c a icm:Condition ; quan:atLeast ( [ rdf:value 5, 1 ] 3 ) .
                ex:d a icm:Condition ; quan:atLeast ( [ rdf:value 5, 1 ] 3 ) ; quan:atLeast ( 5 3 ) .
                ex:e a icm:Condition ; log:anyOf ( ex:c ) .
                """;
        InfModel m = assertAllRuns(t, Map.of("c", false, "d", false, "e", false));
        Resource k = m.listObjectsOfProperty(m.getResource(EX + "c"), m.getProperty(TMF + "contribution")).next()
                .asResource();
        assertEquals(2, m.listObjectsOfProperty(k, RDF.value).toList().size(), "the premise: a two-valued contribution");
    }

    @Test
    void conjunctionOfFourContributionsWithTheFalseOneAnywhere() {
        // each element has four quantity contributions, one of them false; over all elements the false one sits at
        // every position of the fixed contribution order, including the inner ones. The engine orders contributions
        // by the label of their skolem node, a hash of (element, function, argument list). The argument lists are
        // therefore IRI-named (an anonymous list's label is random at parse time, which made the positions covered,
        // and so this test, random): the order, and which positions the false contribution takes, are fixed.
        StringBuilder t = new StringBuilder();
        Map<String, Boolean> exp = new HashMap<>();
        for (int i = 0; i < 12; i++) {
            t.append("ex:c").append(i).append(" a icm:Condition");
            StringBuilder lists = new StringBuilder();
            for (int j = 0; j < 4; j++) {
                boolean bad = j == i % 4;
                int big = 10 + i * 4 + j;
                String l = "ex:args" + i + "_" + j;
                t.append(" ; quan:atLeast ").append(l);
                lists.append(l).append(" rdf:first ").append(bad ? 1 : big)
                     .append(" ; rdf:rest ( ").append(bad ? big : 1).append(" ) .\n");
            }
            t.append(" .\n").append(lists);
            exp.put("c" + i, false);
        }
        t.append("ex:all a icm:Condition ; quan:atLeast ( 2 1 ), ( 3 1 ), ( 4 1 ), ( 5 1 ) .\n");
        exp.put("all", true);
        InfModel m = assertAllRuns(t.toString(), exp);

        Set<Integer> positions = new TreeSet<>();
        Property contribution = m.getProperty(TMF + "contribution");
        for (int i = 0; i < 12; i++) {
            List<Resource> ks = m.listObjectsOfProperty(m.getResource(EX + "c" + i), contribution)
                    .mapWith(RDFNode::asResource).toList().stream()
                    .sorted(Comparator.comparing(r -> r.asNode().getBlankNodeLabel())).toList();
            for (int p = 0; p < ks.size(); p++) {
                if (m.containsLiteral(ks.get(p), RDF.value, false)) positions.add(p);
            }
        }
        assertEquals(Set.of(0, 1, 2, 3), positions, "the false contribution should occur at every position");
    }

    @Test
    void elementsWithoutContributionsAreFalse() {
        String t = """
                ex:i a icm:Intent .
                ex:e a icm:Expectation .
                ex:c a icm:Condition .
                ex:l a log:Condition .
                ex:v a icm:Condition ; rdfs:label "a property that is not a function" .
                """;
        assertAllRuns(t, Map.of("i", false, "e", false, "c", false, "l", false, "v", false));
    }

    @Test
    void anElementWithAnInputBooleanValueAndNoFunctionKeepsIt() {
        assertAllRuns("ex:c a icm:Condition ; rdf:value true .\n", Map.of("c", true));
    }

    @Test
    void argumentAndContributionNodesNeverGetADefault() {
        String t = """
                ex:c a icm:Condition ; quan:atLeast ( [ met:lastValue ( ex:M ) ] 5 ) .
                ex:M a met:Metric .
                """;
        InfModel m = assertAllRuns(t, Map.of("c", false));
        Resource lastValueNode = m.listSubjectsWithProperty(m.getProperty(TioNamespaces.MET + "lastValue")).next();
        assertFalse(m.contains(lastValueNode, RDF.value), "[ met:lastValue ( ... ) ] is an argument, not an element");
        Property contribution = m.getProperty(TMF + "contribution");
        for (RDFNode k : m.listObjectsOfProperty(contribution).toList()) {
            assertEquals(1, m.listObjectsOfProperty(k.asResource(), RDF.value).toList().size(), "contribution " + k);
        }
    }

    @Test
    void legacyQuantityFormsContributeToTheElement() {
        String t = """
                ex:a a icm:Condition ; quan:quanatLeast ( 5 3 ) .
                ex:b a icm:Condition , quan:quanatMost ; rdf:first 5 ; rdf:rest ( 3 ) .
                """;
        InfModel m = assertAllRuns(t, Map.of("a", true, "b", false));
        assertTrue(m.getRawModel().contains(m.getResource(EX + "b"), HAS_CONTRIBUTOR));
    }

    /** The invariant covers untyped IRI subjects of a function too (the rules type them tmf:Element). */
    @Test
    void untypedIriSubjectsOfAFunctionHaveExactlyOneValue() {
        String t = """
                ex:u quan:atLeast ( 5 3 ) .
                ex:v quan:atMost ( 5 3 ) .
                ex:w quan:atLeast ( 5 3 ) ; quan:atMost ( 5 3 ) .
                """;
        InfModel m = assertAllRuns(t, Map.of("u", true, "v", false, "w", false));
        for (String e : List.of("u", "v", "w")) {
            assertTrue(iriElements(m).contains(m.getResource(EX + e)), e + " is covered by the invariant");
        }
    }

    /** Runs with the ontology only (mf:logistic is a fun:Function only there), over every seed. */
    static InfModel assertRunsWithOntology(String turtle, Map<String, Boolean> expected) {
        assumeTrue(ontology().isPresent(), "needs the licensed ontology");
        InfModel last = null;
        for (long seed : SEEDS) {
            InfModel m = run(turtle, seed, true);
            String where = " (seed " + seed + ")\n" + turtle;
            assertEveryElementHasExactlyOneBooleanValue(m, where);
            assertStageBWritesOnlyOnContributorsOrDefaults(m, where);
            for (var e : expected.entrySet()) {
                assertEquals(Set.of(e.getValue().toString()), values(m, e.getKey()), () -> e.getKey() + where);
            }
            last = m;
        }
        return last;
    }

    static Set<String> failures(Model m, String local) {
        Set<String> out = new TreeSet<>();
        for (RDFNode k : m.listObjectsOfProperty(m.getResource(EX + local), m.getProperty(TMF + "contribution")).toList()) {
            m.listObjectsOfProperty(k.asResource(), m.getProperty(TMF + "failure"))
                    .forEachRemaining(f -> out.add(f.asLiteral().getLexicalForm()));
        }
        return out;
    }

    @Test
    void anElementWhoseOnlyFunctionIsNotImplementedIsFalseAndOperatorsResolve() {
        // fail closed: mf:logistic is a declared fun:Function that no rule implements yet
        String t = """
                ex:c a icm:Condition ; mf:logistic ( 1 2 3 ) .
                ex:all a icm:Condition ; log:allOf ( ex:c ) .
                ex:any a icm:Condition ; log:anyOf ( ex:c ex:t ) .
                ex:I a icm:Intent ; log:allOf ( ex:all ) .
                """ + leaf("t", true);
        InfModel m = assertRunsWithOntology(t, Map.of("c", false, "all", false, "any", true, "t", true, "I", false));
        assertTrue(m.getRawModel().contains(m.getResource(EX + "c"), HAS_CONTRIBUTOR));
        assertEquals(Set.of("function not implemented: logistic"), failures(m, "c"));
    }

    @Test
    void anImplementedTrueFunctionAndAnUnimplementedOneAreFalse() {
        // TR292E §4: true iff ALL contributing properties are true
        String t = """
                ex:c a icm:Condition ; quan:atLeast ( 5 3 ) ; mf:logistic ( 1 2 3 ) .
                ex:all a icm:Condition ; log:allOf ( ex:c ) .
                """;
        InfModel m = assertRunsWithOntology(t, Map.of("c", false, "all", false));
        assertEquals(Set.of("function not implemented: logistic"), failures(m, "c"));
    }

    @Test
    void onlyStageADecidedContributionsAreMarkedValuedInA() {
        // the any-false guard (a negation over a contribution's value) applies only to these
        String t = KB + """
                ex:c a icm:Condition ; quan:atLeast ( 5 3 ) ; log:allOf ( ex:t ) ; log:match ( ex:User1 ex:userClass ex:Gold ) .
                """ + leaf("t", true);
        InfModel m = assertAllRuns(t, Map.of("c", true));
        Property valuedInA = m.getProperty(TMF + "valuedInA");
        Property function = m.getProperty(TMF + "function");
        for (RDFNode k : m.listObjectsOfProperty(m.getResource(EX + "c"), m.getProperty(TMF + "contribution")).toList()) {
            String fn = m.getProperty(k.asResource(), function).getResource().getLocalName();
            assertEquals(fn.equals("atLeast"), m.getRawModel().contains(k.asResource(), valuedInA), fn);
        }
    }

    // ── nesting, including members that only get a value from the default rule ──

    static Stream<Arguments> nested() {
        List<Arguments> out = new ArrayList<>();
        for (boolean x : new boolean[]{true, false})
            for (boolean y : new boolean[]{true, false})
                for (String z : new String[]{"true", "false", "default", "untyped"})
                    out.add(Arguments.of(x, y, z));
        return out.stream();
    }

    @ParameterizedTest(name = "allOf(x={0}, allOf(y={1}, z={2}))")
    @MethodSource("nested")
    void nestedAllOfWithAMemberResolvedByTheDefault(boolean x, boolean y, String z) {
        StringBuilder t = new StringBuilder("""
                ex:o a icm:Condition ; log:allOf ( ex:x ex:i ) .
                ex:i a icm:Condition ; log:allOf ( ex:y ex:z ) .
                """);
        t.append(leaf("x", x)).append(leaf("y", y));
        switch (z) {
            case "true", "false" -> t.append(leaf("z", Boolean.parseBoolean(z)));
            case "default" -> t.append("ex:z a icm:Condition .\n");
            default -> { } // untyped, no value: dropped from the inner list
        }
        boolean inner = switch (z) {
            case "true", "untyped" -> y;
            default -> false;
        };
        Map<String, Boolean> exp = new HashMap<>(Map.of("o", x && inner, "i", inner, "x", x, "y", y));
        if (!z.equals("untyped")) exp.put("z", z.equals("true"));
        assertAllRuns(t.toString(), exp);
    }

    @Test
    void deeperNestingMixingOperatorsAndElementKinds() {
        // o = oneOf( anyOf(a, noneOf(b, d)), allOf(e), noneOf(oneOf(a, b)) ), d typed without value
        String t = """
                ex:I a icm:Intent ; log:allOf ( ex:E ) .
                ex:E a icm:Expectation ; log:allOf ( ex:o ) .
                ex:o a log:Condition ; log:oneOf ( ex:p ex:q ex:r ) .
                ex:p a log:Condition ; log:anyOf ( ex:a ex:s ) .
                ex:s a log:Condition ; log:noneOf ( ex:b ex:d ) .
                ex:q a log:Condition ; log:allOf ( ex:e ) .
                ex:r a icm:Condition ; log:noneOf ( ex:t ) .
                ex:t a icm:Condition ; log:oneOf ( ex:a ex:b ) .
                ex:d a log:Condition .
                """ + leaf("a", false) + leaf("b", false) + leaf("e", false);
        // d=F (default); s = noneOf(F,F) = T; p = anyOf(F,T) = T; q = F; t = oneOf(F,F) = F; r = T; o = oneOf(T,F,T) = F
        assertAllRuns(t, Map.of("d", false, "s", true, "p", true, "q", false, "t", false, "r", true, "o", false,
                "E", false, "I", false));
    }

    @Test
    void blankNodeConditionsContributeButAreNotReported() {
        String t = """
                ex:I a icm:Intent ; log:allOf ( [ a icm:Condition ; quan:atLeast ( 5 3 ) ] [ a icm:Condition ] ) .
                ex:J a icm:Intent ; log:anyOf ( [ a icm:Condition ; quan:atLeast ( 5 3 ) ] [ a icm:Condition ] ) .
                """;
        assertAllRuns(t, Map.of("I", false, "J", true));
    }

    @Test
    void stableOverManyOrders() {
        String t = """
                ex:o a icm:Condition ; log:allOf ( ex:x ex:i ) .
                ex:i a icm:Condition ; log:anyOf ( ex:y ex:j ) ; quan:atLeast ( 5 3 ) .
                ex:j a icm:Condition ; log:noneOf ( ex:z ex:w ) .
                ex:z a icm:Condition .
                """ + leaf("x", true) + leaf("y", false) + leaf("w", false);
        for (long seed = 1; seed <= 40; seed++) {
            InfModel m = run(t, seed, false);
            assertEveryElementHasExactlyOneBooleanValue(m, " seed " + seed);
            for (String e : List.of("o", "i", "j", "x")) assertEquals(Set.of("true"), values(m, e), e + " seed " + seed);
            for (String e : List.of("y", "z", "w")) assertEquals(Set.of("false"), values(m, e), e + " seed " + seed);
        }
    }

    // ── TR292E §3 / §6.4-6.9: statement evaluation against the knowledge base ────

    static final String KB = """
            ex:User1 ex:userClass ex:Gold .
            ex:User2 ex:userClass ex:Silver .
            ex:User3 ex:userClass ex:Gold .
            ex:Svc ex:status "up" .
            ex:Gold1 a rdfs:Container ; rdfs:member ex:User1 .
            ex:Both a rdfs:Container ; rdfs:member ex:User1, ex:User3 .
            ex:Mixed a rdfs:Container ; rdfs:member ex:User1, ex:User2 .
            ex:None a rdfs:Container ; rdfs:member ex:User2 .
            ex:Empty a rdfs:Container .
            """;

    @Test
    void matchIsTrueIffTheStatementIsInTheKnowledgeBase() {
        String t = KB + """
                ex:m1 a log:Condition ; log:match ( ex:User1 ex:userClass ex:Gold ) .
                ex:m2 a log:Condition ; log:match ( ex:User2 ex:userClass ex:Gold ) .
                ex:m3 a log:Condition ; log:match ( ex:Svc ex:status "up" ) .
                ex:m4 a log:Condition ; log:match ( ex:User1 ex:userClass ) .
                ex:m5 a log:Condition ; log:match ( ) .
                ex:m6 a log:Condition ; log:match ( ex:User1 "userClass" ex:Gold ) .
                ex:m7 a log:Condition ; log:match ( ex:User1 ex:userClass ex:Gold ex:extra ) .
                ex:m8 a log:Condition ; log:match ( "User1" ex:userClass ex:Gold ) .
                """;
        assertAllRuns(t, Map.of("m1", true, "m2", false, "m3", true, "m4", false, "m5", false, "m6", false,
                "m7", false, "m8", false));
    }

    @Test
    void matchOverContainers() {
        String t = KB + """
                ex:all1 a log:Condition ; log:matchAll ( ex:Both ex:userClass ex:Gold ) .
                ex:all2 a log:Condition ; log:matchAll ( ex:Mixed ex:userClass ex:Gold ) .
                ex:all3 a log:Condition ; log:matchAll ( ex:Empty ex:userClass ex:Gold ) .
                ex:any1 a log:Condition ; log:matchAny ( ex:Mixed ex:userClass ex:Gold ) .
                ex:any2 a log:Condition ; log:matchAny ( ex:None ex:userClass ex:Gold ) .
                ex:any3 a log:Condition ; log:matchAny ( ex:Empty ex:userClass ex:Gold ) .
                ex:any4 a log:Condition ; log:matchAny ( [ a rdfs:Container ; rdfs:member ex:User2, ex:User3 ]
                                                          ex:userClass ex:Gold ) .
                ex:none1 a log:Condition ; log:matchNone ( ex:None ex:userClass ex:Gold ) .
                ex:none2 a log:Condition ; log:matchNone ( ex:Mixed ex:userClass ex:Gold ) .
                ex:none3 a log:Condition ; log:matchNone ( ex:Empty ex:userClass ex:Gold ) .
                ex:one1 a log:Condition ; log:matchOne ( ex:Mixed ex:userClass ex:Gold ) .
                ex:one2 a log:Condition ; log:matchOne ( ex:Both ex:userClass ex:Gold ) .
                ex:one3 a log:Condition ; log:matchOne ( ex:None ex:userClass ex:Gold ) .
                ex:bad1 a log:Condition ; log:matchAny ( ex:Mixed ex:userClass ) .
                ex:bad2 a log:Condition ; log:matchAll ( ex:Both "p" ex:Gold ) .
                """;
        Map<String, Boolean> exp = new HashMap<>();
        exp.putAll(Map.of("all1", true, "all2", false, "all3", false, "any1", true, "any2", false, "any3", false,
                "any4", true));
        exp.putAll(Map.of("none1", true, "none2", false, "none3", false, "one1", true, "one2", false, "one3", false,
                "bad1", false, "bad2", false));
        assertAllRuns(t, exp);
    }

    @Test
    void matchStatementOverReifiedStatements() {
        String t = KB + """
                ex:s1 a log:Condition ; log:matchStatement (
                    [ rdf:subject ex:User1 ; rdf:predicate ex:userClass ; rdf:object ex:Gold ] ) .
                ex:s2 a log:Condition ; log:matchStatement (
                    [ rdf:subject ex:User1 ; rdf:predicate ex:userClass ; rdf:object ex:Gold ]
                    [ rdf:subject ex:User3 ; rdf:predicate ex:userClass ; rdf:object ex:Gold ] ) .
                ex:s3 a log:Condition ; log:matchStatement (
                    [ rdf:subject ex:User1 ; rdf:predicate ex:userClass ; rdf:object ex:Gold ]
                    [ rdf:subject ex:User2 ; rdf:predicate ex:userClass ; rdf:object ex:Gold ] ) .
                ex:s4 a log:Condition ; log:matchStatement ( ) .
                ex:s5 a log:Condition ; log:matchStatement ( [ rdf:subject ex:User1 ; rdf:predicate ex:userClass ] ) .
                ex:s6 a log:Condition ; log:matchStatement ( ex:User1 ex:userClass ex:Gold ) .
                """;
        assertAllRuns(t, Map.of("s1", true, "s2", true, "s3", false, "s4", false, "s5", false, "s6", false));
    }

    @Test
    void matchResultsFeedOperatorsAndConjunction() {
        String t = KB + """
                ex:c a icm:Condition ; log:anyOf ( ex:m1 ex:m2 ) .
                ex:d a icm:Condition ; log:match ( ex:User1 ex:userClass ex:Gold ) ; log:matchAny ( ex:None ex:userClass ex:Gold ) .
                ex:m1 a log:Condition ; log:match ( ex:User2 ex:userClass ex:Gold ) .
                ex:m2 log:matchAll ( ex:Both ex:userClass ex:Gold ) .
                """;
        assertAllRuns(t, Map.of("c", true, "d", false, "m1", false));
    }

    // ── through the engine ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "engine contract, anyOf member {0}")
    @ValueSource(booleans = {true, false})
    void theEngineReportsTheValuesInTheContract(boolean member) {
        String expression = PREFIXES + """
                ex:I a icm:Intent ; log:allOf ( ex:E ) .
                ex:E a icm:Expectation ; log:anyOf ( ex:C ex:D ) .
                ex:D a icm:Condition .
                """ + leaf("C", member);
        EvaluationOutcome out = new JenaEvaluationEngine(ONTOLOGY_DIR).evaluate(
                new EvaluationInput("t", expression, null, null, List.of()));
        Model c = out.resultGraph();
        assertTrue(c.containsLiteral(c.getResource(EX + "I"), RDF.value, member));
        assertTrue(c.containsLiteral(c.getResource(EX + "E"), RDF.value, member));
        assertTrue(c.containsLiteral(c.getResource(EX + "C"), RDF.value, member));
        assertTrue(c.containsLiteral(c.getResource(EX + "D"), RDF.value, false));
        assertEquals(4, c.listStatements(null, RDF.value, (RDFNode) null).toList().size());
        c.listStatements().forEachRemaining(st -> assertFalse(st.getPredicate().getURI().startsWith(TMF), st::toString));
    }

    @Test
    void theEngineLoadsTheLogicRuleFilesInTheirStages() {
        assertEquals("tio_a_core.rules", JenaEvaluationEngine.STAGE_A.get(0));
        assertTrue(JenaEvaluationEngine.STAGE_A.contains("tio_a_logic.rules"));
        assertTrue(JenaEvaluationEngine.STAGE_B.contains("tio_b_logic.rules"));
        for (String f : List.of("tio_a_core.rules", "tio_a_logic.rules", "tio_b_logic.rules")) {
            assertFalse(JenaEvaluationEngine.parseRules(f).isEmpty(), f);
        }
    }

    @Test
    void cost() {
        // a 20-condition intent, like the spike's cost case, through the real rule files
        StringBuilder t = new StringBuilder("ex:I a icm:Intent ; log:allOf ( ex:top ) .\n");
        for (int i = 0; i < 10; i++) t.append(leaf("l" + i, i % 3 != 0));
        for (int i = 0; i < 5; i++) t.append("ex:g").append(i).append(" a icm:Condition ; log:")
                .append(i % 2 == 0 ? "allOf" : "anyOf").append(" ( ex:l").append(i).append(" ex:l")
                .append(i + 1).append(" ex:l").append(i + 2).append(" ex:l").append(i + 3).append(" ) .\n");
        for (int i = 0; i < 4; i++) t.append("ex:h").append(i).append(" a icm:Condition ; log:")
                .append(i % 2 == 0 ? "noneOf" : "oneOf").append(" ( ex:g").append(i).append(" ex:g")
                .append(i + 1).append(" ) .\n");
        t.append("ex:top a icm:Condition ; log:allOf ( ex:h0 ex:h1 ex:h2 ex:h3 ) .\n");
        String turtle = t.toString();
        for (boolean withOntology : ontologyModes()) {
            for (int i = 0; i < 20; i++) run(turtle, 0, withOntology);
            int n = 50;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                InfModel m = run(turtle, 0, withOntology);
                assertEquals(1, values(m, "I").size());
            }
            double ms = (System.nanoTime() - t0) / 1e6 / n;
            System.out.printf("logic rules, 20 conditions, ontology %s: %.2f ms per evaluation%n", withOntology, ms);
            assertTrue(ms < 500, "the chain should be cheap");
        }
    }
}
