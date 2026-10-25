package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.demoneach.dbgenerator.entity.ReferenceRule;
import ru.demoneach.dbgenerator.entity.Strategy;
import ru.demoneach.dbgenerator.exception.DataGenerationException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static ru.demoneach.dbgenerator.helper.PostgresTestSupport.*;

@EnabledIfEnvironmentVariable(named = "DBGEN_TEST_DATABASE", matches = ".+")
class ReferenceRulesPostgresTest {

    @Test
    void ruleMustNameAnExistingForeignKeyConstraint() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".parent (id int PRIMARY KEY)");
                sql.execute("CREATE TABLE " + schema + ".child (id serial PRIMARY KEY, parent_id int, "
                        + "CONSTRAINT child_parent_fk FOREIGN KEY (parent_id) REFERENCES " + schema + ".parent (id))");
                sql.execute("INSERT INTO " + schema + ".parent SELECT generate_series(1, 5)");
                ReferenceRule rule = new ReferenceRule(List.of(1, 1));

                var existing = parameters(Strategy.DEFAULT, 3, 10, List.of(schema + ".child"), Map.of());
                existing.setReferenceRules(Map.of(schema + ".child.child_parent_fk", rule));
                generate(existing);
                assertEquals(3, count(conn, schema + ".child"));

                for (String missing : List.of(schema + ".child.no_such_fk", schema + ".nosuchtable.child_parent_fk",
                        // exists, but is a primary key, not a foreign key
                        schema + ".child.child_pkey")) {
                    var parameters = parameters(Strategy.DEFAULT, 3, 10, List.of(schema + ".child"), Map.of());
                    parameters.setReferenceRules(Map.of(missing, rule));
                    var e = assertThrows(DataGenerationException.class, () -> generate(parameters), missing);
                    assertTrue(e.getMessage().contains(missing.substring(missing.lastIndexOf('.') + 1)), e.getMessage());
                    assertEquals(3, count(conn, schema + ".child"), "nothing is written when a rule is invalid");
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static ReferenceRule range(int min, int max) {
        return new ReferenceRule(List.of(min, max));
    }

    private static void setUp(java.sql.Statement sql, String schema, int parents, String childColumnSuffix) throws Exception {
        sql.execute("CREATE SCHEMA " + schema);
        sql.execute("CREATE TABLE " + schema + ".parent (id int PRIMARY KEY)");
        sql.execute("CREATE TABLE " + schema + ".child (id serial PRIMARY KEY, parent_id int " + childColumnSuffix
                + ", CONSTRAINT child_parent_fk FOREIGN KEY (parent_id) REFERENCES " + schema + ".parent (id))");
        sql.execute("INSERT INTO " + schema + ".parent SELECT generate_series(1, " + parents + ")");
    }

    /** children-per-parent for the parents that have any, plus how many parents have none */
    private static int[] childStats(java.sql.Statement sql, String schema) throws Exception {
        try (var rs = sql.executeQuery("SELECT coalesce(min(n),0), coalesce(max(n),0), count(*) FROM "
                + "(SELECT count(c.id) n FROM " + schema + ".parent p JOIN " + schema + ".child c ON c.parent_id = p.id GROUP BY p.id) q")) {
            rs.next();
            return new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)};
        }
    }

    @Test
    void childrenPerParentRangeIsRespectedByEveryStrategy() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                setUp(sql, schema, 100, "");
                for (Strategy strategy : Strategy.values()) {
                    for (int[] bounds : new int[][]{{0, 5}, {2, 3}, {1, 1}}) {
                        sql.execute("TRUNCATE " + schema + ".child");
                        var parameters = parameters(strategy, 100, 40, List.of(schema + ".child"), Map.of());
                        parameters.setReferenceRules(Map.of(schema + ".child.child_parent_fk", range(bounds[0], bounds[1])));
                        generate(parameters);

                        String label = strategy + " " + java.util.Arrays.toString(bounds);
                        assertEquals(100, count(conn, schema + ".child"), label);
                        int[] stats = childStats(sql, schema);
                        assertTrue(stats[0] >= Math.max(bounds[0], 1) && stats[1] <= bounds[1], label + " per-parent range " + stats[0] + ".." + stats[1]);
                        if (bounds[0] == 0) assertTrue(stats[2] < 100, label + ": some parents must have no children");
                        if (bounds[0] == 1 && bounds[1] == 1) assertEquals(100, stats[2], label + ": one child per parent");
                    }
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void defaultRuleGivesDistinctParentsAndSmallRunsDoNotNeedTheWholeParent() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                setUp(sql, schema, 100, "UNIQUE");
                generate(parameters(Strategy.DEFAULT, 40, 10, List.of(schema + ".child"), Map.of()));
                try (var rs = sql.executeQuery("SELECT count(DISTINCT parent_id), count(*) FROM " + schema + ".child")) {
                    rs.next();
                    assertEquals(40, rs.getInt(1));
                    assertEquals(40, rs.getInt(2));
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void notEnoughParentsFailsClearlyAndWritesNothing() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                setUp(sql, schema, 3, "");
                var parameters = parameters(Strategy.DEFAULT, 10, 10, List.of(schema + ".child"), Map.of());
                var e = assertThrows(DataGenerationException.class, () -> generate(parameters));
                assertTrue(e.getMessage().contains("child_parent_fk") && e.getMessage().contains("only 3"), e.getMessage());
                assertEquals(0, count(conn, schema + ".child"));

                // 3 parents are enough for 10 rows once up to 4 children per parent are allowed
                var relaxed = parameters(Strategy.DEFAULT, 10, 10, List.of(schema + ".child"), Map.of());
                relaxed.setReferenceRules(Map.of(schema + ".child.child_parent_fk", range(1, 4)));
                generate(relaxed);
                assertEquals(10, count(conn, schema + ".child"));
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void severalChildrenPerParentAreRejectedOnAUniqueReferencingColumn() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                setUp(sql, schema, 10, "UNIQUE");
                var tooMany = parameters(Strategy.DEFAULT, 5, 5, List.of(schema + ".child"), Map.of());
                tooMany.setReferenceRules(Map.of(schema + ".child.child_parent_fk", range(1, 2)));
                var e = assertThrows(DataGenerationException.class, () -> generate(tooMany));
                assertTrue(e.getMessage().contains("unique"), e.getMessage());
                assertEquals(0, count(conn, schema + ".child"));

                var atMostOne = parameters(Strategy.DEFAULT, 5, 5, List.of(schema + ".child"), Map.of());
                atMostOne.setReferenceRules(Map.of(schema + ".child.child_parent_fk", range(0, 1)));
                generate(atMostOne);
                assertEquals(5, count(conn, schema + ".child"));
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void parentAndChildGeneratedInTheSameRunHonourTheRule() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("CREATE TABLE " + schema + ".parent (id serial PRIMARY KEY, name text)");
                sql.execute("CREATE TABLE " + schema + ".child (id serial PRIMARY KEY, parent_id int, "
                        + "CONSTRAINT child_parent_fk FOREIGN KEY (parent_id) REFERENCES " + schema + ".parent (id))");
                var parameters = parameters(Strategy.DEFAULT, 200, 50, List.of(schema + ".parent", schema + ".child"), Map.of());
                parameters.setReferenceRules(Map.of(schema + ".child.child_parent_fk", range(0, 3)));
                generate(parameters);
                assertEquals(200, count(conn, schema + ".parent"));
                assertEquals(200, count(conn, schema + ".child"));
                int[] stats = childStats(sql, schema);
                assertTrue(stats[1] <= 3 && stats[2] < 200, java.util.Arrays.toString(stats));
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void largeParentIsSampledAcrossTheWholeTableNotJustItsStart() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                setUp(sql, schema, 300_000, "UNIQUE");
                sql.execute("ANALYZE " + schema + ".parent");
                generate(parameters(Strategy.DEFAULT, 200, 100, List.of(schema + ".child"), Map.of()));
                try (var rs = sql.executeQuery("SELECT count(DISTINCT parent_id), min(parent_id), max(parent_id) FROM " + schema + ".child")) {
                    rs.next();
                    assertEquals(200, rs.getInt(1));
                    assertTrue(rs.getInt(3) > 150_000, "sample must reach the second half of the parent, max id " + rs.getInt(3));
                    assertTrue(rs.getInt(2) < 150_000, "sample must reach the first half of the parent, min id " + rs.getInt(2));
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
