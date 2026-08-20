package ru.demoneach.dbgenerator.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConnectionParametersTest {

    private ConnectionParameters withPassword(String password) {
        ConnectionParameters parameters = new ConnectionParameters();
        parameters.setHost("localhost");
        parameters.setPort("5432");
        parameters.setUsername("postgres");
        parameters.setPassword(password);
        parameters.setDbName("db");
        return parameters;
    }

    @Test
    void toStringMasksPassword() {
        String result = withPassword("s3cr3t").toString();

        assertFalse(result.contains("s3cr3t"));
        assertTrue(result.contains("password=****"));
    }

    @Test
    void toStringKeepsOtherFieldsVisible() {
        String result = withPassword("s3cr3t").toString();

        assertTrue(result.contains("host=localhost"));
        assertTrue(result.contains("port=5432"));
        assertTrue(result.contains("userName=postgres"));
        assertTrue(result.contains("dbName=db"));
    }

    @Test
    void toStringHandlesNullPassword() {
        String result = withPassword(null).toString();

        assertTrue(result.contains("password=null"));
    }

    @Test
    void equalsAndHashCodeIgnoreNothingAndStayConsistent() {
        ConnectionParameters first = withPassword("s3cr3t");
        ConnectionParameters second = withPassword("s3cr3t");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
