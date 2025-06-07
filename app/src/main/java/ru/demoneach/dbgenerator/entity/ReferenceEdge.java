package ru.demoneach.dbgenerator.entity;

import java.util.Map;

public class ReferenceEdge {
    // perhaps there may be a situation with several references of the same field, but this should not be the case in most cases.
    private Map<Field, Field> referencedFields;

    public ReferenceEdge() {}

    public ReferenceEdge(Map<Field, Field> referencedFields) {
        this.referencedFields = referencedFields;
    }

    public Map<Field, Field> getReferencedFields() {
        return referencedFields;
    }

    public void setReferencedFields(Map<Field, Field> referencedFields) {
        this.referencedFields = referencedFields;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<Field, Field> entry : referencedFields.entrySet()) {
            sb.append(entry.getKey()).append("->").append(entry.getValue()).append("\n");
        }
        return sb.toString();
    }
}
