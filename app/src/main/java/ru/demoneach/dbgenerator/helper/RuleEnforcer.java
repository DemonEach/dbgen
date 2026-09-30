package ru.demoneach.dbgenerator.helper;

import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.generator.DataGenerator;

import java.util.*;
import java.util.function.Supplier;

public class RuleEnforcer {
    private static final String KEY_TEMPLATE = "%s.%s";
    private static final Random RANDOM = new Random();
    private static final Supplier<Object> NO_RULE = () -> null;
    private final Map<String, Rule> fieldGenerationRules = new HashMap<>();
    private final Map<String, Map<Field, Supplier<Object>>> preparedRules = new HashMap<>();

    public RuleEnforcer(Map<String, Rule> rules) {
        // A run owns its configuration; later edits must not invalidate prepared values.
        if (rules != null) rules.forEach((key, rule) -> fieldGenerationRules.put(key,
                rule == null ? null : new Rule(rule.getRuleType(), rule.getValue() == null
                        ? null : new ArrayList<>(rule.getValue()))));
    }

    // Prepare on first use: skipped/default/generated/FK fields never need their rules parsed.
    public Supplier<Object> preparedRule(String schemaTable, Field field) {
        if (fieldGenerationRules.isEmpty()) return null;
        Supplier<Object> rule = preparedRules.computeIfAbsent(schemaTable, key -> new HashMap<>())
                .computeIfAbsent(field, key -> prepareRule(schemaTable, field));
        return rule == NO_RULE ? null : rule;
    }

    private Supplier<Object> prepareRule(String schemaTable, Field field) {
        String key = KEY_TEMPLATE.formatted(schemaTable, SqlIdentifiers.configPart(field.getName()));
        Rule rule = fieldGenerationRules.get(key);
        if (rule == null || rule.getValue() == null || rule.getRuleType() == RuleType.IGNORE) return NO_RULE;
        if (rule.getRuleType() == null) throw new IllegalArgumentException("Missing rule type for " + key);
        int count = switch (rule.getRuleType()) {
            case CONST -> 1;
            case RANGE -> 2;
            case LIST -> rule.getValue().size();
            case IGNORE -> 0;
        };
        if (count == 0 || rule.getValue().size() < count) {
            throw new IllegalArgumentException("Not enough rule values for " + key);
        }
        Object[] values = new Object[count];
        for (int i = 0; i < count; i++) {
            try {
                values[i] = TypeConverterHelper.convertObjectToCorrectType(field.getDbType(), rule.getValue().get(i));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid rule value for " + key + " at index " + i, failure);
            }
        }
        return switch (rule.getRuleType()) {
            case CONST -> () -> copyArray(values[0]);
            case LIST -> () -> copyArray(values[RANDOM.nextInt(values.length)]);
            case RANGE -> () -> DataGenerator.generateRandomValuesInRange(values[0], values[1]);
            case IGNORE -> NO_RULE;
        };
    }

    private static Object copyArray(Object value) {
        if (value instanceof byte[] bytes) return bytes.clone();
        if (value instanceof String[] strings) return strings.clone();
        return value;
    }

    public boolean hasApplicableRule(String schemaTable, Field field) {
        return preparedRule(schemaTable, field) != null;
    }

    public Object extractRuleValue(String schemaTable, Field field) {
        return extractRuleValueOrDefault(schemaTable, field, null);
    }

    public Object extractRuleValueOrDefault(String schemaTable, Field field, Object defaultValue) {
        Supplier<Object> rule = preparedRule(schemaTable, field);
        return rule == null ? defaultValue : rule.get();
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
