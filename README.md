# dbgen

A cli tool to generate data for database

### Requirements

- Java 21  
- Gradle

### Currently supported databases

- Postgresql
    - Generation strategies:
      - With csv file via "COPY"
      - Bulk inserts
      - Inserts

### How to launch

1. Compile with `gradlew build`
2. Get your `app.jar` file
3. Create `application.yaml` and place it at same folder as jar file at step 2
4. You can see example configuration for `application-example.yaml` in root folder of the project with documentation for each field
5. Launch `app.jar` file with `java -jar app.jar`

The configuration is read from the working directory first, then from the jar directory.

### Insert batch sizes

- `DEFAULT`: `batchSave` controls rows per JDBC `executeBatch()` (default: 1000).
- `MULTI`: `batch` controls rows per multi-value INSERT (default: 1).
- `FILE`: neither setting affects COPY.

Both settings must be positive. They do not control transaction commits. Previously
`batchSave` was ignored and DEFAULT queued about 100,000 rows at a time. Start with
1000 and measure on your schema; this is a starting point, not a measured optimum.

### Data formats

- `numeric(p,s)` is generated within the declared precision and scale. Rule values
  are rounded to the scale (half away from zero); overflow raises an error.
- Timestamp precision from 0 to 6 is recognized; PostgreSQL applies the declared
  timestamp precision when storing the value.
- In `CONST`/`LIST`, a YAML null element produces SQL NULL; a quoted `"null"` is text.
- JSONB rules contain JSON text, e.g. `'{"active":true}'`; generated objects are
  serialized as JSON. PostgreSQL validates rule JSON during insertion.
- `bytea` rules use hex, e.g. `'\x0001ff'`. JDBC writes the raw bytes.
- `text[]` rules use a JSON array, e.g. `'["hello",null,""]'`.
- FILE writes UTF-8 CSV. SQL NULL is an unquoted empty field; an empty string is
  quoted. Quotes, commas, line breaks and array elements are escaped for COPY.
