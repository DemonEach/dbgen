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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class ConfigParser {

    public Parameters parseConfig() {
        Pattern p = Pattern.compile("(.*).(.*)");

        Parameters parameters = tryInitCLIParametersFromFile();

        assert parameters != null;
        if (parameters.isDebug()) {
            Logger rootLogger = (Logger) LoggerFactory.getLogger(ROOT_LOGGER_NAME);
            rootLogger.setLevel(Level.DEBUG);
            log.debug("Debug mode enabled");
        }

        List<String> tablesToGenerate = parameters.getTablesToGenerate();

        if (tablesToGenerate != null || !tablesToGenerate.isEmpty()) {
            for (int i = 0; i < tablesToGenerate.size(); i++) {
                Matcher m = p.matcher(tablesToGenerate.get(i));

                if (!m.find()) {
                    throw new ParametFormatException(
                            "%s incorrect format, try using: \"my-schema\".table или my-schema.table"
                                    .formatted(tablesToGenerate.get(i + 1)));
                }
            }

            log.info("Generation will be completed in tables: {}", tablesToGenerate);
        } else {
            log.warn("Generation will be done in ALL schemas for ALL tables!");
        }

        return parameters;
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
