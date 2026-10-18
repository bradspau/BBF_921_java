package org.tmforum.intent.engine;

import java.math.BigDecimal;
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

    private static String withUnit(Object value, Object unit) {
        return plain(value) + (unit != null ? " " + unit : "");
    }

    static String plain(Object v) {
        return v instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(v);
    }
}
