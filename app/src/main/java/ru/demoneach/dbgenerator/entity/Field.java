package ru.demoneach.dbgenerator.entity;

import ru.demoneach.dbgenerator.helper.TypeConverterHelper;
import java.math.BigInteger;
import java.util.regex.Pattern;

public class Field {
    private String name;
    private Class<?> dbType;
    private Integer maxLength;
    private static final Pattern NUMERIC = Pattern.compile("(?:numeric|decimal)\\((\\d+)(?:,\\s*(-?\\d+))?\\)");
    private BigInteger numericLimit;
    private int numericScale;
    private boolean databaseGenerated;
    private boolean hasDefault;

    public boolean isDatabaseGenerated() { return databaseGenerated; }
    public void setDatabaseGenerated(boolean value) { databaseGenerated = value; }
    public boolean hasDefault() { return hasDefault; }
    public void setHasDefault(boolean value) { hasDefault = value; }

    public Field(String name, String dbType) {
        this.name = name;
        this.dbType = TypeConverterHelper.dbTypeToJavaClass(dbType);
        this.maxLength = TypeConverterHelper.extractMaxLength(dbType);
        var numeric = NUMERIC.matcher(dbType);
        if (numeric.matches()) {
            numericLimit = BigInteger.TEN.pow(Integer.parseInt(numeric.group(1)));
            numericScale = numeric.group(2) == null ? 0 : Integer.parseInt(numeric.group(2));
        }
    }

    public BigInteger getNumericLimit() { return numericLimit; }
    public int getNumericScale() { return numericScale; }

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
        result = prime * result + java.util.Objects.hashCode(numericLimit);
        result = prime * result + numericScale;
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
        return numericScale == other.numericScale && java.util.Objects.equals(numericLimit, other.numericLimit);
    }

    @Override
    public String toString() {
        return "%s: %s".formatted(name, dbType);
    }
}
