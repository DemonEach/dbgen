# dbgen

A cli tool to generate data for database

### Requirements

- Java 21  
- Gradle
- PostgreSQL 12 or newer (identity and generated-column metadata is used)

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

- `DEFAULT`: `batchSave` controls rows per JDBC `executeBatch()` (default: 1000). The
  connection also sets the PostgreSQL JDBC driver's `reWriteBatchedInserts=true`, which folds
  a batch into multi-row `INSERT` statements.
- `MULTI`: `batch` controls rows per multi-value INSERT (default: 1000). At `batch: 1` every
  row is its own statement with no JDBC-level batching; a pilot benchmark measured this as
  roughly 30x slower than `DEFAULT`/`FILE` at 100k rows (`bench/results/pilot-*.csv`).
- `FILE`: neither setting affects COPY.

Both settings must be positive. They do not control transaction commits. Previously
`batchSave` was ignored and DEFAULT queued about 100,000 rows at a time. 1000 is a pilot
benchmark starting point for both settings (see `bench/`), not a proven optimum for every
schema — measure on yours, especially for wide tables where more columns means more bind
parameters per statement.
MULTI automatically caps each statement at 65,535 bind parameters, using only the
columns actually inserted; excess rows and the final remainder go into subsequent statements.

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

### Server-generated values

Rule values are parsed once per field on first use: CONST's first value, every LIST
entry, and RANGE's two bounds. LIST/RANGE still select a fresh value per row.
An invalid LIST entry fails at preparation even if random selection would not pick it.
NULL entries remain SQL NULL; numeric rounding and overflow checks still happen when writing.

All strategies omit serial, identity (ALWAYS and BY DEFAULT), and generated columns;
PostgreSQL supplies their values. Generation rules do not override these columns.
Ordinary columns with DEFAULT are also omitted unless a generation rule is supplied.
Use CONST/LIST/RANGE to override an ordinary default, or IGNORE to keep it.

If no input columns remain, all strategies use batched `INSERT ... DEFAULT VALUES`
with `batchSave`, including FILE and MULTI. The requested number of rows is inserted;
constraints such as NOT NULL still apply and failures roll back the generation.
Existing sequences are never reset or repaired automatically.

### Identifier syntax

Configuration names are exact and case-sensitive, including unquoted names (dbgen
does not fold them to lowercase). Existing `my-schema.my_table` names remain valid.
Quote a component containing spaces, dots or double quotes, and double embedded quotes:
`'"My.Schema"."Table"."Column""Name"'` is a YAML key for a column rule.
Use two components for `tablesToGenerate` and three for rules and custom links.
Equivalent quoted/unquoted keys are normalized; duplicate rules/links are rejected.
All SQL identifiers are double-quoted separately.

FILE uses a temporary CSV with a generated filename, independent of table names.
It is removed after COPY or a generation error; cleanup failures are logged.

### Foreign keys

Tables are matched by schema and table, not by name alone. Foreign keys are followed
automatically; `customTableLinks` adds a link that is not (or cannot be) expressed as a
real FK constraint, in the same `schema.table.column: schema.table.column` direction
(source: the table holding the referencing column, value: the table it refers to).

When `tablesToGenerate` restricts a run, a table's FK parent outside that list is not
regenerated: its existing rows are read and reused as-is. This also applies through a
`customTableLinks` entry, and chains through that parent's own FK parents. Unrelated
tables elsewhere in the schema are left alone, so an unsupported FK shape on a table you
are not generating does not block the run.

Composite foreign keys and more than one foreign key between the same pair of tables are
not supported yet; both fail with a clear error naming the constraint and tables instead
of silently using only one column or dropping one of the links. A referenced table with
fewer rows than are being requested (including no rows at all) also fails with a clear
error instead of an unrelated index-out-of-bounds exception.

### Running the PostgreSQL regression test

`GeneratedColumnsPostgresTest` is enabled when `DBGEN_TEST_DATABASE` names a test
database. Optional variables: `DBGEN_TEST_HOST` (localhost), `DBGEN_TEST_PORT` (5432),
`DBGEN_TEST_USER` (postgres), `DBGEN_TEST_PASSWORD` (empty). Run `gradlew test`.
The test creates a unique `dbgen_test_*` schema and drops that schema in cleanup.
It checks repeated loads across all strategies and a regular insert afterwards.
