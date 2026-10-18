package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.StringReader;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spike (Task 10): negation across chained rule stages, for the TR292E logical operators
 * {@code log:allOf/anyOf/noneOf/oneOf} over variable-length RDF lists whose members get their values from an
 * earlier stage (or from stage B itself, when operators are nested).
 *
 * <p>Two stage-B rule sets are compared:
 * <ul>
 *   <li>{@link #NAIVE_B}: "all true" read as "no member that is not true" ({@code noValue} over member values,
 *       which stage B itself derives for nested operators), plus a default-false rule guarded by
 *       {@code noValue(?c rdf:value)}. Kept to document why it is wrong.</li>
 *   <li>{@link #STRATIFIED_B} (the chosen pattern): every operator is computed <em>monotonically</em> by walking
 *       its list from the tail ({@code rdf:nil}) and carrying a per-cell summary (number of true members capped
 *       at 2, whether any member is false). The only negation is over facts that are complete when stage B
 *       starts (stage A's saturated output and input structure): default-false for a typed condition that has
 *       no contributing function, and dropping a member that has no value and can never get one.</li>
 * </ul>
 * Every case runs under both chainings ({@code createInfModel(stageB, stageAInf)} and stage A saturated into a
 * plain model first), with several fixed-seed shuffles of the input triples and of the rule order; all runs
 * must give the same, expected, result.
 */
class RuleStagesSpikeTest {

    static final String EX = "http://example.org/";
    static final String LOG = "http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/";

    static final String PFX = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix fun:  <http://tio.models.tmforum.org/tio/v3.6.0/FunctionOntology/> .
            @prefix log:  <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
            @prefix tmf:  <http://tmforum.org/api/v5/> .
            @prefix ex:   <http://example.org/> .
            """;

    /** Ontology facts the rules rely on (in the real engine they come from the TIO ontology TTL files). */
    static final String ONTOLOGY = """
            log:allOf a fun:Function . log:anyOf a fun:Function .
            log:noneOf a fun:Function . log:oneOf a fun:Function .
            """;

    // ── Stage A: positive facts only ─────────────────────────────────────────────
    // Member values are derived (two hops, so they are not input facts), and the structural fact
    // "this element has a contributing function" is derived here so that stage B can negate it safely.
    static final String STAGE_A = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix fun: <http://tio.models.tmforum.org/tio/v3.6.0/FunctionOntology/> .
            @prefix tmf: <http://tmforum.org/api/v5/> .
            @prefix ex:  <http://example.org/> .
            [obs1: (?m ex:obs ?v) -> (?m ex:seen ?v)]
            [obs2: (?m ex:seen ?v) -> (?m rdf:value ?v)]
            [contributor: (?c ?p ?args) (?p rdf:type fun:Function) -> (?c tmf:hasContributor ?p)]
            """;

    // ── Stage B, chosen pattern: monotone list walk + negation only over stage-A-complete facts ──
    static final String STRATIFIED_B = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix log: <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
            @prefix tmf: <http://tmforum.org/api/v5/> .

            # Default false (TR292E §4): a typed condition with no contributing function and no value.
            # Safe: hasContributor comes from stage A, and nothing in stage B gives a value to an element
            # that has no contributor except this rule.
            [default: (?c rdf:type log:Condition) noValue(?c tmf:hasContributor) noValue(?c rdf:value)
                -> (?c rdf:value "false"^^xsd:boolean)]

            # Mark the cells of every operator's argument list.
            [listAll:  (?c log:allOf ?l)  -> (?l rdf:type tmf:LogCell)]
            [listAny:  (?c log:anyOf ?l)  -> (?l rdf:type tmf:LogCell)]
            [listNone: (?c log:noneOf ?l) -> (?l rdf:type tmf:LogCell)]
            [listOne:  (?c log:oneOf ?l)  -> (?l rdf:type tmf:LogCell)]
            [listRest: (?l rdf:type tmf:LogCell) (?l rdf:rest ?r) -> (?r rdf:type tmf:LogCell)]

            # Classify each cell's member: T, F, or Skip (TR292E §2: non-boolean arguments are removed).
            [argT:  (?x rdf:type tmf:LogCell) (?x rdf:first ?m) (?m rdf:value "true"^^xsd:boolean)  -> (?x tmf:arg tmf:T)]
            [argF:  (?x rdf:type tmf:LogCell) (?x rdf:first ?m) (?m rdf:value "false"^^xsd:boolean) -> (?x tmf:arg tmf:F)]
            [argLT: (?x rdf:type tmf:LogCell) (?x rdf:first "true"^^xsd:boolean)  -> (?x tmf:arg tmf:T)]
            [argLF: (?x rdf:type tmf:LogCell) (?x rdf:first "false"^^xsd:boolean) -> (?x tmf:arg tmf:F)]
            [argNonBoolValue: (?x rdf:type tmf:LogCell) (?x rdf:first ?m) (?m rdf:value ?v) notDType(?v xsd:boolean)
                -> (?x tmf:arg tmf:Skip)]
            [argNonBoolLiteral: (?x rdf:type tmf:LogCell) (?x rdf:first ?m) isLiteral(?m) notDType(?m xsd:boolean)
                -> (?x tmf:arg tmf:Skip)]
            # A member that has no value and can never get one (untyped, no contributor). Safe for the
            # same reason as the default rule.
            [argNoValue: (?x rdf:type tmf:LogCell) (?x rdf:first ?m) notLiteral(?m)
                noValue(?m rdf:value) noValue(?m tmf:hasContributor) noValue(?m rdf:type log:Condition)
                -> (?x tmf:arg tmf:Skip)]

            # Walk from the tail. Per cell: tmf:trues N0|N1|N2 (true members in this suffix, capped at 2)
            # and tmf:hasFalse Yes|No. A cell's summary exists only once its whole suffix is resolved.
            [nil: -> (rdf:nil tmf:trues tmf:N0) (rdf:nil tmf:hasFalse tmf:No)]
            [walkT0: (?x tmf:arg tmf:T) (?x rdf:rest ?r) (?r tmf:trues tmf:N0) -> (?x tmf:trues tmf:N1)]
            [walkT1: (?x tmf:arg tmf:T) (?x rdf:rest ?r) (?r tmf:trues tmf:N1) -> (?x tmf:trues tmf:N2)]
            [walkT2: (?x tmf:arg tmf:T) (?x rdf:rest ?r) (?r tmf:trues tmf:N2) -> (?x tmf:trues tmf:N2)]
            [walkTF: (?x tmf:arg tmf:T) (?x rdf:rest ?r) (?r tmf:hasFalse ?h) -> (?x tmf:hasFalse ?h)]
            [walkFN: (?x tmf:arg tmf:F) (?x rdf:rest ?r) (?r tmf:trues ?n) -> (?x tmf:trues ?n)]
            [walkFF: (?x tmf:arg tmf:F) (?x rdf:rest ?r) (?r tmf:hasFalse ?h) -> (?x tmf:hasFalse tmf:Yes)]
            [walkSN: (?x tmf:arg tmf:Skip) (?x rdf:rest ?r) (?r tmf:trues ?n) -> (?x tmf:trues ?n)]
            [walkSF: (?x tmf:arg tmf:Skip) (?x rdf:rest ?r) (?r tmf:hasFalse ?h) -> (?x tmf:hasFalse ?h)]

            # Operators read the summary of their list's head cell (TR292E §6).
            [allT: (?c log:allOf ?l) (?l tmf:hasFalse tmf:No) (?l tmf:trues ?n) notEqual(?n tmf:N0)
                -> (?c rdf:value "true"^^xsd:boolean)]
            [allF: (?c log:allOf ?l) (?l tmf:hasFalse tmf:Yes) -> (?c rdf:value "false"^^xsd:boolean)]
            [allE: (?c log:allOf ?l) (?l tmf:trues tmf:N0)     -> (?c rdf:value "false"^^xsd:boolean)]
            [anyF: (?c log:anyOf ?l) (?l tmf:trues tmf:N0) -> (?c rdf:value "false"^^xsd:boolean)]
            [anyT: (?c log:anyOf ?l) (?l tmf:trues ?n) notEqual(?n tmf:N0) -> (?c rdf:value "true"^^xsd:boolean)]
            [noneT: (?c log:noneOf ?l) (?l tmf:trues tmf:N0) -> (?c rdf:value "true"^^xsd:boolean)]
            [noneF: (?c log:noneOf ?l) (?l tmf:trues ?n) notEqual(?n tmf:N0) -> (?c rdf:value "false"^^xsd:boolean)]
            [oneT: (?c log:oneOf ?l) (?l tmf:trues tmf:N1) -> (?c rdf:value "true"^^xsd:boolean)]
            [oneF: (?c log:oneOf ?l) (?l tmf:trues ?n) notEqual(?n tmf:N1) -> (?c rdf:value "false"^^xsd:boolean)]
            """;

    // ── Stage B, naive pattern (documented as wrong): negation over values stage B itself derives ──
    static final String NAIVE_B = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix log: <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
            @prefix tmf: <http://tmforum.org/api/v5/> .
            [default: (?c rdf:type log:Condition) noValue(?c rdf:value) -> (?c rdf:value "false"^^xsd:boolean)]
            [cell0: (?c log:allOf ?l) -> (?l tmf:cellOf ?c)]
            [cellN: (?l tmf:cellOf ?c) (?l rdf:rest ?r) -> (?r tmf:cellOf ?c)]
            [notTrue: (?x tmf:cellOf ?c) (?x rdf:first ?m) noValue(?m rdf:value "true"^^xsd:boolean)
                -> (?c tmf:hasNonTrue ?m)]
            [allT: (?c log:allOf ?l) notEqual(?l rdf:nil) noValue(?c tmf:hasNonTrue)
                -> (?c rdf:value "true"^^xsd:boolean)]
            [allF: (?c log:allOf ?l) (?c tmf:hasNonTrue ?m) -> (?c rdf:value "false"^^xsd:boolean)]
            [allE: (?c log:allOf rdf:nil) -> (?c rdf:value "false"^^xsd:boolean)]
            """;

    enum Chaining { CHAINED, SATURATED }

    static final long[] SEEDS = {1, 7, 42, 1234, 99991};

    // ── Running the stages ──────────────────────────────────────────────────────

    static List<Rule> rules(String text, long seed) {
        List<Rule> rules = new ArrayList<>(Rule.parseRules(text));
        if (seed != 0) Collections.shuffle(rules, new Random(seed * 31 + 5));
        return rules;
    }

    static InfModel stage(List<Rule> rules, Model base) {
        GenericRuleReasoner r = new GenericRuleReasoner(rules);
        r.setMode(GenericRuleReasoner.FORWARD_RETE);
        InfModel inf = ModelFactory.createInfModel(r, base);
        inf.prepare();
        return inf;
    }

    /** Input triples, added one by one in a seed-dependent order (seed 0 = document order). */
    static Model input(String turtle, long seed) {
        Model parsed = ModelFactory.createDefaultModel();
        parsed.read(new StringReader(PFX + ONTOLOGY + turtle), null, "TURTLE");
        List<Statement> stmts = new ArrayList<>(parsed.listStatements().toList());
        if (seed != 0) Collections.shuffle(stmts, new Random(seed));
        Model base = ModelFactory.createDefaultModel();
        stmts.forEach(base::add);
        return base;
    }

    static Model run(String stageB, Chaining chaining, String turtle, long seed) {
        InfModel a = stage(rules(STAGE_A, seed), input(turtle, seed));
        Model overA = a;
        if (chaining == Chaining.SATURATED) {
            overA = ModelFactory.createDefaultModel();
            overA.add(a); // copies base + inferred triples
        }
        return stage(rules(stageB, seed), overA);
    }

    /** The set of rdf:value objects of an element, as "true"/"false"/other lexical forms. */
    static Set<String> values(Model m, String local) {
        Set<String> out = new TreeSet<>();
        m.listObjectsOfProperty(m.getResource(EX + local), RDF.value).forEach(n -> out.add(lex(n)));
        return out;
    }

    static String lex(RDFNode n) {
        return n.isLiteral() ? ((Literal) n).getLexicalForm() : n.toString();
    }

    /**
     * Runs a case under every chaining and every seed and asserts each element has exactly the expected
     * value (a single "true" or "false") in all runs.
     */
    static void assertAllRuns(String turtle, Map<String, Boolean> expected) {
        for (Chaining ch : Chaining.values()) {
            for (long seed : withDocumentOrder()) {
                Model m = run(STRATIFIED_B, ch, turtle, seed);
                for (var e : expected.entrySet()) {
                    assertEquals(Set.of(e.getValue().toString()), values(m, e.getKey()),
                            () -> e.getKey() + " under " + ch + ", seed " + seed + "\n" + turtle);
                }
            }
        }
    }

    static long[] withDocumentOrder() {
        long[] s = new long[SEEDS.length + 1];
        System.arraycopy(SEEDS, 0, s, 1, SEEDS.length);
        return s;
    }

    // ── Truth tables: 3 members, every true/false combination, every operator ──

    static final Map<String, Function<boolean[], Boolean>> OPERATORS = new LinkedHashMap<>();
    static {
        OPERATORS.put("allOf", v -> v.length > 0 && count(v) == v.length);
        OPERATORS.put("anyOf", v -> count(v) > 0);
        OPERATORS.put("noneOf", v -> count(v) == 0);
        OPERATORS.put("oneOf", v -> count(v) == 1);
    }

    static int count(boolean[] v) {
        int n = 0;
        for (boolean b : v) if (b) n++;
        return n;
    }

    static Stream<Arguments> truthTable() {
        List<Arguments> out = new ArrayList<>();
        for (String op : OPERATORS.keySet())
            for (int bits = 0; bits < 8; bits++)
                for (boolean typedMembers : new boolean[]{false, true})
                    out.add(Arguments.of(op, bits, typedMembers));
        return out.stream();
    }

    @ParameterizedTest(name = "{0} bits={1} typedMembers={2}")
    @MethodSource("truthTable")
    void operatorOverThreeDerivedMembers(String op, int bits, boolean typedMembers) {
        boolean[] v = {(bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0};
        StringBuilder t = new StringBuilder("ex:c a log:Condition ; log:" + op + " ( ex:a ex:b ex:c2 ) .\n");
        String[] names = {"a", "b", "c2"};
        for (int i = 0; i < 3; i++) {
            if (typedMembers) t.append("ex:").append(names[i]).append(" a log:Condition .\n");
            t.append("ex:").append(names[i]).append(" ex:obs \"").append(v[i]).append("\"^^xsd:boolean .\n");
        }
        assertAllRuns(t.toString(), Map.of("c", OPERATORS.get(op).apply(v),
                "a", v[0], "b", v[1], "c2", v[2]));
    }

    static Stream<Arguments> operators() {
        return OPERATORS.keySet().stream().map(Arguments::of);
    }

    // ── Empty list (TR292E §6: allOf/anyOf/oneOf empty = false, noneOf empty = true) ──

    @ParameterizedTest(name = "{0} ( )")
    @MethodSource("operators")
    void emptyList(String op) {
        assertAllRuns("ex:c a log:Condition ; log:" + op + " ( ) .\n",
                Map.of("c", OPERATORS.get(op).apply(new boolean[0])));
    }

    // ── Non-boolean members are dropped (TR292E §2) ─────────────────────────────

    @ParameterizedTest(name = "{0} with non-boolean members")
    @MethodSource("operators")
    void nonBooleanMembersAreDropped(String op) {
        // ex:a true, ex:n has a decimal value, ex:s a string literal member, ex:u no value at all (untyped)
        String t = "ex:c a log:Condition ; log:" + op + " ( ex:n ex:a \"x\" ex:u 5 ) .\n"
                + "ex:a ex:obs \"true\"^^xsd:boolean .\nex:n ex:obs \"3.5\"^^xsd:decimal .\n";
        assertAllRuns(t, Map.of("c", OPERATORS.get(op).apply(new boolean[]{true})));
    }

    @ParameterizedTest(name = "{0} with only non-boolean members")
    @MethodSource("operators")
    void onlyNonBooleanMembersBehavesAsEmpty(String op) {
        String t = "ex:c a log:Condition ; log:" + op + " ( ex:n ex:u ) .\nex:n ex:obs 4 .\n";
        assertAllRuns(t, Map.of("c", OPERATORS.get(op).apply(new boolean[0])));
    }

    @ParameterizedTest(name = "{0} with literal boolean members")
    @MethodSource("operators")
    void literalBooleanMembers(String op) {
        String t = "ex:c a log:Condition ; log:" + op + " ( true false ) .\n";
        assertAllRuns(t, Map.of("c", OPERATORS.get(op).apply(new boolean[]{true, false})));
    }

    // ── Default false and nesting: the ordering trap ────────────────────────────

    @Test
    void typedConditionWithoutValueDefaultsToFalse() {
        assertAllRuns("ex:z a log:Condition .\n", Map.of("z", false));
    }

    @Test
    void memberWithoutValueThatIsATypedConditionCountsAsFalse() {
        String t = """
                ex:c a log:Condition ; log:allOf ( ex:a ex:z ) .
                ex:d a log:Condition ; log:noneOf ( ex:z ) .
                ex:a ex:obs true .
                ex:z a log:Condition .
                """;
        assertAllRuns(t, Map.of("c", false, "d", true, "z", false, "a", true));
    }

    static Stream<Arguments> nested() {
        // allOf( x, allOf(y, z) ): z only gets its value from the default rule (typed) or is dropped (untyped)
        List<Arguments> out = new ArrayList<>();
        for (boolean x : new boolean[]{true, false})
            for (boolean y : new boolean[]{true, false})
                for (String z : new String[]{"true", "false", "default", "untyped"})
                    out.add(Arguments.of(x, y, z));
        return out.stream();
    }

    @ParameterizedTest(name = "allOf(x={0}, allOf(y={1}, z={2}))")
    @MethodSource("nested")
    void nestedAllOf_withInnerMemberResolvedByDefault(boolean x, boolean y, String z) {
        StringBuilder t = new StringBuilder("""
                ex:o a log:Condition ; log:allOf ( ex:x ex:i ) .
                ex:i a log:Condition ; log:allOf ( ex:y ex:z ) .
                ex:x a log:Condition . ex:y a log:Condition .
                """);
        t.append("ex:x ex:obs ").append(x).append(" .\nex:y ex:obs ").append(y).append(" .\n");
        switch (z) {
            case "true", "false" -> t.append("ex:z a log:Condition ; ex:obs ").append(z).append(" .\n");
            case "default" -> t.append("ex:z a log:Condition .\n");
            default -> { } // untyped, no value: dropped from the inner list
        }
        boolean inner = switch (z) {
            case "true" -> y;
            case "untyped" -> y;   // allOf(y)
            default -> false;      // z false (explicitly or by default)
        };
        Map<String, Boolean> exp = new HashMap<>(Map.of("o", x && inner, "i", inner, "x", x, "y", y));
        if (!z.equals("untyped")) exp.put("z", z.equals("true"));
        assertAllRuns(t.toString(), exp);
    }

    @Test
    void deeperNestingMixingOperators() {
        // o = oneOf( anyOf(a, noneOf(b, d)), allOf(e), noneOf(oneOf(a, b)) ), d typed with no value
        String t = """
                ex:o a log:Condition ; log:oneOf ( ex:p ex:q ex:r ) .
                ex:p a log:Condition ; log:anyOf ( ex:a ex:s ) .
                ex:s a log:Condition ; log:noneOf ( ex:b ex:d ) .
                ex:q a log:Condition ; log:allOf ( ex:e ) .
                ex:r a log:Condition ; log:noneOf ( ex:t ) .
                ex:t a log:Condition ; log:oneOf ( ex:a ex:b ) .
                ex:a ex:obs false . ex:b ex:obs false . ex:e ex:obs false .
                ex:d a log:Condition .
                """;
        // d=false (default); s = noneOf(F,F) = T; p = anyOf(F,T) = T; q = F; t = oneOf(F,F) = F; r = T
        // o = oneOf(T, F, T) = F
        assertAllRuns(t, Map.of("d", false, "s", true, "p", true, "q", false, "t", false, "r", true, "o", false));
    }

    // ── The naive pattern is wrong: documents why the walk is needed ────────────

    static final String NESTED_ALL_TRUE = """
            ex:o a log:Condition ; log:allOf ( ex:x ex:i ) .
            ex:i a log:Condition ; log:allOf ( ex:y ex:j ) .
            ex:j a log:Condition ; log:allOf ( ex:w ex:k ) .
            ex:k a log:Condition ; log:allOf ( ex:v ) .
            ex:x ex:obs true . ex:y ex:obs true . ex:w ex:obs true . ex:v ex:obs true .
            """;

    static final String DEFAULTED_MEMBER = """
            ex:o a log:Condition ; log:allOf ( ex:x ex:z ) .
            ex:z a log:Condition .
            ex:x ex:obs true .
            """;

    /**
     * The naive pattern's result depends on firing order, which depends on rule order, triple order and hash
     * order (blank-node list cells get fresh ids each JVM run). Observed while spiking, over 100 seeds:
     * NESTED_ALL_TRUE is wrong in about 80-90% of CHAINED runs and 100% of SATURATED runs; DEFAULTED_MEMBER
     * gives ex:o both true and false in 100% of runs in both modes. The assertion only needs one wrong run.
     */
    @Test
    void naivePattern_givesWrongAnswersInBothChainings() {
        for (Chaining ch : Chaining.values()) {
            int nestedWrong = 0, defaultWrong = 0;
            for (long seed = 0; seed < 100; seed++) {
                Model m = run(NAIVE_B, ch, NESTED_ALL_TRUE, seed);
                if (!values(m, "o").equals(Set.of("true")) || !values(m, "i").equals(Set.of("true"))) nestedWrong++;
                Model d = run(NAIVE_B, ch, DEFAULTED_MEMBER, seed);
                if (!values(d, "o").equals(Set.of("false"))) defaultWrong++;
            }
            System.out.printf("naive pattern, %s: nested all-true wrong in %d/100, defaulted member wrong in %d/100%n",
                    ch, nestedWrong, defaultWrong);
            assertTrue(nestedWrong > 0 && defaultWrong > 0, "expected the naive pattern to go wrong under " + ch);
        }
    }

    @Test
    void chosenPattern_getsTheNaiveFailuresRight() {
        assertAllRuns(NESTED_ALL_TRUE, Map.of("o", true, "i", true, "j", true, "k", true));
        assertAllRuns(DEFAULTED_MEMBER, Map.of("o", false, "z", false, "x", true));
    }

    @Test
    void chosenPattern_isStableOverManyOrders() {
        String t = """
                ex:o a log:Condition ; log:allOf ( ex:x ex:i ) .
                ex:i a log:Condition ; log:anyOf ( ex:y ex:j ) .
                ex:j a log:Condition ; log:noneOf ( ex:z ex:w ) .
                ex:x ex:obs true . ex:y ex:obs false . ex:w ex:obs false .
                ex:z a log:Condition .
                """;
        for (Chaining ch : Chaining.values())
            for (long seed = 0; seed < 100; seed++) {
                Model m = run(STRATIFIED_B, ch, t, seed);
                for (String e : List.of("o", "i", "j", "x"))
                    assertEquals(Set.of("true"), values(m, e), e + " under " + ch + ", seed " + seed);
                for (String e : List.of("y", "z", "w"))
                    assertEquals(Set.of("false"), values(m, e), e + " under " + ch + ", seed " + seed);
            }
    }

    // ── Cost ────────────────────────────────────────────────────────────────────

    @Test
    void cost_ofTheChainOnASmallModel() {
        // 20 conditions: 10 leaves with derived values, 5 allOf/anyOf of 4 leaves, 4 noneOf/oneOf over those,
        // one top-level allOf: roughly the size of a real intent's logic.
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 10; i++) t.append("ex:l").append(i).append(" a log:Condition ; ex:obs ")
                .append(i % 3 != 0).append(" .\n");
        for (int i = 0; i < 5; i++) t.append("ex:g").append(i).append(" a log:Condition ; log:")
                .append(i % 2 == 0 ? "allOf" : "anyOf").append(" ( ex:l").append(i).append(" ex:l")
                .append(i + 1).append(" ex:l").append(i + 2).append(" ex:l").append(i + 3).append(" ) .\n");
        for (int i = 0; i < 4; i++) t.append("ex:h").append(i).append(" a log:Condition ; log:")
                .append(i % 2 == 0 ? "noneOf" : "oneOf").append(" ( ex:g").append(i).append(" ex:g")
                .append(i + 1).append(" ) .\n");
        t.append("ex:top a log:Condition ; log:allOf ( ex:h0 ex:h1 ex:h2 ex:h3 ) .\n");
        String turtle = t.toString();

        for (Chaining ch : Chaining.values()) {
            for (int i = 0; i < 50; i++) run(STRATIFIED_B, ch, turtle, 0); // warm-up
            int n = 200;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                Model m = run(STRATIFIED_B, ch, turtle, 0);
                assertEquals(1, values(m, "top").size());
            }
            double ms = (System.nanoTime() - t0) / 1e6 / n;
            System.out.printf("stage chain %s: %.3f ms per evaluation (parse rules + input + A + B)%n", ch, ms);
            assertTrue(ms < 200, "the chain should be cheap");
        }
    }
}
