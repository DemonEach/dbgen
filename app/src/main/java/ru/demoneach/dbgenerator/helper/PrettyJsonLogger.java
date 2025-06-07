package ru.demoneach.dbgenerator.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PrettyJsonLogger {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String asPrettyJsonString(Object obj) throws JsonProcessingException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }

    public static String asDefaultString(Object obj) throws JsonProcessingException {
        return objectMapper.writeValueAsString(obj);
    }
}
