package org.tmforum.intent.unit;

import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.handler.JenaBuiltins;

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The quantity builtins used by the stage A rules ({@code tio_a_quantity.rules}), exercised through a real
 * {@link GenericRuleReasoner}: each test case is a subject {@code ex:tN} carrying the builtin's inputs, and a rule
 * records what the builtin bound (or that it succeeded).
 */
class JenaBuiltinsTest {

    static final String PREFIXES = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix met:  <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
            @prefix ex:   <http://example.org/> .
            """;
    static final String EX = "http://example.org/";

    @BeforeAll
    static void register() {
        JenaBuiltins.register();
        JenaBuiltins.register();   // idempotent
    }

    private static InfModel run(String data, String rules) {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(PREFIXES + data), null, "TTL");
        GenericRuleReasoner r = new GenericRuleReasoner(Rule.parseRules(PREFIXES + rules));
        r.setMode(GenericRuleReasoner.FORWARD_RETE);
        InfModel inf = ModelFactory.createInfModel(r, m);
        inf.prepare();
        return inf;
    }

    private static Set<String> objects(Model m, String subject, String property) {
        Set<String> out = new TreeSet<>();
        Resource s = m.getResource(EX + subject);
        m.listObjectsOfProperty(s, m.getProperty(EX + property)).forEach(n -> out.add(text(n)));
        return out;
    }

    private static String text(RDFNode n) {
        if (n.isLiteral()) return n.asLiteral().getLexicalForm();
        return n.isURIResource() ? n.asResource().getURI().substring(EX.length()) : "_";
    }

    // ── unitCompare ───────────────────────────────────────────────────────────────

    static final String COMPARE = """
            [t: (?t ex:va ?va) (?t ex:ua ?ua) (?t ex:vb ?vb) (?t ex:ub ?ub)
                unitCompare(?va, ?ua, ?vb, ?ub, ?c) -> (?t ex:cmp ?c)]
            """;

    private static Set<String> compare(String va, String ua, String vb, String ub) {
        String data = "ex:t ex:va " + va + " ; ex:ua \"" + ua + "\" ; ex:vb " + vb + " ; ex:ub \"" + ub + "\" .";
        return objects(run(data, COMPARE), "t", "cmp");
    }

    @Test
    void unitCompare_sameUnitComparesTheNumbers() {
        assertEquals(Set.of("-1"), compare("8", "ms", "25", "ms"));
        assertEquals(Set.of("0"), compare("25", "ms", "25", "ms"));
        assertEquals(Set.of("1"), compare("150", "Mbps", "100", "Mbps"));
    }

    @Test
    void unitCompare_prefixesAreConverted() {
        assertEquals(Set.of("0"), compare("1", "Gbps", "1000", "Mbps"));
        assertEquals(Set.of("1"), compare("2", "Gbps", "100", "Mbps"));
        assertEquals(Set.of("-1"), compare("0.5", "s", "600", "ms"));
        assertEquals(Set.of("0"), compare("1", "km", "1000", "m"));
        assertEquals(Set.of("0"), compare("0.15", "Gbps", "150", "Mbps"));
    }

    @Test
    void unitCompare_binaryPrefixesAndNamedUnits() {
        assertEquals(Set.of("0"), compare("1", "Kibit", "1024", "bit"));
        assertEquals(Set.of("-1"), compare("1", "kbit", "1024", "bit"));
        assertEquals(Set.of("0"), compare("1", "min", "60", "s"));
        assertEquals(Set.of("0"), compare("5", "percent", "5", "%"));
    }

    @Test
    void unitCompare_valuesWithoutUnitCompareAsPlainNumbers() {
        assertEquals(Set.of("-1"), compare("10", "", "20", ""));
    }

    @Test
    void unitCompare_integerDecimalAndDoubleCompareNumericallyNotLexically() {
        assertEquals(Set.of("0"), compare("\"1.5E2\"^^xsd:double", "Mbps", "150", "Mbps"));
        assertEquals(Set.of("0"), compare("\"150\"^^xsd:integer", "Mbps", "\"150.000\"^^xsd:decimal", "Mbps"));
        assertEquals(Set.of("1"), compare("\"10\"^^xsd:integer", "", "\"9\"^^xsd:decimal", ""),
                "10 > 9 numerically although \"10\" < \"9\" lexically");
        assertEquals(Set.of("-1"), compare("\"9\"^^xsd:integer", "", "\"10\"^^xsd:integer", ""));
    }

    @Test
    void unitCompare_failsOnAUnitMismatch() {
        assertEquals(Set.of(), compare("8", "ms", "25", "Mbps"));
        assertEquals(Set.of(), compare("1", "m", "1", "s"));
        assertEquals(Set.of(), compare("150", "", "100", "Mbps"));
        assertEquals(Set.of(), compare("1", "widgets", "2", "gadgets"));
    }

    @Test
    void unitCompare_failsWithoutThrowingOnOddInput() {
        assertEquals(Set.of(), compare("\"abc\"", "ms", "25", "ms"));
        assertEquals(Set.of(), compare("\"NaN\"^^xsd:double", "", "1", ""));
        assertEquals(Set.of(), compare("ex:notALiteral", "", "1", ""));
        // unbound arguments: the builtin is called with variables only
        InfModel inf = run("ex:t ex:p 1 .", "[t: (?t ex:p ?x) unitCompare(?a, ?b, ?c, ?d, ?e) -> (?t ex:cmp ?e)]");
        assertEquals(Set.of(), objects(inf, "t", "cmp"));
    }

    @Test
    void unitCompare_failsWithoutThrowingOnExtremeExponents() {
        // scale 2147483647: multiplying by the unit factor would underflow the BigDecimal scale
        assertEquals(Set.of(), compare("\"1E-2147483647\"^^xsd:decimal", "ms", "25", "ms"));
        assertEquals(Set.of(), compare("\"1E+2147483647\"^^xsd:double", "ms", "25", "ms"));
        assertEquals(Set.of(), compare("25", "ms", "\"1E-2147483647\"", "s"));
        assertEquals(Set.of(), compare("\"1E-20000\"", "", "1", ""));
        // large but sane exponents still compare
        assertEquals(Set.of("1"), compare("\"1E+300\"^^xsd:double", "Gbps", "1", "bps"));
        assertEquals(Set.of("-1"), compare("\"1E-300\"^^xsd:double", "ps", "1", "s"));
    }

    @Test
    void numbersWithExtremeExponentsAreNotNumbers() {
        InfModel inf = run("ex:a ex:v \"1E-2147483647\" . ex:b ex:v \"1E+2147483647\" . ex:c ex:v \"1E-300\" .", """
                [a: (?t ex:v ?v) isNumber(?v) -> (?t ex:num 'yes')]
                [b: (?t ex:v ?v) notNumber(?v) -> (?t ex:num 'no')]
                """);
        assertEquals(Set.of("no"), objects(inf, "a", "num"));
        assertEquals(Set.of("no"), objects(inf, "b", "num"));
        assertEquals(Set.of("yes"), objects(inf, "c", "num"));
    }

    @Test
    void unitCompare_checksAnAlreadyBoundResult() {
        InfModel inf = run("ex:t ex:va 1 ; ex:ua \"Gbps\" ; ex:vb 1000 ; ex:ub \"Mbps\" .",
                "[t: (?t ex:va ?va) (?t ex:ua ?ua) (?t ex:vb ?vb) (?t ex:ub ?ub) unitCompare(?va, ?ua, ?vb, ?ub, 0)"
                        + " -> (?t ex:equal 'yes')]");
        assertEquals(Set.of("yes"), objects(inf, "t", "equal"));
    }

    // ── unitMismatch ──────────────────────────────────────────────────────────────

    private static Set<String> mismatch(String ua, String ub) {
        String rules = """
                [t2: (?t ex:ua ?ua) (?t ex:ub ?ub) unitMismatch(?ua, ?ub) -> (?t ex:mismatch 'yes')]
                [t3: (?t ex:ua ?ua) (?t ex:ub ?ub) unitMismatch(?ua, ?ub, ?msg) -> (?t ex:message ?msg)]
                """;
        InfModel inf = run("ex:t ex:ua \"" + ua + "\" ; ex:ub \"" + ub + "\" .", rules);
        Set<String> out = new TreeSet<>(objects(inf, "t", "mismatch"));
        out.addAll(objects(inf, "t", "message"));
        return out;
    }

    @Test
    void unitMismatch_succeedsWhenDimensionsDiffer() {
        assertEquals(Set.of("yes", "unit mismatch: 'ms' cannot be compared with 'Mbps'"), mismatch("ms", "Mbps"));
        assertEquals(Set.of("yes", "unit mismatch: a value without unit cannot be compared with 'Mbps'"),
                mismatch("", "Mbps"));
        assertEquals(Set.of("yes", "unit mismatch: 'Mbps' cannot be compared with a value without unit"),
                mismatch("Mbps", ""));
        assertEquals(Set.of("yes", "unit mismatch: 'widgets' cannot be compared with 'gadgets'"),
                mismatch("widgets", "gadgets"));
    }

    @Test
    void unitMismatch_failsWhenDimensionsMatch() {
        assertEquals(Set.of(), mismatch("Gbps", "Mbps"));
        assertEquals(Set.of(), mismatch("s", "ms"));
        assertEquals(Set.of(), mismatch("", ""));
        assertEquals(Set.of(), mismatch("widgets", "widgets"));
    }

    @Test
    void unitMismatch_failsWithoutThrowingOnOddInput() {
        InfModel inf = run("ex:t ex:p 1 .", """
                [t: (?t ex:p ?x) unitMismatch(?a, ?b) -> (?t ex:mismatch 'yes')]
                [u: (?t ex:p ?x) unitMismatch(ex:a, 'ms') -> (?t ex:mismatch 'iri')]
                """);
        assertEquals(Set.of(), objects(inf, "t", "mismatch"));
    }

    // ── isNumber / notNumber ──────────────────────────────────────────────────────

    @Test
    void isNumberAndNotNumberAreComplementary() {
        String data = """
                ex:i ex:v 5 . ex:d ex:v 0.15 . ex:e ex:v "1.5E2"^^xsd:double . ex:s ex:v "abc" .
                ex:n ex:v "NaN"^^xsd:double . ex:r ex:v ex:x .
                """;
        InfModel inf = run(data, """
                [a: (?t ex:v ?v) isNumber(?v) -> (?t ex:num 'yes')]
                [b: (?t ex:v ?v) notNumber(?v) -> (?t ex:num 'no')]
                """);
        assertEquals(Set.of("yes"), objects(inf, "i", "num"));
        assertEquals(Set.of("yes"), objects(inf, "d", "num"));
        assertEquals(Set.of("yes"), objects(inf, "e", "num"));
        assertEquals(Set.of("no"), objects(inf, "s", "num"));
        assertEquals(Set.of("no"), objects(inf, "n", "num"));
        assertEquals(Set.of("no"), objects(inf, "r", "num"));
    }

    // ── newestObservation / noObservation ─────────────────────────────────────────

    static final String NEWEST = """
            [n: (?t ex:metric ?m) newestObservation(?m, ?o) -> (?t ex:newest ?o)]
            [z: (?t ex:metric ?m) noObservation(?m) -> (?t ex:none 'yes')]
            """;

    private static String obs(String id, String metric, String value, String at) {
        return "ex:" + id + " a met:Observation ; met:observedMetric ex:" + metric + " ; rdf:value " + value
                + (at == null ? "" : " ; met:obtainedAt \"" + at + "\"^^xsd:dateTime") + " .\n";
    }

    @Test
    void newestObservation_picksTheGreatestObtainedAtOfThree() {
        String data = "ex:t ex:metric ex:m .\n"
                + obs("o2", "m", "20", "2026-06-11T10:00:02Z")
                + obs("o3", "m", "30", "2026-06-11T10:00:03Z")
                + obs("o1", "m", "10", "2026-06-11T10:00:01Z")
                + obs("other", "n", "99", "2026-06-12T00:00:00Z");
        InfModel inf = run(data, NEWEST);
        assertEquals(Set.of("o3"), objects(inf, "t", "newest"));
        assertEquals(Set.of(), objects(inf, "t", "none"));
    }

    @Test
    void newestObservation_comparesInstantsNotLexicalForms() {
        // 11:00+02:00 is 09:00Z, older than 10:00Z although it sorts after it lexically
        String data = "ex:t ex:metric ex:m .\n"
                + obs("a", "m", "1", "2026-06-11T10:00:00Z")
                + obs("b", "m", "2", "2026-06-11T11:00:00+02:00");
        assertEquals(Set.of("a"), objects(run(data, NEWEST), "t", "newest"));
    }

    @Test
    void newestObservation_sameTimestampTieGoesToTheLexicallyLowestIri() {
        String data = "ex:t ex:metric ex:m .\n"
                + obs("obsB", "m", "2", "2026-06-11T10:00:00Z")
                + obs("obsA", "m", "1", "2026-06-11T10:00:00Z")
                + obs("obsC", "m", "3", "2026-06-11T10:00:00Z");
        assertEquals(Set.of("obsA"), objects(run(data, NEWEST), "t", "newest"));
    }

    @Test
    void newestObservation_missingObtainedAtCountsAsOldest() {
        String data = "ex:t ex:metric ex:m .\n"
                + obs("undated", "m", "1", null)
                + obs("dated", "m", "2", "2000-01-01T00:00:00Z");
        assertEquals(Set.of("dated"), objects(run(data, NEWEST), "t", "newest"));

        String onlyUndated = "ex:t ex:metric ex:m .\n" + obs("u2", "m", "1", null) + obs("u1", "m", "2", null);
        assertEquals(Set.of("u1"), objects(run(onlyUndated, NEWEST), "t", "newest"));
    }

    @Test
    void newestObservation_ignoresObservationsWithoutUsableValueOrType() {
        String data = """
                ex:t ex:metric ex:m .
                ex:noValue a met:Observation ; met:observedMetric ex:m ;
                    met:obtainedAt "2026-06-12T00:00:00Z"^^xsd:dateTime .
                ex:untyped met:observedMetric ex:m ; rdf:value 5 ;
                    met:obtainedAt "2026-06-12T00:00:00Z"^^xsd:dateTime .
                ex:nested a met:Observation ; met:observedMetric ex:m ; rdf:value [ rdf:value 7 ; quan:unit "ms" ] ;
                    met:obtainedAt "2026-06-11T00:00:00Z"^^xsd:dateTime .
                """;
        assertEquals(Set.of("nested"), objects(run(data, NEWEST), "t", "newest"));
    }

    @Test
    void noObservation_succeedsOnlyWhenThereIsNone() {
        String data = "ex:t ex:metric ex:m .\n" + obs("x", "other", "1", "2026-06-11T10:00:00Z");
        InfModel inf = run(data, NEWEST);
        assertEquals(Set.of(), objects(inf, "t", "newest"));
        assertEquals(Set.of("yes"), objects(inf, "t", "none"));
    }

    @Test
    void observationBuiltinsFailWithoutThrowingOnOddInput() {
        InfModel inf = run("ex:t ex:metric 5 . ex:u ex:p 1 .", NEWEST + """
                [u1: (?t ex:p ?x) newestObservation(?m, ?o) -> (?t ex:newest ?o)]
                [u2: (?t ex:p ?x) noObservation(?m) -> (?t ex:none 'yes')]
                """);
        assertEquals(Set.of(), objects(inf, "t", "newest"));
        assertEquals(Set.of(), objects(inf, "t", "none"));
        assertEquals(Set.of(), objects(inf, "u", "newest"));
        assertEquals(Set.of(), objects(inf, "u", "none"));
    }

    // ── allSubjectsIn, forAllCopy ─────────────────────────────────────────────────

    @Test
    void allSubjectsIn_readsItsUniverseFromTheRawGraph() {
        // universe: subjects of (? ex:in ex:A) in the input; each must have (? ex:ok ex:A) now
        String data = "ex:a ex:in ex:A ; ex:ok ex:A . ex:b ex:in ex:A . ex:t ex:c ex:A . ex:e ex:c ex:E .";
        String rules = """
                [ok: (?x ex:in ?c) (?x ex:mark ex:yes) -> (?x ex:ok ?c)]
                [all: (?t ex:c ?c) allSubjectsIn(ex:in, ?c, ex:ok, ?c) -> (?t ex:all 'yes')]
                """;
        assertEquals(Set.of(), objects(run(data, rules), "t", "all"), "ex:b is not ok");
        assertEquals(Set.of("yes"), objects(run(data + " ex:b ex:mark ex:yes .", rules), "t", "all"));
        assertEquals(Set.of("yes"), objects(run(data, rules), "e", "all"), "an empty universe: true");
    }

    @Test
    void allSubjectsIn_failsWithoutThrowingOnOddInput() {
        InfModel inf = run("ex:t ex:p 1 .", """
                [u1: (?t ex:p ?x) allSubjectsIn(?unbound, ex:A, ex:ok, ex:A) -> (?t ex:all 'yes')]
                [u2: (?t ex:p ?x) allSubjectsIn(?x, ex:A, ex:ok, ex:A) -> (?t ex:all2 'yes')]
                """);
        assertEquals(Set.of(), objects(inf, "t", "all"));
        assertEquals(Set.of(), objects(inf, "t", "all2"));
    }

    @Test
    void forAllCopy_copiesTheTemplateWithTheMemberAndIgnoresOddInput() {
        String data = """
                ex:k ex:var _:x ; ex:body _:q ; ex:member ex:m1 .
                _:q ex:f ( _:x 5 ) ; ex:label "q" .
                """;
        InfModel inf = run(data, """
                [c: (?k ex:var ?x) (?k ex:body ?q) (?k ex:member ?m) -> forAllCopy(?k, ?q, ?x, ?m)]
                [odd: (?k ex:var ?x) (?k ex:body ?q) -> forAllCopy(?k, 'literal', ?x, ex:m2) forAllCopy(?k, ?x, ?x, ex:m3)]
                """);
        Property copyFor = inf.getProperty(Namespaces.TMF + "copyFor");
        List<Resource> copies = inf.listSubjectsWithProperty(copyFor).toList();
        assertEquals(1, copies.size());
        Resource q2 = copies.get(0);
        assertEquals("q", inf.getProperty(q2, inf.getProperty(EX + "label")).getString());
        Resource list = inf.getProperty(q2, inf.getProperty(EX + "f")).getResource();
        assertEquals(EX + "m1", inf.getProperty(list, RDF.first).getResource().getURI());
        assertFalse(inf.contains(null, RDF.first, inf.getResource(EX + "m2")));
    }
}
