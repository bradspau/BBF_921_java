package org.tmforum.intent.unit;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.compose.Union;
import org.apache.jena.reasoner.InfGraph;
import org.apache.jena.rdf.model.InfModel;
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
import org.tmforum.intent.engine.ContractExtractor;
import org.tmforum.intent.engine.EvaluationInputs;
import org.tmforum.intent.engine.JenaEvaluationEngine;
import org.tmforum.intent.graph.Namespaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Set functions and pools ({@code rules/tio_a_sets.rules}, {@code rules/tio_b_sets.rules}; TR292F, function form of
 * TR292C §2), run through the engine's real two-stage chain ({@link JenaEvaluationEngine#infer}) with the input
 * triples and the rules of each stage shuffled by fixed seeds, with and without the licensed ontology.
 *
 * <p>Pool members are the helper facts {@code ?r tmf:memberOf ?pool}; they are never {@code rdfs:member}, so they never
 * reach the result contract (only chosen members do, Task 15). Every run also asserts the whole-model invariants of
 * {@link JenaLogicRulesTest}: each IRI element has exactly one boolean value, and stage B writes {@code rdf:value}
 * only on subjects with a stage-A contributor (or the default false).
 */
class JenaSetRulesTest {

    static final String EX = "http://example.org/";
    static final String TMF = Namespaces.TMF;
    static final Property MEMBER_OF = ResourceFactory.createProperty(TMF + "memberOf");
    static final Property PROXY_OF = ResourceFactory.createProperty(TMF + "proxyOf");
    static final Property CONTRIBUTION = ResourceFactory.createProperty(TMF + "contribution");

    static final String PREFIXES = """
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix icm:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/> .
            @prefix log:  <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
            @prefix set:  <http://tio.models.tmforum.org/tio/v3.6.0/SetOperators/> .
            @prefix met:  <http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/> .
            @prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
            @prefix ex:   <http://example.org/> .
            """;

    static final long[] SEEDS = {0, 3, 17, 4242};

    // ── running ──────────────────────────────────────────────────────────────────

    /**
     * The prepared stage B model, input and rules shuffled by {@code seed} (0 = as is). {@code extraA}/{@code extraB}
     * are test rules added to stage A / stage B (to simulate members derived by other rules, e.g. Task 15's choice).
     */
    static InfModel run(String turtle, long seed, boolean withOntology, List<Rule> extraA, List<Rule> extraB) {
        Model parsed = EvaluationInputs.parse(PREFIXES + turtle);
        List<Statement> stmts = new ArrayList<>(parsed.listStatements().toList());
        if (seed != 0) Collections.shuffle(stmts, new Random(seed));
        Model input = ModelFactory.createDefaultModel();
        stmts.forEach(input::add);
        Model base = input;
        if (withOntology) {
            base = ModelFactory.createModelForGraph(
                    new Union(input.getGraph(), JenaLogicRulesTest.ontology().orElseThrow().getGraph()));
        }
        return JenaEvaluationEngine.infer(base, rules -> {
            List<Rule> r = new ArrayList<>(rules);
            boolean stageB = r.stream().anyMatch(x -> "default".equals(x.getName()));
            r.addAll(stageB ? extraB : extraA);
            if (seed != 0) Collections.shuffle(r, new Random(seed * 31 + 7));
            return r;
        });
    }

    /** One assertion over a run: the model and a text naming the seed/ontology mode. */
    interface Check {
        void check(InfModel m, String where);
    }

    /** Runs every seed and ontology mode, checks the invariants and {@code check}; returns the last model. */
    static InfModel allRuns(String turtle, List<Rule> extraA, List<Rule> extraB, Check check) {
        InfModel last = null;
        for (boolean withOntology : JenaLogicRulesTest.ontologyModes()) {
            for (long seed : SEEDS) {
                InfModel m = run(turtle, seed, withOntology, extraA, extraB);
                String where = " (seed " + seed + ", ontology " + withOntology + ")\n" + turtle;
                JenaLogicRulesTest.assertEveryElementHasExactlyOneBooleanValue(m, where);
                JenaLogicRulesTest.assertStageBWritesOnlyOnContributorsOrDefaults(m, where);
                if (extraA.isEmpty() && extraB.isEmpty()) assertNoRdfsMemberDerived(m, where);
                check.check(m, where);
                last = m;
            }
        }
        return last;
    }

    static InfModel allRuns(String turtle, Check check) {
        return allRuns(turtle, List.of(), List.of(), check);
    }

    /** Pool members stay helper facts: no rule of either stage derives rdfs:member. */
    static void assertNoRdfsMemberDerived(InfModel stageB, String where) {
        Graph stageADeductions = ((InfGraph) stageB.getRawModel().getGraph()).getDeductionsGraph();
        assertFalse(stageADeductions.contains(Node.ANY, RDFS.member.asNode(), Node.ANY),
                () -> "stage A derived rdfs:member" + where);
        assertFalse(stageB.getDeductionsModel().contains(null, RDFS.member), () -> "stage B derived rdfs:member" + where);
    }

    /** Members of {@code ex:<local>} (local names of IRIs, lexical forms of literals). */
    static Set<String> members(Model m, String local) {
        return members(m, m.getResource(EX + local));
    }

    static Set<String> members(Model m, Resource container) {
        Set<String> out = new TreeSet<>();
        m.listSubjectsWithProperty(MEMBER_OF, container).forEachRemaining(n -> out.add(name(m, n)));
        return out;
    }

    /** A member's name; a literal member's proxy node by its literal. */
    static String name(Model m, RDFNode n) {
        Statement proxy = n.isResource() ? m.getProperty(n.asResource(), PROXY_OF) : null;
        return name(proxy == null ? n : proxy.getObject());
    }

    static String name(RDFNode n) {
        if (n.isLiteral()) return n.asLiteral().getLexicalForm();
        if (n.isURIResource()) return n.asResource().getLocalName();
        return n.toString();
    }

    static Set<String> set(String... s) {
        return new TreeSet<>(Arrays.asList(s));
    }

    static Check members(Map<String, Set<String>> expected) {
        return (m, where) -> expected.forEach((pool, exp) ->
                assertEquals(exp, members(m, pool), () -> "members of " + pool + where));
    }

    static Check values(Map<String, Boolean> expected) {
        return (m, where) -> expected.forEach((e, v) ->
                assertEquals(Set.of(v.toString()), JenaLogicRulesTest.values(m, e), () -> e + where));
    }

    static Rule rule(String text) {
        return Rule.parseRule(text);
    }

    // ── set:resourcesOfType (TR292F §4, §8.15) ───────────────────────────────────

    @Test
    void resourcesOfTypeIncludesSubclassInstances() {
        String t = """
                ex:Big rdfs:subClassOf ex:Widget . ex:Huge rdfs:subClassOf ex:Big .
                ex:w1 a ex:Widget . ex:b1 a ex:Big . ex:h1 a ex:Huge . ex:g1 a ex:Gadget .
                ex:Widgets set:resourcesOfType ( ex:Widget ) .
                ex:Bigs set:resourcesOfType ( ex:Big ) .
                ex:Both set:resourcesOfType ( ex:Big ex:Gadget ) .
                ex:None set:resourcesOfType ( ) .
                ex:Short set:resourcesOfType ex:Widget .
                """;
        allRuns(t, members(Map.of("Widgets", set("w1", "b1", "h1"), "Bigs", set("b1", "h1"),
                "Both", set("b1", "h1", "g1"), "None", set(), "Short", set("w1", "b1", "h1"))));
    }

    @Test
    void resourcesOfTypeUsesTheOntologySubclasses() {
        assumeTrue(JenaLogicRulesTest.ontology().isPresent(), "needs the licensed ontology");
        String t = """
                ex:d a icm:DeliveryExpectation ; icm:target ex:T . ex:T a icm:Target .
                ex:e a icm:Expectation .
                ex:Expectations set:resourcesOfType ( icm:Expectation ) .
                """;
        InfModel m = run(t, 0, true, List.of(), List.of());
        assertEquals(set("d", "e"), members(m, "Expectations"));
        m = run(t, 0, false, List.of(), List.of());
        assertEquals(set("e"), members(m, "Expectations"), "without the ontology no subclass is known");
    }

    // ── set:resourcesWithPropertyObject (§4, §8.17) and the container argument (DEVIATIONS.md entry 2) ─────────

    static final String REGIONS = """
            ex:Node1 ex:region ex:SubnetA .
            ex:Node2 ex:region ex:SubnetB ; ex:neighbor ex:SubnetC .
            ex:Node3 ex:region ex:SubnetA , ex:SubnetB .
            """;

    @Test
    void resourcesWithPropertyObjectMatchesAnyOfTheObjects() {
        String t = REGIONS + """
                ex:C1 set:resourcesWithPropertyObject ( ex:region ex:SubnetA ex:SubnetC ) .
                ex:C2 set:resourcesWithPropertyObject ( ex:neighbor ex:SubnetC ) .
                ex:C3 set:resourcesWithPropertyObject ( ex:region ) .
                ex:Free set:resourcesWithPropertyObject ( ex:inUse false ) .
                ex:u1 ex:inUse false . ex:u2 ex:inUse true . ex:u3 ex:inUse "false" .
                """;
        allRuns(t, members(Map.of("C1", set("Node1", "Node3"), "C2", set("Node2"), "C3", set(),
                "Free", set("u1"))));
    }

    static final String VLANS = """
            ex:svA a ex:SVLAN . ex:svB a ex:SVLAN .
            ex:a0 a ex:CTag ; ex:fromSVLAN ex:svB ; ex:inUse false .
            ex:b1 a ex:CTag ; ex:fromSVLAN ex:svA ; ex:inUse false .
            ex:b2 a ex:CTag ; ex:fromSVLAN ex:svA ; ex:inUse true .
            """;

    @Test
    void aContainerArgumentStandsForItsMembers() {
        String t = VLANS + """
                ex:Selected a icm:Target ; rdfs:member ex:svA .
                ex:OfSelected set:resourcesWithPropertyObject ( ex:fromSVLAN ex:Selected ) .
                ex:SVLANs set:resourcesOfType ( ex:SVLAN ) .
                ex:OfAny set:resourcesWithPropertyObject ( ex:fromSVLAN ex:SVLANs ) .
                ex:Plain rdfs:member ex:svA .
                ex:OfPlain set:resourcesWithPropertyObject ( ex:fromSVLAN ex:Plain ) .
                ex:Odd ex:fromSVLAN ex:Plain .
                ex:Mixed set:resourcesWithPropertyObject ( ex:fromSVLAN ex:svB ex:Selected ) .
                """;
        // a target or a set node is expanded; a plain container (not icm:Target, no set function) is an object
        // like any other, as in the Java engine
        allRuns(t, members(Map.of("OfSelected", set("b1", "b2"), "OfAny", set("a0", "b1", "b2"),
                "OfPlain", set("Odd"), "Mixed", set("a0", "b1", "b2"))));
    }

    // ── set:resourcesWithProperty (§4, §8.16) and set:valuesOfObjectProperty (§4, §8.20) ────────────────────────

    @Test
    void resourcesWithPropertyAndValuesOfObjectProperty() {
        String t = """
                ex:Node1 ex:temp 30 . ex:Node2 ex:other 1 . ex:Node3 ex:temperature 32 .
                ex:Ctemp set:resourcesWithProperty ( ex:temp ex:temperature ) .
                ex:Cnone set:resourcesWithProperty ( ) .
                ex:Slice1 ex:latency 5 , 7 . ex:Slice2 ex:latency 9 ; ex:owner ex:Alice . ex:Slice3 ex:latency 11 .
                ex:Lat set:valuesOfObjectProperty ( ex:latency ex:Slice1 ex:Slice2 ) .
                ex:Owners set:valuesOfObjectProperty ( ex:owner ex:Slice1 ex:Slice2 ) .
                """;
        allRuns(t, members(Map.of("Ctemp", set("Node1", "Node3"), "Cnone", set(), "Lat", set("5", "7", "9"),
                "Owners", set("Alice"))));
    }

    // ── set:union / intersection / difference (§3, §8.1, §8.6, §8.19) ───────────────────────────────────────────

    static final String CONTAINERS = """
            ex:C1 a rdfs:Container ; rdfs:member ex:RBS1 , ex:RBS2 .
            ex:C2 a rdfs:Container ; rdfs:member ex:RBS2 , ex:RBS3 .
            ex:C3 a rdfs:Container ; rdfs:member ex:RBS1 , ex:RBS2 , ex:RBS4 .
            """;

    @Test
    void unionIntersectionAndDifferenceOfContainers() {
        String t = CONTAINERS + """
                ex:U set:union ( ex:C1 ex:C2 ) ; rdfs:member ex:RBS4 .
                ex:I set:intersection ( ex:C1 ex:C2 ex:C3 ) ; rdfs:member ex:RBS4 .
                ex:D set:difference ( ex:C3 ex:C2 ex:C1 ) .
                ex:D1 set:difference ( ex:C3 ) .
                ex:I1 set:intersection ( ex:C2 ) .
                ex:U0 set:union ( ) . ex:I0 set:intersection ( ) . ex:D0 set:difference ( ) .
                """;
        // the PDF examples: U and I also keep their explicit member RBS4
        allRuns(t, members(Map.of("U", set("RBS1", "RBS2", "RBS3", "RBS4"), "I", set("RBS2", "RBS4"),
                "D", set("RBS4"), "D1", set("RBS1", "RBS2", "RBS4"), "I1", set("RBS2", "RBS3"),
                "U0", set(), "I0", set(), "D0", set())));
    }

    @Test
    void setOperatorsOverAnonymousFunctionNodes() {
        String t = """
                ex:w1 a ex:Widget ; ex:inUse true . ex:w2 a ex:Widget ; ex:inUse false .
                ex:w3 a ex:Widget ; ex:inUse false ; ex:broken true . ex:g0 a ex:Gadget ; ex:inUse false .
                ex:Free set:intersection ( [ set:resourcesOfType ( ex:Widget ) ]
                                           [ set:resourcesWithPropertyObject ( ex:inUse false ) ] ) .
                ex:Usable set:difference ( [ set:intersection ( [ set:resourcesOfType ( ex:Widget ) ]
                                                                [ set:resourcesWithPropertyObject ( ex:inUse false ) ] ) ]
                                           [ set:resourcesWithProperty ( ex:broken ) ] ) .
                ex:Any set:union ( [ set:resourcesOfType ( ex:Gadget ) ] [ set:resourcesWithPropertyObject ( ex:inUse true ) ] ) .
                ex:Nested set:union ( ex:Usable [ set:resourcesOfType ( ex:Gadget ) ] ) .
                """;
        allRuns(t, members(Map.of("Free", set("w2", "w3"), "Usable", set("w2"), "Any", set("g0", "w1"),
                "Nested", set("w2", "g0"))));
    }

    @Test
    void severalSetFunctionsOnOneNodeAreAUnion() {
        // TR292A; DEVIATIONS.md entry 1 (the Java engine intersects)
        String t = """
                ex:w1 a ex:Widget ; ex:inUse true . ex:w2 a ex:Widget ; ex:inUse false . ex:g0 a ex:Gadget ; ex:inUse false .
                ex:Pool set:resourcesOfType ( ex:Widget ) ; set:resourcesWithPropertyObject ( ex:inUse false ) .
                """;
        allRuns(t, members(Map.of("Pool", set("w1", "w2", "g0"))));
    }

    // ── pools filtered by other pools; dynamic containers ─────────────────────────

    static final String CTAG_POOL = VLANS + """
            ex:SVLANs a icm:Target ; set:resourcesOfType ( ex:SVLAN ) .
            ex:CTags a icm:Target ;
                set:intersection ( [ set:resourcesOfType ( ex:CTag ) ]
                                   [ set:resourcesWithPropertyObject ( ex:inUse false ) ]
                                   [ set:resourcesWithPropertyObject ( ex:fromSVLAN ex:SelectedSVLAN ) ] ) .
            ex:STagE a icm:DeliveryExpectation ;
                icm:target ex:SelectedSVLAN ; icm:deliveryType ex:SVLAN ; icm:chooseFrom ex:SVLANs .
            ex:SelectedSVLAN a icm:Target .
            """;

    @Test
    void aPoolFilteredByAnotherPool() {
        String t = VLANS + """
                ex:SVLANs a icm:Target ; set:resourcesOfType ( ex:SVLAN ) .
                ex:FreeCTags set:intersection ( [ set:resourcesWithPropertyObject ( ex:fromSVLAN ex:SVLANs ) ]
                                                [ set:resourcesWithPropertyObject ( ex:inUse false ) ] ) .
                """;
        allRuns(t, members(Map.of("SVLANs", set("svA", "svB"), "FreeCTags", set("a0", "b1"))));
    }

    @Test
    void aPoolFollowsTheMembersOfAContainerDerivedInStageA() {
        List<Rule> pickInA = List.of(rule("[pickA: (?t <" + EX + "pick> ?x) -> (?t rdfs:member ?x)]"));
        allRuns(CTAG_POOL + "ex:SelectedSVLAN ex:pick ex:svA .\n", pickInA, List.of(),
                members(Map.of("CTags", set("b1"))));
    }

    @Test
    void aPoolFollowsTheMembersOfATargetChosenInStageB() {
        // simulates Task 15: the choice is derived in stage B as rdfs:member of the delivery target
        List<Rule> pickInB = List.of(rule("[pickB: (?t <" + EX + "pick> ?x) -> (?t rdfs:member ?x)]"));
        allRuns(CTAG_POOL + "ex:SelectedSVLAN ex:pick ex:svA .\n", List.of(), pickInB, (m, where) -> {
            assertEquals(set("b1"), members(m, "CTags"), () -> "CTags" + where);
            assertEquals(set(), members(m.getRawModel(), "CTags"), () -> "stage A cannot know the choice" + where);
        });
        // no choice: the pool is empty
        allRuns(CTAG_POOL, members(Map.of("CTags", set(), "SVLANs", set("svA", "svB"))));
    }

    @Test
    void differenceFromAChosenTargetIsComputedInStageB() {
        List<Rule> pickInB = List.of(rule("[pickB: (?t <" + EX + "pick> ?x) -> (?t rdfs:member ?x)]"));
        String t = CTAG_POOL + """
                ex:SelectedSVLAN ex:pick ex:svA .
                ex:Others set:difference ( ex:SVLANs ex:SelectedSVLAN ) .
                ex:Chosen set:difference ( ex:SelectedSVLAN [ set:resourcesOfType ( ex:CTag ) ] ) .
                """;
        // subtracting a container whose members are chosen in stage B cannot be decided soundly: no members
        allRuns(t, List.of(), pickInB, members(Map.of("Others", set(), "Chosen", set("svA"))));
    }

    // ── corpus pools (their set parts; the choice itself is Task 15) ──────────────

    static String corpus(String name, String file) {
        Path p = Path.of("src/test/resources/conformance", name, file);
        try {
            return Files.isRegularFile(p) ? Files.readString(p) : "";
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static InfModel runCase(String name, boolean withOntology, long seed, List<Rule> extraB) {
        String turtle = EvaluationInputs.join(corpus(name, "expression.ttl"), corpus(name, "observations.ttl"),
                corpus(name, "resources.ttl"));
        Model parsed = EvaluationInputs.parse(turtle);
        List<Statement> stmts = new ArrayList<>(parsed.listStatements().toList());
        if (seed != 0) Collections.shuffle(stmts, new Random(seed));
        Model input = ModelFactory.createDefaultModel();
        stmts.forEach(input::add);
        Model base = withOntology ? ModelFactory.createModelForGraph(
                new Union(input.getGraph(), JenaLogicRulesTest.ontology().orElseThrow().getGraph())) : input;
        return JenaEvaluationEngine.infer(base, rules -> {
            List<Rule> r = new ArrayList<>(rules);
            if (r.stream().anyMatch(x -> "default".equals(x.getName()))) r.addAll(extraB);
            if (seed != 0) Collections.shuffle(r, new Random(seed * 31 + 7));
            return r;
        });
    }

    @Test
    void corpusPoolsHaveTheExpectedMembers() {
        Map<String, Map<String, Set<String>>> cases = Map.of(
                "pool-list-form", Map.of("Pool", set("w1", "w2")),
                "pool-explicit-intersection", Map.of("Pool", set("w2")),
                "pool-legacy-multifunction", Map.of("Pool", set("w1", "w2", "g0")),   // union (entry 1)
                "input-nonlist-resourcesOfType", Map.of("Pool", set("a1", "a2")),     // entry 5 (D4)
                "pool-depends-on-choice", Map.of("SVLANs", set("svA", "svB"), "CTags", set()));
        for (boolean withOntology : JenaLogicRulesTest.ontologyModes()) {
            for (long seed : SEEDS) {
                cases.forEach((name, pools) -> {
                    InfModel m = runCase(name, withOntology, seed, List.of());
                    pools.forEach((pool, exp) -> assertEquals(exp, members(m, pool), name + " " + pool));
                    assertFalse(ContractExtractor.extract(m).contains(null, RDFS.member),
                            name + ": no pool member reaches the contract");
                });
            }
        }
        // pool-depends-on-choice once the S-VLAN svA is chosen (simulated): the C-tag pool follows (entry 2, D1)
        Rule pick = rule("[pick: -> (<" + EX + "SelectedSVLAN> rdfs:member <" + EX + "svA>)]");
        for (long seed : SEEDS) {
            InfModel m = runCase("pool-depends-on-choice", false, seed, List.of(pick));
            assertEquals(set("b1"), members(m, "CTags"));
        }
    }

    @Test
    void seedPoolsHaveTheFreeResources() {
        InfModel m = runCase("seed-fulfilled", false, 0, List.of());
        Set<String> unis = members(m, m.getResource("http://broadband-forum.org/Intent#AvailableUNIInterfaces"));
        assertTrue(unis.contains("UNI-001-1"), () -> "free UNI ports: " + unis);
        assertFalse(ContractExtractor.extract(m).contains(null, RDFS.member));
    }

    // ── set-producing functions are not boolean contributors ──────────────────────

    @Test
    void setProducingFunctionsDoNotContribute() {
        String t = """
                ex:w1 a ex:Widget .
                ex:Pool a icm:Target ; set:resourcesOfType ( ex:Widget ) .
                ex:OnlySet a icm:Condition ; set:resourcesOfType ( ex:Widget ) .
                ex:SetAndTrue a icm:Condition ; set:union ( ex:Pool ) ; set:isMember ( ex:w1 ex:Pool ) .
                """;
        allRuns(t, (m, where) -> {
            values(Map.of("OnlySet", false, "SetAndTrue", true)).check(m, where);
            assertEquals(set("w1"), members(m, "OnlySet"));
            Resource pool = m.getResource(EX + "Pool");
            assertFalse(m.contains(pool, RDF.value), () -> "a pool has no value" + where);
            assertFalse(m.contains(pool, CONTRIBUTION), () -> "a pool has no contribution" + where);
            assertFalse(m.contains(pool, JenaLogicRulesTest.HAS_CONTRIBUTOR), () -> "a pool has no contributor" + where);
            assertEquals(1, m.listObjectsOfProperty(m.getResource(EX + "SetAndTrue"), CONTRIBUTION).toList().size());
            for (RDFNode k : m.listObjectsOfProperty(CONTRIBUTION).toList()) {
                assertEquals(1, m.listObjectsOfProperty(k.asResource(), RDF.value).toList().size(), "contribution " + k);
            }
        });
    }

    // ── boolean set functions (§5, §8.2-8.5, §8.7, §8.8) ──────────────────────────

    static final String USERS = """
            ex:RegionA a rdfs:Container ; rdfs:member ex:Node1 , ex:node2 .
            ex:RegionB a rdfs:Container ; rdfs:member ex:node2 .
            ex:UserGroup1 a rdfs:Container ; rdfs:member ex:User1 , ex:User2 .
            ex:UserGroup2 a rdfs:Container .
            ex:Inc1 rdfs:member ex:User2 , ex:User4 .
            ex:Inc2 rdfs:member ex:User1 , ex:User2 , ex:User3 , ex:User4 .
            ex:Inc3 rdfs:member ex:User2 , ex:User3 , ex:User4 .
            ex:Int1 rdfs:member ex:User2 , ex:User3 , ex:User4 .
            ex:Int2 rdfs:member ex:User1 , ex:User2 , ex:User4 .
            ex:Int3 rdfs:member ex:User3 , ex:User5 .
            ex:Id1 rdfs:member ex:User1 , ex:User2 .
            ex:Id2 rdfs:member ex:User1 , ex:User2 .
            ex:Id3 rdfs:member ex:User1 , ex:User2 , ex:User3 .
            ex:GoldUsers rdfs:member ex:User1 , ex:User2 , ex:User4 .
            ex:Others rdfs:member ex:User3 .
            ex:w1 a ex:Widget .
            """;

    @Test
    void booleanSetFunctionsFollowThePdfExamples() {
        String t = USERS + """
                ex:Elem1 a log:Condition ; set:elementOf ( ex:Node1 ex:RegionA ) .
                ex:Elem2 a log:Condition ; set:elementOf ( ex:Node1 ex:RegionA ex:RegionB ) .
                ex:Elem3 a log:Condition ; set:elementOf ( ex:node2 ex:RegionA ex:RegionB ) .
                ex:Empty1 a log:Condition ; set:empty ( ex:UserGroup1 ex:UserGroup2 ) .
                ex:Empty2 a log:Condition ; set:empty ( ex:UserGroup2 [ set:resourcesOfType ( ex:Nothing ) ] ) .
                ex:Incl1 a log:Condition ; set:includedIn ( ex:Inc1 ex:Inc2 ex:Inc3 ) .
                ex:Incl2 a log:Condition ; set:includedIn ( ex:Inc2 ex:Inc3 ) .
                ex:Incl3 a log:Condition ; set:includedIn ( ex:UserGroup2 ex:Inc1 ) .
                ex:Ints1 a log:Condition ; set:intersectsWith ( ex:Int1 ex:Int2 ex:Int3 ) .
                ex:Ints2 a log:Condition ; set:intersectsWith ( ex:Int2 ex:Int3 ) .
                ex:Ident1 a log:Condition ; set:identical ( ex:Id1 ex:Id2 ex:Id3 ) .
                ex:Ident2 a log:Condition ; set:identical ( ex:Id1 ex:Id2 ) .
                ex:Mem1 a log:Condition ; set:isMember ( ex:User3 ex:GoldUsers ) .
                ex:Mem2 a log:Condition ; set:isMember ( ex:User1 ex:GoldUsers ) .
                ex:Mem3 a log:Condition ; set:isMember ( ex:User3 ex:GoldUsers ex:Others ) .
                ex:Mem4 a log:Condition ; set:isMember ( ex:w1 [ set:resourcesOfType ( ex:Widget ) ] ) .
                ex:Mem5 a log:Condition ; set:isMember ( ex:w1 [ set:resourcesOfType ( ex:Gadget ) ] ) .
                """;
        Map<String, Boolean> exp = new HashMap<>();
        exp.putAll(Map.of("Elem1", true, "Elem2", false, "Elem3", true, "Empty1", false, "Empty2", true,
                "Incl1", true, "Incl2", false, "Incl3", true, "Ints1", true, "Ints2", false));
        exp.putAll(Map.of("Ident1", false, "Ident2", true, "Mem1", false, "Mem2", true, "Mem3", true,
                "Mem4", true, "Mem5", false));
        allRuns(t, values(exp));
    }

    @Test
    void booleanSetFunctionsInOperatorsAndConjunctions() {
        String t = USERS + """
                ex:Any a icm:Condition ; log:anyOf ( [ set:isMember ( ex:User3 ex:GoldUsers ) ]
                                                    [ set:isMember ( ex:User1 ex:GoldUsers ) ] ) .
                ex:Both a icm:Condition ; set:isMember ( ex:User1 ex:GoldUsers ) ; set:empty ( ex:UserGroup1 ) .
                """;
        allRuns(t, values(Map.of("Any", true, "Both", false)));
    }

    @Test
    void literalMembersAreComparedByValue() {
        String t = """
                ex:Slice1 ex:latency 5 , 7 . ex:Slice2 ex:latency 7 , 9 .
                ex:Codes rdfs:member "a" , "b" .
                ex:Lat1 set:valuesOfObjectProperty ( ex:latency ex:Slice1 ) .
                ex:Lat2 set:valuesOfObjectProperty ( ex:latency ex:Slice2 ) .
                ex:Both set:intersection ( ex:Lat1 ex:Lat2 ) .
                ex:HasFive a log:Condition ; set:isMember ( 5 ex:Lat1 ) .
                ex:HasNine a log:Condition ; set:isMember ( 9 ex:Lat1 ) .
                ex:HasCode a log:Condition ; set:elementOf ( "b" ex:Codes ) .
                ex:Shared a log:Condition ; set:intersectsWith ( ex:Lat1 ex:Lat2 ) .
                """;
        allRuns(t, (m, where) -> {
            members(Map.of("Both", set("7"), "Codes", set("a", "b"))).check(m, where);
            values(Map.of("HasFive", true, "HasNine", false, "HasCode", true, "Shared", true)).check(m, where);
        });
    }

    @Test
    void malformedBooleanSetFunctionsAreFalse() {
        String t = USERS + """
                ex:Few a log:Condition ; set:isMember ( ex:User1 ) .
                ex:NoArgs a log:Condition ; set:empty ( ) .
                ex:NotList a log:Condition ; set:includedIn ex:Inc1 .
                ex:Lit a log:Condition ; set:intersectsWith "x" .
                """;
        InfModel m = allRuns(t, values(Map.of("Few", false, "NoArgs", false, "NotList", false, "Lit", false)));
        assertEquals(Set.of("missing operand nodes"), JenaLogicRulesTest.failures(m, "Few"));
    }

    @Test
    void booleanSetFunctionsOverAContainerDecidedLaterFailClosed() {
        String t = CTAG_POOL + """
                ex:OnTarget a log:Condition ; set:isMember ( ex:svA ex:SelectedSVLAN ) .
                ex:OnDependent a log:Condition ; set:empty ( ex:CTags ) .
                ex:OnDifference a log:Condition ; set:isMember ( ex:svA [ set:difference ( ex:SVLANs ) ] ) .
                ex:NotOnTarget a log:Condition ; set:isMember ( ex:svA ex:SVLANs ) .
                """;
        InfModel m = allRuns(t, values(Map.of("OnTarget", false, "OnDependent", false, "OnDifference", false,
                "NotOnTarget", true)));
        assertEquals(1, JenaLogicRulesTest.failures(m, "OnTarget").size());
        assertTrue(JenaLogicRulesTest.failures(m, "OnTarget").iterator().next().contains("not final"));
    }

    // ── set:forAll (§5, §8.4) ─────────────────────────────────────────────────────

    @Test
    void forAllEvaluatesTheConditionForEveryMember() {
        String t = """
                ex:Slice1 ex:availability 0.99 . ex:Slice2 ex:availability 0.98 . ex:Slice3 ex:availability 0.95 .
                ex:Good a log:Condition ; set:forAll ( _:x
                    [ set:valuesOfObjectProperty ( ex:availability ex:Slice1 ex:Slice2 ) ]
                    [ quan:greater ( _:x 0.97 ) ] ) .
                ex:Bad a log:Condition ; set:forAll ( _:y
                    [ set:valuesOfObjectProperty ( ex:availability ex:Slice1 ex:Slice2 ex:Slice3 ) ]
                    [ quan:greater ( _:y 0.97 ) ] ) .
                ex:Vacuous a log:Condition ; set:forAll ( _:z [ set:resourcesOfType ( ex:Nothing ) ]
                    [ quan:greater ( _:z 0.97 ) ] ) .
                ex:Gold rdfs:member ex:u1 , ex:u2 . ex:Team rdfs:member ex:u1 , ex:u2 , ex:u3 . ex:Few rdfs:member ex:u1 .
                ex:AllGold a log:Condition ; set:forAll ( _:u ex:Team
                    [ log:anyOf ( [ set:isMember ( _:u ex:Gold ) ] [ set:isMember ( _:u ex:Few ) ] ) ] ) .
                ex:GoldGold a log:Condition ; set:forAll ( _:v ex:Gold [ set:isMember ( _:v ex:Gold ) ]
                    [ set:isMember ( _:v ex:Team ) ] ) .
                ex:NoBody a log:Condition ; set:forAll ( _:w ex:Gold [ ex:p ex:q ] ) .
                ex:TooFew a log:Condition ; set:forAll ( _:w ex:Gold ) .
                """;
        allRuns(t, values(Map.of("Good", true, "Bad", false, "Vacuous", true, "AllGold", false, "GoldGold", true,
                "NoBody", false, "TooFew", false)));
    }

    // ── fail closed: nested forAll, unknown and volatile containers, literal containers ─────────────────────────

    static Check failureContains(String element, String text) {
        return (m, where) -> assertTrue(JenaLogicRulesTest.failures(m, element).stream().anyMatch(f -> f.contains(text)),
                () -> element + " failures " + JenaLogicRulesTest.failures(m, element) + where);
    }

    @Test
    void aForAllNestedInAForAllIsFalse() {
        String t = """
                ex:Gold rdfs:member ex:u1 . ex:Team rdfs:member ex:u1 , ex:u2 .
                ex:Nested a log:Condition ;
                    set:forAll ( _:a ex:Gold [ set:forAll ( _:b ex:Team [ set:isMember ( _:b ex:Team ) ] ) ] ) .
                ex:I a icm:Intent ; log:allOf ( ex:Nested ) .
                """;
        allRuns(t, (m, where) -> {
            values(Map.of("Nested", false, "I", false)).check(m, where);
            failureContains("Nested", "nested").check(m, where);
        });
    }

    @Test
    void containersWithAFunctionThatIsNotImplementedFailClosed() {
        String t = """
                ex:Gold rdfs:member ex:u1 , ex:u2 . ex:u1 ex:availability 0.5 . ex:u2 ex:availability 0.99 .
                ex:T1 a icm:Target ; rdfs:member ex:u1 , ex:u2 .
                ex:E2 a icm:Expectation ; icm:target ex:T1 ; log:allOf ( ex:HighAvailability ) .
                ex:HighAvailability a log:Condition ; set:forAll ( _:x [ icm:valuesOfTargetProperty ( ex:availability ) ]
                    [ quan:greater ( _:x 0.97 ) ] ) .
                ex:NoTypes a log:Condition ; set:empty ( [ set:typesOfMembers ( ex:Gold ) ] ) .
                ex:Newest a log:Condition ; set:includedIn ( [ set:newestMember ( ex:p ex:Gold ) ] ex:Gold ) .
                ex:Built a log:Condition ; set:isMember ( ex:u1 [ set:union ( ex:Gold [ set:typesOfMembers ( ex:Gold ) ] ) ] ) .
                ex:Diff set:difference ( ex:Gold [ set:typesOfMembers ( ex:Gold ) ] ) .
                """;
        allRuns(t, (m, where) -> {
            values(Map.of("HighAvailability", false, "E2", false, "NoTypes", false, "Newest", false, "Built", false))
                    .check(m, where);
            failureContains("HighAvailability", "not final").check(m, where);
            failureContains("NoTypes", "not final").check(m, where);
            assertEquals(set(), members(m, "Diff"), () -> "a difference over an unknown container" + where);
            assertTrue(m.contains(m.getResource(EX + "Diff"), m.getProperty(TMF + "setFailure")), () -> where);
        });
    }

    @Test
    void anIntentElementsContainerFailsClosedWithAndWithoutTheOntology() {
        // icm:intentElements is a container function no rule implements; without the ontology it is recognised by
        // name (like icm:valuesOf*), so an empty test over it is false, never vacuously true
        String t = """
                ex:x a icm:Condition .
                ex:NoElements a log:Condition ; set:empty ( [ icm:intentElements ( ex:x ) ] ) .
                """;
        allRuns(t, (m, where) -> {
            values(Map.of("NoElements", false)).check(m, where);
            failureContains("NoElements", "not final").check(m, where);
        });
    }

    @Test
    void containerMatchesOverAContainerDecidedLaterFailClosed() {
        String t = """
                ex:A rdfs:member ex:bad1 . ex:bad1 ex:bad true . ex:Cok rdfs:member ex:ok1 .
                ex:U set:union ( [ set:difference ( ex:A ) ] ex:Cok ) .
                ex:NoneBad a log:Condition ; log:matchNone ( ex:U ex:bad true ) .
                ex:E a icm:DeliveryExpectation ; icm:target ex:T ; icm:deliveryType ex:Widget ; icm:chooseFrom ex:P .
                ex:T a icm:Target ; rdfs:member ex:bad1 .
                ex:NoneBadT a log:Condition ; log:matchNone ( ex:T ex:bad true ) .
                ex:AnyOk a log:Condition ; log:matchAny ( ex:Cok ex:bad true ) .
                """;
        allRuns(t, (m, where) -> {
            values(Map.of("NoneBad", false, "NoneBadT", false, "AnyOk", false)).check(m, where);
            failureContains("NoneBad", "not final").check(m, where);
            failureContains("NoneBadT", "not final").check(m, where);
            assertEquals(Set.of(), JenaLogicRulesTest.failures(m, "AnyOk"), () -> where);
        });
    }

    @Test
    void aDifferenceSubtractingAContainerDecidedLaterHasAFailure() {
        String t = """
                ex:A rdfs:member ex:a1 , ex:a2 .
                ex:D set:difference ( ex:A [ set:difference ( ex:A ) ] ) .
                """;
        allRuns(t, (m, where) -> {
            assertEquals(set(), members(m, "D"), () -> where);
            assertTrue(m.contains(m.getResource(EX + "D"), m.getProperty(TMF + "setFailure")), () -> where);
        });
    }

    @Test
    void aLiteralWhereAContainerIsExpectedIsMissingOperandNodes() {
        String t = """
                ex:Gold rdfs:member ex:u1 .
                ex:Inc a log:Condition ; set:includedIn ( "x" ex:Gold ) .
                ex:Ident a log:Condition ; set:identical ( ex:Gold 5 ) .
                ex:Emp a log:Condition ; set:empty ( "x" ) .
                ex:Mem a log:Condition ; set:isMember ( ex:u1 "x" ) .
                ex:All a log:Condition ; set:forAll ( _:x "x" [ set:isMember ( _:x ex:Gold ) ] ) .
                """;
        InfModel m = allRuns(t, values(Map.of("Inc", false, "Ident", false, "Emp", false, "Mem", false, "All", false)));
        for (String e : List.of("Inc", "Ident", "Emp", "Mem", "All")) {
            assertEquals(Set.of("missing operand nodes"), JenaLogicRulesTest.failures(m, e), e);
        }
    }

    @Test
    void aNamedForAllTemplateIsNotReported() {
        String t = """
                ex:Gold rdfs:member ex:u1 , ex:u2 .
                ex:Body a log:Condition ; set:isMember ( _:x ex:Gold ) .
                ex:All a log:Condition ; set:forAll ( _:x ex:Gold ex:Body ) .
                """;
        InfModel m = allRuns(t, values(Map.of("All", true)));
        Model contract = ContractExtractor.extract(m);
        assertFalse(contract.contains(m.getResource(EX + "Body"), RDF.value), "a template is not reported");
        assertTrue(contract.contains(m.getResource(EX + "All"), RDF.value));
    }

    // ── cost: a 1,000-port inventory ──────────────────────────────────────────────

    @Test
    void thousandPortInventory() {
        String expression = corpus("seed-fulfilled", "expression.ttl");
        StringBuilder unis = new StringBuilder(corpus("seed-fulfilled", "resources.ttl"));
        for (int i = 0; i < 1000; i++) {
            unis.append("\npon:UNI-000-").append(String.format("%04d", i)).append("\n    a pon:UNIPort ;\n")
                .append("    pon:name \"b").append(i).append("\" ;\n    pon:inUse false ;\n")
                .append("    pon:provisioningState pon:Ready ;\n    pon:operationalState pon:Up ;\n")
                .append("    pon:region bbf:NorthRegion .\n");
        }
        String turtle = EvaluationInputs.join(expression, corpus("seed-fulfilled", "observations.ttl"), unis.toString());
        Model input = EvaluationInputs.parse(turtle);
        List<Boolean> modes = JenaLogicRulesTest.ontologyModes();
        for (boolean withOntology : modes) {
            Model base = withOntology ? ModelFactory.createModelForGraph(
                    new Union(input.getGraph(), JenaLogicRulesTest.ontology().orElseThrow().getGraph())) : input;
            long best = Long.MAX_VALUE;
            InfModel m = null;
            for (int rep = 0; rep < 8; rep++) {
                long t0 = System.nanoTime();
                m = JenaEvaluationEngine.infer(base, r -> r);
                m.size();   // force saturation of both stages
                best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
            }
            // the same without the set rules, for comparison
            Set<String> setRules = new HashSet<>();
            for (String f : List.of("tio_a_sets.rules", "tio_b_sets.rules")) {
                JenaEvaluationEngine.parseRules(f).forEach(r -> setRules.add(r.getName()));
            }
            long without = Long.MAX_VALUE;
            for (int rep = 0; rep < 8; rep++) {
                long t0 = System.nanoTime();
                InfModel w = JenaEvaluationEngine.infer(base,
                        r -> r.stream().filter(x -> !setRules.contains(x.getName())).toList());
                w.size();
                without = Math.min(without, (System.nanoTime() - t0) / 1_000_000);
            }
            Resource pool = m.getResource("http://broadband-forum.org/Intent#AvailableUNIInterfaces");
            int poolSize = members(m, pool).size();
            System.out.printf("1000-UNI inventory (ontology %s): %d input triples, pool %d members, best of 8: %d ms"
                            + " (without the set rules: %d ms)%n", withOntology, input.size(), poolSize, best, without);
            assertTrue(poolSize >= 1000, "pool " + poolSize);
            assertFalse(ContractExtractor.extract(m).contains(null, RDFS.member));
            assertTrue(best < 10_000, "1000-UNI evaluation took " + best + " ms");
        }
    }
}
