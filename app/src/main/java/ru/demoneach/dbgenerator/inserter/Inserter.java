package ru.demoneach.dbgenerator.inserter;

import lombok.Getter;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.generator.DataGenerator;
import ru.demoneach.dbgenerator.helper.RuleEnforcer;

import java.sql.Connection;
import java.util.Map;

@Getter
public abstract class Inserter {

    private Connection conn;
    private RuleEnforcer ruleEnforcer;
    private DataGenerator dataGenerator;

    Inserter(Map<String, Rule> fieldGenerationRules, Connection conn) {
        this.conn = conn;
        this.ruleEnforcer = new RuleEnforcer(fieldGenerationRules);
        this.dataGenerator = new DataGenerator(this.ruleEnforcer);
    }
}
