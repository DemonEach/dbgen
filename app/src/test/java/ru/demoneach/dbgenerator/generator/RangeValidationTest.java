package ru.demoneach.dbgenerator.generator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RangeValidationTest {

    @Test
    void equalBoundsReturnThatValueForEveryType() {
        assertEquals(5, DataGenerator.generateRandomValuesInRange(5, 5));
        assertEquals(5L, DataGenerator.generateRandomValuesInRange(5L, 5L));
        assertEquals((short) 5, DataGenerator.generateRandomValuesInRange((short) 5, (short) 5));
        assertEquals(1.5d, DataGenerator.generateRandomValuesInRange(1.5d, 1.5d));
        assertEquals(1.5f, DataGenerator.generateRandomValuesInRange(1.5f, 1.5f));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(now, DataGenerator.generateRandomValuesInRange(now, now));
        assertEquals(now, DataGenerator.generateRandomValuesInRange(now, now.plusMillis(500)));
    }

    @Test
    void minAboveMaxIsRejectedWithValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataGenerator.generateRandomValuesInRange(10, 1));
        assertTrue(e.getMessage().contains("10") && e.getMessage().contains("1"));
        assertThrows(IllegalArgumentException.class,
                () -> DataGenerator.generateRandomValuesInRange(Instant.now().plusSeconds(5), Instant.now()));
    }

    @Test
    void unsupportedAndMismatchedTypesFailInsteadOfReturningNull() {
        assertThrows(IllegalArgumentException.class, () -> DataGenerator.generateRandomValuesInRange("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> DataGenerator.generateRandomValuesInRange(1, 2L));
        assertThrows(IllegalArgumentException.class, () -> DataGenerator.generateRandomValuesInRange(null, 1));
    }

    @Test
    void valuesStayInsideBoundsAndWideIntRangeDoesNotOverflow() {
        for (int i = 0; i < 1000; i++) {
            int v = (Integer) DataGenerator.generateRandomValuesInRange(3, 7);
            assertTrue(v >= 3 && v < 7);
            int wide = (Integer) DataGenerator.generateRandomValuesInRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertTrue(wide < Integer.MAX_VALUE);
            BigDecimal d = (BigDecimal) DataGenerator.generateRandomValuesInRange(new BigDecimal("1"), new BigDecimal("2"));
            assertTrue(d.compareTo(BigDecimal.ONE) >= 0 && d.compareTo(new BigDecimal("2")) <= 0);
        }
    }
}
