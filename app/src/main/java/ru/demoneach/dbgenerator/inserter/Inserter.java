package ru.demoneach.dbgenerator.inserter;

import lombok.Getter;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.Table;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.exception.DataGenerationException;
import ru.demoneach.dbgenerator.generator.DataGenerator;
import ru.demoneach.dbgenerator.helper.RuleEnforcer;

import ru.demoneach.dbgenerator.helper.SqlIdentifiers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
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
        String sql = "INSERT INTO " + SqlIdentifiers.qualified(table.getSchema(), table.getTableName()) + " DEFAULT VALUES";
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

    // Referenced values are read once from the parent table and consumed one per row (see
    // fieldReferenceValueMap). An empty list means the parent has fewer existing/generated rows
    // than are being requested here (including a parent with no rows at all); fail with the
    // table/field instead of an IndexOutOfBoundsException on remove(-1).
    protected Object nextReferencedValue(Table table, Field field, List<Object> values) {
        if (values.isEmpty()) {
            throw new DataGenerationException(
                    "No more values to reference for %s.%s: the referenced table has fewer rows than requested"
                            .formatted(table, field.getName()));
        }
        return values.remove(values.size() - 1);
    }
}
