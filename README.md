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
RANGE bounds are validated at that point too: both must be the same supported type
(numeric, integer types, float/double, timestamp) and min must not exceed max, otherwise the
error names the field. The upper bound is exclusive; `min == max` always yields that value.
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

Several foreign keys between the same two tables (for example `created_by` and `updated_by`
both pointing to `users`) are supported: each key gets its own values, chosen independently, and
its own `referenceRules` entry.

Composite foreign keys are supported: a child row takes all the key columns from one parent row,
so the pair (or triple, ...) is always an existing combination. `childrenPerParent` counts children
per parent row, and the unique-column check treats a unique index on any part of the key as unique.

Overlapping keys are not supported: a column that belongs to more than one key (for example
`order_id` in both a composite key and a separate foreign key to `orders`, or a `customTableLinks`
entry onto a column that already has a foreign key to another table) cannot be filled consistently,
so the run fails before anything is written with a message naming the column and the keys involved.
A `customTableLinks` entry that repeats a real foreign key is merged with it. A referenced table
with fewer rows than are being requested (including no rows at all) also fails with a clear error.

### Reference rules

`referenceRules` controls how many child rows point to one parent row. The key is
`schema.table.constraint_name` of the foreign key (so composite keys and several keys between
the same two tables can be told apart; see `\d table` in psql), the value is
`childrenPerParent: [min, max]`, inclusive, with `0 <= min <= max` and `max >= 1`:

```yaml
referenceRules:
  shop.orders.orders_customer_id_fkey:
    childrenPerParent: [0, 5]   # 0..5 orders per customer, some customers have none
```

- Without a rule a foreign key uses `[1, 1]`: every parent is referenced by exactly one child,
  so the parent table needs at least `amountOfEntries` rows.
- `amountOfEntries` is still the total number of rows generated in the child table. The range
  decides how they are spread: parents are picked at random and each gets between `min` and `max`
  children. Parents that are not picked get none, which is how `min = 0` leaves childless parents.
  Child rows of one parent are shuffled across the table, not stored next to each other.
- When the numbers do not fit (for example 7 rows with exactly 3 children per parent, or too few
  parent rows for the requested `max`) the run fails before writing anything and says why.
- The key must name an existing foreign key constraint, or the referencing column of a
  `customTableLinks` entry (see below), otherwise the run stops at startup. A rule with
  `max > 1` on a referencing column that has a unique index is rejected too, since the insert
  would fail on it.

A link from `customTableLinks` has no constraint, so its rule is keyed by the referencing column,
written exactly as in `customTableLinks`. This is how separate services that share one database
(one schema each, no foreign key between them) get several children per parent:

```yaml
customTableLinks:
  order_service.order.client_id: client_service.client.id
referenceRules:
  order_service.order.client_id:
    childrenPerParent: [1, 5]   # 1..5 orders per client
```

Both schemas must be in the one database the generator connects to; a table in another database
cannot be linked. Without a rule a custom link uses `[1, 1]` like any other key. If a custom link
repeats a real foreign key, the rule can use either the column or the constraint name, but not
both. A key that names both a constraint and a custom-link column of the same table is ambiguous
and rejected.

Parents are chosen randomly with bounded memory. A parent table that is much larger than needed
is read with `TABLESAMPLE BERNOULLI`, which costs one sequential scan; a small one is read and
shuffled in memory. Table size comes from `pg_class.reltuples` (run `ANALYZE` to keep it accurate);
if it is unknown the table is counted exactly when it looks big. A parent that cannot be sampled
(for example a partitioned table) and has more than about `4 * rows` rows is read in storage order
instead of uniformly, and a warning is logged.

### Running the tests

`./gradlew test` runs the unit tests. The PostgreSQL tests (`GeneratedColumnsPostgresTest`,
`StrategiesPostgresTest`: type round-trip, batching and rollback for all three strategies) are
enabled only when `DBGEN_TEST_DATABASE` names a test database; without it they are skipped
silently. Optional variables: `DBGEN_TEST_HOST` (localhost), `DBGEN_TEST_PORT` (5432),
`DBGEN_TEST_USER` (postgres), `DBGEN_TEST_PASSWORD` (empty). The tests create and drop their
own schemas.

Everything at once, with a throwaway PostgreSQL 16 in Docker (stopped even if tests fail):

```bash
docker run -d --rm --name dbgen-test -e POSTGRES_PASSWORD=x -e POSTGRES_DB=dbgen_test -p 15434:5432 postgres:16-alpine >/dev/null && until docker exec dbgen-test pg_isready -U postgres -d dbgen_test >/dev/null 2>&1; do sleep 1; done; sleep 2; DBGEN_TEST_DATABASE=dbgen_test DBGEN_TEST_PORT=15434 DBGEN_TEST_PASSWORD=x ./gradlew test --rerun-tasks; docker stop dbgen-test >/dev/null
```

`--rerun-tasks` bypasses Gradle's cached results. The report is in
`app/build/reports/tests/test/index.html`; with the database up, no test should be skipped.
