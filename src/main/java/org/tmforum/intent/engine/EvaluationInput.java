package org.tmforum.intent.engine;

import java.util.Collection;

/** Turtle strings exactly as the dispatcher holds them. */
public record EvaluationInput(String intentId, String expression, String observations,
                              String resources, Collection<String> held) {}
