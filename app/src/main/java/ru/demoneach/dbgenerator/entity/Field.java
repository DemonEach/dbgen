package ru.demoneach.dbgenerator.entity;

import ru.demoneach.dbgenerator.helper.TypeConverterHelper;

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
        this.dbType = TypeConverterHelper.dbTypeToJavaClass(dbType);
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
