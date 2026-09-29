# P1 speed benchmark

Compares the three insert strategies against a disposable, throwaway PostgreSQL container.
Never point this at a real database — `run-benchmark.ps1` truncates the benchmark tables
before every run.

## Setup

```
docker run -d --name dbgen-bench -e POSTGRES_PASSWORD=postgres -p 15432:5432 ^
  postgres:16-alpine postgres -c shared_buffers=256MB -c fsync=on -c synchronous_commit=on -c max_connections=50
docker exec -i dbgen-bench psql -U postgres < bench/schema.sql
```

`build/p0check/check.ps1` must have been run at least once so `build/p0check/classes` and
`build/p0check/lib` exist (the benchmark reuses that compiled output; it does not rebuild).

## Running

```
powershell -File bench/run-benchmark.ps1
```

Parameters: `-RowCounts`, `-Shapes` (`narrow`, `wide`, `parent_child`), `-Strategies`
(`DEFAULT`, `MULTI`, `FILE`), `-Reps`, `-ContainerName`, `-Port`. Defaults are the pilot run:
10k/100k rows, all three shapes, all three strategies, `batch`/`batchSave` left at their
config defaults, 3 reps each.

Each run writes and verifies (row COUNT, and for `parent_child` also FK integrity via a
LEFT JOIN) before being recorded. A failed verification or non-zero exit code is printed
immediately and still recorded with `verify_ok=False`, so it will not be silently averaged
away. Raw per-run rows go to `bench/results/pilot-<timestamp>.csv`; a median summary prints
at the end.

`wall_ms` is the whole `java ... App` process (JVM start, connect, metadata/graph build,
generate, insert, commit, shutdown). `gen_ms` is parsed from the app's own
"Starting/Finished generation for table" log lines and only covers generation + insert,
not JVM/connect/metadata overhead — millisecond log resolution makes it noisy for very
short runs.

## Known gaps (see TODO.md P1)

- Only tests up to 100k rows and one table shape per category; the 1M-row tier, the
  batch-size sweep (100/1000/5000) for DEFAULT/MULTI, and already-filled tables from the
  full P1 matrix are not covered here.
- No warm-up run is discarded; each measured run is a fresh JVM/OS/DB-cache state.
- No heap/GC or JDBC-vs-commit breakdown is captured.
- Single-row p50/p95 latency is not measured separately.
