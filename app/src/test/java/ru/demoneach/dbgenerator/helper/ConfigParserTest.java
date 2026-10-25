package ru.demoneach.dbgenerator.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.exception.ConfigParsingException;
import ru.demoneach.dbgenerator.exception.ParametFormatException;

class ConfigParserTest {

    private String originalUserDir;

    @BeforeEach
    void saveUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        System.setProperty("user.dir", originalUserDir);
    }

    private void writeConfig(Path dir, String yaml) throws IOException {
        Files.writeString(dir.resolve("application.yaml"), yaml);
        System.setProperty("user.dir", dir.toAbsolutePath().toString());
    }

    @Test
    void configIsNotBundledAsClasspathResource() {
        // The app must only ever load application.yaml from the filesystem (next to the working
        // directory or next to the jar), never as a packaged classpath resource - otherwise a
        // real application.yaml under src/main/resources would ship its credentials inside the jar.
        assertNull(ConfigParser.class.getResourceAsStream("/application.yaml"));
    }

    @Test
    void parseConfigReadsFromWorkingDirectory(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, """
                batch: 1
                amountOfEntries: 10
                connectionParameters:
                  host: localhost
                  port: 5432
                  username: postgres
                  password: postgres
                  dbName: test_db
                """);

        Parameters parameters = new ConfigParser().parseConfig();

        assertEquals(10, parameters.getAmountOfEntries());
        assertEquals("localhost", parameters.getConnectionParameters().getHost());
        assertEquals("postgres", parameters.getConnectionParameters().getPassword());
    }

    @Test
    void parseConfigThrowsWhenFileIsMissingEverywhere(@TempDir Path tempDir) {
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString());

        assertThrows(ConfigParsingException.class, () -> new ConfigParser().parseConfig());
    }

    @Test
    void parseConfigThrowsOnEmptyFile(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, "");

        assertThrows(ConfigParsingException.class, () -> new ConfigParser().parseConfig());
    }

    @Test
    void parseConfigRejectsNonPositiveBatch(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, """
                batch: 0
                amountOfEntries: 10
                connectionParameters:
                  host: localhost
                """);

        assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig());
    }

    @Test
    void parseConfigRejectsInvalidBatchSave(@TempDir Path tempDir) throws IOException {
        for (String value : new String[]{"0", "-1", "null"}) {
            writeConfig(tempDir, "batchSave: " + value);
            assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig());
        }
    }

    @Test
    void parseConfigUsesDefaultBatchSave(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, "amountOfEntries: 0");
        assertEquals(1000, new ConfigParser().parseConfig().getBatchSave());
    }

    @Test
    void parseConfigRejectsNegativeAmountOfEntries(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, """
                batch: 1
                amountOfEntries: -1
                connectionParameters:
                  host: localhost
                """);

        assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig());
    }

    @Test
    void parseConfigRejectsMalformedTableName(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, """
                batch: 1
                amountOfEntries: 10
                connectionParameters:
                  host: localhost
                tablesToGenerate:
                  - not_a_valid_schema_table_pair
                """);

        assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig());
    }

    @Test
    void normalizesRuleAndLinkNamesAndRejectsAliases(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, """
                tablesToGenerate: ['"My.Schema"."Table"']
                fieldGenerationRules:
                  '"My.Schema"."Table"."a.b"':
                    ruleType: CONST
                    value: ['7']
                customTableLinks:
                  '"My.Schema"."Table"."a.b"': 'public."Parent".id'
                """);
        Parameters parameters = new ConfigParser().parseConfig();
        String key = "\"My.Schema\".Table.\"a.b\"";
        assertEquals(java.util.List.of("7"), parameters.getFieldGenerationRules().get(key).getValue());
        assertEquals("public.Parent.id", parameters.getCustomTableLinks().get(key));
        writeConfig(tempDir, """
                fieldGenerationRules:
                  public.t.id: {ruleType: CONST, value: ['1']}
                  '"public".t.id': {ruleType: CONST, value: ['2']}
                """);
        assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig());
    }

    @Test
    void parseConfigAcceptsQuotedSchemaAndTableName(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, """
                batch: 1
                amountOfEntries: 10
                connectionParameters:
                  host: localhost
                tablesToGenerate:
                  - '"my-schema"."my_table"'
                """);

        Parameters parameters = new ConfigParser().parseConfig();

        assertEquals(java.util.List.of("my-schema.my_table"), parameters.getTablesToGenerate());
    }

    private static final String MINIMAL = """
            amountOfEntries: 10
            connectionParameters:
              host: localhost
              port: 5432
              username: postgres
              password: postgres
              dbName: test_db
            """;

    @Test
    void parsesAndNormalizesReferenceRules(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, MINIMAL + """
                referenceRules:
                  shop.orders.orders_customer_fk:
                    childrenPerParent: [0, 5]
                  '"my-schema"."Order Items".fk':
                    childrenPerParent: [1, 1]
                """);

        var rules = new ConfigParser().parseConfig().getReferenceRules();

        assertEquals(2, rules.size());
        var orders = rules.get("shop.orders.orders_customer_fk");
        assertEquals(0, orders.min());
        assertEquals(5, orders.max());
        assertEquals(1, rules.get("my-schema.\"Order Items\".fk").max());
    }

    @Test
    void referenceRulesAreOptional(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, MINIMAL);

        assertNull(new ConfigParser().parseConfig().getReferenceRules());
    }

    @Test
    void rejectsInvalidReferenceRules(@TempDir Path tempDir) throws IOException {
        for (String range : new String[]{"[1]", "[1, 2, 3]", "[3, 1]", "[-1, 2]", "[0, 0]", "[1, null]"}) {
            writeConfig(tempDir, MINIMAL + "referenceRules:\n  shop.orders.fk:\n    childrenPerParent: " + range + "\n");
            var e = assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig(), range);
            assertTrue(e.getMessage().contains("shop.orders.fk"), e.getMessage());
        }
        writeConfig(tempDir, MINIMAL + "referenceRules:\n  shop.orders.fk: {}\n");
        assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig());
    }

    @Test
    void rejectsMalformedAndDuplicateReferenceRuleKeys(@TempDir Path tempDir) throws IOException {
        writeConfig(tempDir, MINIMAL + "referenceRules:\n  shop.orders:\n    childrenPerParent: [1, 1]\n");
        assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig());

        writeConfig(tempDir, MINIMAL + """
                referenceRules:
                  shop.orders.fk:
                    childrenPerParent: [1, 1]
                  shop."orders".fk:
                    childrenPerParent: [1, 2]
                """);
        assertThrows(ParametFormatException.class, () -> new ConfigParser().parseConfig());
    }
}
