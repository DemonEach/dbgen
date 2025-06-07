package ru.demoneach.dbgenerator.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.postgresql.util.PGobject;
import ru.demoneach.dbgenerator.entity.Field;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

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
        if (field.getDbType().equals(String.class)) {
            preparedStatement.setString(index, (String) object);
        } else if (field.getDbType().equals(Integer.class)) {
            preparedStatement.setInt(index, (Integer) object);
        } else if (field.getDbType().equals(Long.class)) {
            preparedStatement.setLong(index, (Long) object);
        } else if (field.getDbType().equals(Boolean.class)) {
            preparedStatement.setBoolean(index, (Boolean) object);
        } else if (field.getDbType().equals(Float.class)) {
            preparedStatement.setFloat(index, (Float) object);
        } else if (field.getDbType().equals(Short.class)) {
            preparedStatement.setShort(index, (Short) object);
        } else if (field.getDbType().equals(Instant.class)) {
            preparedStatement.setTimestamp(index, Timestamp.from((Instant) object));
        } else if (field.getDbType().equals(BigDecimal.class)) {
            preparedStatement.setBigDecimal(index, (BigDecimal) object);
        } else if (field.getDbType().equals(UUID.class)) {
            preparedStatement.setObject(index, object);
        } else if (field.getDbType().equals(LocalTime.class)) {
            preparedStatement.setTime(index, (Time) object);
        } else if (field.getDbType().equals(Map.class)) {
            PGobject jsonObject = new PGobject();
            jsonObject.setType("jsonb");
            jsonObject.setValue(PrettyJsonLogger.asDefaultString(object));
            preparedStatement.setObject(index, jsonObject);
        } else if (field.getDbType().equals(String[].class)) {
            // don't like that I need connection to create a simple array, seems stupid
            Array arr = conn.createArrayOf("text", (String[]) object);
            preparedStatement.setArray(index, arr);
        } else if (field.getDbType().equals(byte[].class)) {
            preparedStatement.setBytes(index, convertObjectToBytes(object));
        } else {
            preparedStatement.setObject(index, object.toString());
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
}
