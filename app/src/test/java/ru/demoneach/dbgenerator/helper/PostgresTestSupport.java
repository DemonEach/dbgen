package ru.demoneach.dbgenerator.helper;

import ru.demoneach.dbgenerator.entity.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Connection settings and scratch-schema helpers shared by the tests that need a real PostgreSQL. */
final class PostgresTestSupport {
    private PostgresTestSupport() {
    }

    static ConnectionParameters connection() {
        ConnectionParameters connection = new ConnectionParameters();
        connection.setHost(System.getenv().getOrDefault("DBGEN_TEST_HOST", "localhost"));
        connection.setPort(System.getenv().getOrDefault("DBGEN_TEST_PORT", "5432"));
        connection.setDbName(System.getenv("DBGEN_TEST_DATABASE"));
        connection.setUsername(System.getenv().getOrDefault("DBGEN_TEST_USER", "postgres"));
        connection.setPassword(System.getenv().getOrDefault("DBGEN_TEST_PASSWORD", ""));
        return connection;
    }

    static Connection open() throws SQLException {
        ConnectionParameters c = connection();
        return DriverManager.getConnection("jdbc:postgresql://%s:%s/%s".formatted(c.getHost(), c.getPort(), c.getDbName()),
                c.getUsername(), c.getPassword());
    }

    static String newSchemaName() {
        return "dbgen_test_" + UUID.randomUUID().toString().replace("-", "");
    }

    static Parameters parameters(Strategy strategy, int rows, int batch, List<String> tables, Map<String, Rule> rules) {
        Parameters parameters = new Parameters();
        parameters.setConnectionParameters(connection());
        parameters.setStrategy(strategy);
        parameters.setAmountOfEntries(rows);
        parameters.setBatch(batch);
        parameters.setBatchSave(batch);
        parameters.setTablesToGenerate(tables);
        parameters.setFieldGenerationRules(rules);
        return parameters;
    }

    static void generate(Parameters parameters) throws Exception {
        new PgSQLDataGenerator(parameters).generateDataForTables(parameters);
    }

    static long count(Connection conn, String qualifiedTable) throws SQLException {
        try (var st = conn.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM " + qualifiedTable)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
