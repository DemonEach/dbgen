package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.demoneach.dbgenerator.entity.ReferenceRule;
import ru.demoneach.dbgenerator.entity.Strategy;
import ru.demoneach.dbgenerator.exception.DataGenerationException;

import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static ru.demoneach.dbgenerator.helper.PostgresTestSupport.*;

/** Several foreign keys between one pair of tables, each with its own rule. */
@EnabledIfEnvironmentVariable(named = "DBGEN_TEST_DATABASE", matches = ".+")
class MultipleForeignKeysPostgresTest {

    private static ReferenceRule range(int min, int max) {
        return new ReferenceRule(List.of(min, max));
    }

    private static void createUsersAndPosts(Statement sql, String schema, int users) throws Exception {
        sql.execute("CREATE SCHEMA " + schema);
        sql.execute("CREATE TABLE " + schema + ".users (id int PRIMARY KEY)");
        sql.execute("CREATE TABLE " + schema + ".posts (id serial PRIMARY KEY, created_by int, updated_by int, "
                + "CONSTRAINT posts_created_by_fk FOREIGN KEY (created_by) REFERENCES " + schema + ".users (id), "
                + "CONSTRAINT posts_updated_by_fk FOREIGN KEY (updated_by) REFERENCES " + schema + ".users (id))");
        sql.execute("INSERT INTO " + schema + ".users SELECT generate_series(1, " + users + ")");
    }

    /** {min, max, number of users that have any} of posts per user for one referencing column */
    private static int[] perUser(Statement sql, String schema, String column) throws Exception {
        try (var rs = sql.executeQuery("SELECT coalesce(min(n),0), coalesce(max(n),0), count(*) FROM "
                + "(SELECT count(*) n FROM " + schema + ".posts GROUP BY " + column + ") q")) {
            rs.next();
            return new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)};
        }
    }

    @Test
    void everyKeyBetweenTheSameTablesGetsItsOwnValuesAndItsOwnRule() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createUsersAndPosts(sql, schema, 50);
                for (Strategy strategy : Strategy.values()) {
                    sql.execute("TRUNCATE " + schema + ".posts");
                    var parameters = parameters(strategy, 100, 30, List.of(schema + ".posts"), Map.of());
                    parameters.setReferenceRules(Map.of(
                            schema + ".posts.posts_created_by_fk", range(1, 5),
                            // 100 posts need all 50 users with exactly 2 each
                            schema + ".posts.posts_updated_by_fk", range(0, 2)));
                    generate(parameters);

                    assertEquals(100, count(conn, schema + ".posts"), strategy.name());
                    int[] created = perUser(sql, schema, "created_by");
                    int[] updated = perUser(sql, schema, "updated_by");
                    assertTrue(created[0] >= 1 && created[1] <= 5, strategy + " created_by " + List.of(created[0], created[1]));
                    assertEquals(List.of(2, 2, 50), List.of(updated[0], updated[1], updated[2]), strategy + " updated_by");
                    try (var rs = sql.executeQuery("SELECT count(*) FROM " + schema + ".posts WHERE created_by <> updated_by")) {
                        rs.next();
                        assertTrue(rs.getInt(1) > 0, strategy + ": the two keys must be filled independently");
                    }
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void defaultRuleGivesEachKeyDistinctParents() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createUsersAndPosts(sql, schema, 100);
                generate(parameters(Strategy.DEFAULT, 100, 30, List.of(schema + ".posts"), Map.of()));
                for (String column : List.of("created_by", "updated_by")) {
                    assertEquals(List.of(1, 1, 100), List.of(perUser(sql, schema, column)[0], perUser(sql, schema, column)[1],
                            perUser(sql, schema, column)[2]), column);
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void parentAndChildInTheSameRunAreOrderedWithParallelKeys() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("CREATE TABLE " + schema + ".users (id serial PRIMARY KEY)");
                sql.execute("CREATE TABLE " + schema + ".posts (id serial PRIMARY KEY, created_by int REFERENCES "
                        + schema + ".users (id), updated_by int REFERENCES " + schema + ".users (id))");
                generate(parameters(Strategy.DEFAULT, 50, 20, List.of(schema + ".posts", schema + ".users"), Map.of()));
                assertEquals(50, count(conn, schema + ".users"));
                assertEquals(50, count(conn, schema + ".posts"));
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void customLinkRepeatingARealKeyIsMergedAndTheRuleStillApplies() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createUsersAndPosts(sql, schema, 100);
                var parameters = parameters(Strategy.DEFAULT, 60, 20, List.of(schema + ".posts"), Map.of());
                parameters.setCustomTableLinks(Map.of(schema + ".posts.created_by", schema + ".users.id"));
                parameters.setReferenceRules(Map.of(schema + ".posts.posts_created_by_fk", range(3, 3)));
                generate(parameters);
                assertEquals(List.of(3, 3, 20), List.of(perUser(sql, schema, "created_by")[0], perUser(sql, schema, "created_by")[1],
                        perUser(sql, schema, "created_by")[2]));
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void oneColumnInTwoKeysIsRejectedBeforeAnythingIsWritten() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createUsersAndPosts(sql, schema, 10);
                sql.execute("CREATE TABLE " + schema + ".admins (id int PRIMARY KEY)");
                var parameters = parameters(Strategy.DEFAULT, 5, 5, List.of(schema + ".posts"), Map.of());
                parameters.setCustomTableLinks(Map.of(schema + ".posts.created_by", schema + ".admins.id"));
                var e = assertThrows(DataGenerationException.class, () -> generate(parameters));
                assertTrue(e.getMessage().contains("created_by") && e.getMessage().contains("posts_created_by_fk")
                        && e.getMessage().contains("customTableLinks"), e.getMessage());
                assertEquals(0, count(conn, schema + ".posts"));
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
