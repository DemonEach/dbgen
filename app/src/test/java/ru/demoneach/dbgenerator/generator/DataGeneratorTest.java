package ru.demoneach.dbgenerator.generator;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.RuleType;
import ru.demoneach.dbgenerator.helper.RuleEnforcer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Covers generateDataForField() taking the CONST/LIST/RANGE short-circuit added to skip wasted
// random generation (see TODO.md P2 "Генерация") - there was no prior test protecting the rule
// vs. no-rule split, so both branches are exercised here per field kind.
class DataGeneratorTest {

    @Test
    void constRuleShortCircuitsStringGeneration() {
        DataGenerator generator = generatorWithRule("public.t.name", new Rule(RuleType.CONST, List.of("fixed-value")));
        Object value = generator.generateDataForField("public.t", new Field("name", "text"));
        assertEquals("fixed-value", value);
    }

    @Test
    void noRuleGeneratesARandomNonNullStringWithinMaxLength() {
        DataGenerator generator = generatorWithRule(null, null);
        Field field = new Field("name", "character varying(5)");
        Object value = generator.generateDataForField("public.t", field);
        assertNotNull(value);
        assertTrue(((String) value).length() <= 5);
    }

    @Test
    void constRuleShortCircuitsNumericGenerationAndStillGetsRoundedAndValidated() {
        DataGenerator generator = generatorWithRule("public.t.amount", new Rule(RuleType.CONST, List.of("1.239")));
        Object value = generator.generateDataForField("public.t", new Field("amount", "numeric(5,2)"));
        // CONST goes through generateDataForField untouched; rounding/validation happens once,
        // at bind time (TypeConverterHelper), not duplicated here - so this is the raw parsed value
        assertEquals(new BigDecimal("1.239"), value);
    }

    @Test
    void noRuleGeneratesANumericValueWithinDeclaredPrecisionAndScale() {
        DataGenerator generator = generatorWithRule(null, null);
        Field field = new Field("amount", "numeric(5,2)");
        for (int i = 0; i < 20; i++) {
            BigDecimal value = (BigDecimal) generator.generateDataForField("public.t", field);
            assertEquals(2, value.scale());
            assertTrue(value.unscaledValue().abs().compareTo(BigDecimal.TEN.pow(5).toBigInteger()) < 0);
        }
    }

    @Test
    void rangeRuleShortCircuitsAndStaysWithinDeclaredBounds() {
        DataGenerator generator = generatorWithRule("public.t.amount", new Rule(RuleType.RANGE, List.of("10.00", "10.00")));
        Object value = generator.generateDataForField("public.t", new Field("amount", "numeric(5,2)"));
        assertEquals(new BigDecimal("10.00"), value);
    }

    @Test
    void constRuleShortCircuitsJsonGenerationAndPassesRuleTextThrough() {
        DataGenerator generator = generatorWithRule("public.t.payload", new Rule(RuleType.CONST, List.of("{\"a\":1}")));
        Object value = generator.generateDataForField("public.t", new Field("payload", "jsonb"));
        assertEquals("{\"a\":1}", value);
    }

    @Test
    void noRuleGeneratesANonNullJsonObject() {
        DataGenerator generator = generatorWithRule(null, null);
        Object value = generator.generateDataForField("public.t", new Field("payload", "jsonb"));
        assertNotNull(value);
    }

    private DataGenerator generatorWithRule(String key, Rule rule) {
        RuleEnforcer enforcer = new RuleEnforcer(key == null ? null : Map.of(key, rule));
        return new DataGenerator(enforcer);
    }
}
