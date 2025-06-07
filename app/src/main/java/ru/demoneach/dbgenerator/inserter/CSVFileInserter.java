package ru.demoneach.dbgenerator.inserter;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import ru.demoneach.dbgenerator.App;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.Table;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Slf4j
public class CSVFileInserter extends Inserter implements DataInserter {

    private static final Long LOGGING_STEP = 10_000L;
    // COPY <table> FROM <file>, docs: https://www.postgresql.org/docs/current/sql-copy.html
    private static final String SQL_COPY_CMD_TEMPLATE = "COPY %s FROM STDIN CSV HEADER DELIMITER ',';";
    private CopyManager copyManager;

    public CSVFileInserter(Map<String, Rule> fieldGenerationRules, Connection conn) throws SQLException {
        super(fieldGenerationRules, conn);
        this.copyManager = new CopyManager((BaseConnection) conn);
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

                if (i % LOGGING_STEP == 0) {
                    log.info("Generated {} of {} entries for table: {}", i, parameters.getAmountOfEntries(), sourceTable);
                }
            }
        } catch (IOException e) {
            log.error("Cannot create file", e);
        }

        log.info("CSV File for table {} successfully created at: {}", sourceTable.getTableName(), csvFile.getAbsolutePath());
        String sqlCopyStatement = SQL_COPY_CMD_TEMPLATE.formatted(sourceTable.getTableName());

        try {
            long rowsUpdated = copyManager.copyIn(sqlCopyStatement, new FileInputStream(csvFile));
            log.debug("Inserted/updated {} from CSV file: {}", rowsUpdated, csvFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String prepareDataForCsvFile(Table table, List<Field> fields, Map<Field, List<Object>> fieldReferenceValueMap) throws JsonProcessingException {
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < fields.size(); i++) {
            if (fieldReferenceValueMap.containsKey(fields.get(i))) {
                List<Object> fieldValues = fieldReferenceValueMap.get(fields.get(i));
                Object queryParamValue = fieldValues.remove(fieldValues.size() - 1);
                stringBuilder.append("\"").append(queryParamValue);
            } else {
                Object generatedObject = this.getDataGenerator().generateDataForField(table.toString(), fields.get(i));

                if (generatedObject.getClass().isArray()) {
                    generatedObject = convertArrayToInsertableString((Object[]) generatedObject);
                }

                stringBuilder.append("\"").append(generatedObject);
            }

            stringBuilder.append("\"").append(",");
        }

        stringBuilder.setLength(stringBuilder.length() - 1);
        return stringBuilder.toString();
    }

    private String convertArrayToInsertableString(Object[] array) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("{");

        for (int i = 0; i < array.length; i++) {
            stringBuilder.append((String) array[i]).append(",");
        }

        stringBuilder.setLength(stringBuilder.length() - 1);
        stringBuilder.append("}");
        return stringBuilder.toString();
    }

    public String generateCsvHeader(Table table, List<Field> fields) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < fields.size(); i++) {
            if (this.getRuleEnforcer().checkIfFieldIgnored(table, fields.get(i))) {
                continue;
            }

            sb.append("\"").append(fields.get(i).getName()).append("\"").append(",");
        }

        sb.setLength(sb.length() - 1);

        return sb.toString();
    }
}
