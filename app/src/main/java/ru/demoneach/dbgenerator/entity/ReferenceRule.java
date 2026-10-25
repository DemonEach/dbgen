package ru.demoneach.dbgenerator.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * How many rows of the referencing (child) table point to one row of the parent table.
 * Configured per foreign key constraint under {@code referenceRules}; a constraint without
 * a rule behaves like {@code [1, 1]}: every parent is used by exactly one child.
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ReferenceRule {
    public static final ReferenceRule DEFAULT = new ReferenceRule(List.of(1, 1));

    // [min, max], both inclusive
    private List<Integer> childrenPerParent;

    public int min() {
        return childrenPerParent.get(0);
    }

    public int max() {
        return childrenPerParent.get(1);
    }
}
