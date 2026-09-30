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

    public static Object generateRandomValuesInRange(Object minBound, Object maxBound) throws IllegalArgumentException {
        if (!minBound.getClass().equals(maxBound.getClass())) {
            throw new IllegalArgumentException("Классы для min: %s и max: %s не совпадают".formatted(minBound.getClass(), maxBound.getClass()));
        }

        return switch (minBound.getClass()) {
            case Class c when BigDecimal.class.equals(c) -> {
                BigDecimal min = (BigDecimal) minBound;
                BigDecimal max = (BigDecimal) maxBound;
                if (min.compareTo(max) > 0) throw new IllegalArgumentException("numeric RANGE min exceeds max");
                if (min.compareTo(max) == 0) yield min;
                yield min.add(max.subtract(min).multiply(BigDecimal.valueOf(random.nextDouble())));
            }
            case Class c when Integer.class.equals(c) ->
                    random.nextInt((Integer) maxBound - (Integer) minBound) + (Integer) minBound;
            case Class c when Double.class.equals(c) ->
                    random.nextDouble((Double) maxBound - (Double) minBound) + (Double) minBound;
            case Class c when Long.class.equals(c) ->
                    random.nextLong((Long) maxBound - (Long) minBound) + (Long) minBound;
            // the cast has to cover the whole expression, otherwise the result is an Integer
            // and setShort() later fails with a ClassCastException
            case Class c when Short.class.equals(c) ->
                    (short) (random.nextInt((Short) maxBound - (Short) minBound) + (Short) minBound);
            case Class c when Float.class.equals(c) ->
                    random.nextFloat((Float) maxBound - (Float) minBound) + (Float) minBound;
            case Class c when Instant.class.equals(c) ->
                    Instant.ofEpochSecond(random.nextLong(((Instant) minBound).getEpochSecond(), ((Instant) maxBound).getEpochSecond()));
            default -> null;
        };
    }
}
