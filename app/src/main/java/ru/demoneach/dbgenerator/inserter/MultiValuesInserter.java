package ru.demoneach.dbgenerator.inserter;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.Table;
import ru.demoneach.dbgenerator.helper.TypeConverterHelper;

import java.net.URISyntaxException;
import ru.demoneach.dbgenerator.helper.SqlIdentifiers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Slf4j
public class MultiValuesInserter extends Inserter implements DataInserter {
    public MultiValuesInserter(Map<String, Rule> fieldGenerationRules, Connection conn) {
        super(fieldGenerationRules, conn);
    }

    @Override
    public void generateAndInsert(Table sourceTable, Parameters parameters, Map<Field, List<Object>> fieldReferenceValueMap) throws SQLException, JsonProcessingException, URISyntaxException {
        List<Field> fields = this.getRuleEnforcer().filterIgnoredFields(sourceTable);

        if (fields.isEmpty()) {
            insertDefaultRows(sourceTable, parameters);
            return;
        }

        // PostgreSQL JDBC extended protocol allows at most 65535 bind parameters.
        int batch = Math.min(parameters.getBatch(), 65535 / fields.size());
        int amountOfEntries = parameters.getAmountOfEntries();

        int fullBatches = amountOfEntries / batch;
        int remainder = amountOfEntries % batch;

        if (fullBatches > 0) {
            insertInBatchesOf(sourceTable, fields, fieldReferenceValueMap, batch, fullBatches);
        }

        // amountOfEntries is not necessarily a multiple of batch, the rest goes in a smaller statement
        if (remainder > 0) {
            insertInBatchesOf(sourceTable, fields, fieldReferenceValueMap, remainder, 1);
        }
    }

    private void insertInBatchesOf(Table sourceTable,
                                   List<Field> fields,
                                   Map<Field, List<Object>> fieldReferenceValueMap,
                                   int batch,
                                   int timesToExecute) throws SQLException, JsonProcessingException {
        String sqlQuery = this.generateMultipleValuesInsertSqlTemplateString(sourceTable, fields, batch);

        try (PreparedStatement preparedStatement = this.getConn().prepareStatement(sqlQuery)) {
            for (int i = 0; i < timesToExecute; i++) {
                prepareDataForStatementForMultipleValues(sourceTable, preparedStatement, fields, fieldReferenceValueMap, batch);

                preparedStatement.execute();
            }
        }
    }

    private void prepareDataForStatementForMultipleValues(Table table, PreparedStatement preparedStatement,
                                                         List<Field> fields, Map<Field, List<Object>> references,
                                                         int batch) throws SQLException, JsonProcessingException {
        String tableName = table.toString();
        for (int i = 0; i < batch; i++) {
            for (int j = 0; j < fields.size(); j++) {
                Field field = fields.get(j);
                Object value = references != null && references.containsKey(field)
                        ? nextReferencedValue(table, field, references.get(field))
                        : this.getDataGenerator().generateDataForField(tableName, field);
                TypeConverterHelper.setCorrectDbTypeOfObject(preparedStatement,
                        i * fields.size() + j + 1, field, value, this.getConn());
            }
        }
    }

    private String generateMultipleValuesInsertSqlTemplateString(Table table, List<Field> fields, Integer valuesAmount) {
        String sqlTemplateString = "INSERT INTO " + SqlIdentifiers.qualified(table.getSchema(), table.getTableName());

        StringBuilder sb = new StringBuilder(sqlTemplateString);
        sb.append(" (");

        for (int i = 0; i < fields.size(); i++) {
            if (this.getRuleEnforcer().checkIfFieldIgnored(table, fields.get(i))) {
                continue;
            }
            sb.append(SqlIdentifiers.quote(fields.get(i).getName())).append(",");
        }

        sb.setLength(sb.length() - 1);
        sb.append(")");
        sb.append(" VALUES ");

        StringBuilder templateValues = new StringBuilder();
        templateValues.append("(");

        for (int i = 0; i < fields.size(); i++) {
            if (this.getRuleEnforcer().checkIfFieldIgnored(table, fields.get(i))) {
                continue;
            }
            templateValues.append("?,");
        }

        templateValues.setLength(templateValues.length() - 1);
        templateValues.append(")");

        for (int i = 0; i < valuesAmount; i++) {
            sb.append(templateValues);
            sb.append(",");
        }

        sb.setLength(sb.length() - 1);

        return sb.toString();
    }
}
