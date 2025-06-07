package ru.demoneach.dbgenerator.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.postgresql.util.PGobject;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Ignorable;
import ru.demoneach.dbgenerator.entity.Strategy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class TypeConverterHelper {

    public static Object convertObjectToCorrectType(Class clazz, String objectStringRepresentation) {
        return switch (clazz) {
            case Class c when Integer.class.equals(c) -> Integer.parseInt(objectStringRepresentation);
            case Class c when Long.class.equals(c) -> Long.parseLong(objectStringRepresentation);
            case Class c when Double.class.equals(c) -> Double.parseDouble(objectStringRepresentation);
            case Class c when Short.class.equals(c) -> Short.parseShort(objectStringRepresentation);
            case Class c when Instant.class.equals(c) -> Instant.parse(objectStringRepresentation);
            case Class c when Float.class.equals(c) -> Float.parseFloat(objectStringRepresentation);
            case Class c when UUID.class.equals(c) -> UUID.fromString(objectStringRepresentation);
            case Class c when Boolean.class.equals(c) -> Boolean.valueOf(objectStringRepresentation);
            default -> objectStringRepresentation;
        };
    }

    public static void setCorrectDbTypeOfObject(PreparedStatement preparedStatement, Integer index, Field field, Object object, Connection conn) throws SQLException, JsonProcessingException {
        switch (field.getDbType()) {
            case Class c when String.class.equals(c) -> preparedStatement.setString(index, (String) object);
            case Class c when Integer.class.equals(c) -> preparedStatement.setInt(index, (Integer) object);
            case Class c when Long.class.equals(c) -> preparedStatement.setLong(index, (Long) object);
            case Class c when Boolean.class.equals(c) -> preparedStatement.setBoolean(index, (Boolean) object);
            case Class c when Float.class.equals(c) -> preparedStatement.setFloat(index, (Float) object);
            case Class c when Short.class.equals(c) -> preparedStatement.setShort(index, (Short) object);
            case Class c when Instant.class.equals(c) -> preparedStatement.setTimestamp(index, Timestamp.from((Instant) object));
            case Class c when BigDecimal.class.equals(c) -> preparedStatement.setBigDecimal(index, (BigDecimal) object);
            case Class c when UUID.class.equals(c) -> preparedStatement.setObject(index, object);
            case Class c when byte[].class.equals(c) -> preparedStatement.setBytes(index, convertObjectToBytes(object));
            case Class c when Map.class.equals(c) -> {
                PGobject jsonObject = new PGobject();
                jsonObject.setType("jsonb");
                jsonObject.setValue(PrettyJsonLogger.asDefaultString(object));
                preparedStatement.setObject(index, jsonObject);
            }
            case Class c when String[].class.equals(c) -> {
                // don't like that I need connection to create a simple array, seems stupid
                Array arr = conn.createArrayOf("text", (String[]) object);
                preparedStatement.setArray(index, arr);
            }
            default -> preparedStatement.setObject(index, object.toString());
        }
    }

    private static byte[] convertObjectToBytes(Object object) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(object);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Mapping accourding to https://www.postgresql.org/docs/current/datatype.html
     * </p>
     * There is another data types that being used quite rarely so at the moment they are not supported
     */
    public static Class<?> dbTypeToJavaClass(String dbType) {
        Pattern pattern = Pattern.compile("(character varying|character)\\(\\d+\\)");

        if(pattern.matcher(dbType).matches()) {
            return String.class;
        }

        return switch (dbType) {
            case "uuid" -> UUID.class;
            case "bigint" -> Long.class;
            case "boolean" -> Boolean.class;
            case "bytea" -> byte[].class;
            case "text[]", "_text" -> String[].class;
            case "jsonb" -> Map.class;
            case "hstore" -> HashMap.class;
            case "character", "character varying", "text" -> String.class;
            case "money", "numeric", "double precision" -> BigDecimal.class;
            case "real" -> Float.class;
            case "integer" -> Integer.class;
            case "timestamp", "date",
                 "timestamp with time zone",
                 "timestamp(6) with time zone",
                 "timestamp without time zone",
                 "timestamp(6) without time zone" -> Instant.class;
            case "smallint" -> Short.class;
            case "time" -> LocalTime.class;
            case "serial", "smallserial", "bigserial" -> Ignorable.class;
            default -> String.class;
        };
    }
}
