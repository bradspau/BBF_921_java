package org.tmforum.intent.engine;

import org.apache.jena.graph.compose.Union;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tmforum.intent.graph.Namespaces;
import org.tmforum.intent.handler.JenaBuiltins;
import org.tmforum.intent.model.EvaluationResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Rule-based engine (spec 5.5, staging decided in 5.5.1).
 *
 * <p>Per evaluation: base model = ontology (cached, read-only, unioned rather than copied) ∪ expression ∪
 * observations ∪ resources ∪ held facts ({@link EvaluationInputs}, the same conventions as the Java engine), with
 * every triple in the engine's helper namespace removed from the input first ({@link #stripHelperTriples}); stage A =
 * {@link GenericRuleReasoner} ({@code FORWARD_RETE}) over {@link #STAGE_A}; the choice strata ({@link #CHOICE_STAGE},
 * one per level up to {@link #MAX_CHOICE_DEPTH}) and stage B ({@link #STAGE_B}), each a reasoner chained on the
 * previous stage's {@link InfModel}; then {@link ContractExtractor} and {@link ResultAdapter}.
 *
 * <p>Not ready until the conformance suite passes ({@link #READY}, flipped in Task 17), and never ready without
 * the ontology files.
 */
@Component
public class JenaEvaluationEngine implements EvaluationEngine {

    /** Flipped to true once the conformance suite passes for this engine (Task 17). */
    public static final boolean READY = false;

    /** Stage A rule files (positive facts), classpath resources under {@code rules/}, in load order. */
    public static final List<String> STAGE_A = List.of("tio_a_core.rules", "tio_a_quantity.rules",
            "tio_a_logic.rules", "tio_a_sets.rules", "tio_a_delivery.rules", "tio_a_reasons.rules");
    /**
     * Rule files of one choice stratum (design 5.5 "Sets and pools"): loaded once per level n = 1 ..
     * {@link #MAX_CHOICE_DEPTH}, with {@code tmf:PrevLevel}/{@code tmf:ThisLevel} replaced by {@code tmf:L(n-1)} /
     * {@code tmf:Ln}; each copy is its own stage between stage A and stage B.
     */
    public static final List<String> CHOICE_STAGE = List.of("tio_c_choose.rules", "tio_b_pools.rules");
    /**
     * Number of choice strata. A pool that depends on choices nested deeper than this is not chosen from (fail closed,
     * tio_b_delivery.rules). The seed needs 2 (UNI and S-VLAN from complete pools, the C-tag pool after the S-VLAN
     * choice). The level facts of tio_a_delivery.rules go up to {@code tmf:L3} = MAX_CHOICE_DEPTH + 1.
     */
    public static final int MAX_CHOICE_DEPTH = 2;
    /** Stage B (value stage) rule files: defaults, combination, negation, delivery values, reasons. */
    public static final List<String> STAGE_B = List.of("tio_b_pools.rules", "tio_b_logic.rules", "tio_b_sets.rules",
            "tio_b_delivery.rules", "tio_b_reasons.rules");

    private static final Map<String, List<Rule>> RULES = new ConcurrentHashMap<>();

    static { JenaBuiltins.register(); }

    private final Path ontologyDir;

    /** Uses {@code ontology/} relative to the working directory. */
    public JenaEvaluationEngine() {
        this(Path.of("ontology"));
    }

    @Autowired
    public JenaEvaluationEngine(@Value("${intent.ontology-dir:ontology}") String ontologyDir) {
        this(Path.of(ontologyDir));
    }

    public JenaEvaluationEngine(Path ontologyDir) {
        this.ontologyDir = ontologyDir;
    }

    @Override public String name() { return "jena"; }

    @Override
    public EngineStatus status() {
        if (OntologyModels.load(ontologyDir).isEmpty()) {
            return EngineStatus.notReady("ontology files not found in ontology/");
        }
        return READY ? EngineStatus.ok() : EngineStatus.notReady("conformance suite not passing");
    }

    @Override
    public EvaluationOutcome evaluate(EvaluationInput in) {
        if (in.expression() == null || in.expression().isBlank()) return degraded("Empty expression");
        String combined = EvaluationInputs.join(in.expression(), in.observations(), in.resources());
        if (EvaluationInputs.tooLarge(combined)) return degraded(EvaluationInputs.tooLargeReason());
        Model input;
        try {
            input = EvaluationInputs.parse(combined);
        } catch (Exception e) {
            return degraded("Turtle parse error: " + e.getMessage());
        }
        stripHelperTriples(input);
        EvaluationInputs.addHeld(input, in.held());

        Model base = withOntology(input);
        if (ContractExtractor.elements(base, List.of(ResultAdapter.ICM_INTENT)).isEmpty()) {
            // No IRI-identified icm:Intent: nothing to report on (TR290 §4.2), the same empty contract as the Java
            // engine's (ResultGraphWriter); the rules are not run (task 16 report, no-intent policy).
            Model empty = ModelFactory.createDefaultModel();
            return new EvaluationOutcome(empty, ResultAdapter.adapt(empty, base));
        }
        InfModel stageB = infer(base, UnaryOperator.identity());

        Model contract = ContractExtractor.extract(stageB);
        return new EvaluationOutcome(contract, ResultAdapter.adapt(contract, base));
    }

    /**
     * Removes from the input every triple the rules could mistake for one of their own helper facts: a predicate in the
     * {@code tmf:} namespace ({@link Namespaces#TMF}) or an {@code rdf:type} with a {@code tmf:} class. Input cannot
     * then assert, for example, {@code tmf:implemented}, {@code tmf:valuedInA} or {@code tmf:chosenFor}.
     */
    public static void stripHelperTriples(Model input) {
        List<Statement> helper = new ArrayList<>();
        input.listStatements().forEachRemaining(st -> {
            boolean tmfPredicate = st.getPredicate().getURI().startsWith(Namespaces.TMF);
            boolean tmfType = st.getPredicate().equals(RDF.type) && st.getObject().isURIResource()
                    && st.getObject().asResource().getURI().startsWith(Namespaces.TMF);
            if (tmfPredicate || tmfType) helper.add(st);
        });
        input.remove(helper);
    }

    /** Input ∪ ontology without copying the ontology; writes (none expected) would go to the input. */
    private Model withOntology(Model input) {
        Optional<Model> ontology = OntologyModels.load(ontologyDir);
        if (ontology.isEmpty()) return input;
        Model m = ModelFactory.createModelForGraph(new Union(input.getGraph(), ontology.get().getGraph()));
        m.setNsPrefixes(input.getNsPrefixMap());
        return m;
    }

    /**
     * Runs the stages over {@code base}, each chained on the previous one's {@link InfModel} (design 5.5.1): stage A,
     * the choice strata choose-1 .. choose-{@link #MAX_CHOICE_DEPTH}, stage B. Returns the prepared stage B model;
     * {@code getRawModel()} of it is the last choice stratum, which contains everything before it. {@code order} may
     * reorder each stage's rules (tests shuffle them to prove the result does not depend on rule order); production
     * passes the identity.
     */
    public static InfModel infer(Model base, UnaryOperator<List<Rule>> order) {
        return infer(base, order, MAX_CHOICE_DEPTH);
    }

    /**
     * As {@link #infer(Model, UnaryOperator)} with {@code maxChoiceDepth} choice strata instead of
     * {@link #MAX_CHOICE_DEPTH} (0 to 2; tests use it to show what the strata do). Production uses the default.
     */
    public static InfModel infer(Model base, UnaryOperator<List<Rule>> order, int maxChoiceDepth) {
        if (maxChoiceDepth < 0 || maxChoiceDepth > MAX_CHOICE_DEPTH) {
            throw new IllegalArgumentException("choice depth 0.." + MAX_CHOICE_DEPTH + ": " + maxChoiceDepth);
        }
        Model previous = base;
        InfModel stage = null;
        for (List<Rule> rules : stages(maxChoiceDepth)) {
            stage = ModelFactory.createInfModel(reasoner(order.apply(rules)), previous);
            previous = stage;
        }
        stage.prepare();
        return stage;
    }

    /** The rules of each stage in order: stage A, one choice stratum per level, stage B. */
    public static List<List<Rule>> stages() {
        return stages(MAX_CHOICE_DEPTH);
    }

    static List<List<Rule>> stages(int depth) {
        List<List<Rule>> stages = new ArrayList<>();
        stages.add(rules(STAGE_A, 0, depth));
        for (int level = 1; level <= depth; level++) stages.add(rules(CHOICE_STAGE, level, depth));
        stages.add(rules(STAGE_B, 0, depth));
        return stages;
    }

    private static List<Rule> rules(List<String> files, int level, int depth) {
        List<Rule> rules = new ArrayList<>();
        for (String f : files) {
            rules.addAll(RULES.computeIfAbsent(f + "@" + level + "/" + depth, k -> parseRules(f, level, depth)));
        }
        return rules;
    }

    private static GenericRuleReasoner reasoner(List<Rule> rules) {
        GenericRuleReasoner r = new GenericRuleReasoner(rules);
        r.setMode(GenericRuleReasoner.FORWARD_RETE);
        return r;
    }

    /** Parses one rule file from the classpath ({@code rules/<name>}); a listed file that is missing is a bug. */
    public static List<Rule> parseRules(String name) {
        return parseRules(name, 0);
    }

    /**
     * Parses one rule file for choice level {@code level} (0 outside the choice strata): {@code tmf:ThisLevel},
     * {@code tmf:PrevLevel} and {@code tmf:MaxLevel} become {@code tmf:L<level>}, {@code tmf:L<level-1>} and
     * {@code tmf:L<MAX_CHOICE_DEPTH>}.
     */
    public static List<Rule> parseRules(String name, int level) {
        return parseRules(name, level, MAX_CHOICE_DEPTH);
    }

    private static List<Rule> parseRules(String name, int level, int depth) {
        String path = "rules/" + name;
        InputStream s = JenaEvaluationEngine.class.getClassLoader().getResourceAsStream(path);
        if (s == null) throw new IllegalStateException("rule file not on the classpath: " + path);
        String text;
        try (s) {
            text = new String(s.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        text = text.replace("tmf:MaxLevel", "tmf:L" + depth);
        if (level > 0) {
            text = text.replace("tmf:ThisLevel", "tmf:L" + level).replace("tmf:PrevLevel", "tmf:L" + (level - 1));
        }
        try (BufferedReader r = new BufferedReader(new StringReader(text))) {
            return Rule.parseRules(Rule.rulesParserFromReader(r));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static EvaluationOutcome degraded(String reason) {
        return new EvaluationOutcome(ModelFactory.createDefaultModel(), EvaluationResult.degraded(reason, List.of()));
    }
}
