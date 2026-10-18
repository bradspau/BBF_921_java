package org.tmforum.intent.handler;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit handling for quantity comparison (TR292D §2.5, §5.2).
 *
 * A unit string is an optional prefix (SI "M", "k", "m" ... or binary "Ki", "Mi" ...) followed by a unit symbol,
 * for example {@code Mbps}, {@code ms}, {@code Kibit}. Comparison converts both sides to the symbol's base
 * scale, so 1 {@code Gbps} equals 1000 {@code Mbps}. Argument unit dimensions must match; a quantity without a
 * unit is dimensionless and only compares with another dimensionless value (or a plain number).
 *
 * Unit symbols are looked up in a small registry ({@link #SYMBOLS}); a unit that is not in it is treated as its
 * own dimension, so it only matches the identical string. Composite expressions such as {@code m/s} are not
 * decomposed.
 */
public final class Quantities {

    private Quantities() {}

    /** A unit reduced to a dimension (what is measured) and the factor from the unit to that dimension's base. */
    public record Unit(String dimension, BigDecimal factor) {
        public static final Unit NONE = new Unit("", BigDecimal.ONE);
    }

    /** Raised when two quantities cannot be compared because their units have different dimensions. */
    public static final class UnitMismatch extends RuntimeException {
        public UnitMismatch(String message) { super(message); }
    }

    /** symbol → (dimension, factor to the dimension's base unit) */
    private static final Map<String, Unit> SYMBOLS = new LinkedHashMap<>();
    /** prefix → factor, longest prefixes first so "Ki" and "da" win over "K"/"d". */
    private static final Map<String, BigDecimal> PREFIXES = new LinkedHashMap<>();

    static {
        // information and data rate (bit is the base; B = byte = 8 bit)
        SYMBOLS.put("bit", new Unit("bit", BigDecimal.ONE));
        SYMBOLS.put("B", new Unit("bit", BigDecimal.valueOf(8)));
        SYMBOLS.put("bps", new Unit("bit/s", BigDecimal.ONE));
        SYMBOLS.put("Bps", new Unit("bit/s", BigDecimal.valueOf(8)));
        // time (second is the base)
        SYMBOLS.put("s", new Unit("s", BigDecimal.ONE));
        SYMBOLS.put("min", new Unit("s", BigDecimal.valueOf(60)));
        SYMBOLS.put("h", new Unit("s", BigDecimal.valueOf(3600)));
        SYMBOLS.put("d", new Unit("s", BigDecimal.valueOf(86400)));
        // SI base and common derived units
        for (String sym : new String[]{"m", "g", "A", "K", "mol", "cd", "Hz", "N", "Pa", "J", "W", "C", "V", "Wh"}) {
            SYMBOLS.put(sym, new Unit(sym, BigDecimal.ONE));
        }
        // ratios
        SYMBOLS.put("percent", new Unit("percent", BigDecimal.ONE));
        SYMBOLS.put("%", new Unit("percent", BigDecimal.ONE));

        // binary prefixes (ISO/IEC 80000, Table 2)
        String[] binary = {"Ki", "Mi", "Gi", "Ti", "Pi", "Ei", "Zi", "Yi"};
        for (int i = 0; i < binary.length; i++) {
            PREFIXES.put(binary[i], new BigDecimal(BigInteger.valueOf(1024).pow(i + 1)));
        }
        PREFIXES.put("da", BigDecimal.TEN);
        // decimal prefixes (ISO/IEC 80000, Table 1)
        String[] big = {"k", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q"};
        int[] bigExp = {3, 6, 9, 12, 15, 18, 21, 24, 27, 30};
        for (int i = 0; i < big.length; i++) PREFIXES.put(big[i], BigDecimal.TEN.pow(bigExp[i]));
        PREFIXES.put("h", BigDecimal.TEN.pow(2));
        String[] small = {"d", "c", "m", "μ", "u", "n", "p", "f", "a", "z", "y", "r", "q"};
        int[] smallExp = {1, 2, 3, 6, 6, 9, 12, 15, 18, 21, 24, 27, 30};
        for (int i = 0; i < small.length; i++) {
            PREFIXES.put(small[i], BigDecimal.ONE.divide(BigDecimal.TEN.pow(smallExp[i])));
        }
    }

    /** Reduces a unit string to dimension and factor. Null or blank means dimensionless. */
    public static Unit parse(String unit) {
        if (unit == null || unit.isBlank()) return Unit.NONE;
        String u = unit.trim();
        Unit direct = SYMBOLS.get(u);
        if (direct != null) return direct;
        // longest matching prefix first, so "Ki" is tried before "K"-like single letters
        for (Map.Entry<String, BigDecimal> p : PREFIXES.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length())).toList()) {
            if (u.length() > p.getKey().length() && u.startsWith(p.getKey())) {
                Unit base = SYMBOLS.get(u.substring(p.getKey().length()));
                if (base != null) return new Unit(base.dimension(), base.factor().multiply(p.getValue()));
            }
        }
        return new Unit(u, BigDecimal.ONE);   // unknown unit: its own dimension, compares only with itself
    }

    /**
     * Compares {@code a} (in unit {@code ua}) with {@code b} (in unit {@code ub}) after unit conversion.
     *
     * @return negative, zero or positive as a is less than, equal to or greater than b
     * @throws UnitMismatch if the two units measure different things
     */
    public static int compare(BigDecimal a, String ua, BigDecimal b, String ub) {
        Unit pa = parse(ua);
        Unit pb = parse(ub);
        if (!pa.dimension().equals(pb.dimension())) {
            throw new UnitMismatch("unit mismatch: " + describe(ua) + " cannot be compared with " + describe(ub));
        }
        return a.multiply(pa.factor()).compareTo(b.multiply(pb.factor()));
    }

    private static String describe(String unit) {
        return (unit == null || unit.isBlank()) ? "a value without unit" : "'" + unit.trim() + "'";
    }
}
