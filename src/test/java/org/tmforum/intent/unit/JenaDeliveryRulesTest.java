package org.tmforum.intent.unit;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.compose.Union;
import org.apache.jena.reasoner.InfGraph;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.jupiter.api.Test;
import org.tmforum.intent.conformance.ConformanceCase;
import org.tmforum.intent.conformance.ConformanceLoader;
import org.tmforum.intent.engine.*;
import org.tmforum.intent.graph.Namespaces;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Delivery expectations, {@code icm:chooseFrom} and held resources (TR290 §2.5, design §5.2, §5.5): stage A
 * ({@code tio_a_delivery.rules}), the choice strata ({@code tio_c_choose.rules} + {@code tio_b_pools.rules}, one per
 * level) and the value stage ({@code tio_b_delivery.rules}), run through the engine's real chain
 * ({@link JenaEvaluationEngine#infer}) with the input triples and the rules of every stage shuffled by fixed seeds,
 * with and without the licensed ontology.
 *
 * <p>Every run also asserts the whole-model invariants of {@link JenaLogicRulesTest} (each IRI element exactly one
 * boolean value; stage B values only on contributors or defaults) and that every derived {@code rdfs:member} is a
 * choice ({@code ?r tmf:chosenFor ?target}).
 */
class JenaDeliveryRulesTest {

    static final String EX = "http://example.org/";
    static final String TMF = Namespaces.TMF;
    static final String BBF = "http://broadband-forum.org/Intent#";
    static final String PON = "http://broadband-forum.org/ont/pon-resource#";
    static final Property CHOSEN_FOR = ResourceFactory.createProperty(TMF + "chosenFor");
    static final Property LEVEL_AT_LEAST = ResourceFactory.createProperty(TMF + "levelAtLeast");
    static final long[] SEEDS = {0, 3, 17, 4242};

    static final String DELIVERY = """
            ex:E a icm:DeliveryExpectation ;
                icm:target ex:T ; icm:deliveryType ex:Widget ; icm:chooseFrom ex:Pool .
            ex:T a icm:Target .
            """;

    // ── running ──────────────────────────────────────────────────────────────────

    static InfModel run(String turtle, long seed, boolean withOntology) {
        Model parsed = EvaluationInputs.parse(JenaSetRulesTest.PREFIXES + turtle);
        return runModel(parsed, seed, withOntology);
    }

    static InfModel runModel(Model parsed, long seed, boolean withOntology) {
        List<Statement> stmts = new ArrayList<>(parsed.listStatements().toList());
        if (seed != 0) Collections.shuffle(stmts, new Random(seed));
        Model input = ModelFactory.createDefaultModel();
        stmts.forEach(input::add);
        Model base = !withOntology ? input : ModelFactory.createModelForGraph(
                new Union(input.getGraph(), JenaLogicRulesTest.ontology().orElseThrow().getGraph()));
        return JenaEvaluationEngine.infer(base, rules -> {
            List<Rule> r = new ArrayList<>(rules);
            if (seed != 0) Collections.shuffle(r, new Random(seed * 31 + 7));
            return r;
        });
    }

    interface Check {
        void check(InfModel m, String where);
    }

    static InfModel allRuns(String turtle, Check check) {
        InfModel last = null;
        for (boolean withOntology : JenaLogicRulesTest.ontologyModes()) {
            for (long seed : SEEDS) {
                InfModel m = run(turtle, seed, withOntology);
                String where = " (seed " + seed + ", ontology " + withOntology + ")\n" + turtle;
                assertInvariants(m, where);
                check.check(m, where);
                last = m;
            }
        }
        return last;
    }

    static void assertInvariants(InfModel m, String where) {
        JenaLogicRulesTest.assertEveryElementHasExactlyOneBooleanValue(m, where);
        JenaLogicRulesTest.assertStageBWritesOnlyOnContributorsOrDefaults(m, where);
        // every rdfs:member a rule derived is a choice, marked for ContractExtractor
        Model input = baseOf(m);
        for (Statement st : m.listStatements(null, RDFS.member, (RDFNode) null).toList()) {
            if (input.contains(st)) continue;
            assertTrue(st.getObject().isResource() && m.contains(st.getObject().asResource(), CHOSEN_FOR, st.getSubject()),
                    () -> "derived " + st + " is not marked tmf:chosenFor" + where);
        }
    }

    /** The input model under all the chained stages. */
    static Model baseOf(InfModel m) {
        Graph g = m.getGraph();
        while (g instanceof InfGraph inf) g = inf.getRawGraph();
        return ModelFactory.createModelForGraph(g);
    }

    /** The model of the stage {@code back} stages before the last one (1 = the last choice stratum). */
    static Model stageBefore(InfModel m, int back) {
        Graph g = m.getGraph();
        for (int i = 0; i < back; i++) g = ((InfGraph) g).getRawGraph();
        return ModelFactory.createModelForGraph(g);
    }

    /** Members of {@code ex:<local>} (rdfs:member, input and chosen), by local name. */
    static Set<String> targetMembers(Model m, String iri) {
        Set<String> out = new TreeSet<>();
        m.listObjectsOfProperty(m.getResource(iri), RDFS.member).forEachRemaining(n -> out.add(JenaSetRulesTest.name(n)));
        return out;
    }

    static Set<String> chosen(Model m, String iri) {
        Set<String> out = new TreeSet<>();
        m.listSubjectsWithProperty(CHOSEN_FOR, m.getResource(iri)).forEachRemaining(n -> out.add(JenaSetRulesTest.name(n)));
        return out;
    }

    static Check members(Map<String, Set<String>> expected) {
        return (m, where) -> expected.forEach((t, exp) -> {
            assertEquals(exp, targetMembers(m, EX + t), () -> "members of " + t + where);
        });
    }

    static Check values(Map<String, Boolean> expected) {
        return JenaSetRulesTest.values(expected)::check;
    }

    static Check failures(String element, String... texts) {
        return (m, where) -> assertEquals(new TreeSet<>(Arrays.asList(texts)), JenaLogicRulesTest.failures(m, element),
                () -> element + " failures" + where);
    }

    static Check all(Check... checks) {
        return (m, where) -> { for (Check c : checks) c.check(m, where); };
    }

    static Set<String> set(String... s) {
        return JenaSetRulesTest.set(s);
    }

    // ── the four choose cases (moved from JenaRulesTest / tio_choose.rules) ─────────────────────────────────────

    @Test
    void choosesTheLowestNamedCandidateOfTheDeliveryType() {
        // pool members listed out of order, one of the wrong type, to make the order matter
        InfModel m = allRuns(DELIVERY + """
                ex:Pool a icm:Target ; rdfs:member ex:w3 , ex:g0 , ex:w1 , ex:w2 .
                ex:w1 a ex:Widget . ex:w2 a ex:Widget . ex:w3 a ex:Widget . ex:g0 a ex:Gadget .
                """, all(members(Map.of("T", set("w1"))), values(Map.of("E", true)), failures("E")));
        Model contract = ContractExtractor.extract(m);
        assertEquals(List.of(m.createStatement(m.getResource(EX + "T"), RDFS.member, m.getResource(EX + "w1"))),
                contract.listStatements(null, RDFS.member, (RDFNode) null).toList());
    }

    @Test
    void withNoCandidateOfTheDeliveryTypeNothingIsChosenAndTheExpectationIsFalse() {
        allRuns(DELIVERY + """
                ex:Pool a icm:Target ; rdfs:member ex:g1 .
                ex:g1 a ex:Gadget .
                """, all(members(Map.of("T", set())), values(Map.of("E", false)),
                failures("E", "no candidate of type Widget in chooseFrom pool")));
    }

    @Test
    void aTargetThatAlreadyHasAMemberOfTheTypeIsSatisfied() {
        InfModel m = allRuns(DELIVERY + """
                ex:T rdfs:member ex:w9 .
                ex:w9 a ex:Widget .
                ex:Pool a icm:Target ; rdfs:member ex:w1 , ex:w2 .
                ex:w1 a ex:Widget . ex:w2 a ex:Widget .
                """, all(members(Map.of("T", set("w9"))), values(Map.of("E", true))));
        assertEquals(set(), chosen(m, EX + "T"), "nothing is chosen");
        assertFalse(ContractExtractor.extract(m).contains(null, RDFS.member), "an input member is not a choice");
    }

    @Test
    void choosesPerExpectation() {
        allRuns(DELIVERY + """
                ex:Pool a icm:Target ; rdfs:member ex:w2 , ex:w1 .
                ex:w1 a ex:Widget . ex:w2 a ex:Widget .
                ex:E2 a icm:DeliveryExpectation ;
                    icm:target ex:T2 ; icm:deliveryType ex:Gadget ; icm:chooseFrom ex:Pool2 .
                ex:T2 a icm:Target .
                ex:Pool2 a icm:Target ; rdfs:member ex:g2 , ex:g1 .
                ex:g1 a ex:Gadget . ex:g2 a ex:Gadget .
                """, all(members(Map.of("T", set("w1"), "T2", set("g1"))), values(Map.of("E", true, "E2", true))));
    }

    @Test
    void theLowestCandidateIsTheJavaEnginesOrderOverAFunctionPool() {
        // set-function pool, IRIs differing in case and length: String order (upper case first, prefix first)
        allRuns(DELIVERY + """
                ex:Pool a icm:Target ; set:resourcesOfType ( ex:Widget ) .
                ex:wa a ex:Widget . ex:wB a ex:Widget . ex:w a ex:Widget . ex:w0 a ex:Widget .
                """, members(Map.of("T", set("w"))));
        allRuns(DELIVERY + """
                ex:Pool a icm:Target ; set:resourcesOfType ( ex:Widget ) .
                ex:wa a ex:Widget . ex:wB a ex:Widget .
                """, members(Map.of("T", set("wB"))));
    }

    // ── held resources ───────────────────────────────────────────────────────────────────────────────────────────

    static final String HOLDS = "<urn:tmf921:evaluation> <urn:tmf921:evaluation#holds> ";

    @Test
    void aHeldResourceOfTheDeliveryTypeIsChosenFirstAndThePoolIsNotConsulted() {
        // w5 is held and not in the pool; the pool has the lower w1; a held gadget is ignored
        InfModel m = allRuns(DELIVERY + """
                ex:Pool a icm:Target ; rdfs:member ex:w1 , ex:w2 .
                ex:w1 a ex:Widget . ex:w2 a ex:Widget . ex:w5 a ex:Widget ; ex:inUse true . ex:g0 a ex:Gadget .
                """ + HOLDS + "ex:w5 , ex:g0 .\n", all(members(Map.of("T", set("w5"))), values(Map.of("E", true))));
        assertEquals(set("w5"), chosen(m, EX + "T"), "a held resource is reported as the target's member");
        // the lowest held resource of the type, even if the target already has one (as the Java engine)
        allRuns(DELIVERY + """
                ex:T rdfs:member ex:w0 . ex:w0 a ex:Widget .
                ex:Pool a icm:Target ; rdfs:member ex:w1 . ex:w1 a ex:Widget .
                ex:w7 a ex:Widget . ex:w6 a ex:Widget .
                """ + HOLDS + "ex:w7 , ex:w6 .\n", members(Map.of("T", set("w0", "w6"))));
        // held resources of another type only: the pool is used
        allRuns(DELIVERY + """
                ex:Pool a icm:Target ; rdfs:member ex:w1 . ex:w1 a ex:Widget . ex:g0 a ex:Gadget .
                """ + HOLDS + "ex:g0 .\n", members(Map.of("T", set("w1"))));
        // a held resource of the type without any pool
        allRuns("""
                ex:E a icm:DeliveryExpectation ; icm:target ex:T ; icm:deliveryType ex:Widget .
                ex:T a icm:Target . ex:w5 a ex:Widget .
                """ + HOLDS + "ex:w5 .\n", all(members(Map.of("T", set("w5"))), values(Map.of("E", true))));
    }

    // ── a pool that follows another choice ───────────────────────────────────────────────────────────────────────

    static final String VLANS = """
            ex:STagE a icm:DeliveryExpectation ;
                icm:target ex:SelectedSVLAN ; icm:deliveryType ex:SVLAN ; icm:chooseFrom ex:SVLANs .
            ex:SelectedSVLAN a icm:Target .
            ex:SVLANs a icm:Target ; set:resourcesOfType ( ex:SVLAN ) .
            ex:CTagE a icm:DeliveryExpectation ;
                icm:target ex:SelectedCTag ; icm:deliveryType ex:CTag ; icm:chooseFrom ex:CTags .
            ex:SelectedCTag a icm:Target .
            ex:CTags a icm:Target ;
                set:intersection ( [ set:resourcesOfType ( ex:CTag ) ]
                                   [ set:resourcesWithPropertyObject ( ex:inUse false ) ]
                                   [ set:resourcesWithPropertyObject ( ex:fromSVLAN ex:SelectedSVLAN ) ] ) .
            ex:svA a ex:SVLAN . ex:svB a ex:SVLAN .
            ex:a0 a ex:CTag ; ex:fromSVLAN ex:svB ; ex:inUse false .
            ex:b1 a ex:CTag ; ex:fromSVLAN ex:svA ; ex:inUse false .
            ex:b2 a ex:CTag ; ex:fromSVLAN ex:svA ; ex:inUse true .
            """;

    @Test
    void theCTagPoolFollowsTheChosenSVlanWhateverTheOrderOfTheExpectations() {
        for (String order : List.of("ex:STagE ex:CTagE", "ex:CTagE ex:STagE")) {
            String t = VLANS + "ex:Intent a icm:Intent ; log:allOf ( ex:Top ) .\n"
                    + "ex:Top a icm:Expectation ; log:allOf ( " + order + " ) .\n";
            InfModel m = allRuns(t, all(members(Map.of("SelectedSVLAN", set("svA"), "SelectedCTag", set("b1"))),
                    values(Map.of("STagE", true, "CTagE", true, "Top", true, "Intent", true))));
            // levels: the S-VLAN pool is complete after stage A, the C-tag pool after the first choice
            assertEquals(set("L0"), levels(m, "SVLANs"));
            assertEquals(set("L0", "L1"), levels(m, "CTags"));
            assertEquals(set("L0", "L1", "L2"), levels(m, "SelectedCTag"));
            // stage A and the first stratum cannot know the C-tag choice
            Model choose1 = stageBefore(m, 2);
            Model stageA = stageBefore(m, 3);
            assertEquals(set(), targetMembers(stageA, EX + "SelectedSVLAN"));
            assertEquals(set("svA"), targetMembers(choose1, EX + "SelectedSVLAN"));
            assertEquals(set(), targetMembers(choose1, EX + "SelectedCTag"));
        }
    }

    static Set<String> levels(Model m, String local) {
        Set<String> out = new TreeSet<>();
        m.listObjectsOfProperty(m.getResource(EX + local), LEVEL_AT_LEAST).forEachRemaining(n -> out.add(JenaSetRulesTest.name(n)));
        return out;
    }

    @Test
    void levelsAreExactlyTheVolatileContainers() {
        InfModel m = run(VLANS + """
                ex:D set:difference ( ex:SVLANs ) .
                ex:U set:union ( ex:D ) .
                """, 0, false);
        Property volatileP = m.getProperty(TMF + "volatile");
        Set<Resource> volatileNodes = m.listSubjectsWithProperty(volatileP).toSet();
        Set<Resource> levelOne = m.listSubjectsWithProperty(LEVEL_AT_LEAST, m.getResource(TMF + "L1")).toSet();
        assertEquals(volatileNodes, levelOne);
        assertFalse(volatileNodes.isEmpty());
    }

    @Test
    void aPoolBuiltOnASetDifferenceIsChosenFromInTheSecondStratum() {
        allRuns(DELIVERY + """
                ex:All a icm:Target ; set:resourcesOfType ( ex:Widget ) .
                ex:Used rdfs:member ex:w1 .
                ex:Pool a icm:Target ; set:difference ( ex:All ex:Used ) .
                ex:w1 a ex:Widget . ex:w2 a ex:Widget . ex:w3 a ex:Widget .
                """, all(members(Map.of("T", set("w2"))), values(Map.of("E", true))));
    }

    @Test
    void choicesNestedDeeperThanTheStrataFailClosed() {
        // S -> C -> D: the D pool follows the C choice, which follows the S choice (level 2: no stratum left)
        String t = VLANS + """
                ex:DE a icm:DeliveryExpectation ; icm:target ex:SelectedD ; icm:deliveryType ex:D ; icm:chooseFrom ex:Ds .
                ex:SelectedD a icm:Target .
                ex:Ds a icm:Target ; set:resourcesWithPropertyObject ( ex:fromCTag ex:SelectedCTag ) .
                ex:d1 a ex:D ; ex:fromCTag ex:b1 .
                ex:Intent a icm:Intent ; log:allOf ( ex:STagE ex:CTagE ex:DE ) .
                """;
        assertEquals(2, JenaEvaluationEngine.MAX_CHOICE_DEPTH);
        allRuns(t, all(members(Map.of("SelectedSVLAN", set("svA"), "SelectedCTag", set("b1"), "SelectedD", set())),
                values(Map.of("STagE", true, "CTagE", true, "DE", false, "Intent", false)),
                failures("DE", "chooseFrom pool membership is not final: it depends on a function that is not"
                        + " implemented or on choices nested too deeply")));
        // a target that is satisfied by its input members needs no choice, however deep its pool
        allRuns(t + "ex:SelectedD rdfs:member ex:d0 . ex:d0 a ex:D .\n",
                all(members(Map.of("SelectedD", set("d0"))), values(Map.of("DE", true))));
    }

    @Test
    void aPoolThatDependsOnItsOwnTargetOrOnAnUnknownFunctionFailsClosed() {
        String text = "chooseFrom pool membership is not final: it depends on a function that is not implemented"
                + " or on choices nested too deeply";
        allRuns("""
                ex:E a icm:DeliveryExpectation ; icm:target ex:T ; icm:deliveryType ex:Widget ; icm:chooseFrom ex:Pool .
                ex:T a icm:Target .
                ex:Pool a icm:Target ; set:union ( [ set:resourcesOfType ( ex:Widget ) ] ex:T ) .
                ex:w1 a ex:Widget .
                """, all(members(Map.of("T", set())), values(Map.of("E", false)), failures("E", text)));
        allRuns(DELIVERY + """
                ex:Pool a icm:Target ; set:union ( [ set:typesOfMembers ( ex:Other ) ] [ set:resourcesOfType ( ex:Widget ) ] ) .
                ex:w1 a ex:Widget .
                """, all(members(Map.of("T", set())), values(Map.of("E", false)), failures("E", text)));
    }

    // ── the value of a delivery expectation ──────────────────────────────────────────────────────────────────────

    @Test
    void aDeliveryExpectationIsTrueIffItsTargetHasAMemberOfTheDeliveryType() {
        String t = """
                ex:Big rdfs:subClassOf ex:Widget .
                ex:w1 a ex:Widget . ex:b1 a ex:Big . ex:g1 a ex:Gadget .
                ex:Right a icm:DeliveryExpectation ; icm:target ex:T1 ; icm:deliveryType ex:Widget .
                ex:T1 a icm:Target ; rdfs:member ex:g1 , ex:w1 .
                ex:Wrong a icm:DeliveryExpectation ; icm:target ex:T2 ; icm:deliveryType ex:Widget .
                ex:T2 a icm:Target ; rdfs:member ex:g1 .
                ex:Sub a icm:DeliveryExpectation ; icm:target ex:T3 ; icm:deliveryType ex:Widget .
                ex:T3 a icm:Target ; rdfs:member ex:b1 .
                ex:Untyped a icm:DeliveryExpectation ; icm:target ex:T4 .
                ex:T4 a icm:Target ; rdfs:member ex:g1 .
                ex:UntypedEmpty a icm:DeliveryExpectation ; icm:target ex:T5 .
                ex:T5 a icm:Target .
                ex:NoTarget a icm:DeliveryExpectation ; icm:deliveryType ex:Widget .
                ex:UntypedPool a icm:DeliveryExpectation ; icm:target ex:T6 ; icm:chooseFrom ex:P6 .
                ex:T6 a icm:Target . ex:P6 rdfs:member ex:g1 , ex:w1 .
                ex:EmptyPool a icm:DeliveryExpectation ; icm:target ex:T7 ; icm:chooseFrom ex:P7 .
                ex:T7 a icm:Target .
                """;
        allRuns(t, all(
                values(Map.of("Right", true, "Wrong", false, "Sub", true, "Untyped", true, "UntypedEmpty", false,
                        "NoTarget", false, "UntypedPool", true, "EmptyPool", false)),
                members(Map.of("T6", set("g1"))),
                failures("Wrong", "no target member of type Widget"), failures("UntypedEmpty", "no target member"),
                failures("NoTarget", "missing icm:target"), failures("EmptyPool", "no candidate in chooseFrom pool"),
                failures("Right"), failures("Sub")));
    }

    @Test
    void deliveryExpectationsRollUpThroughAllOf() {
        String t = """
                ex:Intent a icm:Intent ; log:allOf ( ex:Top ex:Perf ) .
                ex:Top a icm:Expectation ; log:allOf ( ex:E1 ex:E2 ) .
                ex:E1 a icm:DeliveryExpectation ; icm:target ex:T1 ; icm:deliveryType ex:Widget ; icm:chooseFrom ex:P1 .
                ex:T1 a icm:Target . ex:P1 rdfs:member ex:w1 . ex:w1 a ex:Widget .
                ex:E2 a icm:DeliveryExpectation ; icm:target ex:T2 ; icm:deliveryType ex:Gadget ; icm:chooseFrom ex:P2 .
                ex:T2 a icm:Target . ex:P2 rdfs:member %s .
                ex:Perf a icm:PropertyExpectation ; log:allOf ( ex:C ) .
                ex:C a icm:Condition ; quan:atLeast ( 5 3 ) .
                """;
        allRuns(String.format(t, "ex:g1 . ex:g1 a ex:Gadget"),
                values(Map.of("E1", true, "E2", true, "Top", true, "Perf", true, "Intent", true)));
        allRuns(String.format(t, "ex:w1"),
                values(Map.of("E1", true, "E2", false, "Top", false, "Perf", true, "Intent", false)));
    }

    @Test
    void aDeliveryCheckAndAnotherFunctionOnTheSameExpectationCombineByConjunction() {
        allRuns(DELIVERY + """
                ex:Pool rdfs:member ex:w1 . ex:w1 a ex:Widget .
                ex:E quan:atLeast ( 3 5 ) .
                """, all(members(Map.of("T", set("w1"))), values(Map.of("E", false))));
    }

    // ── the seed intent ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theSeedIntentAgainstTheRealInventoryChoosesOneOfEach() {
        String turtle = EvaluationInputs.join(TestInputs.seedIntent(), TestInputs.observations(TestInputs.ALL_OK),
                TestInputs.inventory());
        Model input = EvaluationInputs.parse(turtle);
        for (boolean withOntology : JenaLogicRulesTest.ontologyModes()) {
            for (long seed : SEEDS) {
                InfModel m = runModel(input, seed, withOntology);
                String where = " (seed " + seed + ", ontology " + withOntology + ")";
                assertInvariants(m, where);
                assertEquals(set("UNI-001-1"), chosen(m, BBF + "SelectedUNIInterface"), where);
                assertEquals(set("SVLAN-N-HSI"), chosen(m, BBF + "SelectedSVLANAllocation"), where);
                assertEquals(set("CTAG-N-001"), chosen(m, BBF + "SelectedCTAGAllocation"), where);
                for (String e : List.of("HSIIntent", "HSIServiceProvisioningExpectation",
                        "HSISTagProvisioningExpectation", "HSICTAGProvisioningExpectation", "HSIPerformanceExpectation",
                        "HSIMonitoringExpectation")) {
                    assertEquals(Set.of("true"), values(m, BBF + e), () -> e + where);
                }
            }
        }
    }

    static Set<String> values(Model m, String iri) {
        Set<String> out = new TreeSet<>();
        m.listObjectsOfProperty(m.getResource(iri), RDF.value).forEachRemaining(n -> out.add(JenaLogicRulesTest.lex(n)));
        return out;
    }

    /**
     * The delivery, pool and seed corpus cases on the rule engine, apart from what Task 16 adds (icm:reason and
     * met:observed with the observation triples): the extracted contract equals the expected one without those.
     */
    @Test
    void deliveryPoolAndSeedCorpusCasesMatchApartFromReasonsAndObservations() {
        Map<String, ConformanceCase> cases = new TreeMap<>();
        ConformanceLoader.loadAll().forEach(c -> cases.put(c.name(), c));
        List<String> names = cases.keySet().stream()
                .filter(n -> n.startsWith("delivery-") || n.startsWith("pool-") || n.startsWith("seed-")).toList();
        assertTrue(names.size() >= 12, () -> "cases: " + names);
        for (String name : names) {
            ConformanceCase c = cases.get(name);
            Model input = EvaluationInputs.parse(EvaluationInputs.join(c.expression(), c.observations(), c.resources()));
            EvaluationInputs.addHeld(input, c.held());
            Model expected = withoutTask16(c.expectedJena() != null ? c.expectedJena() : c.expected());
            List<Boolean> modes = JenaLogicRulesTest.ontology().isPresent() ? List.of(true) : List.of();
            for (boolean withOntology : modes) {     // the contract's element types need the ontology
                for (long seed : SEEDS) {
                    InfModel m = runModel(input, seed, withOntology);
                    Model actual = withoutTask16(ContractExtractor.extract(m));
                    assertTrue(ResultContract.sameResults(actual, expected),
                            () -> name + " (seed " + seed + ") differs:\n  actual " + actual.listStatements().toList()
                                    + "\n  expected " + expected.listStatements().toList());
                }
            }
        }
    }

    /** The contract without icm:reason and met:observed (and the reason / observation nodes): Task 16's part. */
    static Model withoutTask16(Model contract) {
        Model out = ModelFactory.createDefaultModel();
        Set<RDFNode> linked = new HashSet<>();
        contract.listStatements(null, ResultContract.ICM_REASON, (RDFNode) null).forEachRemaining(s -> linked.add(s.getObject()));
        contract.listStatements(null, ResultContract.MET_OBSERVED, (RDFNode) null).forEachRemaining(s -> linked.add(s.getObject()));
        for (Statement st : contract.listStatements().toList()) {
            if (st.getPredicate().equals(ResultContract.ICM_REASON) || st.getPredicate().equals(ResultContract.MET_OBSERVED)) continue;
            if (linked.contains(st.getSubject())) continue;
            out.add(st);
        }
        return out;
    }

    @Test
    void reevaluationHoldingTheAllocatedResourcesGivesTheSameGraph() {
        Map<String, ConformanceCase> cases = new HashMap<>();
        ConformanceLoader.loadAll().forEach(c -> cases.put(c.name(), c));
        Model first = contractOf(cases.get("seed-fulfilled"));
        Model again = contractOf(cases.get("seed-reevaluation-held"));
        assertTrue(ResultContract.sameResults(first, again), () -> first.listStatements().toList() + "\n"
                + again.listStatements().toList());
        assertEquals(3, again.listStatements(null, RDFS.member, (RDFNode) null).toList().size());
    }

    static Model contractOf(ConformanceCase c) {
        Model input = EvaluationInputs.parse(EvaluationInputs.join(c.expression(), c.observations(), c.resources()));
        EvaluationInputs.addHeld(input, c.held());
        return ContractExtractor.extract(runModel(input, 0, JenaLogicRulesTest.ontology().isPresent()));
    }

    // ── Review Focus 4: a 1,000-UNI inventory yields one chosen member per target ────────────────────────────────

    @Test
    void thousandUniInventoryYieldsExactlyOneChosenMemberPerTarget() {
        String expression = JenaSetRulesTest.corpus("seed-fulfilled", "expression.ttl");
        StringBuilder unis = new StringBuilder(JenaSetRulesTest.corpus("seed-fulfilled", "resources.ttl"));
        for (int i = 0; i < 1000; i++) {
            unis.append("\npon:UNI-000-").append(String.format("%04d", i)).append("\n    a pon:UNIPort ;\n")
                .append("    pon:inUse false ;\n    pon:provisioningState pon:Ready ;\n")
                .append("    pon:operationalState pon:Up .\n");
        }
        String turtle = EvaluationInputs.join(expression, JenaSetRulesTest.corpus("seed-fulfilled", "observations.ttl"),
                unis.toString());
        Model input = EvaluationInputs.parse(turtle);
        for (boolean withOntology : JenaLogicRulesTest.ontologyModes()) {
            Model base = withOntology ? ModelFactory.createModelForGraph(
                    new Union(input.getGraph(), JenaLogicRulesTest.ontology().orElseThrow().getGraph())) : input;
            long best = Long.MAX_VALUE;
            InfModel m = null;
            for (int rep = 0; rep < 8; rep++) {
                long t0 = System.nanoTime();
                m = JenaEvaluationEngine.infer(base, r -> r);
                m.size();   // force saturation of every stage
                best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
            }
            Model contract = ContractExtractor.extract(m);
            List<Statement> uniMembers = contract.listStatements(contract.getResource(BBF + "SelectedUNIInterface"),
                    RDFS.member, (RDFNode) null).toList();
            System.out.printf("1000-UNI inventory with choice strata (ontology %s): %d input triples, best of 8: %d ms,"
                    + " contract rdfs:member %d%n", withOntology, input.size(), best,
                    contract.listStatements(null, RDFS.member, (RDFNode) null).toList().size());
            assertEquals(1, uniMembers.size(), () -> "chosen UNI members: " + uniMembers);
            assertEquals(PON + "UNI-000-0000", uniMembers.get(0).getObject().asResource().getURI());
            assertEquals(3, contract.listStatements(null, RDFS.member, (RDFNode) null).toList().size());
            assertTrue(best < 10_000, "1000-UNI evaluation took " + best + " ms");
        }
    }

    // ── hardening: the input cannot assert helper facts; the extractor reports the derived value ──────────────────

    @Test
    void helperTriplesInTheInputAreIgnored() {
        String expression = JenaSetRulesTest.PREFIXES + """
                @prefix tmf: <http://tmforum.org/api/v5/> .
                ex:Intent a icm:Intent ; log:allOf ( ex:E ) .
                ex:E a icm:DeliveryExpectation ; icm:target ex:T ; icm:deliveryType ex:Widget .
                ex:T a icm:Target ; rdfs:member ex:x .
                ex:x tmf:chosenFor ex:T ; tmf:instanceOf ex:Widget .
                ex:T tmf:hasMemberOfType ex:Widget .
                ex:C a icm:Condition , tmf:Element ; quan:atLeast ( 3 5 ) ; tmf:result tmf:T .
                ex:Intent2 a icm:Intent ; log:allOf ( ex:C ) .
                """;
        EvaluationOutcome out = new JenaEvaluationEngine().evaluate(
                new EvaluationInput("spoof", expression, "", "", Set.of()));
        Model contract = out.resultGraph();
        assertFalse(contract.contains(null, RDFS.member), "ex:x was not chosen");
        assertTrue(contract.containsLiteral(contract.getResource(EX + "C"), RDF.value, false));
        assertFalse(contract.containsLiteral(contract.getResource(EX + "C"), RDF.value, true));
        if (JenaLogicRulesTest.ontology().isPresent()) {
            assertTrue(contract.containsLiteral(contract.getResource(EX + "E"), RDF.value, false), "x is no Widget");
        }
    }

    @Test
    void anInputValueOnAnElementWithAContributorIsNotReported() {
        String t = """
                ex:C a icm:Condition ; rdf:value true ; quan:atLeast ( 3 5 ) .
                ex:D a icm:Condition ; rdf:value false ; quan:atLeast ( 5 3 ) .
                ex:V a icm:Condition ; rdf:value true .
                """;
        for (boolean withOntology : JenaLogicRulesTest.ontologyModes()) {
            Model contract = ContractExtractor.extract(run(t, 0, withOntology));
            assertEquals(Set.of("false"), values(contract, EX + "C"));
            assertEquals(Set.of("true"), values(contract, EX + "D"));
            assertEquals(Set.of("true"), values(contract, EX + "V"), "no contributor: the input value stays");
        }
    }

    @Test
    void theStagesAreStageAThenOneChoiceStratumPerLevelThenStageB() {
        assertEquals(2 + JenaEvaluationEngine.MAX_CHOICE_DEPTH, JenaEvaluationEngine.stages().size());
        assertTrue(JenaEvaluationEngine.STAGE_A.contains("tio_a_delivery.rules"));
        assertTrue(JenaEvaluationEngine.CHOICE_STAGE.contains("tio_c_choose.rules"));
        assertTrue(JenaEvaluationEngine.CHOICE_STAGE.contains("tio_b_pools.rules"));
        assertTrue(JenaEvaluationEngine.STAGE_B.contains("tio_b_delivery.rules"));
        assertFalse(String.join(",", JenaEvaluationEngine.STAGE_B).contains("tio_choose"));
        // each stratum chooses from its own level only
        List<Rule> choose2 = JenaEvaluationEngine.parseRules("tio_c_choose.rules", 2);
        assertTrue(choose2.stream().anyMatch(r -> r.toString().contains(TMF + "L1")
                && r.toString().contains(TMF + "L2") && r.getName().equals("poolChoice")));
        assertTrue(choose2.stream().noneMatch(r -> r.toString().contains("ThisLevel") || r.toString().contains("PrevLevel")));
        // the level facts reach MAX_CHOICE_DEPTH + 1
        assertTrue(JenaEvaluationEngine.parseRules("tio_a_delivery.rules").stream()
                .anyMatch(r -> r.toString().contains(TMF + "L" + (JenaEvaluationEngine.MAX_CHOICE_DEPTH + 1))));
    }

}
