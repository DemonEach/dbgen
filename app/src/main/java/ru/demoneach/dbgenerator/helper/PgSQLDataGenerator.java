package ru.demoneach.dbgenerator.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.entity.exception.ConfigParsingException;
import ru.demoneach.dbgenerator.inserter.CSVFileInserter;
import ru.demoneach.dbgenerator.inserter.DataInserter;
import ru.demoneach.dbgenerator.inserter.MultiValuesInserter;
import ru.demoneach.dbgenerator.inserter.SimpleValuesInserter;

import java.io.*;
import java.net.URISyntaxException;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

// TODO: make interface for another databases
@Slf4j
public class PgSQLDataGenerator {

    private Connection conn;
    private DatabaseLayout databaseLayout;
    private DataInserter dataInserter;

    private static final String GET_DATABASE_STRUCTURE = """
            SELECT schemaname, tablename FROM pg_tables
                WHERE schemaname NOT ILIKE '%pg_%' AND schemaname != 'information_schema'       
            """;

    private static final String GET_TABLE_STRUCTURE = """
            SELECT
                a.attname as "column",
                pg_catalog.format_type(a.atttypid, a.atttypmod) as "type",
                CASE WHEN EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_depend d
                    JOIN pg_catalog.pg_class c ON c.oid = d.objid
                    JOIN pg_catalog.pg_attribute a2 ON a2.attrelid = d.refobjid AND a2.attnum = d.refobjsubid
                    WHERE d.classid = 'pg_catalog.pg_class'::regclass
                    AND d.refclassid = 'pg_catalog.pg_class'::regclass
                    AND d.deptype = 'a'
                    AND c.relkind = 'S'
                    AND a2.attrelid = a.attrelid
                    AND a2.attname = a.attname
                ) THEN TRUE ELSE FALSE END as "is_serial"
            FROM
                pg_catalog.pg_attribute a
            WHERE
                a.attnum > 0
                AND NOT a.attisdropped
                AND a.attrelid = (
                    SELECT c.oid
                    FROM pg_catalog.pg_class c
                        LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relname = ? AND nspname = ?
                        AND pg_catalog.pg_table_is_visible(c.oid)
                );
            """;

    private static final String GET_REFERENCES_FOR_TABLE_AND_FIELDS = """
            SELECT c.confrelid::regclass::text AS referenced_table
                  ,f.attname AS referenced_column
                  ,c.conname AS fk_name
                  ,pg_get_constraintdef(c.oid) AS fk_definition
                  ,a.attname
            FROM   pg_attribute  a 
            JOIN   pg_constraint c ON (c.conrelid, c.conkey[1]) = (a.attrelid, a.attnum)
            JOIN   pg_attribute  f ON f.attrelid = c.confrelid
                                  AND f.attnum = ANY (confkey)
            WHERE  a.attrelid = ?::regclass   -- table name 
            AND    c.contype  = 'f'
            GROUP  BY c.confrelid, c.conname, c.oid, a.attname, f.attname;
            """;

    public PgSQLDataGenerator(Parameters parameters) throws Exception {
        ConnectionParameters connectionParameters = parameters.getConnectionParameters();

        if (Objects.isNull(connectionParameters)) {
            throw new ConfigParsingException("Connection parameters are not set");
        }

        String url = "jdbc:postgresql://%s:%s/%s".formatted(connectionParameters.getHost(), connectionParameters.getPort(), connectionParameters.getDbName());
        Properties props = new Properties();
        props.setProperty("user", connectionParameters.getUsername());
        props.setProperty("password", connectionParameters.getPassword());

        this.conn = DriverManager.getConnection(url, props);
        this.databaseLayout = new DatabaseLayout();
        Map<String, Rule> fieldGenerationRules =  parameters.getFieldGenerationRules();

        this.dataInserter = switch (parameters.getStrategy()) {
            case Strategy.FILE:
                log.debug("Starting generation through file generation and COPY command");
                yield new CSVFileInserter(fieldGenerationRules, this.conn);
            case Strategy.MULTI:
                log.debug("Starting generation through INSERT VALUES pattern");
                yield new MultiValuesInserter(fieldGenerationRules, this.conn);
            default:
                log.debug("Using simple insert for each row");
                yield new SimpleValuesInserter(fieldGenerationRules, this.conn);
        };

        log.info("Successfully connected to DB: {}", url);

        formDatabaseStructure(parameters);
    }

    private void extractTablesAndSchemas(Strategy strategy) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(GET_DATABASE_STRUCTURE)) {
            ResultSet schemasAndTables = statement.executeQuery();

            while (schemasAndTables.next()) {
                String schemaName = schemasAndTables.getString("schemaname");
                String tableName = schemasAndTables.getString("tablename");
                Map<String, String> tableFields = new LinkedHashMap<>();
                conn.setSchema(schemaName);

                try (PreparedStatement getTableFieldsStatement = conn.prepareStatement(GET_TABLE_STRUCTURE)) {
                    getTableFieldsStatement.setString(1, tableName);
                    getTableFieldsStatement.setString(2, schemaName);

                    ResultSet getTableFieldsResult = getTableFieldsStatement.executeQuery();

                    while (getTableFieldsResult.next()) {
                        String columnName = getTableFieldsResult.getString("column");
                        String columnType = getTableFieldsResult.getString("type");
                        boolean isSerial = getTableFieldsResult.getBoolean("is_serial");

                        // for DEFAULT and MULTI insertion value can be ignored, but for FILE it should be used
                        if (isSerial && !Strategy.FILE.equals(strategy)) {
                            columnType = "serial";
                        }

                        tableFields.put(columnName, columnType);
                    }
                }

                this.databaseLayout.addTableVertex(schemaName, tableName, tableFields);
            }
        }
    }

    private void makeLinksBetweenTables() throws SQLException {
        Set<Table> verticies = this.databaseLayout.getLayoutGraph().vertexSet();
        for (Table table : verticies) {
            // TODO: надо учитывать также другие констреинты, как например "unique"
            try (PreparedStatement getReferencesStatement = conn.prepareStatement(GET_REFERENCES_FOR_TABLE_AND_FIELDS)) {
                conn.setSchema(table.getSchema());
                getReferencesStatement.setString(1, "%s.%s".formatted(table.getSchema(), table.getTableName()));

                ResultSet references = getReferencesStatement.executeQuery();
                while (Objects.nonNull(references) && references.next()) {
                    String referencedTableName = references.getString("referenced_table");
                    String referencedColumn = references.getString("referenced_column");
                    String attributeName = references.getString("attname");

                    Table referencedTable = verticies.stream()
                            .filter(t -> t.getTableName().equalsIgnoreCase(referencedTableName))
                            .findFirst()
                            .orElse(null);

                    if (Objects.isNull(referencedTable)) {
                        continue;
                    }

                    Field referencedFieldOrigTable = referencedTable.getFields().stream()
                            .filter(f -> f.getName().equalsIgnoreCase(referencedColumn))
                            .findFirst()
                            .orElse(null);

                    Field referenceFieldCurrentTable = table.getFields().stream()
                            .filter(f -> f.getName().equalsIgnoreCase(attributeName))
                            .findFirst()
                            .orElse(null);

                    if (Objects.isNull(referencedFieldOrigTable) || Objects.isNull(referenceFieldCurrentTable)) {
                        continue;
                    }

                    this.databaseLayout.addTableEdge(referencedTable, table, Map.of(referencedFieldOrigTable, referenceFieldCurrentTable));
                }
            }
        }
    }

    private void formDatabaseStructure(Parameters parameters) throws SQLException {
        extractTablesAndSchemas(parameters.getStrategy());
        excludeTablesThatAreNotRequired(parameters.getTablesToGenerate());
        makeLinksBetweenTables();
        addCustomLinksBetweenTables(parameters.getCustomTableLinks());

        if (log.isDebugEnabled()) {
            try {
                this.databaseLayout.printGraph();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void addCustomLinksBetweenTables(Map<String, String> tablesLinkMap) {
        if (tablesLinkMap == null || tablesLinkMap.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> tableLink : tablesLinkMap.entrySet()) {
            Table origTable = this.databaseLayout
                    .getLayoutGraph()
                    .vertexSet()
                    .stream()
                    .filter(t -> tableLink.getKey().contains(t.toString()))
                    .findFirst()
                    .orElse(null);

            Table referencedTable = this.databaseLayout
                    .getLayoutGraph()
                    .vertexSet()
                    .stream()
                    .filter(t -> tableLink.getValue().contains(t.toString()))
                    .findFirst()
                    .orElse(null);

            assert origTable != null : "Cannot find source table %s".formatted(tableLink.getKey());
            assert referencedTable != null : "Cannot find referenced table %s".formatted(tableLink.getValue());

            String origColumnName = tableLink.getKey().split("\\.")[2];
            String refColumnName = tableLink.getValue().split("\\.")[2];

            Field referencedFieldOrigTable = referencedTable.getFields().stream()
                    .filter(f -> f.getName().equalsIgnoreCase(refColumnName))
                    .findFirst()
                    .orElse(null);

            Field referenceFieldCurrentTable = origTable.getFields().stream()
                    .filter(f -> f.getName().equalsIgnoreCase(origColumnName))
                    .findFirst()
                    .orElse(null);

            if (Objects.isNull(referencedFieldOrigTable) || Objects.isNull(referenceFieldCurrentTable)) {
                continue;
            }

            this.databaseLayout.addTableEdge(origTable, referencedTable, Map.of(referenceFieldCurrentTable, referencedFieldOrigTable));
        }
    }

    public void generateDataForTables(Parameters parameters) throws SQLException, URISyntaxException {
        try {
            conn.setAutoCommit(false);

            Graph<Table, ReferenceEdge> graph = this.databaseLayout.getLayoutGraph();
            // TODO: add to additional option to exclude cycle
//            List<Table> visitedTables = new ArrayList<>();
//            List<Table> oneDegreeVerticies = new ArrayList<>();

//            CycleDetector<Table, ReferenceEdge> cycleDetector = new CycleDetector<>(graph);
//            Set<Table> cycles = cycleDetector.findCycles();
//            for (Table source : cycles) {
//                for (Table target : cycles) {
//                    if (graph.containsEdge(source, target)) {
//                        graph.removeEdge(source, target);
//                        log.info("Problematic edge: {} -> {}", source, target);
//                    }
//                }
//            }
            List<Table> sortedTables = topologicalSort(graph);

            for (Table table : sortedTables) {
                log.info("Starting generation for table: {}", table);
                Map<Field, List<Object>> fieldValuesMap = new HashMap<>();
                Set<ReferenceEdge> edges = graph.edgesOf(table);

                for (ReferenceEdge edge : edges) {
                    if (graph.getEdgeSource(edge).equals(table)) {
                        continue;
                    }

                    fieldValuesMap.putAll(extractLinkedField(graph.getEdgeSource(edge), edge.getReferencedFields()));
                }

                this.dataInserter.generateAndInsert(table, parameters, fieldValuesMap);
                log.info("Finished generation for table: {}", table);
            }

            conn.setAutoCommit(true);
        } catch (SQLException e) {
            log.error("Cannot execute query", e);
            conn.rollback();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            conn.close();
        }
    }

    private void excludeTablesThatAreNotRequired(List<String> requiredTables) {
        if (requiredTables == null || requiredTables.isEmpty()) {
            return;
        }

        Set<Table> verticesToRemove = this.databaseLayout.getLayoutGraph().vertexSet()
                .stream()
                .filter(t -> !requiredTables.contains(t.toString()))
                .collect(Collectors.toSet());
        this.databaseLayout.getLayoutGraph().removeAllVertices(verticesToRemove);
    }

    private List<Table> topologicalSort(Graph<Table, ReferenceEdge> graph) {
        List<Table> result = new ArrayList<>();
        Map<Table, Integer> inDegree = new HashMap<>();

        for (Table table : graph.vertexSet()) {
            inDegree.put(table, 0);
        }

        for (ReferenceEdge edge : graph.edgeSet()) {
            Table target = graph.getEdgeTarget(edge);
            inDegree.put(target, inDegree.get(target) + 1);
        }

        Queue<Table> queue = new LinkedList<>();
        for (Map.Entry<Table, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            Table table = queue.poll();
            result.add(table);

            // Уменьшаем количество входящих ребер для соседних вершин
            for (ReferenceEdge edge : graph.outgoingEdgesOf(table)) {
                Table neighbor = graph.getEdgeTarget(edge);
                inDegree.put(neighbor, inDegree.get(neighbor) - 1);

                // Если количество входящих ребер стало равно 0, добавляем вершину в очередь
                if (inDegree.get(neighbor) == 0) {
                    queue.add(neighbor);
                }
            }
        }

        // Если размер результата не равен количеству вершин, значит в графе есть цикл
        if (result.size() != graph.vertexSet().size()) {
            throw new IllegalStateException("Граф содержит цикл, топологическая сортировка невозможна");
        }

        return result;
    }

    private Map<Field, List<Object>> extractLinkedField(Table sourceTable, Map<Field, Field> referenceFieldMap) throws SQLException {
        String sqlFields = String.join(",", referenceFieldMap.keySet().stream().map(Field::getName).toArray(String[]::new));
        String sqlQuery = "SELECT %s FROM %s".formatted(sqlFields, sourceTable.getTableName());
        Map<Field, List<Object>> linkedFields = referenceFieldMap.values().stream().collect(Collectors.toMap(
                field -> field,
                value -> new ArrayList<>()
        ));
        conn.setSchema(sourceTable.getSchema());

        try (PreparedStatement preparedStatement = conn.prepareStatement(sqlQuery)) {
            preparedStatement.setFetchSize(100);
            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                for (Field field : referenceFieldMap.keySet()) {
                    linkedFields.get(referenceFieldMap.get(field)).add(resultSet.getObject(field.getName()));
                }
            }
        }

        return linkedFields;
    }
}
