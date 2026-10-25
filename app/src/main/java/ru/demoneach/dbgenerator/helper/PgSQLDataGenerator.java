package ru.demoneach.dbgenerator.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.Graph;
import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.exception.ConfigParsingException;
import ru.demoneach.dbgenerator.exception.DataGenerationException;
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
    private ParentRowSource parentRowSource;
    private final Random random = new Random();

    private static final String GET_DATABASE_STRUCTURE = """
            SELECT schemaname, tablename FROM pg_tables
                WHERE left(schemaname, 3) <> 'pg_' AND schemaname != 'information_schema'
            """;

    private static final String GET_TABLE_STRUCTURE = """
            SELECT
                a.attname as "column",
                pg_catalog.format_type(a.atttypid, a.atttypmod) as "type",
                a.attidentity <> '' OR a.attgenerated <> '' AS is_generated,
                a.atthasdef AS has_default,
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
            SELECT parent.relname AS referenced_table, ns.nspname AS referenced_schema
                  ,f.attname AS referenced_column
                  ,c.conname AS fk_name
                  ,a.attname
                  ,cardinality(c.conkey) AS fk_column_count
            FROM   pg_constraint c
            JOIN   pg_attribute  a ON (c.conrelid, c.conkey[1]) = (a.attrelid, a.attnum)
            JOIN   pg_class parent ON parent.oid = c.confrelid
            JOIN   pg_namespace ns ON ns.oid = parent.relnamespace
            JOIN   pg_attribute  f ON f.attrelid = c.confrelid
                                  AND f.attnum = c.confkey[1]
            WHERE  c.conrelid = ?::regclass   -- table name
            AND    c.contype  = 'f';
            """;

    private static final String FIND_FOREIGN_KEY_CONSTRAINT = """
            SELECT 1
            FROM   pg_constraint c
            JOIN   pg_class t ON t.oid = c.conrelid
            JOIN   pg_namespace n ON n.oid = t.relnamespace
            WHERE  n.nspname = ? AND t.relname = ? AND c.conname = ? AND c.contype = 'f'
            """;

    private static final String FIND_UNIQUE_INDEX_ON_FOREIGN_KEY = """
            SELECT idx.relname
            FROM   pg_constraint c
            JOIN   pg_class t ON t.oid = c.conrelid
            JOIN   pg_namespace n ON n.oid = t.relnamespace
            JOIN   pg_index i ON i.indrelid = c.conrelid
            JOIN   pg_class idx ON idx.oid = i.indexrelid
            WHERE  n.nspname = ? AND t.relname = ? AND c.conname = ? AND c.contype = 'f'
            AND    i.indisunique AND i.indpred IS NULL AND i.indexprs IS NULL
            AND    i.indkey::int2[] <@ c.conkey
            LIMIT  1
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
        // lets the driver fold a JDBC executeBatch() of INSERTs into multi-row statements;
        // only DEFAULT (SimpleValuesInserter) uses executeBatch, see bench/results for measurements
        props.setProperty("reWriteBatchedInserts", "true");

        this.conn = DriverManager.getConnection(url, props);
        try {
            this.databaseLayout = new DatabaseLayout();
            this.parentRowSource = new JdbcParentRowSource(this.conn);
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
        } catch (Exception | Error failure) {
            try {
                conn.close();
            } catch (SQLException | RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    PgSQLDataGenerator(Connection conn, DatabaseLayout databaseLayout, DataInserter dataInserter) {
        this(conn, databaseLayout, dataInserter, new JdbcParentRowSource(conn));
    }

    PgSQLDataGenerator(Connection conn, DatabaseLayout databaseLayout, DataInserter dataInserter, ParentRowSource parentRowSource) {
        this.conn = conn;
        this.databaseLayout = databaseLayout;
        this.dataInserter = dataInserter;
        this.parentRowSource = parentRowSource;
    }

    private void extractTablesAndSchemas() throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(GET_DATABASE_STRUCTURE)) {
            ResultSet schemasAndTables = statement.executeQuery();

            while (schemasAndTables.next()) {
                String schemaName = schemasAndTables.getString("schemaname");
                String tableName = schemasAndTables.getString("tablename");
                Table table = new Table();
                table.setSchema(schemaName);
                table.setTableName(tableName);
                List<Field> tableFields = new ArrayList<>();
                table.setFields(tableFields);
                conn.setSchema(schemaName);

                try (PreparedStatement getTableFieldsStatement = conn.prepareStatement(GET_TABLE_STRUCTURE)) {
                    getTableFieldsStatement.setString(1, tableName);
                    getTableFieldsStatement.setString(2, schemaName);

                    ResultSet getTableFieldsResult = getTableFieldsStatement.executeQuery();

                    while (getTableFieldsResult.next()) {
                        String columnName = getTableFieldsResult.getString("column");
                        String columnType = getTableFieldsResult.getString("type");
                        boolean isSerial = getTableFieldsResult.getBoolean("is_serial");

                        Field field = new Field(columnName, columnType);
                        field.setDatabaseGenerated(isSerial || getTableFieldsResult.getBoolean("is_generated"));
                        field.setHasDefault(getTableFieldsResult.getBoolean("has_default"));
                        tableFields.add(field);
                    }
                }

                this.databaseLayout.getLayoutGraph().addVertex(table);
            }
        }
    }

    // Returns the FK parent tables found for `table` (querying pg_constraint) after adding the
    // corresponding graph edges, so the caller can keep walking the FK graph outward from them.
    private List<Table> queryForeignKeyParents(Table table, Set<Table> verticies) throws SQLException {
        List<Table> discoveredParents = new ArrayList<>();
        // TODO: it would be nice to account for other constraints, i.e "unique"
        try (PreparedStatement getReferencesStatement = conn.prepareStatement(GET_REFERENCES_FOR_TABLE_AND_FIELDS)) {
            conn.setSchema(table.getSchema());
            getReferencesStatement.setString(1, SqlIdentifiers.qualified(table.getSchema(), table.getTableName()));

            ResultSet references = getReferencesStatement.executeQuery();
            while (Objects.nonNull(references) && references.next()) {
                String fkName = references.getString("fk_name");
                int fkColumnCount = references.getInt("fk_column_count");
                if (fkColumnCount != 1) {
                    throw new DataGenerationException(
                            "Composite foreign key %s on %s references %d columns; composite FKs are not supported yet"
                                    .formatted(fkName, table, fkColumnCount));
                }

                String referencedTableName = references.getString("referenced_table");
                String referencedSchema = references.getString("referenced_schema");
                String referencedColumn = references.getString("referenced_column");
                String attributeName = references.getString("attname");

                Table referencedTable = verticies.stream()
                        .filter(t -> t.getTableName().equals(referencedTableName) && t.getSchema().equals(referencedSchema))
                        .findFirst()
                        .orElse(null);

                if (Objects.isNull(referencedTable)) {
                    continue;
                }

                Field referencedFieldOrigTable = referencedTable.getFields().stream()
                        .filter(f -> f.getName().equals(referencedColumn))
                        .findFirst()
                        .orElse(null);

                Field referenceFieldCurrentTable = table.getFields().stream()
                        .filter(f -> f.getName().equals(attributeName))
                        .findFirst()
                        .orElse(null);

                if (Objects.isNull(referencedFieldOrigTable) || Objects.isNull(referenceFieldCurrentTable)) {
                    continue;
                }

                this.databaseLayout.addTableEdge(referencedTable, table, Map.of(referencedFieldOrigTable, referenceFieldCurrentTable), fkName);
                discoveredParents.add(referencedTable);
            }
        }
        return discoveredParents;
    }

    // Discovers the FK graph. When tablesToGenerate restricts the run, only the requested tables
    // and their FK parents - direct, transitive, or reached through a customTableLinks edge - are
    // queried and kept; every other table (including ones with an FK shape this tool does not
    // support yet, such as a composite key) is left alone instead of blocking the run.
    private void discoverForeignKeyGraph(List<String> requiredTables) throws SQLException {
        Graph<Table, ReferenceEdge> graph = this.databaseLayout.getLayoutGraph();
        Set<Table> verticies = graph.vertexSet();
        boolean scoped = requiredTables != null && !requiredTables.isEmpty();

        Set<Table> generationTargets = scoped
                ? verticies.stream().filter(t -> requiredTables.contains(t.toString())).collect(Collectors.toSet())
                : Set.copyOf(verticies);
        Set<Table> visited = new HashSet<>(generationTargets);
        Deque<Table> toVisit = new ArrayDeque<>(generationTargets);

        while (!toVisit.isEmpty()) {
            Table table = toVisit.poll();
            // a parent already linked through a customTableLinks edge still needs to be visited
            // itself, so its own FK parents are not missed
            for (ReferenceEdge edge : graph.incomingEdgesOf(table)) {
                Table parent = graph.getEdgeSource(edge);
                if (visited.add(parent)) toVisit.add(parent);
            }
            for (Table parent : queryForeignKeyParents(table, verticies)) {
                if (visited.add(parent)) toVisit.add(parent);
            }
        }

        if (scoped) {
            Set<Table> verticesToRemove = verticies.stream().filter(t -> !visited.contains(t)).collect(Collectors.toSet());
            graph.removeAllVertices(verticesToRemove);
            this.databaseLayout.setGenerationTargets(generationTargets);
        }
    }

    private void formDatabaseStructure(Parameters parameters) throws SQLException {
        extractTablesAndSchemas();
        // Custom links only need the vertices (already all loaded above) and are cheap, so they
        // are added first; discoverForeignKeyGraph() then also walks them when deciding which
        // tables need their FKs queried.
        addCustomLinksBetweenTables(parameters.getCustomTableLinks());
        discoverForeignKeyGraph(parameters.getTablesToGenerate());
        validateReferencingColumnsAreNotShared();
        validateReferenceRules(parameters.getReferenceRules());

        if (log.isDebugEnabled()) {
            try {
                this.databaseLayout.printGraph();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // Every foreign key gets its values independently, so a column that belongs to two keys (a custom
    // link onto a column that is already a foreign key, or overlapping composite keys) cannot be
    // filled consistently. Fail before anything is written.
    private void validateReferencingColumnsAreNotShared() {
        Graph<Table, ReferenceEdge> graph = this.databaseLayout.getLayoutGraph();
        for (Table table : graph.vertexSet()) {
            if (!this.databaseLayout.isGenerationTarget(table)) continue;
            Map<Field, ReferenceEdge> owner = new HashMap<>();
            for (ReferenceEdge edge : graph.incomingEdgesOf(table)) {
                if (graph.getEdgeSource(edge).equals(table)) continue;
                for (Field column : edge.getReferencedFields().values()) {
                    ReferenceEdge other = owner.put(column, edge);
                    if (other != null) {
                        throw new DataGenerationException(
                                "Column %s of %s belongs to more than one foreign key (%s and %s); overlapping keys are not supported yet"
                                        .formatted(column.getName(), table, describe(other, graph), describe(edge, graph)));
                    }
                }
            }
        }
    }

    private static String describe(ReferenceEdge edge, Graph<Table, ReferenceEdge> graph) {
        return edge.getConstraintName() != null
                ? edge.getConstraintName()
                : "customTableLinks to " + graph.getEdgeSource(edge);
    }

    // A rule naming a constraint that does not exist is almost certainly a typo and would be
    // silently ignored, so every key is checked against pg_constraint before any data is written.
    // A rule allowing several children per parent on a column that is unique would only fail
    // later on the insert, so that is rejected here too.
    private void validateReferenceRules(Map<String, ReferenceRule> referenceRules) throws SQLException {
        if (referenceRules == null || referenceRules.isEmpty()) return;
        try (PreparedStatement exists = conn.prepareStatement(FIND_FOREIGN_KEY_CONSTRAINT);
             PreparedStatement unique = conn.prepareStatement(FIND_UNIQUE_INDEX_ON_FOREIGN_KEY)) {
            for (Map.Entry<String, ReferenceRule> entry : referenceRules.entrySet()) {
                List<String> name = SqlIdentifiers.parse(entry.getKey(), 3);
                for (PreparedStatement statement : List.of(exists, unique)) {
                    statement.setString(1, name.get(0));
                    statement.setString(2, name.get(1));
                    statement.setString(3, name.get(2));
                }
                try (ResultSet found = exists.executeQuery()) {
                    if (!found.next()) {
                        throw new DataGenerationException(
                                "referenceRules: foreign key constraint %s not found on table %s.%s"
                                        .formatted(name.get(2), name.get(0), name.get(1)));
                    }
                }
                if (entry.getValue().max() > 1) {
                    try (ResultSet index = unique.executeQuery()) {
                        if (index.next()) {
                            throw new DataGenerationException(
                                    "referenceRules %s: childrenPerParent %s allows several children per parent, but the referencing column(s) are unique (index %s)"
                                            .formatted(entry.getKey(), entry.getValue().getChildrenPerParent(), index.getString(1)));
                        }
                    }
                }
            }
        }
    }

    private static String referenceRuleKey(Table child, String constraintName) {
        return SqlIdentifiers.configPart(child.getSchema()) + "." + SqlIdentifiers.configPart(child.getTableName())
                + "." + SqlIdentifiers.configPart(constraintName);
    }

    // package-private for PgSQLDataGeneratorCustomLinksTest: exercising it does not need a
    // live Connection, only a pre-populated DatabaseLayout, so there is no DB-mocking seam for it.
    void addCustomLinksBetweenTables(Map<String, String> tablesLinkMap) {
        if (tablesLinkMap == null || tablesLinkMap.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> tableLink : tablesLinkMap.entrySet()) {
            List<String> sourceName = SqlIdentifiers.parse(tableLink.getKey(), 3);
            List<String> targetName = SqlIdentifiers.parse(tableLink.getValue(), 3);
            Table origTable = this.databaseLayout
                    .getLayoutGraph()
                    .vertexSet()
                    .stream()
                    .filter(t -> sourceName.get(0).equals(t.getSchema()) && sourceName.get(1).equals(t.getTableName()))
                    .findFirst()
                    .orElse(null);

            Table referencedTable = this.databaseLayout
                    .getLayoutGraph()
                    .vertexSet()
                    .stream()
                    .filter(t -> targetName.get(0).equals(t.getSchema()) && targetName.get(1).equals(t.getTableName()))
                    .findFirst()
                    .orElse(null);

            if (origTable == null) {
                throw new DataGenerationException("customTableLinks: cannot find source table %s".formatted(tableLink.getKey()));
            }
            if (referencedTable == null) {
                throw new DataGenerationException("customTableLinks: cannot find referenced table %s".formatted(tableLink.getValue()));
            }

            String origColumnName = sourceName.get(2);
            String refColumnName = targetName.get(2);

            Field referencedFieldOrigTable = referencedTable.getFields().stream()
                    .filter(f -> f.getName().equals(refColumnName))
                    .findFirst()
                    .orElse(null);

            Field referenceFieldCurrentTable = origTable.getFields().stream()
                    .filter(f -> f.getName().equals(origColumnName))
                    .findFirst()
                    .orElse(null);

            if (Objects.isNull(referencedFieldOrigTable) || Objects.isNull(referenceFieldCurrentTable)) {
                continue;
            }

            // edge must run parent -> child, same as the automatically detected FKs above:
            // origTable is the table that holds the referencing column (the child), referencedTable
            // is the table whose column is referenced (the parent) and must be generated first.
            this.databaseLayout.addTableEdge(referencedTable, origTable, Map.of(referencedFieldOrigTable, referenceFieldCurrentTable));
        }
    }

    public void generateDataForTables(Parameters parameters) throws SQLException, URISyntaxException, JsonProcessingException {
        try (Connection connection = conn) {
            conn.setAutoCommit(false);
            try {
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
                Map<String, ReferenceRule> referenceRules = parameters.getReferenceRules();
                Set<String> unusedRules = referenceRules == null ? new HashSet<>() : new HashSet<>(referenceRules.keySet());

                for (Table table : sortedTables) {
                    if (!this.databaseLayout.isGenerationTarget(table)) {
                        log.info("Skipping {}: outside tablesToGenerate, used only as an existing FK reference", table);
                        continue;
                    }

                    log.info("Starting generation for table: {}", table);
                    Map<Field, List<Object>> fieldValuesMap = new HashMap<>();
                    Set<ReferenceEdge> edges = graph.edgesOf(table);

                    for (ReferenceEdge edge : edges) {
                        if (graph.getEdgeSource(edge).equals(table)) {
                            continue;
                        }

                        ReferenceRule rule = ReferenceRule.DEFAULT;
                        if (edge.getConstraintName() != null && referenceRules != null) {
                            String key = referenceRuleKey(table, edge.getConstraintName());
                            ReferenceRule configured = referenceRules.get(key);
                            if (configured != null) {
                                rule = configured;
                                unusedRules.remove(key);
                            }
                        }
                        fieldValuesMap.putAll(extractLinkedField(graph.getEdgeSource(edge), table, edge, parameters.getAmountOfEntries(), rule));
                    }

                    this.dataInserter.generateAndInsert(table, parameters, fieldValuesMap);
                    log.info("Finished generation for table: {}", table);
                }

                if (!unusedRules.isEmpty()) {
                    log.warn("referenceRules ignored, their foreign keys were not used in this run: {}", unusedRules);
                }

                conn.commit();
            } catch (SQLException | JsonProcessingException | URISyntaxException | RuntimeException | Error failure) {
                try {
                    conn.rollback();
                } catch (SQLException | RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
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

            // Reducing the number of incoming edges for neighboring vertices
            for (ReferenceEdge edge : graph.outgoingEdgesOf(table)) {
                Table neighbor = graph.getEdgeTarget(edge);
                inDegree.put(neighbor, inDegree.get(neighbor) - 1);

                // Если количество входящих ребер стало равно 0, добавляем вершину в очередь
                if (inDegree.get(neighbor) == 0) {
                    queue.add(neighbor);
                }
            }
        }

        // If the result size is not equal to the number of vertices, then there is a cycle in the graph
        if (result.size() != graph.vertexSet().size()) {
            throw new IllegalStateException("Граф содержит цикл, топологическая сортировка невозможна");
        }

        return result;
    }

    // Builds, for every referencing column of the child, the list of values the inserters consume.
    // Inserters take values from the END of the list, one per row, so the list is filled in reverse:
    // the last element is the value for the first row. Several columns of one link stay in sync
    // because they are all built from the same row-to-parent plan.
    private Map<Field, List<Object>> extractLinkedField(Table parent, Table child, ReferenceEdge edge, int amountOfEntries, ReferenceRule rule) throws SQLException {
        Map<Field, Field> referenceFieldMap = edge.getReferencedFields();
        String link = "Foreign key %s on %s".formatted(edge.getConstraintName() == null ? "(custom link to " + parent + ")" : edge.getConstraintName(), child);
        List<Field> parentFields = new ArrayList<>(referenceFieldMap.keySet());

        int wanted = ReferencePlanner.parentsToRead(amountOfEntries, rule, link);
        List<Object[]> parentRows = parentRowSource.read(parent, parentFields, wanted);
        int[] parentOfRow = ReferencePlanner.assign(amountOfEntries, parentRows.size(), rule, random, link);

        Map<Field, List<Object>> linkedFields = new HashMap<>();
        for (int column = 0; column < parentFields.size(); column++) {
            List<Object> values = new ArrayList<>(amountOfEntries);
            for (int row = amountOfEntries - 1; row >= 0; row--) {
                values.add(parentRows.get(parentOfRow[row])[column]);
            }
            linkedFields.put(referenceFieldMap.get(parentFields.get(column)), values);
        }
        return linkedFields;
    }
}
