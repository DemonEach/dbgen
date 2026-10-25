package ru.demoneach.dbgenerator.generator;

import org.jeasy.random.EasyRandom;
import org.jeasy.random.EasyRandomParameters;
import ru.demoneach.dbgenerator.entity.DummyObject;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.helper.RuleEnforcer;

import java.time.Instant;
import java.math.BigDecimal;
import java.math.BigInteger;
import ru.demoneach.dbgenerator.helper.TypeConverterHelper;
import java.util.Map;
import java.util.Set;
import java.util.Random;

public class DataGenerator {

    private static final Random random = new Random();
    private static final Integer COLLECTION_MIN_RANGE = 1;
    private static final Integer COLLECTION_MAX_RANGE = 3;

    private EasyRandom generalGenerator;
    private RuleEnforcer ruleEnforcer;


    public DataGenerator(RuleEnforcer ruleEnforcer) {
        this.ruleEnforcer = ruleEnforcer;
        long seed = new Random().nextLong();

        EasyRandomParameters easyRandomParameters = new EasyRandomParameters();
        easyRandomParameters.seed(seed);
        easyRandomParameters.collectionSizeRange(COLLECTION_MIN_RANGE, COLLECTION_MAX_RANGE);
        easyRandomParameters.randomize(String.class, new CustomStringRandomizer());
        this.generalGenerator = new EasyRandom(easyRandomParameters);
    }

    // TODO: make generation more SOLID
    public Object generateDataForField(String schemaTable, Field field) {
        // CONST/LIST/RANGE fully replace the generated value, so skip generating (and for JSON/
        // numeric, the extra object/BigInteger work) one that would just be thrown away.
        var rule = ruleEnforcer.preparedRule(schemaTable, field);
        if (rule != null) {
            return rule.get();
        }

        if (field.getNumericLimit() != null) {
            BigInteger unscaled;
            do {
                unscaled = new BigInteger(field.getNumericLimit().bitLength(), random);
            } while (unscaled.compareTo(field.getNumericLimit()) >= 0);
            if (random.nextBoolean()) unscaled = unscaled.negate();
            return TypeConverterHelper.numericValue(field, new BigDecimal(unscaled, field.getNumericScale()));
        }
        if (field.getDbType().equals(String.class)) {
            String str = this.generalGenerator.nextObject(String.class);

            if (field.getMaxLength() != null) {
                str = str.substring(0, field.getMaxLength() > str.length() ? str.length() : field.getMaxLength());
            }

            return str;
        }

        // JSON handling: a random Map cannot be generated in a meaningful way,
        // so a dummy object is generated and serialized to json instead
        if (field.getDbType().equals(Map.class)) {
            return this.generalGenerator.nextObject(DummyObject.class);
        }

        return this.generalGenerator.nextObject(field.getDbType());
    }

    /**
     * Checks that both bounds are of the same supported type and min does not exceed max.
     * Called when a RANGE rule is prepared so a bad rule fails before any row is generated.
     */
    public static void validateRange(Object minBound, Object maxBound) throws IllegalArgumentException {
        if (minBound == null || maxBound == null) {
            throw new IllegalArgumentException("RANGE bounds must not be null");
        }
        if (!minBound.getClass().equals(maxBound.getClass())) {
            throw new IllegalArgumentException("Классы для min: %s и max: %s не совпадают".formatted(minBound.getClass(), maxBound.getClass()));
        }
        if (!(minBound instanceof Comparable<?>) || !SUPPORTED_RANGE_TYPES.contains(minBound.getClass())) {
            throw new IllegalArgumentException("RANGE is not supported for type " + minBound.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        int order = ((Comparable<Object>) minBound).compareTo(maxBound);
        if (order > 0) {
            throw new IllegalArgumentException("RANGE min %s exceeds max %s".formatted(minBound, maxBound));
        }
    }

    private static final Set<Class<?>> SUPPORTED_RANGE_TYPES =
            Set.of(BigDecimal.class, Integer.class, Double.class, Long.class, Short.class, Float.class, Instant.class);

    /** Upper bound is exclusive, except when min equals max: then min is returned. */
    public static Object generateRandomValuesInRange(Object minBound, Object maxBound) throws IllegalArgumentException {
        validateRange(minBound, maxBound);
        if (minBound.equals(maxBound) || (minBound instanceof BigDecimal a && a.compareTo((BigDecimal) maxBound) == 0)) {
            return minBound;
        }

        return switch (minBound) {
            case BigDecimal min -> {
                BigDecimal max = (BigDecimal) maxBound;
                yield min.add(max.subtract(min).multiply(BigDecimal.valueOf(random.nextDouble())));
            }
            case Integer min -> random.nextInt(min, (Integer) maxBound);
            case Double min -> random.nextDouble(min, (Double) maxBound);
            case Long min -> random.nextLong(min, (Long) maxBound);
            // the cast has to cover the whole expression, otherwise the result is an Integer
            // and setShort() later fails with a ClassCastException
            case Short min -> (short) random.nextInt(min, (Short) maxBound);
            case Float min -> random.nextFloat(min, (Float) maxBound);
            case Instant min -> {
                long maxSecond = ((Instant) maxBound).getEpochSecond();
                // bounds inside the same second: nextLong(a, a) would throw
                yield min.getEpochSecond() == maxSecond ? min : Instant.ofEpochSecond(random.nextLong(min.getEpochSecond(), maxSecond));
            }
            default -> throw new IllegalArgumentException("RANGE is not supported for type " + minBound.getClass().getSimpleName());
        };
    }
}
