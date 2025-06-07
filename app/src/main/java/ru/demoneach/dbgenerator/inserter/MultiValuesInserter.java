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
import java.util.Objects;

@Slf4j
public class MultiValuesInserter extends Inserter implements DataInserter {
    public MultiValuesInserter(Map<String, Rule> fieldGenerationRules, Connection conn) {
        super(fieldGenerationRules, conn);
    }

    @Override
    public void generateAndInsert(Table sourceTable, Parameters parameters, Map<Field, List<Object>> fieldReferenceValueMap) throws SQLException, JsonProcessingException, URISyntaxException {
        List<Field> fields = this.getRuleEnforcer().filterIgnoredFields(sourceTable);
        Integer batch = parameters.getBatch();

        String sqlQuery = this.generateMultipleValuesInsertSqlTemplateString(sourceTable, fields, batch);
        try (PreparedStatement preparedStatement = this.getConn().prepareStatement(sqlQuery)) {
            for (int i = 0; i < parameters.getAmountOfEntries() / batch; i++) {
                prepareDataForStatementForMultipleValues(sourceTable, preparedStatement, fields, batch);
//                preparedStatement.addBatch();
                if (Objects.nonNull(fieldReferenceValueMap) && !fieldReferenceValueMap.isEmpty()) {
                    setReferencedFieldFromList(preparedStatement, fields, fieldReferenceValueMap, batch);
                }

                preparedStatement.execute();
//                if (i % parameters.getBatchSave() == 0 && i > 0) {
//                    preparedStatement.executeBatch();
//                    preparedStatement.clearBatch();
//                }
            }

            preparedStatement.execute();
        }
    }

    private void setReferencedFieldFromList(PreparedStatement preparedStatement,
                                            List<Field> fields,
                                            Map<Field, List<Object>> fieldReferenceValueMap,
                                            Integer batch) throws SQLException, JsonProcessingException {
        for (int i = 0; i < batch; i++) {
            for (Field field : fieldReferenceValueMap.keySet()) {
                Integer queryParamId = (i * fields.size()) + fields.indexOf(field) + 1;
                List<Object> fieldValues = fieldReferenceValueMap.get(field);
                Object queryParamValue = fieldValues.remove(fieldValues.size() - 1);
                TypeConverterHelper.setCorrectDbTypeOfObject(preparedStatement, queryParamId, field, queryParamValue, this.getConn());
            }
        }
    }

    private void prepareDataForStatementForMultipleValues(Table table, PreparedStatement preparedStatement, List<Field> fields, Integer batch) throws SQLException, JsonProcessingException {
        for (int i = 0; i < batch; i++) {
            for (int j = 0; j < fields.size(); j++) {
                Object generatedObject = this.getDataGenerator().generateDataForField(table.toString(), fields.get(j));
                Integer queryIndex = (i * fields.size()) + j + 1;

                TypeConverterHelper.setCorrectDbTypeOfObject(preparedStatement, queryIndex, fields.get(j), generatedObject, this.getConn());
            }
        }
    }

    private String generateMultipleValuesInsertSqlTemplateString(Table table, List<Field> fields, Integer valuesAmount) {
        String sqlTemplateString = "INSERT INTO \"%s\".%s".formatted(table.getSchema(), table.getTableName());

        StringBuilder sb = new StringBuilder(sqlTemplateString);
        sb.append(" (");

        for (int i = 0; i < fields.size(); i++) {
            if (this.getRuleEnforcer().checkIfFieldIgnored(table, fields.get(i))) {
                continue;
            }
            sb.append(fields.get(i).getName()).append(",");
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
