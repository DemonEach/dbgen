package ru.demoneach.dbgenerator.generator;

import java.time.Instant;
import java.util.*;

import org.jeasy.random.EasyRandom;
import org.jeasy.random.EasyRandomParameters;

import org.jeasy.random.randomizers.text.StringRandomizer;
import ru.demoneach.dbgenerator.entity.DummyObject;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.helper.RuleEnforcer;

public class DataGenerator {
    
    private EasyRandom generalGenerator;
    private EasyRandom easyRandomStringGenerator;
    private RuleEnforcer ruleEnforcer;

    public DataGenerator(RuleEnforcer ruleEnforcer) {
        this.ruleEnforcer = ruleEnforcer;

        EasyRandomParameters easyRandomParameters = new EasyRandomParameters();
        easyRandomParameters.seed(new Random().nextLong());
        this.generalGenerator = new EasyRandom(easyRandomParameters);

        EasyRandomParameters easyRandomParametersStringGenerator = new EasyRandomParameters();
        long seed = new Random().nextLong();
        easyRandomParametersStringGenerator.seed(seed);
        easyRandomParametersStringGenerator.randomize(String.class, new StringRandomizer(10, 250, seed));
        this.easyRandomStringGenerator = new EasyRandom(easyRandomParametersStringGenerator);
    }

    // TODO: make generation more SOLID
    public Object generateDataForField(String schemaTable, Field field) {
        if (field.getDbType().equals(String.class)) {
            String str = this.easyRandomStringGenerator.nextObject(String.class);

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

        Random random = new Random();

        return switch (minBound.getClass()) {
            case Class c when Integer.class.equals(c) -> random.nextInt((Integer) maxBound - (Integer)minBound) + (Integer)minBound;
            case Class c when Double.class.equals(c) -> random.nextDouble((Double) maxBound - (Double)minBound) + (Double)minBound;
            case Class c when Long.class.equals(c) -> random.nextLong((Long) maxBound - (Long)minBound) + (Long)minBound;
            case Class c when Short.class.equals(c) -> (short) random.nextInt((Short) maxBound - (Short)minBound) + (Short)minBound;
            case Class c when Float.class.equals(c) -> random.nextFloat((Float) maxBound - (Float)minBound) + (Float)minBound;
            case Class c when Instant.class.equals(c) -> Instant.ofEpochSecond(random.nextLong(((Instant)minBound).getEpochSecond(), ((Instant)maxBound).getEpochSecond()));
            default -> null;
        };
    }
}
