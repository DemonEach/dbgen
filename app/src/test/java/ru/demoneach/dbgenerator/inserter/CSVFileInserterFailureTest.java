package ru.demoneach.dbgenerator.inserter;

import org.junit.jupiter.api.Test;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.QueryExecutor;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Table;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.RuleType;

import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CSVFileInserterFailureTest {
    @Test
    void doesNotStartCopyWhenCsvCannotBeWritten() throws Exception {
        BaseConnection connection = connection();
        Table table = new Table();
        table.setSchema("public");
        // Special table names must never be used as filesystem paths.
        table.setTableName(UUID.randomUUID() + "/missing/table");
        table.setFieldsFromMap(Map.of("value", "integer"));
        CSVFileInserter inserter = new CSVFileInserter(Map.of(), connection) {
            @Override java.io.File createCsvFile() throws java.io.IOException {
                throw new java.io.IOException("disk unavailable");
            }
        };
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> inserter.generateAndInsert(table, new Parameters(), Map.of()));
        assertTrue(failure.getMessage().startsWith("Cannot write CSV file:"));
        assertNotNull(failure.getCause());
    }

    @Test
    void serializesGeneratedAndReferencedNullsWithoutLosingColumns() throws Exception {
        Table table = new Table();
        table.setSchema("public");
        table.setTableName("data");
        Field first = new Field("first", "integer");
        Field second = new Field("second", "text");
        Field third = new Field("third", "text");
        table.setFields(List.of(first, second, third));
        CSVFileInserter inserter = new CSVFileInserter(Map.of(
                "public.data.second", new Rule(RuleType.CONST, List.of("")),
                "public.data.third", new Rule(RuleType.CONST, Arrays.asList((String) null))), connection());
        assertEquals(",\"\",", inserter.prepareDataForCsvFile(table, table.getFields(),
                Map.of(first, new ArrayList<>(Arrays.asList((Object) null))), 1L));
    }

    private BaseConnection connection() {
        QueryExecutor executor = (QueryExecutor) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{QueryExecutor.class}, (proxy, method, args) -> {
                    throw new AssertionError("Must not start COPY: " + method.getName());
                });
        return (BaseConnection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{BaseConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEncoding" -> null;
                    case "getQueryExecutor" -> executor;
                    default -> throw new AssertionError("Must not access DB: " + method.getName());
                });
    }
}
