package ru.demoneach.dbgenerator.entity;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseLayoutTest {

    @Test
    void severalForeignKeysBetweenSamePairOfTablesAreSeparateEdges() {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "parent", Map.of("id", "integer"));
        layout.addTableVertex("public", "child", Map.of("created_by", "integer", "updated_by", "integer"));

        Table parent = findTable(layout, "parent");
        Table child = findTable(layout, "child");
        Field id = fieldNamed(parent, "id");

        layout.addTableEdge(parent, child, Map.of(id, fieldNamed(child, "created_by")), "child_created_by_fkey");
        layout.addTableEdge(parent, child, Map.of(id, fieldNamed(child, "updated_by")), "child_updated_by_fkey");

        var edges = layout.getLayoutGraph().getAllEdges(parent, child);
        assertEquals(2, edges.size());
        assertEquals(Set.of("child_created_by_fkey", "child_updated_by_fkey"),
                edges.stream().map(ReferenceEdge::getConstraintName).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void sameLinkFoundTwiceKeepsOneEdgeAndTheConstraintName() {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "parent", Map.of("id", "integer"));
        layout.addTableVertex("public", "child", Map.of("parent_id", "integer"));
        Table parent = findTable(layout, "parent");
        Table child = findTable(layout, "child");
        Map<Field, Field> link = Map.of(fieldNamed(parent, "id"), fieldNamed(child, "parent_id"));

        layout.addTableEdge(parent, child, link, null, true); // customTableLinks entry, no constraint
        layout.addTableEdge(parent, child, link, "child_parent_fkey"); // the real foreign key
        layout.addTableEdge(parent, child, link); // a repeat must not drop the name again

        var edges = layout.getLayoutGraph().getAllEdges(parent, child);
        assertEquals(1, edges.size());
        ReferenceEdge merged = edges.iterator().next();
        assertEquals("child_parent_fkey", merged.getConstraintName());
        assertTrue(merged.isCustomLink(), "the edge must remember it was also declared in customTableLinks");
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
