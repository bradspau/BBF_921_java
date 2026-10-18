package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.*;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Input conventions shared by both engines: how the expression, observations and resources Turtle strings are
 * joined and parsed, the size limit, and the neutral "held" fact (spec 5.2).
 */
public final class EvaluationInputs {

    private EvaluationInputs() {}

    /** Base IRI every input is parsed with. */
    public static final String BASE = "http://tmforum.org/";
    public static final int MAX_TURTLE_BYTES = 512 * 1024;

    /** Evaluation context carried in the model: the resources the intent being evaluated already holds. */
    public static final Resource EVAL_CONTEXT = ResourceFactory.createResource("urn:tmf921:evaluation");
    public static final Property EVAL_HOLDS = ResourceFactory.createProperty("urn:tmf921:evaluation#holds");

    /** Joins the non-blank parts with newlines, so prefixes declared by the expression apply to what follows. */
    public static String join(String... parts) {
        return Arrays.stream(parts).filter(s -> s != null && !s.isBlank()).collect(Collectors.joining("\n"));
    }

    public static boolean tooLarge(String turtle) {
        return turtle.getBytes(StandardCharsets.UTF_8).length > MAX_TURTLE_BYTES;
    }

    /** The degraded-result reason for an input over {@link #MAX_TURTLE_BYTES}. */
    public static String tooLargeReason() {
        return "Expression exceeds size limit (" + MAX_TURTLE_BYTES + " bytes)";
    }

    /** Parses Turtle with {@link #BASE}; throws on a syntax error. */
    public static Model parse(String turtle) {
        Model g = ModelFactory.createDefaultModel();
        g.read(new StringReader(turtle), BASE, "TURTLE");
        return g;
    }

    /** Adds {@code <urn:tmf921:evaluation> <urn:tmf921:evaluation#holds> <iri>} for each held resource. */
    public static void addHeld(Model g, Collection<String> held) {
        if (held == null) return;
        for (String uri : held) g.add(EVAL_CONTEXT, EVAL_HOLDS, g.createResource(uri));
    }
}
