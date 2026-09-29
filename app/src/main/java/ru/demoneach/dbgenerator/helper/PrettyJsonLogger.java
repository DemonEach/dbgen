package ru.demoneach.dbgenerator.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class PrettyJsonLogger {
    // registered for java.time types: generated JSONB values (e.g. DummyObject) carry Instant fields
    private static final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public static String asPrettyJsonString(Object obj) throws JsonProcessingException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }

    public static String asDefaultString(Object obj) throws JsonProcessingException {
        return objectMapper.writeValueAsString(obj);
    }

    public static String[] parseStringArray(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, String[].class);
    }
}
