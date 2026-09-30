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
(`DEFAULT`, `MULTI`, `FILE`), `-Reps`, `-Batch`, `-BatchSave`, `-Warmups`, `-Tag`,
`-ContainerName`, `-Port`, `-DockerExe`. Defaults: 10k/100k rows, all shapes and
strategies, batch/batchSave=1000, 3 measured runs and no warm-up runs.

For a DEFAULT batchSave sweep (PowerShell):

```powershell
foreach ($size in 100, 1000, 5000) {
    & ./bench/run-benchmark.ps1 -RowCounts 100000 -Strategies DEFAULT `
        -BatchSave $size -Warmups 1 -Reps 5 -Tag "batchsave-$size"
}
```

Warm-up runs are verified and saved with `warmup=True`, but excluded from medians.
Each run launches a fresh JVM: this warms the environment, not the next JVM's JIT.
Each run writes and verifies (row COUNT, and for `parent_child` also FK integrity via a
LEFT JOIN) before being recorded. A failed verification or non-zero exit code is printed
immediately and still recorded with `verify_ok=False`, so it will not be silently averaged
away. The script fails if any run fails; medians include only successful measured runs.
Raw per-run rows go to `bench/results/<Tag>-<timestamp>.csv`; a median summary prints
at the end.

`wall_ms` is the whole `java ... App` process (JVM start, connect, metadata/graph build,
generate, insert, commit, shutdown). `gen_ms` is parsed from the app's own
"Starting/Finished generation for table" log lines and covers generation + FK reads + insert, excluding commit and JVM/connect/metadata overhead — millisecond log resolution makes it noisy for very
short runs.

## Known gaps (see TODO.md P1)

- Already-filled tables are not covered; each run truncates its benchmark tables.
- No deterministic data seed or randomized variant order is configured.
- No heap/GC or JDBC-vs-commit breakdown is captured.
- Single-row p50/p95 latency is not measured separately.

## DEFAULT batchSave experiment (2026-09-30)

PostgreSQL 16.15, fsync/synchronous_commit on, shared_buffers=256MB,
application commit 3e4b78e, reWriteBatchedInserts=true. Fresh tables and JVM per run;
no generation rules. Values below are median full CLI wall times in milliseconds.

| Rows per table / shape | batchSave=100 | 1000 | 5000 |
|---|---:|---:|---:|
| 100k narrow | 1045.9 | 787.5 | 715.4 |
| 100k wide | 2501.7 | 2163.9 | 2025.3 |
| 100k parent-child | 3142.7 | 2508.9 | 2336.3 |
| 1M narrow | — | 2636.8 | 2459.3 |
| 1M wide | — | 12806.9 | 12198.5 |

100k: one warm-up and five measured runs; 1M: one warm-up and three measured runs.
All COUNT/FK checks passed. Parent-child inserts the requested number into each table.
The 1M comparison used reversed variant order. Raw logs: `results/batchsave-*-20260930-*.csv`.
5000 reduced time by about 5–9% versus 1000 in these workloads. The application default
remains 1000: heap/GC and larger row payloads were not measured. Try 5000 on your schema
when tuning bulk loads; this is not a universal optimum.
