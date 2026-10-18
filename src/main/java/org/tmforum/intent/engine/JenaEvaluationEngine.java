package org.tmforum.intent.engine;

import org.apache.jena.graph.compose.Union;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tmforum.intent.handler.JenaBuiltins;
import org.tmforum.intent.model.EvaluationResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * observations ∪ resources ∪ held facts ({@link EvaluationInputs}, the same conventions as the Java engine);
 * stage A = {@link GenericRuleReasoner} ({@code FORWARD_RETE}) over {@link #STAGE_A}; stage B = a reasoner over
 * {@link #STAGE_B} chained on stage A's {@link InfModel}; then {@link ContractExtractor} and
 * {@link ResultAdapter}.
 *
 * <p>Not ready until the conformance suite passes ({@link #READY}, flipped in Task 17), and never ready without
 * the ontology files.
 */
@Component
public class JenaEvaluationEngine implements EvaluationEngine {

    /** Flipped to true once the conformance suite passes for this engine (Task 17). */
    public static final boolean READY = false;

    /** Stage A rule files (positive facts), classpath resources under {@code rules/}, in load order. */
    public static final List<String> STAGE_A =
            List.of("tio_a_core.rules", "tio_a_quantity.rules", "tio_a_logic.rules");
    /** Stage B rule files (defaults, combination, negation, choice), classpath resources under {@code rules/}. */
    public static final List<String> STAGE_B = List.of("tio_b_logic.rules");

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
        EvaluationInputs.addHeld(input, in.held());

        Model base = withOntology(input);
        InfModel stageB = infer(base, UnaryOperator.identity());

        Model contract = ContractExtractor.extract(stageB);
        return new EvaluationOutcome(contract, ResultAdapter.adapt(contract, base));
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
     * Runs stage A over {@code base} and stage B chained on it (design 5.5.1) and returns the prepared stage B model;
     * {@code getRawModel()} of the result is stage A. {@code order} may reorder each stage's rules (tests shuffle
     * them to prove the result does not depend on rule order); production passes the identity.
     */
    public static InfModel infer(Model base, UnaryOperator<List<Rule>> order) {
        InfModel stageA = ModelFactory.createInfModel(reasoner(order.apply(rules(STAGE_A))), base);
        InfModel stageB = ModelFactory.createInfModel(reasoner(order.apply(rules(STAGE_B))), stageA);
        stageB.prepare();
        return stageB;
    }

    private static List<Rule> rules(List<String> files) {
        List<Rule> rules = new ArrayList<>();
        for (String f : files) rules.addAll(RULES.computeIfAbsent(f, JenaEvaluationEngine::parseRules));
        return rules;
    }

    private static GenericRuleReasoner reasoner(List<Rule> rules) {
        GenericRuleReasoner r = new GenericRuleReasoner(rules);
        r.setMode(GenericRuleReasoner.FORWARD_RETE);
        return r;
    }

    /** Parses one rule file from the classpath ({@code rules/<name>}); a listed file that is missing is a bug. */
    public static List<Rule> parseRules(String name) {
        String path = "rules/" + name;
        InputStream s = JenaEvaluationEngine.class.getClassLoader().getResourceAsStream(path);
        if (s == null) throw new IllegalStateException("rule file not on the classpath: " + path);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(s, StandardCharsets.UTF_8))) {
            return Rule.parseRules(Rule.rulesParserFromReader(r));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static EvaluationOutcome degraded(String reason) {
        return new EvaluationOutcome(ModelFactory.createDefaultModel(), EvaluationResult.degraded(reason, List.of()));
    }
}
