package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.generator.DataGenerator;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TypeConverterHelperTest {
    @Test
    void recognizesNumericAndTimestampModifiers() {
        for (String type : new String[]{"numeric", "numeric(10,2)", "numeric(3)", "numeric(2,-3)", "numeric(2,4)"}) {
            assertEquals(BigDecimal.class, TypeConverterHelper.dbTypeToJavaClass(type));
            assertNull(TypeConverterHelper.extractMaxLength(type));
        }
        for (int precision = 0; precision <= 6; precision++) {
            for (String zone : new String[]{"", " with time zone", " without time zone"}) {
                assertEquals(Instant.class, TypeConverterHelper.dbTypeToJavaClass("timestamp(" + precision + ")" + zone));
            }
        }
    }

    @Test
    void generatedNumericFitsDeclaredPrecisionAndScale() {
        DataGenerator generator = new DataGenerator(new RuleEnforcer(Map.of()));
        for (String type : new String[]{"numeric(1,0)", "numeric(10,2)", "numeric(2,-3)", "numeric(2,4)"}) {
            Field field = new Field("amount", type);
            for (int i = 0; i < 100; i++) {
                BigDecimal value = (BigDecimal) generator.generateDataForField("public.test", field);
                assertEquals(field.getNumericScale(), value.scale());
                assertTrue(value.unscaledValue().abs().compareTo(field.getNumericLimit()) < 0);
            }
        }
    }

    @Test
    void numericRulesRoundAndRejectOverflow() {
        Field field = new Field("amount", "numeric(3,2)");
        assertEquals(new BigDecimal("1.24"), TypeConverterHelper.numericValue(field, new BigDecimal("1.235")));
        assertThrows(IllegalArgumentException.class, () -> TypeConverterHelper.numericValue(field, new BigDecimal("9.995")));
        assertEquals(new BigDecimal("12.34"), TypeConverterHelper.convertObjectToCorrectType(BigDecimal.class, "12.34"));
        assertEquals(new BigDecimal("3.0"), DataGenerator.generateRandomValuesInRange(new BigDecimal("3.0"), new BigDecimal("3.0")));
    }

    @Test
    void csvDistinguishesNullEmptyAndLiteralNullAndEscapesQuotes() throws Exception {
        Field text = new Field("text", "text");
        assertEquals("", TypeConverterHelper.toCsvValue(text, null));
        assertEquals("\"\"", TypeConverterHelper.toCsvValue(text, ""));
        assertEquals("\"null\"", TypeConverterHelper.toCsvValue(text, "null"));
        assertEquals("\"Привет, \"\"мир\"\"\r\nnext\"", TypeConverterHelper.toCsvValue(text, "Привет, \"мир\"\r\nnext"));
    }

    @Test
    void csvSerializesJsonByteaAndTextArrays() throws Exception {
        Field json = new Field("json", "jsonb");
        assertEquals("\"{\"\"a\"\":1}\"", TypeConverterHelper.toCsvValue(json, Map.of("a", 1)));
        assertEquals(TypeConverterHelper.toCsvValue(json, Map.of("a", 1)), TypeConverterHelper.toCsvValue(json, "{\"a\":1}"));
        Field bytes = new Field("bytes", "bytea");
        assertEquals("\"\\x0001ff\"", TypeConverterHelper.toCsvValue(bytes, new byte[]{0, 1, -1}));
        assertArrayEquals(new byte[]{0, 1, -1}, (byte[]) TypeConverterHelper.convertObjectToCorrectType(byte[].class, "\\x0001ff"));
        Field array = new Field("items", "text[]");
        assertArrayEquals(new String[]{"a,b", null, ""}, (String[]) TypeConverterHelper.convertObjectToCorrectType(String[].class, "[\"a,b\",null,\"\"]"));
        assertEquals("\"{}\"", TypeConverterHelper.toCsvValue(array, new String[]{}));
        String pgArray = "{\"a,b\",\"a\\\"b\",\"c\\\\d\",\"\",NULL,\"NULL\"}";
        assertEquals("\"" + pgArray.replace("\"", "\"\"") + "\"",
                TypeConverterHelper.toCsvValue(array, new String[]{"a,b", "a\"b", "c\\d", "", null, "NULL"}));
    }

    @Test
    void jdbcBindsNullRawBytesAndJsonWithoutJavaSerialization() throws Exception {
        Object[][] lastCall = {null};
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (p, method, args) -> {
                    lastCall[0] = new Object[]{method.getName(), args};
                    return null;
                });
        TypeConverterHelper.setCorrectDbTypeOfObject(statement, 1, new Field("value", "integer"), null, null);
        assertEquals("setNull", lastCall[0][0]);
        assertArrayEquals(new Object[]{1, Types.NULL}, (Object[]) lastCall[0][1]);
        byte[] bytes = {0, -1};
        TypeConverterHelper.setCorrectDbTypeOfObject(statement, 1, new Field("value", "bytea"), bytes, null);
        assertEquals("setBytes", lastCall[0][0]);
        assertSame(bytes, ((Object[]) lastCall[0][1])[1]);
        TypeConverterHelper.setCorrectDbTypeOfObject(statement, 1, new Field("value", "jsonb"), "{\"a\":1}", null);
        PGobject value = (PGobject) ((Object[]) lastCall[0][1])[1];
        assertEquals("jsonb", value.getType());
        assertEquals("{\"a\":1}", value.getValue());
    }
}
