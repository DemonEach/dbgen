package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.DatabaseLayout;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Table;
import ru.demoneach.dbgenerator.inserter.DataInserter;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PgSQLDataGeneratorForeignKeyTest {

    @Test
    void parentOutsideTablesToGenerateIsUsedAsReferenceButNotGenerated() throws Exception {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "parent", Map.of("id", "integer"));
        layout.addTableVertex("public", "child", Map.of("parent_id", "integer"));

        Table parent = findTable(layout, "parent");
        Table child = findTable(layout, "child");
        Field parentId = fieldNamed(parent, "id");
        Field childParentId = fieldNamed(child, "parent_id");

        layout.addTableEdge(parent, child, Map.of(parentId, childParentId));
        layout.setGenerationTargets(Set.of(child));

        List<String> generatedTables = new ArrayList<>();
        List<Object> valuesSeenByChild = new ArrayList<>();
        DataInserter inserter = (table, parameters, references) -> {
            generatedTables.add(table.getTableName());
            valuesSeenByChild.addAll(references.get(childParentId));
        };

        List<Integer> capturedLimits = new ArrayList<>();
        Connection conn = mockConnectionReturningRows(List.of(42, 43), capturedLimits);
        Parameters parameters = new Parameters();
        parameters.setAmountOfEntries(2);
        new PgSQLDataGenerator(conn, layout, inserter).generateDataForTables(parameters);

        assertEquals(List.of("child"), generatedTables, "parent outside tablesToGenerate must not be (re)generated");
        assertEquals(List.of(42, 43), valuesSeenByChild, "child must still receive real existing parent values");
        assertEquals(List.of(2), capturedLimits,
                "the parent query must be bounded to amountOfEntries instead of fetching the whole table");
    }

    private static Table findTable(DatabaseLayout layout, String name) {
        return layout.getLayoutGraph().vertexSet().stream()
                .filter(t -> t.getTableName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Field fieldNamed(Table table, String name) {
        return table.getFields().stream()
                .filter(f -> f.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private Connection mockConnectionReturningRows(List<Object> parentRows, List<Integer> capturedLimits) {
        Iterator<Object> rows = parentRows.iterator();
        Object[] currentRow = new Object[1];
        ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "next" -> {
                            if (!rows.hasNext()) yield false;
                            currentRow[0] = rows.next();
                            yield true;
                        }
                        case "getObject" -> currentRow[0];
                        case "findColumn" -> 1;
                        default -> throw new AssertionError("Unexpected ResultSet call: " + method.getName());
                    };
                });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setFetchSize", "close" -> null;
                    case "setInt" -> { capturedLimits.add((Integer) args[1]); yield null; }
                    case "executeQuery" -> resultSet;
                    default -> throw new AssertionError("Unexpected PreparedStatement call: " + method.getName());
                });
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "commit", "rollback", "close", "setSchema" -> null;
                    case "prepareStatement" -> statement;
                    default -> throw new AssertionError("Unexpected Connection call: " + method.getName());
                });
    }
}
