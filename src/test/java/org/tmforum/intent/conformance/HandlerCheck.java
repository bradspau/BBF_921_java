package org.tmforum.intent.conformance;

import org.tmforum.intent.engine.HandlerProjection;
import org.tmforum.intent.model.EvaluationResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The handler-level expectation of a conformance case, shared by {@code ResultAdapterTest} and
 * {@link ReadinessGuardTest}.
 *
 * <ul>
 *   <li>A case without {@code handler.txt}: the Java and the rule engine's handler results agree
 *       ({@link HandlerProjection#firstDifference}: state, reason, ordered condition entries on the consumed keys,
 *       Flow 3 proposal).</li>
 *   <li>A case with {@code handler.txt} (every case with a registered deviation, and some others): the rule engine's
 *       projection equals the file's, and the Java engine's equals it too or the {@code java-} lines where the Java
 *       engine differs, so the deviation stays visible.</li>
 * </ul>
 *
 * <p>{@code handler.txt} format: {@code state:}, {@code reason:} (empty for none) and one {@code condition:} line per
 * entry ({@link HandlerProjection#line}); {@code java-state:}, {@code java-reason:}, {@code java-condition:} likewise
 * for the Java engine where it differs. {@code #} starts a comment. The files are engine output dumped from both
 * engines, then reviewed by hand against the Java oracle and the PDFs.
 */
public final class HandlerCheck {

    private HandlerCheck() {}

    /** The case's handler.txt as {jena, java} expectations, or null when the case has none. */
    public static HandlerProjection[] expectations(ConformanceCase c) {
        String path = ConformanceLoader.ROOT + "/" + c.name() + "/handler.txt";
        try (InputStream in = HandlerCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> one = new HashMap<>();
            List<String> conds = new ArrayList<>();
            List<String> javaConds = new ArrayList<>();
            for (String l : text.split("\\R")) {
                if (l.isBlank() || l.startsWith("#")) continue;
                int i = l.indexOf(':');
                String key = l.substring(0, i).trim();
                String value = l.substring(i + 1).trim();
                switch (key) {
                    case "condition" -> conds.add(value);
                    case "java-condition" -> javaConds.add(value);
                    default -> one.put(key, value);
                }
            }
            HandlerProjection jena = new HandlerProjection(one.get("state"), one.getOrDefault("reason", ""), conds);
            HandlerProjection java = one.containsKey("java-state")
                    ? new HandlerProjection(one.get("java-state"), one.getOrDefault("java-reason", ""), javaConds)
                    : jena;
            return new HandlerProjection[]{jena, java};
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Why the handler results of case {@code c} do not meet its expectation, or null when they do. */
    public static String mismatch(ConformanceCase c, EvaluationResult java, EvaluationResult jena) {
        HandlerProjection[] expected = expectations(c);
        if (expected == null) {
            if (c.expectedJena() != null) return c.name() + ": a registered deviation needs a reviewed handler.txt";
            String d = HandlerProjection.firstDifference(c.expression(), java, "java", jena, "jena");
            return d == null ? null : c.name() + ": " + d;
        }
        HandlerProjection pj = HandlerProjection.of(jena);
        if (!expected[0].equals(pj)) return c.name() + ": Jena against handler.txt: expected " + expected[0] + " but was " + pj;
        HandlerProjection pa = HandlerProjection.of(java);
        if (!expected[1].equals(pa)) return c.name() + ": Java against handler.txt: expected " + expected[1] + " but was " + pa;
        return null;
    }
}
