-- Benchmark schema for P1 (see TODO.md). Run once against a disposable database.
-- Table shapes: narrow (few scalar columns), wide (text/jsonb/array/numeric), parent/child (FK).

CREATE TABLE narrow (
    id serial PRIMARY KEY,
    value integer NOT NULL
);

CREATE TABLE wide (
    id serial PRIMARY KEY,
    col1 text NOT NULL,
    col2 text NOT NULL,
    col3 text NOT NULL,
    col4 text NOT NULL,
    col5 text NOT NULL,
    amount numeric(12,2) NOT NULL,
    payload jsonb NOT NULL,
    tags text[] NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE bench_parent (
    id serial PRIMARY KEY,
    code text NOT NULL
);

CREATE TABLE bench_child (
    id serial PRIMARY KEY,
    parent_id integer NOT NULL REFERENCES bench_parent(id),
    value integer NOT NULL
);
