package ru.demoneach.dbgenerator.entity;

import java.util.Map;

public class ReferenceEdge {
    // parent column -> referencing column; an edge is one foreign key (several edges may join the same two tables)
    private Map<Field, Field> referencedFields;

    // name of the foreign key constraint; null for links that come from customTableLinks
    private String constraintName;

    public ReferenceEdge() {}

    public ReferenceEdge(Map<Field, Field> referencedFields) {
        this.referencedFields = referencedFields;
    }

    public ReferenceEdge(Map<Field, Field> referencedFields, String constraintName) {
        this.referencedFields = referencedFields;
        this.constraintName = constraintName;
    }

    // true when the link was also (or only) declared in customTableLinks
    private boolean customLink;

    public boolean isCustomLink() {
        return customLink;
    }

    public void setCustomLink(boolean customLink) {
        this.customLink = customLink;
    }

    public String getConstraintName() {
        return constraintName;
    }

    public void setConstraintName(String constraintName) {
        this.constraintName = constraintName;
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
