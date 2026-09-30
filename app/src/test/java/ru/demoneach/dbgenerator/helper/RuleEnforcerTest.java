package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.RuleType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleEnforcerTest {

    @Test
    void preparesOncePerTableAndFieldAndKeepsNumericValidationAtSerialization() throws Exception {
        Field amount = new Field("amount", "numeric(5,2)");
        Rule rule = new Rule(RuleType.CONST, new java.util.ArrayList<>(List.of("1.239")));
        RuleEnforcer enforcer = new RuleEnforcer(Map.of("a.t.amount", rule,
                "b.t.amount", new Rule(RuleType.CONST, List.of("1000"))));
        rule.getValue().set(0, "9");
        var prepared = enforcer.preparedRule("a.t", amount);
        assertSame(prepared, enforcer.preparedRule("a.t", amount));
        assertSame(prepared.get(), prepared.get());
        assertEquals(new BigDecimal("1.239"), prepared.get());
        assertEquals("\"1.24\"", TypeConverterHelper.toCsvValue(amount, prepared.get()));
        assertThrows(IllegalArgumentException.class,
                () -> TypeConverterHelper.toCsvValue(amount, enforcer.extractRuleValue("b.t", amount)));
        assertNull(enforcer.preparedRule("c.t", amount));
    }

    @Test
    void cachedArraysAreNotSharedAndListRetainsNullEntries() {
        Field bytes = new Field("bytes", "bytea");
        Field tags = new Field("tags", "text[]");
        RuleEnforcer enforcer = new RuleEnforcer(Map.of(
                "public.t.bytes", new Rule(RuleType.CONST, List.of("\\x01ff")),
                "public.t.tags", new Rule(RuleType.LIST, List.of("[\"a\",null]")),
                "public.t.name", new Rule(RuleType.LIST, java.util.Arrays.asList((String) null))));
        ((byte[]) enforcer.extractRuleValue("public.t", bytes))[0] = 9;
        assertArrayEquals(new byte[]{1, (byte) 255}, (byte[]) enforcer.extractRuleValue("public.t", bytes));
        ((String[]) enforcer.extractRuleValue("public.t", tags))[0] = "changed";
        assertArrayEquals(new String[]{"a", null}, (String[]) enforcer.extractRuleValue("public.t", tags));
        Field name = new Field("name", "text");
        assertTrue(enforcer.hasApplicableRule("public.t", name));
        assertNull(enforcer.extractRuleValueOrDefault("public.t", name, "fallback"));
    }

    @Test
    void preparesEveryListEntryAndOnlyUsedConstAndRangeEntries() {
        Field value = new Field("value", "integer");
        RuleEnforcer list = new RuleEnforcer(Map.of("public.t.value",
                new Rule(RuleType.LIST, List.of("1", "invalid"))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> list.extractRuleValue("public.t", value)).getMessage().contains("public.t.value"));
        assertEquals(1, new RuleEnforcer(Map.of("public.t.value",
                new Rule(RuleType.CONST, List.of("1", "invalid")))).extractRuleValue("public.t", value));
        var range = new RuleEnforcer(Map.of("public.t.value",
                new Rule(RuleType.RANGE, List.of("1", "1000000", "invalid"))))
                .preparedRule("public.t", value);
        java.util.Set<Object> samples = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            int result = (Integer) range.get();
            assertTrue(result >= 1 && result < 1000000);
            samples.add(result);
        }
        assertTrue(samples.size() > 1);
    }

    @Test
    void noRulesAtAllMeansNoApplicableRuleAnywhere() {
        RuleEnforcer enforcer = new RuleEnforcer(null);
        Field field = new Field("name", "text");
        assertFalse(enforcer.hasApplicableRule("public.t", field));
        assertNull(enforcer.extractRuleValue("public.t", field));

        assertFalse(new RuleEnforcer(Map.of()).hasApplicableRule("public.t", field));
    }

    @Test
    void fieldWithoutAConfiguredRuleHasNone() {
        Field field = new Field("name", "text");
        RuleEnforcer enforcer = new RuleEnforcer(Map.of(
                "public.t.other_field", new Rule(RuleType.CONST, List.of("x"))));
        assertFalse(enforcer.hasApplicableRule("public.t", field));
    }

    @Test
    void ignoreRuleIsNeverApplicableHere() {
        // IGNORE fields are filtered out before generation is ever attempted (filterIgnoredFields);
        // hasApplicableRule must not report true for it, so a field that reaches generation despite
        // carrying IGNORE falls through to normal random generation instead of the dead safety net.
        Field field = new Field("name", "text");
        RuleEnforcer enforcer = new RuleEnforcer(Map.of("public.t.name", new Rule(RuleType.IGNORE, null)));
        assertFalse(enforcer.hasApplicableRule("public.t", field));
    }

    @Test
    void ruleWithoutAValueListIsNotApplicable() {
        Field field = new Field("name", "text");
        RuleEnforcer enforcer = new RuleEnforcer(Map.of("public.t.name", new Rule(RuleType.CONST, null)));
        assertFalse(enforcer.hasApplicableRule("public.t", field));
        assertNull(enforcer.extractRuleValue("public.t", field));
    }

    @Test
    void constRuleIsApplicableAndReturnsItsSingleValue() {
        Field field = new Field("name", "text");
        RuleEnforcer enforcer = new RuleEnforcer(Map.of(
                "public.t.name", new Rule(RuleType.CONST, List.of("fixed", "ignored-rest"))));
        assertTrue(enforcer.hasApplicableRule("public.t", field));
        assertEquals("fixed", enforcer.extractRuleValue("public.t", field));
    }

    @Test
    void listRuleIsApplicableAndReturnsOneOfItsValues() {
        Field field = new Field("name", "text");
        List<String> options = List.of("a", "b", "c");
        RuleEnforcer enforcer = new RuleEnforcer(Map.of("public.t.name", new Rule(RuleType.LIST, options)));
        assertTrue(enforcer.hasApplicableRule("public.t", field));
        for (int i = 0; i < 20; i++) {
            assertTrue(options.contains(enforcer.extractRuleValue("public.t", field)));
        }
    }

    @Test
    void rangeRuleIsApplicableAndStaysWithinBounds() {
        Field field = new Field("amount", "numeric(10,2)");
        RuleEnforcer enforcer = new RuleEnforcer(Map.of(
                "public.t.amount", new Rule(RuleType.RANGE, List.of("1.00", "2.00"))));
        assertTrue(enforcer.hasApplicableRule("public.t", field));
        for (int i = 0; i < 20; i++) {
            BigDecimal value = (BigDecimal) enforcer.extractRuleValue("public.t", field);
            assertTrue(value.compareTo(new BigDecimal("1.00")) >= 0 && value.compareTo(new BigDecimal("2.00")) <= 0);
        }
    }

    @Test
    void constNullElementResolvesToSqlNullNotToNoRule() {
        // "hasApplicableRule" must still say true here: the rule applies, it just resolves to NULL,
        // which is different from "no rule -> generate a random value".
        Field field = new Field("name", "text");
        List<String> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        RuleEnforcer enforcer = new RuleEnforcer(Map.of("public.t.name", new Rule(RuleType.CONST, withNull)));
        assertTrue(enforcer.hasApplicableRule("public.t", field));
        assertNull(enforcer.extractRuleValue("public.t", field));
    }
}
