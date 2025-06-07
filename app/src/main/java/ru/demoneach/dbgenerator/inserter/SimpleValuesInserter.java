package ru.demoneach.dbgenerator.inserter;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.Table;
import ru.demoneach.dbgenerator.helper.TypeConverterHelper;

import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Slf4j
public class SimpleValuesInserter extends Inserter implements DataInserter {

    public SimpleValuesInserter(Map<String, Rule> fieldGenerationRules, Connection conn) {
        super(fieldGenerationRules, conn);
    }

    @Override
    public void generateAndInsert(Table sourceTable, Parameters parameters, Map<Field, List<Object>> fieldReferenceValueMap) throws SQLException, JsonProcessingException, URISyntaxException {
        this.getConn().setSchema(sourceTable.getSchema());
        List<Field> fields = this.getRuleEnforcer().filterIgnoredFields(sourceTable);

        String sqlQuery = this.generateInsertSqlTemplateString(sourceTable, fields);
        try (PreparedStatement preparedStatement = this.getConn().prepareStatement(sqlQuery)) {
            for (int i = 0; i < parameters.getAmountOfEntries(); i++) {
                prepareDataForStatement(sourceTable, preparedStatement, fields, fieldReferenceValueMap);
                preparedStatement.addBatch();

                if (i % 100_000 == 0 && i > 0) {
                    preparedStatement.executeBatch();
                    preparedStatement.clearBatch();
                }
            }

            preparedStatement.executeBatch();
        }
    }

    private void prepareDataForStatement(Table table, PreparedStatement preparedStatement, List<Field> fields, Map<Field, List<Object>> fieldReferenceValueMap) throws SQLException, JsonProcessingException {
        for (int i = 0; i < fields.size(); i++) {
            Integer queryIndex = i + 1;
            Object generatedObject;

            if (fieldReferenceValueMap.containsKey(fields.get(i))) {
                List<Object> fieldValues = fieldReferenceValueMap.get(fields.get(i));
                generatedObject = fieldValues.remove(fieldValues.size() - 1);
            } else {
                generatedObject = this.getDataGenerator().generateDataForField(table.toString(), fields.get(i));
            }

            TypeConverterHelper.setCorrectDbTypeOfObject(preparedStatement, queryIndex, fields.get(i), generatedObject, this.getConn());
        }
    }

    private String generateInsertSqlTemplateString(Table table, List<Field> fields) {
        String sqlTemplateString = "INSERT INTO \"%s\".%s".formatted(table.getSchema(), table.getTableName());

        StringBuilder sb = new StringBuilder(sqlTemplateString);
        sb.append(" (");

        for (Field field : fields) {
            if (this.getRuleEnforcer().checkIfFieldIgnored(table, field)) {
                continue;
            }
            sb.append(field.getName()).append(",");
        }

        sb.setLength(sb.length() - 1);
        sb.append(")");
        sb.append(" VALUES (");

        for (@SuppressWarnings("unused") Field field : fields) {
            if (this.getRuleEnforcer().checkIfFieldIgnored(table, field)) {
                continue;
            }

            sb.append("?,");
        }

        sb.setLength(sb.length() - 1);
        sb.append(")");

        return sb.toString();
    }
}
