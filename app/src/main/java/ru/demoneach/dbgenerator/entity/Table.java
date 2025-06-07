package ru.demoneach.dbgenerator.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

public class Table {
    private String schema;
    private String tableName;
    private List<Field> fields;

    public List<Field> getFields() {
        return fields;
    }

    public void setFields(List<Field> fields) {
        this.fields = fields;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public void setFieldsFromMap(Map<String, String> fields) {
        this.fields = new ArrayList<>();
        for (Entry<String, String> field : fields.entrySet()) {
            Field newField = new Field(field.getKey(), field.getValue());
            this.fields.add(newField);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        Table table = (Table) o;
        return Objects.equals(schema, table.schema) && Objects.equals(tableName, table.tableName) && Objects.equals(fields, table.fields);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(schema);
        result = 31 * result + Objects.hashCode(tableName);
        result = 31 * result + Objects.hashCode(fields);
        return result;
    }

    @Override
    public String toString() {
        return "%s.%s".formatted(schema, tableName);
    }
}
