package ru.demoneach.dbgenerator.inserter;

import org.junit.jupiter.api.Test;
import org.postgresql.core.BaseConnection;
import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.helper.RuleEnforcer;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultRowsTest {
    @Test
    void databaseGeneratedColumnsAreOmittedAndOrdinaryDefaultsCanBeOverridden() {
        Table table = table();
        Field id = table.getFields().getFirst();
        Field value = new Field("value", "integer");
        value.setHasDefault(true);
        table.setFields(List.of(id, value));
        assertTrue(new RuleEnforcer(Map.of()).filterIgnoredFields(table).isEmpty());
        Rule constant = new Rule(RuleType.CONST, List.of("42"));
        assertEquals(List.of(value), new RuleEnforcer(Map.of(
                table + ".id", constant, table + ".value", constant)).filterIgnoredFields(table));
        assertTrue(new RuleEnforcer(Map.of(table + ".value", new Rule(RuleType.IGNORE, null)))
                .filterIgnoredFields(table).isEmpty());
    }

    @Test
    void allStrategiesInsertExactDefaultRowCountIncludingRemainders() throws Exception {
        for (Strategy strategy : Strategy.values()) {
            for (int rows : new int[]{0, 1, 6, 7}) {
                List<Integer> batches = new ArrayList<>();
                Parameters parameters = new Parameters();
                parameters.setAmountOfEntries(rows);
                parameters.setBatchSave(3);
                BaseConnection connection = connection(batches, null);
                DataInserter inserter = switch (strategy) {
                    case FILE -> new CSVFileInserter(Map.of(), connection);
                    case MULTI -> new MultiValuesInserter(Map.of(), connection);
                    case DEFAULT -> new SimpleValuesInserter(Map.of(), connection);
                };
                inserter.generateAndInsert(table(), parameters, Map.of());
                assertEquals(rows, batches.stream().mapToInt(Integer::intValue).sum());
                assertTrue(batches.stream().allMatch(size -> size > 0 && size <= 3));
            }
        }
    }

    @Test
    void defaultInsertFailureIsNotSwallowed() {
        SQLException failure = new SQLException("NOT NULL without default");
        assertSame(failure, assertThrows(SQLException.class, () ->
                new MultiValuesInserter(Map.of(), connection(new ArrayList<>(), failure))
                        .generateAndInsert(table(), new Parameters(), Map.of())));
    }

    private Table table() {
        Table table = new Table();
        table.setSchema("my-schema");
        table.setTableName("My\"Table");
        Field id = new Field("id", "integer");
        id.setDatabaseGenerated(true);
        table.setFields(List.of(id));
        return table;
    }

    private BaseConnection connection(List<Integer> batches, SQLException failure) {
        int[] pending = {0};
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addBatch" -> pending[0]++;
                        case "executeBatch" -> {
                            if (failure != null) throw failure;
                            batches.add(pending[0]);
                            int[] counts = new int[pending[0]];
                            pending[0] = 0;
                            return counts;
                        }
                        case "clearBatch" -> pending[0] = 0;
                        case "close" -> { }
                        default -> fail("Unexpected statement call: " + method.getName());
                    }
                    return null;
                });
        return (BaseConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{BaseConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        assertEquals("INSERT INTO \"my-schema\".\"My\"\"Table\" DEFAULT VALUES", args[0]);
                        yield statement;
                    }
                    case "setSchema", "getEncoding", "getQueryExecutor" -> null;
                    default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                });
    }
}
