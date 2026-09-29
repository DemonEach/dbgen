package ru.demoneach.dbgenerator.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.postgresql.util.PGobject;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Ignorable;
import ru.demoneach.dbgenerator.entity.SequentialPositive;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TypeConverterHelper {

    public static final String IGNORED = "ignored";

    // character varying(255), character(3), ... - the only types carrying a length limit we care about
    private static final Pattern CHAR_TYPE_WITH_LENGTH =
            Pattern.compile("^(?:character varying|character|varchar|char)\\((\\d+)\\)$");
    private static final Pattern NUMERIC = Pattern.compile("(?:numeric|decimal)(?:\\(\\d+(?:,\\s*-?\\d+)?\\))?");
    private static final Pattern TIMESTAMP = Pattern.compile("timestamp(?:\\([0-6]\\))?(?: with(?:out)? time zone)?");

    public static Object convertObjectToCorrectType(Class clazz, String objectStringRepresentation) {
        if (objectStringRepresentation == null) return null;
        return switch (clazz) {
            case Class c when BigDecimal.class.equals(c) -> new BigDecimal(objectStringRepresentation);
            case Class c when String[].class.equals(c) -> {
                try {
                    yield PrettyJsonLogger.parseStringArray(objectStringRepresentation);
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("text[] rules require a JSON array of strings", e);
                }
            }
            case Class c when byte[].class.equals(c) -> {
                if (!objectStringRepresentation.startsWith("\\x")) {
                    throw new IllegalArgumentException("bytea rules require \\x followed by hexadecimal bytes");
                }
                yield HexFormat.of().parseHex(objectStringRepresentation.substring(2));
            }
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
        if (object == null) {
            preparedStatement.setNull(index, Types.NULL);
            return;
        }
        switch (field.getDbType()) {
            case Class c when String.class.equals(c) -> preparedStatement.setString(index, (String) object);
            case Class c when Integer.class.equals(c) -> preparedStatement.setInt(index, (Integer) object);
            case Class c when Long.class.equals(c) -> preparedStatement.setLong(index, (Long) object);
            case Class c when Boolean.class.equals(c) -> preparedStatement.setBoolean(index, (Boolean) object);
            case Class c when Float.class.equals(c) -> preparedStatement.setFloat(index, (Float) object);
            case Class c when Short.class.equals(c) -> preparedStatement.setShort(index, (Short) object);
            case Class c when Instant.class.equals(c) ->
                    preparedStatement.setTimestamp(index, Timestamp.from((Instant) object));
            case Class c when BigDecimal.class.equals(c) -> preparedStatement.setBigDecimal(index, numericValue(field, (BigDecimal) object));
            case Class c when UUID.class.equals(c) -> preparedStatement.setObject(index, object);
            case Class c when byte[].class.equals(c) -> preparedStatement.setBytes(index, (byte[]) object);
            case Class c when Map.class.equals(c) -> {
                PGobject jsonObject = new PGobject();
                jsonObject.setType("jsonb");
                jsonObject.setValue(jsonValue(object));
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

    /**
     * Extracts the declared length of a character type, i.e. 255 out of "character varying(255)".
     *
     * @return the declared length, or null if the type carries no length limit
     */
    public static Integer extractMaxLength(String dbType) {
        if (dbType == null || dbType.isEmpty()) {
            return null;
        }

        Matcher matcher = CHAR_TYPE_WITH_LENGTH.matcher(dbType);

        return matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
    }

    public static BigDecimal numericValue(Field field, BigDecimal value) {
        if (field.getNumericLimit() == null) return value;
        BigDecimal rounded = value.setScale(field.getNumericScale(), RoundingMode.HALF_UP);
        if (rounded.unscaledValue().abs().compareTo(field.getNumericLimit()) >= 0) {
            throw new IllegalArgumentException("numeric value exceeds precision for field " + field.getName());
        }
        return rounded;
    }

    private static String jsonValue(Object value) throws JsonProcessingException {
        // Rules contain JSON text; generated POJOs/maps must be serialized instead.
        if (value instanceof PGobject pg) return pg.getValue();
        if (value instanceof String text) return text;
        return PrettyJsonLogger.asDefaultString(value);
    }

    public static String toCsvValue(Field field, Object value) throws SQLException, JsonProcessingException {
        if (value == null) return ""; // COPY CSV: unquoted empty field is SQL NULL.
        String text;
        if (field.getDbType().equals(Map.class)) {
            text = jsonValue(value);
        } else if (value instanceof byte[] bytes) {
            text = "\\x" + HexFormat.of().formatHex(bytes);
        } else if (field.getDbType().equals(String[].class)) {
            Object[] elements = value instanceof Array array ? (Object[]) array.getArray() : (Object[]) value;
            StringJoiner result = new StringJoiner(",", "{", "}");
            for (Object element : elements) {
                result.add(element == null ? "NULL" : "\"" + element.toString()
                        .replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
            text = result.toString();
        } else if (value instanceof BigDecimal decimal) {
            text = numericValue(field, decimal).toPlainString();
        } else {
            text = value.toString();
        }
        return text == null ? "" : "\"" + text.replace("\"", "\"\"") + "\"";
    }

    /**
     * Mapping accourding to https://www.postgresql.org/docs/current/datatype.html
     * </p>
     * There is another data types that being used quite rarely so at the moment they are not supported
     */
    public static Class<?> dbTypeToJavaClass(String dbType) {
        if (NUMERIC.matcher(dbType).matches()) return BigDecimal.class;
        if (TIMESTAMP.matcher(dbType).matches()) return Instant.class;
        if (CHAR_TYPE_WITH_LENGTH.matcher(dbType).matches()) {
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
            case "serial", "smallserial", "bigserial" -> SequentialPositive.class;
            default -> Ignorable.class;
        };
    }
}
