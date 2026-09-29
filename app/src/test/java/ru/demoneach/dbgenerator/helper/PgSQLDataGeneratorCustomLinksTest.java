package ru.demoneach.dbgenerator.helper;

import org.jgrapht.Graph;
import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.DatabaseLayout;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.ReferenceEdge;
import ru.demoneach.dbgenerator.entity.Table;
import ru.demoneach.dbgenerator.exception.DataGenerationException;
import ru.demoneach.dbgenerator.inserter.DataInserter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PgSQLDataGeneratorCustomLinksTest {

    @Test
    void customLinkEdgeRunsFromParentToChildSoParentIsGeneratedFirst() {
        DatabaseLayout layout = new DatabaseLayout();
        // vertex insertion order deliberately does not match parent/child order
        layout.addTableVertex("public", "child", Map.of("parent_id", "integer"));
        layout.addTableVertex("public", "parent", Map.of("id", "integer"));

        generator(layout).addCustomLinksBetweenTables(Map.of("public.child.parent_id", "public.parent.id"));

        Table child = findTable(layout, "child");
        Table parent = findTable(layout, "parent");
        Graph<Table, ReferenceEdge> graph = layout.getLayoutGraph();

        assertTrue(graph.containsEdge(parent, child), "edge must run parent -> child, not child -> parent");
        assertFalse(graph.containsEdge(child, parent));

        Field parentId = fieldNamed(parent, "id");
        Field childParentId = fieldNamed(child, "parent_id");
        assertEquals(Map.of(parentId, childParentId), graph.getEdge(parent, child).getReferencedFields());
    }

    @Test
    void unknownSourceTableIsReportedClearly() {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "parent", Map.of("id", "integer"));

        DataGenerationException failure = assertThrows(DataGenerationException.class, () ->
                generator(layout).addCustomLinksBetweenTables(Map.of("public.missing.x", "public.parent.id")));
        assertTrue(failure.getMessage().contains("public.missing"));
    }

    @Test
    void unknownReferencedTableIsReportedClearly() {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "child", Map.of("parent_id", "integer"));

        DataGenerationException failure = assertThrows(DataGenerationException.class, () ->
                generator(layout).addCustomLinksBetweenTables(Map.of("public.child.parent_id", "public.missing.id")));
        assertTrue(failure.getMessage().contains("public.missing"));
    }

    private PgSQLDataGenerator generator(DatabaseLayout layout) {
        return new PgSQLDataGenerator(null, layout, (DataInserter) (table, parameters, references) -> {});
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
}
