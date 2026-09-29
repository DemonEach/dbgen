package ru.demoneach.dbgenerator.inserter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.copy.CopyIn;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.QueryExecutor;
import ru.demoneach.dbgenerator.entity.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuotedInsertTest {
    @Test
    void allStrategiesQuoteEveryIdentifierAndCopyCleansItsFile(@TempDir Path dir) throws Exception {
        Table table = new Table();
        table.setSchema("Schema.Space");
        table.setTableName("Table\"Name");
        table.setFields(List.of(new Field("Value\".Name", "integer")));
        for (Strategy strategy : Strategy.values()) {
            List<String> queries = new ArrayList<>();
            PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> switch (m.getName()) {
                        case "setInt", "addBatch", "clearBatch", "close" -> null;
                        case "executeBatch" -> new int[]{1};
                        case "execute" -> false;
                        default -> throw new AssertionError(m.getName());
                    });
            CopyIn copy = (CopyIn) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CopyIn.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "writeToCopy" -> null;
                        case "endCopy" -> 1L;
                        case "isActive" -> false;
                        default -> throw new AssertionError(m.getName());
                    });
            QueryExecutor executor = (QueryExecutor) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{QueryExecutor.class}, (p, m, a) -> {
                        assertEquals("startCopy", m.getName());
                        queries.add((String) a[0]);
                        return copy;
                    });
            BaseConnection connection = (BaseConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{BaseConnection.class}, (p, m, a) -> switch (m.getName()) {
                        case "setSchema", "getEncoding" -> null;
                        case "getQueryExecutor" -> executor;
                        case "getAutoCommit" -> false;
                        case "prepareStatement" -> { queries.add((String) a[0]); yield statement; }
                        default -> throw new AssertionError(m.getName());
                    });
            Path csv = dir.resolve("data.csv");
            DataInserter inserter = switch (strategy) {
                case DEFAULT -> new SimpleValuesInserter(Map.of(), connection);
                case MULTI -> new MultiValuesInserter(Map.of(), connection);
                case FILE -> new CSVFileInserter(Map.of(), connection) {
                    @Override java.io.File createCsvFile() { return csv.toFile(); }
                };
            };
            Parameters parameters = new Parameters();
            parameters.setAmountOfEntries(1);
            inserter.generateAndInsert(table, parameters, Map.of());
            String target = "\"Schema.Space\".\"Table\"\"Name\" (\"Value\"\".Name\")";
            assertEquals(List.of(strategy == Strategy.FILE
                    ? "COPY " + target + " FROM STDIN WITH (FORMAT CSV, HEADER, ENCODING 'UTF8');"
                    : "INSERT INTO " + target + " VALUES (?)"), queries);
            assertFalse(Files.exists(csv));
        }
    }
}
