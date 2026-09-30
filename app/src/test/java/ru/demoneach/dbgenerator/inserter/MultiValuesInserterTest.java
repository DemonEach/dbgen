package ru.demoneach.dbgenerator.inserter;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.exception.DataGenerationException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class MultiValuesInserterTest {
    @Test
    void bindsReferencesOnceAcrossBatchesAndRemainderWithoutGeneratingThem() throws Exception {
        Table table = table(2);
        Field reference = table.getFields().get(0);
        Field ignored = new Field("id", "integer");
        ignored.setDatabaseGenerated(true);
        table.getFields().add(ignored);
        Map<String, Rule> rules = Map.of(
                "public.test.c0", new Rule(RuleType.CONST, List.of("not an integer")),
                "public.test.c1", new Rule(RuleType.CONST, List.of("7")));
        Recording jdbc = new Recording();
        List<Object> values = new ArrayList<>(List.of(11, 12, 13, 14, 15));
        new MultiValuesInserter(rules, jdbc.connection()).generateAndInsert(table, parameters(5, 2),
                Map.of(reference, values, ignored, List.of()));
        assertEquals(List.of(4, 4, 2), jdbc.executedSizes);
        assertEquals(List.of(15, 7, 14, 7, 13, 7, 12, 7, 11, 7), jdbc.values);
        assertTrue(values.isEmpty());
        assertThrows(DataGenerationException.class, () -> new MultiValuesInserter(rules, jdbc.connection())
                .generateAndInsert(table, parameters(1, 2), Map.of(reference, values)));
    }

    @Test
    void capsWideBatchesAndPreservesRemainderAndZeroRows() throws Exception {
        Table table = table(66);
        Map<String, Rule> rules = new HashMap<>();
        table.getFields().forEach(f -> rules.put("public.test." + f.getName(), new Rule(RuleType.CONST, List.of("1"))));
        Recording jdbc = new Recording();
        MultiValuesInserter inserter = new MultiValuesInserter(rules, jdbc.connection());
        inserter.generateAndInsert(table, parameters(0, Integer.MAX_VALUE), null);
        assertEquals(0, jdbc.prepares);
        inserter.generateAndInsert(table, parameters(2000, Integer.MAX_VALUE), null);
        assertEquals(List.of(65472, 65472, 1056), jdbc.executedSizes);
    }

    private static Table table(int columns) {
        Table table = new Table();
        table.setSchema("public");
        table.setTableName("test");
        table.setFields(new ArrayList<>(IntStream.range(0, columns)
                .mapToObj(i -> new Field("c" + i, "integer")).toList()));
        return table;
    }

    private static Parameters parameters(int rows, int batch) {
        Parameters parameters = new Parameters();
        parameters.setAmountOfEntries(rows);
        parameters.setBatch(batch);
        return parameters;
    }

    private static class Recording {
        int prepares;
        final List<Integer> executedSizes = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (p, m, a) -> {
                        assertEquals("prepareStatement", m.getName());
                        prepares++;
                        int count = (int) ((String) a[0]).chars().filter(c -> c == '?').count();
                        assertTrue(count <= 65535);
                        int[] bound = {0};
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (sp, sm, sa) -> switch (sm.getName()) {
                                    case "setInt" -> {
                                        assertEquals(++bound[0], sa[0]);
                                        values.add(sa[1]);
                                        yield null;
                                    }
                                    case "execute" -> {
                                        assertEquals(count, bound[0]);
                                        executedSizes.add(count);
                                        bound[0] = 0;
                                        yield false;
                                    }
                                    case "close" -> null;
                                    default -> throw new AssertionError(sm.getName());
                                });
                    });
        }
    }
}

