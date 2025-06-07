package ru.demoneach.dbgenerator.generator;

import org.jeasy.random.EasyRandom;
import org.jeasy.random.EasyRandomParameters;
import ru.demoneach.dbgenerator.entity.DummyObject;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.helper.RuleEnforcer;

import java.time.Instant;
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
        if (field.getDbType().equals(String.class)) {
            String str = this.generalGenerator.nextObject(String.class);

            if (field.getMaxLength() != null) {
                str = str.substring(0, field.getMaxLength() > str.length() ? str.length() : field.getMaxLength());
            }

            return ruleEnforcer.extractRuleValueOrDefault(schemaTable, field, str);
        }

        // JSON handling
        if (field.getDbType().equals(Map.class)) {
            this.generalGenerator.nextObject(DummyObject.class);
        }

        return ruleEnforcer.extractRuleValueOrDefault(schemaTable, field, this.generalGenerator.nextObject(field.getDbType()));
    }

    public static Object generateRandomValuesInRange(Object minBound, Object maxBound) throws IllegalArgumentException {
        if (!minBound.getClass().equals(maxBound.getClass())) {
            throw new IllegalArgumentException("Классы для min: %s и max: %s не совпадают".formatted(minBound.getClass(), maxBound.getClass()));
        }

        return switch (minBound.getClass()) {
            case Class c when Integer.class.equals(c) ->
                    random.nextInt((Integer) maxBound - (Integer) minBound) + (Integer) minBound;
            case Class c when Double.class.equals(c) ->
                    random.nextDouble((Double) maxBound - (Double) minBound) + (Double) minBound;
            case Class c when Long.class.equals(c) ->
                    random.nextLong((Long) maxBound - (Long) minBound) + (Long) minBound;
            case Class c when Short.class.equals(c) ->
                    (short) random.nextInt((Short) maxBound - (Short) minBound) + (Short) minBound;
            case Class c when Float.class.equals(c) ->
                    random.nextFloat((Float) maxBound - (Float) minBound) + (Float) minBound;
            case Class c when Instant.class.equals(c) ->
                    Instant.ofEpochSecond(random.nextLong(((Instant) minBound).getEpochSecond(), ((Instant) maxBound).getEpochSecond()));
            default -> null;
        };
    }
}
