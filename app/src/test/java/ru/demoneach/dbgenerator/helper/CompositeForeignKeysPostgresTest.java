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

/** Foreign keys over several columns: a child row must take all columns from one parent row. */
@EnabledIfEnvironmentVariable(named = "DBGEN_TEST_DATABASE", matches = ".+")
class CompositeForeignKeysPostgresTest {

    private static ReferenceRule range(int min, int max) {
        return new ReferenceRule(List.of(min, max));
    }

    // 20 orders x 3 lines = 60 existing parent rows
    private static void createOrderItemsAndShipments(Statement sql, String schema, String shipmentsExtra) throws Exception {
        sql.execute("CREATE SCHEMA " + schema);
        sql.execute("CREATE TABLE " + schema + ".order_items (order_id int, line_no int, PRIMARY KEY (order_id, line_no))");
        sql.execute("INSERT INTO " + schema + ".order_items SELECT o, l FROM generate_series(1, 20) o, generate_series(1, 3) l");
        sql.execute("CREATE TABLE " + schema + ".shipments (id serial PRIMARY KEY, order_id int, line_no int" + shipmentsExtra
                + ", CONSTRAINT shipments_item_fk FOREIGN KEY (order_id, line_no) REFERENCES " + schema + ".order_items (order_id, line_no))");
    }

    /** {min, max, number of distinct (order_id, line_no) pairs} of shipments per pair */
    private static int[] perPair(Statement sql, String schema) throws Exception {
        try (var rs = sql.executeQuery("SELECT coalesce(min(n),0), coalesce(max(n),0), count(*) FROM "
                + "(SELECT count(*) n FROM " + schema + ".shipments GROUP BY order_id, line_no) q")) {
            rs.next();
            return new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3)};
        }
    }

    @Test
    void everyChildRowTakesAllColumnsFromOneExistingParentRow() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createOrderItemsAndShipments(sql, schema, "");
                for (Strategy strategy : Strategy.values()) {
                    sql.execute("TRUNCATE " + schema + ".shipments");
                    // the foreign key itself makes PostgreSQL reject a pair that is not a parent row
                    generate(parameters(strategy, 40, 15, List.of(schema + ".shipments"), Map.of()));
                    assertEquals(40, count(conn, schema + ".shipments"), strategy.name());
                    assertEquals(List.of(1, 1, 40), List.of(perPair(sql, schema)[0], perPair(sql, schema)[1], perPair(sql, schema)[2]),
                            strategy + ": default is one child per parent row");
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void childrenPerParentRuleAppliesToTheWholeCompositeKey() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createOrderItemsAndShipments(sql, schema, "");
                for (Strategy strategy : Strategy.values()) {
                    sql.execute("TRUNCATE " + schema + ".shipments");
                    var parameters = parameters(strategy, 100, 30, List.of(schema + ".shipments"), Map.of());
                    parameters.setReferenceRules(Map.of(schema + ".shipments.shipments_item_fk", range(0, 3)));
                    generate(parameters);

                    assertEquals(100, count(conn, schema + ".shipments"), strategy.name());
                    int[] pairs = perPair(sql, schema);
                    assertTrue(pairs[1] <= 3 && pairs[0] >= 1, strategy + " " + List.of(pairs[0], pairs[1]));
                    assertTrue(pairs[2] >= 34, strategy + ": at least 34 of the 60 parent rows are needed for 100 children");
                }
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void parentAndChildWithCompositeKeyAreGeneratedInOneRun() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("CREATE TABLE " + schema + ".order_items (order_id int, line_no int, PRIMARY KEY (order_id, line_no))");
                sql.execute("CREATE TABLE " + schema + ".shipments (id serial PRIMARY KEY, order_id int, line_no int, "
                        + "FOREIGN KEY (order_id, line_no) REFERENCES " + schema + ".order_items (order_id, line_no))");
                generate(parameters(Strategy.DEFAULT, 50, 20, List.of(schema + ".shipments", schema + ".order_items"), Map.of()));
                assertEquals(50, count(conn, schema + ".order_items"));
                assertEquals(50, count(conn, schema + ".shipments"));
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void severalChildrenPerParentAreRejectedWhenAnyPartOfTheKeyIsUnique() throws Exception {
        for (String unique : List.of(", UNIQUE (order_id, line_no)", ", UNIQUE (order_id)")) {
            String schema = newSchemaName();
            try (var conn = open(); var sql = conn.createStatement()) {
                try {
                    createOrderItemsAndShipments(sql, schema, unique);
                    var parameters = parameters(Strategy.DEFAULT, 5, 5, List.of(schema + ".shipments"), Map.of());
                    parameters.setReferenceRules(Map.of(schema + ".shipments.shipments_item_fk", range(1, 2)));
                    var e = assertThrows(DataGenerationException.class, () -> generate(parameters), unique);
                    assertTrue(e.getMessage().contains("unique"), e.getMessage());
                    assertEquals(0, count(conn, schema + ".shipments"));
                } finally {
                    sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }

    @Test
    void overlappingKeysAreRejectedBeforeAnythingIsWritten() throws Exception {
        String schema = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createOrderItemsAndShipments(sql, schema, "");
                sql.execute("CREATE TABLE " + schema + ".orders (id int PRIMARY KEY)");
                sql.execute("ALTER TABLE " + schema + ".shipments ADD CONSTRAINT shipments_order_fk "
                        + "FOREIGN KEY (order_id) REFERENCES " + schema + ".orders (id)");
                var parameters = parameters(Strategy.DEFAULT, 5, 5, List.of(schema + ".shipments"), Map.of());
                var e = assertThrows(DataGenerationException.class, () -> generate(parameters));
                assertTrue(e.getMessage().contains("order_id") && e.getMessage().contains("shipments_item_fk")
                        && e.getMessage().contains("shipments_order_fk"), e.getMessage());
                assertEquals(0, count(conn, schema + ".shipments"));
            } finally {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
