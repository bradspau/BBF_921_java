package org.tmforum.intent.unit;

import org.junit.jupiter.api.Test;
import org.tmforum.intent.handler.Quantities;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class QuantitiesTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void sameUnit_comparesTheNumbers() {
        assertTrue(Quantities.compare(d("8"), "ms", d("25"), "ms") < 0);
        assertEquals(0, Quantities.compare(d("25"), "ms", d("25"), "ms"));
        assertTrue(Quantities.compare(d("150"), "Mbps", d("100"), "Mbps") > 0);
    }

    @Test
    void prefixesAreConverted() {
        assertEquals(0, Quantities.compare(d("1"), "Gbps", d("1000"), "Mbps"));
        assertTrue(Quantities.compare(d("2"), "Gbps", d("100"), "Mbps") > 0);
        assertTrue(Quantities.compare(d("0.5"), "s", d("600"), "ms") < 0);
        assertEquals(0, Quantities.compare(d("1"), "km", d("1000"), "m"));
    }

    @Test
    void binaryPrefixesUsePowersOfTwo() {
        assertEquals(0, Quantities.compare(d("1"), "Kibit", d("1024"), "bit"));
        assertTrue(Quantities.compare(d("1"), "kbit", d("1024"), "bit") < 0, "k is 1000, Ki is 1024");
        assertEquals(0, Quantities.compare(d("1"), "MiB", d("8388608"), "bit"));
    }

    @Test
    void namedUnitsWithoutPrefixAreRecognised() {
        assertEquals(0, Quantities.compare(d("1"), "min", d("60"), "s"));
        assertEquals(0, Quantities.compare(d("1"), "B", d("8"), "bit"));
        assertEquals(0, Quantities.compare(d("5"), "percent", d("5"), "%"));
    }

    @Test
    void differentDimensionsDoNotCompare() {
        UnitMismatchAssert.thrown(() -> Quantities.compare(d("8"), "ms", d("25"), "Mbps"));
        UnitMismatchAssert.thrown(() -> Quantities.compare(d("1"), "m", d("1"), "s"));
    }

    @Test
    void aValueWithoutUnitOnlyComparesWithAnotherValueWithoutUnit() {
        assertTrue(Quantities.compare(d("10"), null, d("20"), "") < 0);
        UnitMismatchAssert.thrown(() -> Quantities.compare(d("150"), null, d("100"), "Mbps"));
        UnitMismatchAssert.thrown(() -> Quantities.compare(d("150"), "Mbps", d("100"), null));
    }

    @Test
    void anUnknownUnitMatchesOnlyItself() {
        assertTrue(Quantities.compare(d("1"), "widgets", d("2"), "widgets") < 0);
        UnitMismatchAssert.thrown(() -> Quantities.compare(d("1"), "widgets", d("2"), "gadgets"));
    }

    @Test
    void conversionIsExact() {
        // 0.1 + 0.2 style rounding must not appear: compare as exact decimals
        assertEquals(0, Quantities.compare(d("0.3"), "s", d("300"), "ms"));
        assertEquals(0, Quantities.compare(d("1.5"), "Gbps", d("1500"), "Mbps"));
    }

    private static final class UnitMismatchAssert {
        static void thrown(Runnable r) {
            assertThrows(Quantities.UnitMismatch.class, r::run);
        }
    }
}
