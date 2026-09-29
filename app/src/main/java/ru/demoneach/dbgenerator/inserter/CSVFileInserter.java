package ru.demoneach.dbgenerator.inserter;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import ru.demoneach.dbgenerator.App;
import ru.demoneach.dbgenerator.entity.*;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class CSVFileInserter extends Inserter implements DataInserter {

    private static final Long LOGGING_STEP = 100_000L;
    // COPY <table> (<columns>) FROM <file>, docs: https://www.postgresql.org/docs/current/sql-copy.html
    // the column list is required: ignored fields are not written to the csv, so the table column
    // order alone does not describe the file
    private static final String SQL_COPY_CMD_TEMPLATE =
            "COPY \"%s\".\"%s\" (%s) FROM STDIN CSV HEADER DELIMITER ',';";
    private CopyManager copyManager;

    public CSVFileInserter(Map<String, Rule> fieldGenerationRules, Connection conn) throws SQLException {
        super(fieldGenerationRules, conn);
        this.copyManager = new CopyManager((BaseConnection) conn);
    }

    @Override
    public void generateAndInsert(Table sourceTable, Parameters parameters, Map<Field, List<Object>> fieldReferenceValueMap) throws SQLException, JsonProcessingException, URISyntaxException {
        List<Field> fields = this.getRuleEnforcer().filterIgnoredFields(sourceTable);

        if (fields.isEmpty()) {
            log.warn("Table {} has no fields to generate, skipping it", sourceTable);
            return;
        }

        String csvHeader = this.generateColumnList(fields);
        URL url = App.class.getProtectionDomain().getCodeSource().getLocation();
        File jarFile = new File(url.toURI());
        String directory = jarFile.isFile() ? jarFile.getParentFile().getAbsolutePath() : jarFile.getAbsolutePath();

        String fileName = sourceTable + ".csv";
        // TODO: maybe create temp file and not delete manually?
        File csvFile = new File(directory, fileName);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
            writer.write(csvHeader);
            writer.newLine();

            // TODO: maybe add option for multi threading to speed up generation
            for (long i = 0; i < parameters.getAmountOfEntries(); i++) {
                writer.write(this.prepareDataForCsvFile(sourceTable, fields, fieldReferenceValueMap, i + 1));
                writer.newLine();

                if (i % LOGGING_STEP == 0) {
                    log.info("Generated {} of {} entries for table: {}", i, parameters.getAmountOfEntries(), sourceTable);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write CSV file: " + csvFile.getAbsolutePath(), e);
        }

        log.info("CSV File for table {} successfully created at: {}", sourceTable.getTableName(), csvFile.getAbsolutePath());
        String sqlCopyStatement = SQL_COPY_CMD_TEMPLATE.formatted(
                sourceTable.getSchema(), sourceTable.getTableName(), csvHeader);

        try (InputStream input = new FileInputStream(csvFile)) {
            long rowsUpdated = copyManager.copyIn(sqlCopyStatement, input);
            log.debug("Inserted/updated {} from CSV file: {}", rowsUpdated, csvFile.getAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load CSV file: " + csvFile.getAbsolutePath(), e);
        }
    }

    private String prepareDataForCsvFile(Table table, List<Field> fields, Map<Field, List<Object>> fieldReferenceValueMap, Long positiveSeq) throws JsonProcessingException {
        StringBuilder stringBuilder = new StringBuilder();

        for (int i = 0; i < fields.size(); i++) {
            if (fieldReferenceValueMap.containsKey(fields.get(i))) {
                List<Object> fieldValues = fieldReferenceValueMap.get(fields.get(i));
                Object queryParamValue = fieldValues.remove(fieldValues.size() - 1);
                stringBuilder.append("\"").append(queryParamValue);
            } else {
                Object generatedObject;

                if (fields.get(i).getDbType().equals(SequentialPositive.class)) {
                    generatedObject = positiveSeq;
                } else {
                    generatedObject = this.getDataGenerator().generateDataForField(table.toString(), fields.get(i));
                }

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

    /**
     * Builds the quoted, comma separated column list. It is used both as the csv header and as the
     * column list of the COPY statement, so the two can not drift apart.
     */
    private String generateColumnList(List<Field> fields) {
        return fields.stream()
                .map(field -> "\"%s\"".formatted(field.getName()))
                .collect(Collectors.joining(","));
    }
}
