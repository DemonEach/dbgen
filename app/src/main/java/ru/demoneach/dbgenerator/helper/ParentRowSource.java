package ru.demoneach.dbgenerator.helper;

import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Table;

import java.sql.SQLException;
import java.util.List;

/** Supplies existing rows of a parent table to be referenced by generated child rows. */
public interface ParentRowSource {
    /**
     * @param columns parent columns to read, in the order of the returned arrays
     * @param wanted  how many rows are needed; fewer are returned only if the table has fewer
     * @return distinct parent rows in random order
     */
    List<Object[]> read(Table parent, List<Field> columns, int wanted) throws SQLException;
}
