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
