package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.ReferenceRule;
import ru.demoneach.dbgenerator.exception.DataGenerationException;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ReferencePlannerTest {
    private static ReferenceRule rule(int min, int max) {
        return new ReferenceRule(List.of(min, max));
    }

    private static int[] childrenPerParent(int[] plan, int parents) {
        int[] counts = new int[parents];
        for (int parent : plan) counts[parent]++;
        return counts;
    }

    @Test
    void defaultRuleUsesEveryParentExactlyOnce() {
        ReferenceRule rule = ReferenceRule.DEFAULT;
        assertEquals(100, ReferencePlanner.parentsToRead(100, rule, "t"));
        int[] counts = childrenPerParent(ReferencePlanner.assign(100, 100, rule, new Random(1), "t"), 100);
        for (int count : counts) assertEquals(1, count);
    }

    @Test
    void defaultRuleFailsWhenParentHasFewerRowsThanRequested() {
        var e = assertThrows(DataGenerationException.class,
                () -> ReferencePlanner.assign(10, 4, ReferenceRule.DEFAULT, new Random(1), "fk on child"));
        assertTrue(e.getMessage().contains("fk on child") && e.getMessage().contains("only 4"), e.getMessage());
    }

    @Test
    void everyCountStaysInsideTheRangeAndTotalIsExact() {
        int[][] ranges = {{0, 5}, {1, 5}, {2, 3}, {0, 1}, {3, 3}, {1, 100}, {0, 100}, {5, 10}};
        for (int[] range : ranges) {
            for (int rows : new int[]{1, 7, 100, 1000, 12_345}) {
                ReferenceRule rule = rule(range[0], range[1]);
                int wanted;
                try {
                    wanted = ReferencePlanner.parentsToRead(rows, rule, "t");
                } catch (DataGenerationException infeasible) {
                    continue; // e.g. 7 rows in groups of exactly 3
                }
                int[] plan = ReferencePlanner.assign(rows, wanted, rule, new Random(rows * 31L + range[1]), "t");
                assertEquals(rows, plan.length);
                int[] counts = childrenPerParent(plan, wanted);
                for (int count : counts) {
                    assertTrue(count >= range[0] && count <= range[1],
                            "count %d outside %s for %d rows".formatted(count, List.of(range[0], range[1]), rows));
                }
            }
        }
    }

    @Test
    void zeroMinimumLeavesSomeParentsWithoutChildren() {
        ReferenceRule rule = rule(0, 5);
        int wanted = ReferencePlanner.parentsToRead(1000, rule, "t");
        int[] counts = childrenPerParent(ReferencePlanner.assign(1000, wanted, rule, new Random(7), "t"), wanted);
        assertTrue(java.util.Arrays.stream(counts).anyMatch(c -> c == 0), "no parent without children");
        assertTrue(java.util.Arrays.stream(counts).anyMatch(c -> c == 5), "no parent with the maximum");
    }

    @Test
    void positiveMinimumMeansEveryUsedParentHasChildren() {
        ReferenceRule rule = rule(2, 4);
        int wanted = ReferencePlanner.parentsToRead(1000, rule, "t");
        assertTrue(wanted >= 250 && wanted <= 500, "parents: " + wanted);
        for (int count : childrenPerParent(ReferencePlanner.assign(1000, wanted, rule, new Random(3), "t"), wanted)) {
            assertTrue(count >= 2 && count <= 4);
        }
    }

    @Test
    void fewerParentsThanTargetAreUsedWhenMaximumAllows() {
        ReferenceRule rule = rule(1, 5);
        int wanted = ReferencePlanner.parentsToRead(1000, rule, "t"); // about 333
        int[] counts = childrenPerParent(ReferencePlanner.assign(1000, 250, rule, new Random(5), "t"), 250);
        assertTrue(wanted > 250);
        assertEquals(1000, java.util.Arrays.stream(counts).sum());
        // 199 parents are not enough for 1000 rows at max 5
        assertThrows(DataGenerationException.class, () -> ReferencePlanner.assign(1000, 199, rule, new Random(5), "t"));
    }

    @Test
    void impossibleSplitIsReportedWithBothNumbers() {
        var e = assertThrows(DataGenerationException.class, () -> ReferencePlanner.parentsToRead(7, rule(3, 3), "fk"));
        assertTrue(e.getMessage().contains("7") && e.getMessage().contains("3..3"), e.getMessage());
        assertThrows(DataGenerationException.class, () -> ReferencePlanner.parentsToRead(2, rule(3, 4), "fk"));
    }

    @Test
    void zeroRowsNeedNoParentsAndNoPlan() {
        assertEquals(0, ReferencePlanner.parentsToRead(0, rule(1, 3), "t"));
        assertEquals(0, ReferencePlanner.assign(0, 0, rule(1, 3), new Random(), "t").length);
    }

    @Test
    void childrenOfOneParentAreShuffledAcrossRows() {
        int[] plan = ReferencePlanner.assign(1000, ReferencePlanner.parentsToRead(1000, rule(5, 5), "t"), rule(5, 5), new Random(9), "t");
        int adjacentSame = 0;
        for (int i = 1; i < plan.length; i++) if (plan[i] == plan[i - 1]) adjacentSame++;
        assertTrue(adjacentSame < 50, "children of a parent are clustered: " + adjacentSame);
    }
}
