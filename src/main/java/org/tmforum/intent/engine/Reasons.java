package org.tmforum.intent.engine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Human-readable explanations of why a leaf element is false, shared by the result graph and TR290 reports. */
public final class Reasons {

    private Reasons() {}

    /** @return the explanation for a leaf detail map, or null when there is nothing to say */
    public static String of(Map<String, Object> d) {
        if (d.get("error") != null) return d.get("error").toString();
        if (d.get("observed") != null && d.get("operator") != null && d.get("bound") != null) {
            return withUnit(d.get("observed"), d.get("observedUnit")) + " " + d.get("operator") + " "
                    + withUnit(d.get("bound"), d.get("boundUnit")) + " is false";
        }
        return null;
    }

    /**
     * The evaluation-level reason of a degraded result, shared by both engines:
     * {@code "Conditions not met: <label>; <label>"} over the conditions that did not pass, or
     * {@code "Conditions not met"} when there are none.
     */
    public static String conditionsNotMet(List<Map<String, Object>> conditions) {
        List<String> labels = conditions.stream()
                .filter(c -> !Boolean.TRUE.equals(c.get("passed")))
                .map(Reasons::failLabel)
                .toList();
        return labels.isEmpty() ? "Conditions not met"
                : "Conditions not met: " + String.join("; ", labels);
    }

    private static String failLabel(Map<String, Object> c) {
        if (c.containsKey("error")) return c.get("type") + ": " + c.get("error");
        String t = String.valueOf(c.get("type"));
        String sym = (String) c.get("operator");
        if (sym == null) return t + ": FAIL";
        Object obs = c.get("observed"); Object bnd = c.get("bound");
        if (bnd == null) bnd = c.get("lower") + "…" + c.get("upper");
        String ou = c.get("observedUnit") != null ? " " + c.get("observedUnit") : "";
        String bu = c.get("boundUnit") != null ? " " + c.get("boundUnit") : "";
        return obs + ou + " " + sym + " " + bnd + bu + ": FAIL";
    }

    private static String withUnit(Object value, Object unit) {
        return plain(value) + (unit != null ? " " + unit : "");
    }

    public static String plain(Object v) {
        return v instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(v);
    }
}
