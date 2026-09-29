package ru.demoneach.dbgenerator.helper;

import static org.slf4j.Logger.ROOT_LOGGER_NAME;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import lombok.extern.slf4j.Slf4j;

import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.exception.ConfigParsingException;
import ru.demoneach.dbgenerator.exception.ParametFormatException;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import ru.demoneach.dbgenerator.entity.Rule;



@Slf4j
public class ConfigParser {

    public Parameters parseConfig() {
        Parameters parameters = tryInitCLIParametersFromFile();

        // an empty yaml file is parsed into null by snakeyaml
        if (parameters == null) {
            throw new ConfigParsingException("Config file is empty, nothing to generate");
        }

        if (parameters.isDebug()) {
            Logger rootLogger = (Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME);
            rootLogger.setLevel(Level.DEBUG);
            log.debug("Debug mode enabled");
        }

        validateBatchParameters(parameters);

        List<String> tablesToGenerate = parameters.getTablesToGenerate();

        if (tablesToGenerate != null && !tablesToGenerate.isEmpty()) {
            parameters.setTablesToGenerate(tablesToGenerate.stream().map(name -> SqlIdentifiers.normalize(name, 2)).toList());

            log.info("Generation will be completed in tables: {}", tablesToGenerate);
        } else {
            log.warn("Generation will be done in ALL schemas for ALL tables!");
        }

        if (parameters.getFieldGenerationRules() != null) {
            Map<String, Rule> rules = new LinkedHashMap<>();
            parameters.getFieldGenerationRules().forEach((key, rule) -> {
                String normalized = SqlIdentifiers.normalize(key, 3);
                if (rules.containsKey(normalized)) throw new ParametFormatException("Duplicate rule: " + key);
                rules.put(normalized, rule);
            });
            parameters.setFieldGenerationRules(rules);
        }
        if (parameters.getCustomTableLinks() != null) {
            Map<String, String> links = new LinkedHashMap<>();
            parameters.getCustomTableLinks().forEach((key, value) -> {
                String normalized = SqlIdentifiers.normalize(key, 3);
                if (links.containsKey(normalized)) throw new ParametFormatException("Duplicate link: " + key);
                links.put(normalized, SqlIdentifiers.normalize(value, 3));
            });
            parameters.setCustomTableLinks(links);
        }
        return parameters;
    }

    private void validateBatchParameters(Parameters parameters) {
        Integer batch = parameters.getBatch();

        if (batch == null || batch < 1) {
            throw new ParametFormatException(
                    "batch must be a positive number, but was: %s".formatted(batch));
        }

        Integer batchSave = parameters.getBatchSave();
        if (batchSave == null || batchSave < 1) {
            throw new ParametFormatException(
                    "batchSave must be a positive number, but was: %s".formatted(batchSave));
        }

        Integer amountOfEntries = parameters.getAmountOfEntries();

        if (amountOfEntries == null || amountOfEntries < 0) {
            throw new ParametFormatException(
                    "amountOfEntries must not be negative, but was: %s".formatted(amountOfEntries));
        }
    }

    private Parameters tryInitCLIParametersFromFile() {
        String fileName = "application.yaml";

        File currentDirFile = new File(System.getProperty("user.dir"), fileName);
        try (InputStream inputStream = new FileInputStream(currentDirFile)) {
            Yaml yaml = new Yaml();
            return yaml.loadAs(inputStream, Parameters.class);
        } catch (IOException ioe) {
            log.warn(
                    "Cannot load config from current directory ({}), trying jar directory",
                    currentDirFile.getAbsolutePath());
        }

        try {
            URL url = ConfigParser.class.getProtectionDomain().getCodeSource().getLocation();
            File jarFile = new File(url.toURI());
            String jarDirectory =
                    jarFile.isFile()
                            ? jarFile.getParentFile().getAbsolutePath()
                            : jarFile.getAbsolutePath();
            File jarDirFile = new File(jarDirectory, fileName);

            try (InputStream inputStream = new FileInputStream(jarDirFile)) {
                Yaml yaml = new Yaml();
                return yaml.loadAs(inputStream, Parameters.class);
            } catch (IOException ioe) {
                throw new ConfigParsingException(
                        "Cannot open file %s, maybe it doesn't exists"
                                .formatted(jarDirFile.getAbsolutePath()));
            }
        } catch (URISyntaxException e) {
            throw new ConfigParsingException(
                    "Cannot determine jar location: %s".formatted(e.getMessage()));
        }
    }
}
