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

/**
 * Two services living in different schemas of one database, joined only by customTableLinks
 * (no foreign key constraint): several orders per client through a referenceRules entry.
 */
@EnabledIfEnvironmentVariable(named = "DBGEN_TEST_DATABASE", matches = ".+")
class CustomLinkReferenceRulesPostgresTest {

    private static ReferenceRule range(int min, int max) {
        return new ReferenceRule(List.of(min, max));
    }

    /** "order" is a reserved word, so the table also checks identifier quoting; 20 existing clients */
    private static void createServices(Statement sql, String clientSchema, String orderSchema, String orderColumnExtra) throws Exception {
        sql.execute("CREATE SCHEMA " + clientSchema);
        sql.execute("CREATE SCHEMA " + orderSchema);
        sql.execute("CREATE TABLE " + clientSchema + ".client (id int PRIMARY KEY)");
        sql.execute("INSERT INTO " + clientSchema + ".client SELECT generate_series(1, 20)");
        sql.execute("CREATE TABLE " + orderSchema + ".\"order\" (id serial PRIMARY KEY, client_id int" + orderColumnExtra + ")");
    }

    private static void dropServices(Statement sql, String clientSchema, String orderSchema) throws Exception {
        sql.execute("DROP SCHEMA IF EXISTS " + clientSchema + " CASCADE");
        sql.execute("DROP SCHEMA IF EXISTS " + orderSchema + " CASCADE");
    }

    /** {min, max, clients that have any, orders whose client does not exist} */
    private static int[] stats(Statement sql, String clientSchema, String orderSchema) throws Exception {
        try (var rs = sql.executeQuery("SELECT coalesce(min(n),0), coalesce(max(n),0), count(*) FROM "
                + "(SELECT count(*) n FROM " + orderSchema + ".\"order\" GROUP BY client_id) q")) {
            rs.next();
            int[] result = {rs.getInt(1), rs.getInt(2), rs.getInt(3), 0};
            try (var orphans = sql.executeQuery("SELECT count(*) FROM " + orderSchema + ".\"order\" o LEFT JOIN "
                    + clientSchema + ".client c ON c.id = o.client_id WHERE c.id IS NULL")) {
                orphans.next();
                result[3] = orphans.getInt(1);
            }
            return result;
        }
    }

    private static void link(ru.demoneach.dbgenerator.entity.Parameters parameters, String clientSchema, String orderSchema) {
        parameters.setCustomTableLinks(Map.of(orderSchema + ".order.client_id", clientSchema + ".client.id"));
    }

    @Test
    void severalOrdersPerClientAcrossSchemasWithoutAForeignKey() throws Exception {
        String clients = newSchemaName();
        String orders = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createServices(sql, clients, orders, "");
                for (Strategy strategy : Strategy.values()) {
                    sql.execute("TRUNCATE " + orders + ".\"order\"");
                    var parameters = parameters(strategy, 60, 25, List.of(orders + ".order"), Map.of());
                    link(parameters, clients, orders);
                    parameters.setReferenceRules(Map.of(orders + ".order.client_id", range(1, 5)));
                    generate(parameters);

                    assertEquals(60, count(conn, orders + ".\"order\""), strategy.name());
                    int[] stats = stats(sql, clients, orders);
                    assertEquals(0, stats[3], strategy + ": every order must point to an existing client");
                    assertTrue(stats[0] >= 1 && stats[1] <= 5, strategy + " per client " + List.of(stats[0], stats[1]));
                    assertTrue(stats[2] >= 12, strategy + ": at least 12 clients are needed for 60 orders at max 5");
                }
            } finally {
                dropServices(sql, clients, orders);
            }
        }
    }

    @Test
    void withoutARuleACustomLinkStillGivesOneOrderPerClient() throws Exception {
        String clients = newSchemaName();
        String orders = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createServices(sql, clients, orders, "");
                var parameters = parameters(Strategy.DEFAULT, 15, 10, List.of(orders + ".order"), Map.of());
                link(parameters, clients, orders);
                generate(parameters);
                int[] stats = stats(sql, clients, orders);
                assertEquals(List.of(1, 1, 15, 0), List.of(stats[0], stats[1], stats[2], stats[3]));
            } finally {
                dropServices(sql, clients, orders);
            }
        }
    }

    @Test
    void ruleKeyThatIsNeitherAConstraintNorACustomLinkIsRejected() throws Exception {
        String clients = newSchemaName();
        String orders = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createServices(sql, clients, orders, "");
                var parameters = parameters(Strategy.DEFAULT, 5, 5, List.of(orders + ".order"), Map.of());
                link(parameters, clients, orders);
                parameters.setReferenceRules(Map.of(orders + ".order.customer_id", range(1, 2)));
                var e = assertThrows(DataGenerationException.class, () -> generate(parameters));
                assertTrue(e.getMessage().contains("customer_id") && e.getMessage().contains("customTableLinks"), e.getMessage());
                assertEquals(0, count(conn, orders + ".\"order\""));
            } finally {
                dropServices(sql, clients, orders);
            }
        }
    }

    @Test
    void severalOrdersPerClientAreRejectedWhenTheOrderColumnIsUnique() throws Exception {
        String clients = newSchemaName();
        String orders = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createServices(sql, clients, orders, " UNIQUE");
                var parameters = parameters(Strategy.DEFAULT, 5, 5, List.of(orders + ".order"), Map.of());
                link(parameters, clients, orders);
                parameters.setReferenceRules(Map.of(orders + ".order.client_id", range(1, 2)));
                var e = assertThrows(DataGenerationException.class, () -> generate(parameters));
                assertTrue(e.getMessage().contains("unique"), e.getMessage());
                assertEquals(0, count(conn, orders + ".\"order\""));
            } finally {
                dropServices(sql, clients, orders);
            }
        }
    }

    @Test
    void aKeyNamingBothAConstraintAndACustomLinkColumnIsAmbiguous() throws Exception {
        String clients = newSchemaName();
        String orders = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createServices(sql, clients, orders, ", CONSTRAINT client_id FOREIGN KEY (client_id) REFERENCES "
                        + clients + ".client (id)");
                var parameters = parameters(Strategy.DEFAULT, 5, 5, List.of(orders + ".order"), Map.of());
                link(parameters, clients, orders);
                parameters.setReferenceRules(Map.of(orders + ".order.client_id", range(1, 2)));
                var e = assertThrows(DataGenerationException.class, () -> generate(parameters));
                assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
                assertEquals(0, count(conn, orders + ".\"order\""));
            } finally {
                dropServices(sql, clients, orders);
            }
        }
    }

    @Test
    void aCustomLinkRepeatingARealKeyAcceptsTheRuleByColumnOrByConstraintButNotBoth() throws Exception {
        String clients = newSchemaName();
        String orders = newSchemaName();
        try (var conn = open(); var sql = conn.createStatement()) {
            try {
                createServices(sql, clients, orders, ", CONSTRAINT order_client_fk FOREIGN KEY (client_id) REFERENCES "
                        + clients + ".client (id)");
                for (String key : List.of(orders + ".order.client_id", orders + ".order.order_client_fk")) {
                    sql.execute("TRUNCATE " + orders + ".\"order\"");
                    var parameters = parameters(Strategy.DEFAULT, 60, 25, List.of(orders + ".order"), Map.of());
                    link(parameters, clients, orders);
                    parameters.setReferenceRules(Map.of(key, range(3, 3)));
                    generate(parameters);
                    int[] stats = stats(sql, clients, orders);
                    assertEquals(List.of(3, 3, 20), List.of(stats[0], stats[1], stats[2]), key);
                }

                var both = parameters(Strategy.DEFAULT, 60, 25, List.of(orders + ".order"), Map.of());
                link(both, clients, orders);
                both.setReferenceRules(Map.of(
                        orders + ".order.client_id", range(3, 3),
                        orders + ".order.order_client_fk", range(1, 5)));
                sql.execute("TRUNCATE " + orders + ".\"order\"");
                var e = assertThrows(DataGenerationException.class, () -> generate(both));
                assertTrue(e.getMessage().contains("same foreign key"), e.getMessage());
                assertEquals(0, count(conn, orders + ".\"order\""));
            } finally {
                dropServices(sql, clients, orders);
            }
        }
    }
}
