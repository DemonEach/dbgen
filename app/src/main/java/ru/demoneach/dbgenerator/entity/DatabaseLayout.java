package ru.demoneach.dbgenerator.entity;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.util.mxCellRenderer;
import org.jgrapht.Graph;
import org.jgrapht.ext.JGraphXAdapter;
import org.jgrapht.graph.DefaultDirectedGraph;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class DatabaseLayout {

    Graph<Table, ReferenceEdge> layoutGraph;

    public DatabaseLayout() {
        this.layoutGraph = new DefaultDirectedGraph<>(ReferenceEdge.class);
    }

    public void addTableVertex(String schema, String tableName, Map<String, String> tableFields) {
        Table table = new Table();
        table.setSchema(schema);
        table.setTableName(tableName);
        table.setFieldsFromMap(tableFields);

        layoutGraph.addVertex(table);
    }

    public void addTableEdge(Table referencedTable, Table currentTable, Map<Field, Field> referenceFieldMap) {
        ReferenceEdge referenceEdge = new ReferenceEdge(referenceFieldMap);

        if (this.layoutGraph.containsEdge(referenceEdge)) {
            return;
        }

        this.layoutGraph.addEdge(referencedTable, currentTable, referenceEdge);
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
