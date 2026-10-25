package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.DatabaseLayout;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Table;
import ru.demoneach.dbgenerator.inserter.DataInserter;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
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

        List<Integer> requestedRows = new ArrayList<>();
        List<List<Field>> requestedColumns = new ArrayList<>();
        ParentRowSource source = (parentTable, columns, wanted) -> {
            assertEquals("parent", parentTable.getTableName());
            requestedRows.add(wanted);
            requestedColumns.add(columns);
            return new ArrayList<>(List.of(new Object[]{42}, new Object[]{43}));
        };
        Parameters parameters = new Parameters();
        parameters.setAmountOfEntries(2);
        new PgSQLDataGenerator(mockConnection(), layout, inserter, source).generateDataForTables(parameters);

        assertEquals(List.of("child"), generatedTables, "parent outside tablesToGenerate must not be (re)generated");
        assertEquals(2, valuesSeenByChild.size());
        assertEquals(Set.of(42, 43), Set.copyOf(valuesSeenByChild), "child must still receive real existing parent values, each used once by default");
        assertEquals(List.of(2), requestedRows,
                "by default only as many parent rows as child rows are requested, not the whole table");
        assertEquals(List.of(List.of(parentId)), requestedColumns);
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

    private Connection mockConnection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "setAutoCommit", "commit", "rollback", "close", "setSchema" -> null;
                    default -> throw new AssertionError("Unexpected Connection call: " + method.getName());
                });
    }
}
