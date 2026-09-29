package ru.demoneach.dbgenerator.helper;

import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.*;
import ru.demoneach.dbgenerator.exception.ParametFormatException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SqlIdentifiersTest {
    @Test
    void quotesSqlAndParsesQuotedComponentsWithoutLosingCase() {
        assertEquals("\"My-Schema\".\"a\"\"b\"", SqlIdentifiers.qualified("My-Schema", "a\"b"));
        assertEquals(List.of("my.schema", "a\"b", "Column Name"),
                SqlIdentifiers.parse("\"my.schema\".\"a\"\"b\".\"Column Name\"", 3));
        assertEquals("My-Schema.Table", SqlIdentifiers.normalize("\"My-Schema\".\"Table\"", 2));
        assertNotEquals(SqlIdentifiers.normalize("public.Foo", 2), SqlIdentifiers.normalize("public.foo", 2));
    }

    @Test
    void rejectsMalformedNames() {
        for (String name : new String[]{"", "public", "public.", ".table", "public..table", "public.table.extra",
                "\"public.table", "public\".table", "\"public\"x.table", "public.\"\"", "public.a b", "public.\0x"}) {
            assertThrows(ParametFormatException.class, () -> SqlIdentifiers.parse(name, 2), name);
        }
        assertThrows(ParametFormatException.class, () -> SqlIdentifiers.parse(null, 2));
    }

    @Test
    void specialNamesUseTheSameKeysForTableSelectionAndRules() {
        Table table = new Table();
        table.setSchema("a.b");
        table.setTableName("x\"y");
        Field field = new Field("Column.Name", "integer");
        String config = "\"a.b\".\"x\"\"y\".\"Column.Name\"";
        RuleEnforcer enforcer = new RuleEnforcer(Map.of(SqlIdentifiers.normalize(config, 3),
                new Rule(RuleType.CONST, List.of("42"))));
        assertEquals(SqlIdentifiers.normalize("\"a.b\".\"x\"\"y\"", 2), table.toString());
        assertEquals(42, enforcer.extractRuleValue(table.toString(), field));
    }
}
