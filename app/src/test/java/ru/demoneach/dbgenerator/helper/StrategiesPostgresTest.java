package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.demoneach.dbgenerator.entity.Rule;
import ru.demoneach.dbgenerator.entity.RuleType;
import ru.demoneach.dbgenerator.entity.Strategy;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static ru.demoneach.dbgenerator.helper.PostgresTestSupport.*;

/** Round-trip, batching and rollback checks of all three strategies against a real PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "DBGEN_TEST_DATABASE", matches = ".+")
class StrategiesPostgresTest {

    private static Rule constant(String value) {
        return new Rule(RuleType.CONST, java.util.Collections.singletonList(value));
    }

    @Test
    void complexTypesRoundTripIdenticallyThroughEveryStrategy() throws Exception {
        String schema = newSchemaName();
        String tricky = "O'Neil, \"quoted\" \\ back\nnew line, ünïcode ✓";
        try (Connection conn = open(); var sql = conn.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".typed (num numeric(10,2), txt text, doc jsonb, raw bytea, "
                        + "tags text[], empty_tags text[], nothing text, ts timestamptz, small smallint, big bigint)");
                Map<String, Rule> rules = new LinkedHashMap<>();
                String prefix = schema + ".typed.";
                rules.put(prefix + "num", constant("12345.675"));
                rules.put(prefix + "txt", constant(tricky));
                rules.put(prefix + "doc", constant("{\"a\": [1, 2, {\"b\": \"x,y\"}], \"s\": \"q\\\"uote\"}"));
                rules.put(prefix + "raw", constant("\\x0001ff2c22"));
                rules.put(prefix + "tags", constant("[\"a,b\", null, \"\", \"c\\\"d\", \"e\\\\f\"]"));
                rules.put(prefix + "empty_tags", constant("[]"));
                rules.put(prefix + "nothing", constant(null));
                rules.put(prefix + "ts", constant("2026-03-01T10:15:30.123456Z"));
                rules.put(prefix + "small", new Rule(RuleType.LIST, List.of("7")));
                rules.put(prefix + "big", new Rule(RuleType.RANGE, List.of("5", "6")));

                List<String> snapshots = new ArrayList<>();
                for (Strategy strategy : Strategy.values()) {
                    sql.execute("TRUNCATE " + schema + ".typed");
                    // 5 rows with batch 2: two full batches and a remainder
                    generate(parameters(strategy, 5, 2, List.of(schema + ".typed"), rules));
                    assertEquals(5, count(conn, schema + ".typed"), strategy.name());
                    try (var rs = sql.executeQuery("SELECT DISTINCT num::text, txt, doc::text, encode(raw,'hex'), tags::text, "
                            + "empty_tags::text, nothing IS NULL, extract(epoch from ts)::text, small, big FROM " + schema + ".typed")) {
                        assertTrue(rs.next(), strategy.name());
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= 10; i++) row.append(rs.getString(i)).append('|');
                        assertFalse(rs.next(), strategy + ": all rows must be identical");
                        snapshots.add(row.toString());
                    }
                }
                // absolute expectations on the first strategy, equality for the rest
                String first = snapshots.get(0);
                assertTrue(first.startsWith("12345.68|" + tricky + "|"), first);
                assertTrue(first.contains("|0001ff2c22|"), first);
                assertTrue(first.contains("{\"a,b\",NULL,\"\",\"c\\\"d\",\"e\\\\f\"}"), first);
                assertTrue(first.contains("|{}|t|1772360130.123456|7|5|"), first);
                for (int i = 1; i < snapshots.size(); i++) {
                    assertEquals(first, snapshots.get(i), "strategy " + Strategy.values()[i] + " differs from " + Strategy.values()[0]);
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void remainderAndRepeatedRunsInsertExactlyTheRequestedRows() throws Exception {
        String schema = newSchemaName();
        try (Connection conn = open(); var sql = conn.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".t (v int)");
                Map<String, Rule> rules = Map.of(schema + ".t.v", constant("1"));
                long expected = 0;
                for (Strategy strategy : Strategy.values()) {
                    for (int[] shape : new int[][]{{7, 3}, {6, 3}, {1, 5}, {0, 3}}) {
                        generate(parameters(strategy, shape[0], shape[1], List.of(schema + ".t"), rules));
                        expected += shape[0];
                        assertEquals(expected, count(conn, schema + ".t"), strategy + " rows=" + shape[0] + " batch=" + shape[1]);
                    }
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void failureInTheMiddleRollsBackEverythingAndKeepsExistingRows() throws Exception {
        String schema = newSchemaName();
        try (Connection conn = open(); var sql = conn.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                // ok_table is valid; unique_table fails from the 2nd row on (duplicate CONST value)
                sql.execute("CREATE TABLE " + schema + ".ok_table (v int)");
                sql.execute("CREATE TABLE " + schema + ".unique_table (v int UNIQUE)");
                sql.execute("INSERT INTO " + schema + ".ok_table VALUES (99)");
                sql.execute("INSERT INTO " + schema + ".unique_table VALUES (100)");
                Map<String, Rule> rules = Map.of(
                        schema + ".ok_table.v", constant("1"),
                        schema + ".unique_table.v", constant("5"));
                for (Strategy strategy : Strategy.values()) {
                    // batch 1 for DEFAULT/MULTI means the failing row is not in the first statement
                    var parameters = parameters(strategy, 4, 1, List.of(schema + ".ok_table", schema + ".unique_table"), rules);
                    assertThrows(Exception.class, () -> generate(parameters), strategy.name());
                    assertEquals(1, count(conn, schema + ".ok_table"), strategy + ": ok_table must be rolled back");
                    assertEquals(1, count(conn, schema + ".unique_table"), strategy + ": unique_table must be rolled back");
                    try (var rs = sql.executeQuery("SELECT v FROM " + schema + ".ok_table")) {
                        assertTrue(rs.next());
                        assertEquals(99, rs.getInt(1), strategy.name());
                    }
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
