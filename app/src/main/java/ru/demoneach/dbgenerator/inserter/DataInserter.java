package ru.demoneach.dbgenerator.inserter;

import com.fasterxml.jackson.core.JsonProcessingException;
import ru.demoneach.dbgenerator.entity.Field;
import ru.demoneach.dbgenerator.entity.Parameters;
import ru.demoneach.dbgenerator.entity.Table;

import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public interface DataInserter {
    /**
     * Generate data with specific way
     *
     * @param sourceTable table that being generated
     * @param parameters generation parameters
     * @param fieldReferenceValueMap referenced values from another table
     * @throws SQLException generally occurred when smth wrong with connection or query
     * @throws JsonProcessingException
     * @throws URISyntaxException
     */
    void generateAndInsert(Table sourceTable, Parameters parameters, Map<Field, List<Object>> fieldReferenceValueMap) throws SQLException, JsonProcessingException, URISyntaxException;
}
