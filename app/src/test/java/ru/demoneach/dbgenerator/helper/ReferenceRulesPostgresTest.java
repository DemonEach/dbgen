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
}
