package ru.demoneach.dbgenerator.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class Field {
    private String name;
    private Class<?> dbType;
    private Integer maxLength;

    public Field(String name, String dbType) {
        this.name = name;
        this.dbType = dbTypeToJavaClass(dbType);
        this.maxLength = extractMaxLength(dbType);
    } 

    private Integer extractMaxLength(String dbType) {
        if (dbType == null) {
            return null;
        }

        if (dbType.isEmpty()) {
            return null;
        }

        dbType = dbType.replaceAll("[^\\d.]", "");

        if (dbType.isEmpty()) {
            return null;
        }

        return Integer.valueOf(dbType);
    }

    /**
     * Маппинг на основании статьи {@link https://www.postgresql.org/docs/current/datatype.html}
     * </p>
     * Есть и другие типы данных, но пока они не поддерживаются, так как очень редко используются 
     */
    private Class<?> dbTypeToJavaClass(String dbType) {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Class<?> getDbType() {
        return dbType;
    }

    public void setDbType(Class<?> dbType) {
        this.dbType = dbType;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        result = prime * result + ((dbType == null) ? 0 : dbType.hashCode());
        result = prime * result + ((maxLength == null) ? 0 : maxLength.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        Field other = (Field) obj;
        if (name == null) {
            if (other.name != null) return false;
        } else if (!name.equals(other.name)) return false;
        if (dbType == null) {
            if (other.dbType != null) return false;
        } else if (!dbType.equals(other.dbType)) return false;
        if (maxLength == null) {
            if (other.maxLength != null) return false;
        } else if (!maxLength.equals(other.maxLength)) return false;
        return true;
    }

    @Override
    public String toString() {
        return "%s: %s".formatted(name, dbType);
    }
}
