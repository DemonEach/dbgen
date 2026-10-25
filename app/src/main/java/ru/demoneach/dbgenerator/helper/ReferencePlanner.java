package ru.demoneach.dbgenerator.helper;

import ru.demoneach.dbgenerator.entity.ReferenceRule;
import ru.demoneach.dbgenerator.exception.DataGenerationException;

import java.util.Random;

/**
 * Decides which parent row every child row refers to, so that each parent used gets between
 * {@code min} and {@code max} children and the total is exactly the requested number of rows.
 * Parents that are not used get no children, which is how a rule with {@code min = 0} leaves some
 * parent rows childless. Pure logic, no database access.
 */
public final class ReferencePlanner {
    private ReferencePlanner() {
    }

    /** How many parent rows are worth reading to generate {@code rows} children. */
    public static int parentsToRead(int rows, ReferenceRule rule, String link) {
        if (rows == 0) return 0;
        long low = lowestParentCount(rows, rule);
        long high = highestParentCount(rows, rule);
        if (low > high) {
            throw new DataGenerationException(
                    "%s: %d rows cannot be split into groups of %d..%d children per parent; change amountOfEntries or childrenPerParent"
                            .formatted(link, rows, rule.min(), rule.max()));
        }
        long target = Math.round(rows / ((rule.min() + rule.max()) / 2.0));
        long parents = Math.max(low, Math.min(high, target));
        if (parents > Integer.MAX_VALUE - 8) {
            throw new DataGenerationException("%s: too many parent rows would be needed for %d rows".formatted(link, rows));
        }
        return (int) parents;
    }

    /**
     * @param parents number of parent rows actually available (at most what parentsToRead asked for)
     * @return for every child row the index of its parent in [0, parents), in random order
     */
    public static int[] assign(int rows, int parents, ReferenceRule rule, Random random, String link) {
        if (rows == 0) return new int[0];
        long low = lowestParentCount(rows, rule);
        if (parents < low) {
            throw new DataGenerationException(
                    "%s: the referenced table has only %d rows, at least %d are needed to generate %d rows with childrenPerParent [%d, %d]"
                            .formatted(link, parents, low, rows, rule.min(), rule.max()));
        }
        int used = (int) Math.min(parents, highestParentCount(rows, rule));
        int min = rule.min();
        int max = rule.max();

        int[] counts = new int[used];
        long sum = 0;
        for (int parent = 0; parent < used; parent++) {
            counts[parent] = min + (max > min ? random.nextInt(max - min + 1) : 0);
            sum += counts[parent];
        }
        adjustToTotal(counts, sum - rows, min, max, random);

        int[] plan = new int[rows];
        int position = 0;
        for (int parent = 0; parent < used; parent++) {
            for (int child = 0; child < counts[parent]; child++) plan[position++] = parent;
        }
        for (int i = rows - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = plan[i];
            plan[i] = plan[j];
            plan[j] = swap;
        }
        return plan;
    }

    // Moves the sum of counts to the requested total one child at a time, picking random
    // parents that can still move. Candidates leave the array as soon as they hit a bound.
    private static void adjustToTotal(int[] counts, long excess, int min, int max, Random random) {
        if (excess == 0) return;
        boolean decrease = excess > 0;
        int[] candidates = new int[counts.length];
        int size = 0;
        for (int parent = 0; parent < counts.length; parent++) {
            if (decrease ? counts[parent] > min : counts[parent] < max) candidates[size++] = parent;
        }
        for (long remaining = Math.abs(excess); remaining > 0; remaining--) {
            if (size == 0) throw new IllegalStateException("Cannot reach the requested total of children");
            int slot = random.nextInt(size);
            int parent = candidates[slot];
            counts[parent] += decrease ? -1 : 1;
            if (decrease ? counts[parent] == min : counts[parent] == max) candidates[slot] = candidates[--size];
        }
    }

    private static long lowestParentCount(int rows, ReferenceRule rule) {
        return (rows + (long) rule.max() - 1) / rule.max();
    }

    private static long highestParentCount(int rows, ReferenceRule rule) {
        return rule.min() == 0 ? Long.MAX_VALUE : rows / rule.min();
    }
}
