package ru.demoneach.dbgenerator.inserter;

import lombok.Getter;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.Table;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.generator.DataGenerator;
import ru.demoneach.dbgenerator.helper.RuleEnforcer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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

    // COPY cannot express a row with no input columns. Use the same bounded JDBC
    // fallback for all strategies and let PostgreSQL evaluate defaults per row.
    protected void insertDefaultRows(Table table, Parameters parameters) throws SQLException {
        if (parameters.getAmountOfEntries() == 0) return;
        String sql = "INSERT INTO \"%s\".\"%s\" DEFAULT VALUES".formatted(
                table.getSchema().replace("\"", "\"\""), table.getTableName().replace("\"", "\"\""));
        int batchSize = parameters.getBatchSave();
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            for (int row = 0; row < parameters.getAmountOfEntries(); row++) {
                statement.addBatch();
                if ((row + 1) % batchSize == 0) {
                    statement.executeBatch();
                    statement.clearBatch();
                }
            }
            if (parameters.getAmountOfEntries() % batchSize != 0) statement.executeBatch();
        }
    }
}
