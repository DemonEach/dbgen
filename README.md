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
