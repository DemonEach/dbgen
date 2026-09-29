package ru.demoneach.dbgenerator.helper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.generator.DataGenerator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

@AllArgsConstructor
@Data
@Getter
@Setter
public class RuleEnforcer {

    private static final String KEY_TEMPLATE = "%s.%s";
    private static final Random RANDOM = new Random();

    private Map<String, Rule> fieldGenerationRules;

    public Object extractRuleValue(String schemaTable, Field field) {
        return extractRuleValueOrDefault(schemaTable, field, null);
    }

    // Lets callers skip generating a value they are about to discard: CONST/LIST/RANGE fully
    // replace it, so there is no need to run EasyRandom (or the numeric/String generation path)
    // first just to throw the result away. IGNORE is excluded: those fields are filtered out
    // before generation (see filterIgnoredFields), so reaching this point with an IGNORE rule
    // would be a bug elsewhere - falling through to normal generation is the safer default.
    public boolean hasApplicableRule(String schemaTable, Field field) {
        if (Objects.isNull(fieldGenerationRules) || fieldGenerationRules.isEmpty()) {
            return false;
        }

        Rule rule = this.fieldGenerationRules.get(KEY_TEMPLATE.formatted(schemaTable, SqlIdentifiers.configPart(field.getName())));
        return rule != null && rule.getValue() != null && rule.getRuleType() != RuleType.IGNORE;
    }

    public Object extractRuleValueOrDefault(String schemaTable, Field field, Object defaultValue) {
        if (Objects.isNull(fieldGenerationRules) || fieldGenerationRules.isEmpty()) {
            return defaultValue;
        }

        Rule rule = this.fieldGenerationRules.get(KEY_TEMPLATE.formatted(schemaTable, SqlIdentifiers.configPart(field.getName())));

        if (rule == null || rule.getValue() == null) {
            return defaultValue;
        }

        // the switch is exhaustive on purpose: adding a new RuleType must not silently
        // fall through to a default branch that puts a raw List into the column
        return switch (rule.getRuleType()) {
            case CONST -> TypeConverterHelper.convertObjectToCorrectType(field.getDbType(), rule.getValue().get(0));
            case LIST -> {
                String str = rule.getValue().get(RANDOM.nextInt(rule.getValue().size()));
                yield TypeConverterHelper.convertObjectToCorrectType(field.getDbType(), str);
            }
            case RANGE -> {
                Object minRange = TypeConverterHelper.convertObjectToCorrectType(field.getDbType(), rule.getValue().get(0));
                Object maxRange = TypeConverterHelper.convertObjectToCorrectType(field.getDbType(), rule.getValue().get(1));

                yield DataGenerator.generateRandomValuesInRange(minRange, maxRange);
            }
            // the field is filtered out before generation, so this is only a safety net
            case IGNORE -> defaultValue;
        };
    }

    private boolean checkIfHasRuleType(String schemaTable, String fieldName, RuleType ruleType) {
        if (Objects.isNull(fieldGenerationRules) || fieldGenerationRules.isEmpty()) {
            return false;
        }

        Rule rule = this.fieldGenerationRules.get(KEY_TEMPLATE.formatted(schemaTable, SqlIdentifiers.configPart(fieldName)));

        if (rule == null || rule.getRuleType() == null) {
            return false;
        }

        return ruleType.equals(rule.getRuleType());
    }

    public boolean checkIfFieldIgnored(Table table, Field field) {
        if (field.isDatabaseGenerated()) return true;
        if (field.hasDefault() && (fieldGenerationRules == null
                || fieldGenerationRules.get(KEY_TEMPLATE.formatted(table, SqlIdentifiers.configPart(field.getName()))) == null)) return true;
        if (field.getDbType().equals(Ignorable.class)) {
            return true;
        }

        // rules are keyed by schema.table.column, so the schema qualified name is required here
        return checkIfHasRuleType(table.toString(), field.getName(), RuleType.IGNORE);
    }

    public List<Field> filterIgnoredFields(Table table) {
        return table.getFields().stream().filter(f -> !this.checkIfFieldIgnored(table, f)).toList();
    }
}
