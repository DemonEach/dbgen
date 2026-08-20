package ru.demoneach.dbgenerator.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        assertEquals(1, parameters.getTablesToGenerate().size());
    }
}
