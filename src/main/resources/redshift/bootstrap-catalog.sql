-- Bootstrap Redshift catalog and system views for PostgreSQL 15
-- Provides metadata compatibility for BI and migration tooling (Flyway, Liquibase, dbt, DBeaver, Tableau)

-- 1. pg_table_def: inspect column definitions across tables
CREATE OR REPLACE VIEW pg_catalog.pg_table_def AS
SELECT
    n.nspname::name AS schemaname,
    c.relname::name AS tablename,
    a.attname::name AS "column",
    format_type(a.atttypid, a.atttypmod)::character varying(256) AS type,
    'none'::character varying(256) AS encoding,
    false::boolean AS distkey,
    0::integer AS sortkey,
    a.attnotnull::boolean AS notnull
FROM pg_catalog.pg_attribute a
JOIN pg_catalog.pg_class c ON a.attrelid = c.oid
JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
WHERE a.attnum > 0
  AND NOT a.attisdropped
  AND c.relkind IN ('r', 'v', 'm', 'p')
  AND n.nspname NOT IN ('pg_toast');

-- 2. svv_table_info: table-level metadata and storage layout summary
CREATE OR REPLACE VIEW pg_catalog.svv_table_info AS
SELECT
    current_database()::name AS "database",
    n.nspname::name AS "schema",
    c.oid::integer AS table_id,
    c.relname::name AS "table",
    'N'::character varying(1) AS encoded,
    'EVEN'::character varying(20) AS diststyle,
    NULL::character varying(128) AS sortkey1,
    0::integer AS max_varchar,
    0::integer AS sortkey_num,
    1::bigint AS size,
    0.00::numeric(6,2) AS pct_used,
    0::bigint AS empty,
    0.00::numeric(5,2) AS unsorted,
    0.00::numeric(5,2) AS stats_off,
    COALESCE(c.reltuples::bigint, 0::bigint) AS tbl_rows,
    1.00::numeric(6,2) AS skew_sortkey1,
    1.00::numeric(6,2) AS skew_rows
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
WHERE c.relkind = 'r'
  AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast');

-- 3. svv_all_columns: all columns across database and schemas
CREATE OR REPLACE VIEW pg_catalog.svv_all_columns AS
SELECT
    current_database()::character varying AS database_name,
    table_schema::character varying AS schema_name,
    table_name::character varying AS table_name,
    column_name::character varying AS column_name,
    ordinal_position::integer AS ordinal_position,
    column_default::character varying AS column_default,
    is_nullable::character varying AS is_nullable,
    data_type::character varying AS data_type,
    character_maximum_length::integer AS character_maximum_length,
    numeric_precision::integer AS numeric_precision,
    numeric_scale::integer AS numeric_scale,
    NULL::character varying AS remarks
FROM information_schema.columns;

-- 4. svv_tables: table catalog list
CREATE OR REPLACE VIEW pg_catalog.svv_tables AS
SELECT
    table_catalog::character varying AS table_catalog,
    table_schema::character varying AS table_schema,
    table_name::character varying AS table_name,
    table_type::character varying AS table_type,
    NULL::character varying AS remarks
FROM information_schema.tables;

-- 5. stv_tbl_perm: physical table persistence information
CREATE OR REPLACE VIEW pg_catalog.stv_tbl_perm AS
SELECT
    c.oid::integer AS id,
    c.relname::name AS name,
    d.oid::integer AS db_id,
    CASE WHEN c.relpersistence = 't' THEN 1 ELSE 0 END::integer AS temp,
    0::bigint AS insert_prn,
    0::bigint AS delete_prn,
    1::integer AS backup
FROM pg_catalog.pg_class c
CROSS JOIN (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database()) d
WHERE c.relkind = 'r';

-- 6. stl_load_errors: load error log table for COPY commands
CREATE TABLE IF NOT EXISTS pg_catalog.stl_load_errors (
    userid integer DEFAULT 1,
    slice integer DEFAULT 0,
    tbl integer DEFAULT 0,
    starttime timestamp without time zone DEFAULT now(),
    session integer DEFAULT 0,
    query integer DEFAULT 0,
    filename character varying(256),
    line_number bigint,
    colname character(127),
    type character(10),
    col_length character(10),
    position integer,
    raw_line character varying(1024),
    raw_field_value character varying(1024),
    err_code integer,
    err_reason character(100)
);

-- 7. svl_qlog: basic query execution log view
CREATE OR REPLACE VIEW pg_catalog.svl_qlog AS
SELECT
    1::integer AS userid,
    1::integer AS query,
    0::bigint AS xid,
    pg_backend_pid() AS pid,
    now() AS starttime,
    now() AS endtime,
    0::bigint AS elapsed,
    0::integer AS aborted,
    0::integer AS insert_prn,
    0::integer AS concurrency_scaling_status,
    0::integer AS source_query,
    'default'::character varying(322) AS label;

-- 8. pg_user_info: user information view
CREATE OR REPLACE VIEW pg_catalog.pg_user_info AS
SELECT
    usesysid::integer AS usesysid,
    usename::name AS usename,
    usecreatedb::boolean AS usecreatedb,
    usesuper::boolean AS usesuper,
    false::boolean AS usecatupd,
    valuntil::timestamp without time zone AS valuntil,
    NULL::text AS useconfig
FROM pg_catalog.pg_user;

-- 9. stv_sessions: active database sessions
CREATE OR REPLACE VIEW pg_catalog.stv_sessions AS
SELECT
    pid AS process,
    usename::character varying(128) AS user_name,
    datname::character varying(128) AS db_name,
    backend_start AS starttime,
    COALESCE(client_addr::text, '127.0.0.1')::character varying(45) AS remotehost,
    COALESCE(client_port::text, '0')::character varying(10) AS remoteport
FROM pg_catalog.pg_stat_activity
WHERE datname IS NOT NULL;

-- 10. stv_recents: current and recent queries
CREATE OR REPLACE VIEW pg_catalog.stv_recents AS
SELECT
    CASE WHEN state = 'active' THEN 'Running'::character varying(15) ELSE 'Completed'::character varying(15) END AS status,
    COALESCE(EXTRACT(EPOCH FROM (now() - query_start))::integer * 1000000, 0) AS duration,
    pid AS process,
    usename::character varying(128) AS user_name,
    datname::character varying(128) AS db_name,
    COALESCE(query_start, now()) AS starttime,
    state_change AS endtime,
    COALESCE(query, '')::character varying(4000) AS query
FROM pg_catalog.pg_stat_activity
WHERE datname IS NOT NULL;

-- 11. pg_database_info: database metadata catalog
CREATE OR REPLACE VIEW pg_catalog.pg_database_info AS
SELECT
    datname,
    oid::integer AS datid,
    datdba::integer AS datdba,
    encoding,
    datconnlimit
FROM pg_catalog.pg_database;

-- 12. svv_columns: user-accessible table columns
CREATE OR REPLACE VIEW pg_catalog.svv_columns AS
SELECT
    table_schema::character varying AS schema_name,
    table_name::character varying AS table_name,
    column_name::character varying AS column_name,
    ordinal_position::integer AS ordinal_position,
    column_default::character varying AS column_default,
    is_nullable::character varying AS is_nullable,
    data_type::character varying AS data_type,
    character_maximum_length::integer AS character_maximum_length,
    numeric_precision::integer AS numeric_precision,
    numeric_scale::integer AS numeric_scale,
    NULL::character varying AS remarks
FROM information_schema.columns
WHERE table_schema NOT IN ('pg_catalog', 'information_schema', 'pg_toast');

-- 13. svv_transactions: active transactions and locks
CREATE OR REPLACE VIEW pg_catalog.svv_transactions AS
SELECT
    a.usename::character varying(128) AS txn_owner,
    a.datname::character varying(128) AS txn_db,
    l.virtualtransaction::text AS xid,
    a.pid AS pid,
    a.xact_start AS txn_start,
    l.mode::character varying(128) AS lock_mode
FROM pg_catalog.pg_stat_activity a
JOIN pg_catalog.pg_locks l ON a.pid = l.pid
WHERE a.xact_start IS NOT NULL;

-- 14. stv_slices: cluster slice topology (single-node emulator)
CREATE OR REPLACE VIEW pg_catalog.stv_slices AS
SELECT
    0::integer AS slice,
    0::integer AS node,
    1::integer AS cpu;

-- 15. stl_query: query execution log table
CREATE TABLE IF NOT EXISTS pg_catalog.stl_query (
    query integer DEFAULT 1,
    userid integer DEFAULT 1,
    xid bigint DEFAULT 0,
    pid integer DEFAULT 0,
    starttime timestamp without time zone DEFAULT now(),
    endtime timestamp without time zone DEFAULT now(),
    elapsed bigint DEFAULT 0,
    label character varying(322) DEFAULT 'default',
    querytxt character varying(4000) DEFAULT '',
    database character varying(128) DEFAULT 'dev',
    aborted integer DEFAULT 0
);

-- Grant SELECT to all database users (including IAM temporary users)
GRANT USAGE ON SCHEMA pg_catalog TO PUBLIC;
GRANT SELECT ON ALL TABLES IN SCHEMA pg_catalog TO PUBLIC;
