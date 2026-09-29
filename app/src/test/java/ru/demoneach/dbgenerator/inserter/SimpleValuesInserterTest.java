package ru.demoneach.dbgenerator.inserter;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Table;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleValuesInserterTest {
    @Test
    void flushesConfiguredBatchesAndOnlyNonEmptyRemainder() throws Exception {
        assertBatches(0, 3, List.of());
        assertBatches(2, 3, List.of(2));
        assertBatches(6, 3, List.of(3, 3));
        assertBatches(7, 3, List.of(3, 3, 1));
        assertBatches(3, 1, List.of(1, 1, 1));
    }

    private void assertBatches(int rows, int batchSize, List<Integer> expected) throws Exception {
        List<Integer> batches = new ArrayList<>();
        int[] pending = {0};
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addBatch" -> pending[0]++;
                        case "executeBatch" -> {
                            batches.add(pending[0]);
                            int[] counts = new int[pending[0]];
                            pending[0] = 0;
                            return counts;
                        }
                        case "clearBatch" -> pending[0] = 0;
                        case "setInt", "close" -> { }
                        default -> throw new AssertionError("Unexpected JDBC call: " + method.getName());
                    }
                    return null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> statement;
                    case "setSchema" -> null;
                    default -> throw new AssertionError("Unexpected JDBC call: " + method.getName());
                });

        Table table = new Table();
        table.setSchema("public");
        table.setTableName("batch_test");
        table.setFieldsFromMap(Map.of("value", "integer"));
        Parameters parameters = new Parameters();
        parameters.setAmountOfEntries(rows);
        parameters.setBatchSave(batchSize);

        new SimpleValuesInserter(Map.of(), connection).generateAndInsert(table, parameters, Map.of());
        assertEquals(expected, batches);
        assertEquals(0, pending[0]);
    }
}
