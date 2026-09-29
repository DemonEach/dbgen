package ru.demoneach.dbgenerator.entity;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.exception.DataGenerationException;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseLayoutTest {

    @Test
    void secondForeignKeyBetweenSamePairOfTablesIsRejected() {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "parent", Map.of("id", "integer", "other_id", "integer"));
        layout.addTableVertex("public", "child", Map.of("parent_id", "integer", "other_parent_id", "integer"));

        Table parent = findTable(layout, "parent");
        Table child = findTable(layout, "child");
        Field id = fieldNamed(parent, "id");
        Field otherId = fieldNamed(parent, "other_id");
        Field parentId = fieldNamed(child, "parent_id");
        Field otherParentId = fieldNamed(child, "other_parent_id");

        layout.addTableEdge(parent, child, Map.of(id, parentId));

        // the underlying graph does not support parallel edges: a second, distinct FK between the
        // same pair of tables must fail loudly instead of being silently dropped
        assertThrows(DataGenerationException.class,
                () -> layout.addTableEdge(parent, child, Map.of(otherId, otherParentId)));
    }

    @Test
    void selfReferencingForeignKeyIsAllowedOnce() {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "tree", Map.of("id", "integer", "parent_id", "integer"));
        Table tree = findTable(layout, "tree");
        Field id = fieldNamed(tree, "id");
        Field parentId = fieldNamed(tree, "parent_id");

        assertDoesNotThrow(() -> layout.addTableEdge(tree, tree, Map.of(id, parentId)));
    }

    @Test
    void everyTableIsAGenerationTargetByDefault() {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "any_table", Map.of("id", "integer"));
        assertTrue(layout.isGenerationTarget(findTable(layout, "any_table")));
    }

    @Test
    void onlyExplicitGenerationTargetsAreMarkedAsSuch() {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "parent", Map.of("id", "integer"));
        layout.addTableVertex("public", "child", Map.of("parent_id", "integer"));
        Table parent = findTable(layout, "parent");
        Table child = findTable(layout, "child");

        layout.setGenerationTargets(Set.of(child));

        assertTrue(layout.isGenerationTarget(child));
        assertFalse(layout.isGenerationTarget(parent));
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
