package ru.demoneach.dbgenerator.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import ru.demoneach.dbgenerator.entity.DatabaseLayout;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.inserter.DataInserter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PgSQLDataGeneratorTransactionTest {
    @Test
    void commitsOnceAfterAllTablesAndClosesConnection() throws Exception {
        Session session = new Session();
        generator(session, (table, parameters, references) -> session.events.add("insert"))
                .generateDataForTables(new Parameters());
        assertEquals(List.of("begin", "insert", "insert", "commit", "close"), session.events);
    }

    @Test
    void rollsBackSqlAndGenerationFailuresAfterEarlierTableWasInserted() {
        for (Throwable failure : List.of(new SQLException("insert"),
                new JsonProcessingException("json") {}, new URISyntaxException("file", "uri"),
                new UncheckedIOException(new IOException("disk full")),
                new IllegalArgumentException("generation"), new AssertionError("generation"))) {
            Session session = new Session();
            DataInserter inserter = (table, parameters, references) -> {
                session.events.add("insert");
                if (session.events.size() == 3) {
                    if (failure instanceof SQLException e) throw e;
                    if (failure instanceof JsonProcessingException e) throw e;
                    if (failure instanceof URISyntaxException e) throw e;
                    if (failure instanceof RuntimeException e) throw e;
                    throw (Error) failure;
                }
            };
            assertSame(failure, assertThrows(Throwable.class,
                    () -> generator(session, inserter).generateDataForTables(new Parameters())));
            assertEquals(List.of("begin", "insert", "insert", "rollback", "close"), session.events);
        }
    }

    @Test
    void preservesOriginalFailureWhenRollbackAndCloseFail() {
        Session session = new Session();
        SQLException failure = new SQLException("insert");
        session.rollbackFailure = new SQLException("rollback");
        session.closeFailure = new SQLException("close");
        assertSame(failure, assertThrows(SQLException.class,
                () -> generator(session, (t, p, r) -> { throw failure; })
                        .generateDataForTables(new Parameters())));
        assertArrayEquals(new Throwable[]{session.rollbackFailure, session.closeFailure}, failure.getSuppressed());
        assertEquals(List.of("begin", "rollback", "close"), session.events);
    }

    @Test
    void commitFailureIsPropagatedAndRollbackAttempted() {
        Session session = new Session();
        session.commitFailure = new SQLException("commit");
        assertSame(session.commitFailure, assertThrows(SQLException.class,
                () -> generator(session, (t, p, r) -> {}).generateDataForTables(new Parameters())));
        assertEquals(List.of("begin", "commit", "rollback", "close"), session.events);
    }

    @Test
    void closeFailureAfterCommitIsReportedWithoutRollback() {
        Session session = new Session();
        session.closeFailure = new SQLException("close");
        assertSame(session.closeFailure, assertThrows(SQLException.class,
                () -> generator(session, (t, p, r) -> {}).generateDataForTables(new Parameters())));
        assertEquals(List.of("begin", "commit", "close"), session.events);
    }

    @Test
    void failedTransactionStartClosesWithoutInsertingOrCommitting() {
        Session session = new Session();
        session.beginFailure = new SQLException("begin");
        assertSame(session.beginFailure, assertThrows(SQLException.class,
                () -> generator(session, (t, p, r) -> fail("Must not insert"))
                        .generateDataForTables(new Parameters())));
        assertEquals(List.of("begin", "close"), session.events);
    }

    private PgSQLDataGenerator generator(Session session, DataInserter inserter) {
        DatabaseLayout layout = new DatabaseLayout();
        layout.addTableVertex("public", "first", Map.of("value", "integer"));
        layout.addTableVertex("public", "second", Map.of("value", "integer"));
        return new PgSQLDataGenerator(session.connection(), layout, inserter);
    }

    private static class Session {
        final List<String> events = new ArrayList<>();
        SQLException beginFailure, commitFailure, rollbackFailure, closeFailure;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        SQLException failure;
                        switch (method.getName()) {
                            case "setAutoCommit" -> {
                                assertEquals(false, args[0], "Must never implicitly commit via autoCommit");
                                events.add("begin");
                                failure = beginFailure;
                            }
                            case "commit" -> { events.add("commit"); failure = commitFailure; }
                            case "rollback" -> { events.add("rollback"); failure = rollbackFailure; }
                            case "close" -> { events.add("close"); failure = closeFailure; }
                            default -> throw new AssertionError("Unexpected JDBC call: " + method.getName());
                        }
                        if (failure != null) throw failure;
                        return null;
                    });
        }
    }
}
