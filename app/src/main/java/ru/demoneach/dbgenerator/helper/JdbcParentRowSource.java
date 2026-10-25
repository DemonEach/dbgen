package ru.demoneach.dbgenerator.helper;

import lombok.extern.slf4j.Slf4j;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Table;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Reads a random subset of a parent table with bounded memory. A parent that is clearly larger than
 * what is needed is sampled with TABLESAMPLE BERNOULLI (one sequential scan, no sort); otherwise
 * a bounded number of rows is read and shuffled in memory.
 * <p>
 * The size comes from pg_class.reltuples, which is only an estimate. When it says "small" or is
 * unknown, a bounded number of rows is read; if the table turns out to be larger, it is counted
 * exactly and then sampled. Only a table that cannot be sampled (for example a partitioned one)
 * and is larger than the bound is read in storage order, which is not uniform.
 */
@Slf4j
public class JdbcParentRowSource implements ParentRowSource {
    private static final String TABLE_STATS = """
            SELECT c.reltuples::bigint, c.relkind
            FROM   pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE  n.nspname = ? AND c.relname = ?
            """;
    private static final int FETCH_SIZE = 5000;
    private static final int MAX_SAMPLE_ATTEMPTS = 8;

    private final Connection conn;
    private final Random random;

    public JdbcParentRowSource(Connection conn) {
        this(conn, new Random());
    }

    JdbcParentRowSource(Connection conn, Random random) {
        this.conn = conn;
        this.random = random;
    }

    @Override
    public List<Object[]> read(Table parent, List<Field> columns, int wanted) throws SQLException {
        if (wanted == 0) return new ArrayList<>();
        String select = "SELECT %s FROM %s".formatted(
                columns.stream().map(field -> SqlIdentifiers.quote(field.getName())).collect(Collectors.joining(",")),
                SqlIdentifiers.qualified(parent.getSchema(), parent.getTableName()));
        long boundedRows = 4L * wanted + 1000;

        long estimate = 0;
        String kind = "";
        try (PreparedStatement statement = conn.prepareStatement(TABLE_STATS)) {
            statement.setString(1, parent.getSchema());
            statement.setString(2, parent.getTableName());
            try (ResultSet stats = statement.executeQuery()) {
                if (stats.next()) {
                    estimate = stats.getLong(1);
                    kind = stats.getString(2);
                }
            }
        }

        boolean sampleable = "r".equals(kind) || "m".equals(kind);
        List<Object[]> rows;
        if (sampleable && estimate > boundedRows) {
            rows = sample(select, estimate, wanted, columns.size());
        } else {
            // The table looks small, or its size is unknown (never analyzed, or a stale estimate).
            // Read one row more than the bound: if it is not there the whole table has been read.
            rows = query(select + " LIMIT " + (boundedRows + 1), columns.size());
            if (rows.size() > boundedRows && sampleable) {
                rows = sample(select, countRows(parent), wanted, columns.size());
            } else if (rows.size() > boundedRows) {
                log.warn("{} is larger than {} rows and cannot be sampled: rows are taken in storage order", parent, boundedRows);
                rows.remove(rows.size() - 1);
            }
        }
        if (rows.size() > wanted) {
            Collections.shuffle(rows, random);
            rows = new ArrayList<>(rows.subList(0, wanted));
        } else {
            Collections.shuffle(rows, random);
        }
        return rows;
    }

    // The estimate can be stale, so a sample that came out too small is retried with a larger share.
    private List<Object[]> sample(String select, long tableRows, int wanted, int columnCount) throws SQLException {
        double percent = Math.min(100.0, 100.0 * 1.2 * wanted / Math.max(1, tableRows));
        List<Object[]> rows = List.of();
        for (int attempt = 0; attempt < MAX_SAMPLE_ATTEMPTS; attempt++) {
            String sql = "%s TABLESAMPLE BERNOULLI (%s) LIMIT %d".formatted(
                    select, String.format(Locale.ROOT, "%.8f", percent), 3L * wanted + 1000);
            rows = query(sql, columnCount);
            if (rows.size() >= wanted || percent >= 100.0) break;
            log.debug("Sample of {}% returned {} of {} rows, retrying with a larger share", percent, rows.size(), wanted);
            percent = Math.min(100.0, percent * 2);
        }
        return rows;
    }

    private long countRows(Table table) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT count(*) FROM " + SqlIdentifiers.qualified(table.getSchema(), table.getTableName()));
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private List<Object[]> query(String sql, int columnCount) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setFetchSize(FETCH_SIZE);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Object[] row = new Object[columnCount];
                    for (int i = 0; i < columnCount; i++) row[i] = resultSet.getObject(i + 1);
                    rows.add(row);
                }
            }
        }
        return rows;
    }
}
