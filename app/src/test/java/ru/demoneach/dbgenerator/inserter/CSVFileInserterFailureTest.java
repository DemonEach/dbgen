package ru.demoneach.dbgenerator.inserter;

import org.junit.jupiter.api.Test;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.QueryExecutor;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Table;

import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CSVFileInserterFailureTest {
    @Test
    void doesNotStartCopyWhenCsvCannotBeWritten() throws Exception {
        QueryExecutor executor = (QueryExecutor) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{QueryExecutor.class}, (proxy, method, args) -> {
                    throw new AssertionError("Must not start COPY: " + method.getName());
                });
        BaseConnection connection = (BaseConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{BaseConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEncoding" -> null;
                    case "getQueryExecutor" -> executor;
                    default -> throw new AssertionError("Must not access DB: " + method.getName());
                });
        Table table = new Table();
        table.setSchema("public");
        // The nonexistent parent directory makes file creation fail on all platforms.
        table.setTableName(UUID.randomUUID() + "/missing/table");
        table.setFieldsFromMap(Map.of("value", "integer"));
        CSVFileInserter inserter = new CSVFileInserter(Map.of(), connection);
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> inserter.generateAndInsert(table, new Parameters(), Map.of()));
        assertTrue(failure.getMessage().startsWith("Cannot write CSV file:"));
        assertNotNull(failure.getCause());
    }
}
