package ru.demoneach.dbgenerator.inserter;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import ru.demoneach.dbgenerator.App;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.Table;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Slf4j
public class CSVFileInserter extends Inserter implements DataInserter {

    // COPY <table> FROM <file>, docs: https://www.postgresql.org/docs/current/sql-copy.html
    private static final String SQL_COPY_CMD_TEMPLATE = "COPY %s FROM %s WITH (FORMAT CSV)";

    public CSVFileInserter(Map<String, Rule> fieldGenerationRules, Connection conn) {
        super(fieldGenerationRules, conn);
    }

    @Override
    public void generateAndInsert(Table sourceTable, Parameters parameters, Map<Field, List<Object>> fieldReferenceValueMap) throws SQLException, JsonProcessingException, URISyntaxException {
        List<Field> fields = this.getRuleEnforcer().filterIgnoredFields(sourceTable);
        String csvHeader = this.generateCsvHeader(sourceTable, fields);
        URL url = App.class.getProtectionDomain().getCodeSource().getLocation();
        File jarFile = new File(url.toURI());
        String directory = jarFile.isFile() ? jarFile.getParentFile().getAbsolutePath() : jarFile.getAbsolutePath();

        String fileName = sourceTable + ".csv";
        // TODO: maybe create temp file and not delete manually?
        File csvFile = new File(directory, fileName);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
            writer.write(csvHeader);
            writer.newLine();

            for (int i = 0; i < parameters.getAmountOfEntries(); i++) {
                writer.write(this.prepareDataForCsvFile(sourceTable, fields, fieldReferenceValueMap));
                writer.newLine();

                if (i % 1000 == 0) {
                    log.debug("Generated {} of {} entries for table: {}", i, parameters.getAmountOfEntries(), sourceTable);
                }
            }
        } catch (IOException e) {
            log.error("Cannot create file", e);
        }

        log.debug("CSV File for table {} successfully created at: {}", sourceTable.getTableName(), csvFile.getAbsolutePath());

        String sqlCopyStatement = SQL_COPY_CMD_TEMPLATE.formatted(sourceTable.getTableName(), csvFile.getAbsolutePath());
        try(PreparedStatement copyStatement = this.getConn().prepareStatement(sqlCopyStatement)) {
            copyStatement.execute();
        }
    }

    private String prepareDataForCsvFile(Table table, List<Field> fields, Map<Field, List<Object>> fieldReferenceValueMap) throws JsonProcessingException {
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < fields.size(); i++) {
            if (fieldReferenceValueMap.containsKey(fields.get(i))) {
                List<Object> fieldValues = fieldReferenceValueMap.get(fields.get(i));
                Object queryParamValue = fieldValues.remove(fieldValues.size() - 1);
                stringBuilder.append(queryParamValue);
            } else {
                Object generatedObject = this.getDataGenerator().generateDataForField(table.toString(), fields.get(i));
                stringBuilder.append(generatedObject);
            }

            stringBuilder.append(",");
        }

        stringBuilder.setLength(stringBuilder.length() - 1);
        return stringBuilder.toString();
    }

    public String generateCsvHeader(Table table, List<Field> fields) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < fields.size(); i++) {
            if (this.getRuleEnforcer().checkIfFieldIgnored(table, fields.get(i))) {
                continue;
            }

            sb.append(fields.get(i).getName()).append(",");
        }

        sb.setLength(sb.length() - 1);

        return sb.toString();
    }
}
