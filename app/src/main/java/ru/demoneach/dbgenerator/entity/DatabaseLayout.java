package ru.demoneach.dbgenerator.entity;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.util.mxCellRenderer;
import org.jgrapht.Graph;
import org.jgrapht.ext.JGraphXAdapter;
import org.jgrapht.graph.DirectedPseudograph;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class DatabaseLayout {

    Graph<Table, ReferenceEdge> layoutGraph;
    // null means "generate every table in the graph" (no tablesToGenerate filter was applied)
    private Set<Table> generationTargets;

    public DatabaseLayout() {
        this.layoutGraph = new DirectedPseudograph<>(ReferenceEdge.class);
    }

    public void addTableVertex(String schema, String tableName, Map<String, String> tableFields) {
        Table table = new Table();
        table.setSchema(schema);
        table.setTableName(tableName);
        table.setFieldsFromMap(tableFields);

        layoutGraph.addVertex(table);
    }

    public void addTableEdge(Table referencedTable, Table currentTable, Map<Field, Field> referenceFieldMap) {
        addTableEdge(referencedTable, currentTable, referenceFieldMap, null);
    }

    public void addTableEdge(Table referencedTable, Table currentTable, Map<Field, Field> referenceFieldMap, String constraintName) {
        // The same link can be found twice, e.g. a customTableLinks entry repeating a real foreign
        // key: keep one edge, but let it carry the constraint name so referenceRules can find it.
        for (ReferenceEdge existing : layoutGraph.getAllEdges(referencedTable, currentTable)) {
            if (existing.getReferencedFields().equals(referenceFieldMap)) {
                if (existing.getConstraintName() == null) existing.setConstraintName(constraintName);
                return;
            }
        }
        // a multigraph: several foreign keys between one pair of tables are separate edges
        layoutGraph.addEdge(referencedTable, currentTable, new ReferenceEdge(referenceFieldMap, constraintName));
    }

    public void setGenerationTargets(Set<Table> generationTargets) {
        this.generationTargets = generationTargets;
    }

    public boolean isGenerationTarget(Table table) {
        return generationTargets == null || generationTargets.contains(table);
    }

    public void printGraph() throws IOException {
        JGraphXAdapter<Table, ReferenceEdge> graphAdapter =
                new JGraphXAdapter<>(this.layoutGraph);
        mxIGraphLayout layout = new mxHierarchicalLayout(graphAdapter);
        layout.execute(graphAdapter.getDefaultParent());

        BufferedImage image =
                mxCellRenderer.createBufferedImage(graphAdapter, null, 1.5, Color.WHITE, true, null);
        File imgFile = new File("./db_layout.png");
        ImageIO.write(image, "PNG", imgFile);
    }

    public Graph<Table, ReferenceEdge> getLayoutGraph() {
        return layoutGraph;
    }

    public void setLayoutGraph(Graph<Table, ReferenceEdge> layoutGraph) {
        this.layoutGraph = layoutGraph;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        DatabaseLayout that = (DatabaseLayout) o;
        return Objects.equals(layoutGraph, that.layoutGraph);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(layoutGraph);
    }
}
