package org.tmforum.intent.engine;

import org.apache.jena.rdf.model.Model;
import org.tmforum.intent.model.EvaluationResult;

public record EvaluationOutcome(Model resultGraph, EvaluationResult result) {}
