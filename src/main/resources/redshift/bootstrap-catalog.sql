-- Bootstrap Redshift catalog and system views for PostgreSQL 15
-- Provides metadata compatibility for BI and migration tooling (Flyway, Liquibase, dbt, DBeaver, Tableau)

-- 1. pg_table_def: inspect column definitions across tables
DROP VIEW IF EXISTS pg_catalog.pg_table_def CASCADE;
CREATE OR REPLACE VIEW pg_catalog.pg_table_def AS
SELECT
    n.nspname::name AS schemaname,
    c.relname::name AS tablename,
    a.attname::name AS "column",
    format_type(a.atttypid, a.atttypmod)::character varying(256) AS type,
    'none'::character varying(32) AS encoding,
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
DROP VIEW IF EXISTS pg_catalog.svv_table_info CASCADE;
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
    'none'::character varying(32) AS sortkey1_enc,
    0::integer AS sortkey_num,
    1::bigint AS size,
    0.0000::numeric(10,4) AS pct_used,
    0::bigint AS empty,
    0.00::numeric(5,2) AS unsorted,
    0.00::numeric(5,2) AS stats_off,
    COALESCE(c.reltuples::bigint, 0::bigint)::numeric(38,0) AS tbl_rows,
    1.00::numeric(19,2) AS skew_sortkey1,
    1.00::numeric(19,2) AS skew_rows,
    COALESCE(c.reltuples::bigint, 0::bigint)::numeric(38,0) AS estimated_visible_rows,
    NULL::text AS risk_event,
    0.00::numeric(12,2) AS vacuum_sort_benefit,
    now()::timestamp without time zone AS create_time
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
WHERE c.relkind = 'r'
  AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast');

-- 3. svv_all_columns: all columns across database and schemas
DROP VIEW IF EXISTS pg_catalog.svv_all_columns CASCADE;
CREATE OR REPLACE VIEW pg_catalog.svv_all_columns AS
SELECT
    current_database()::character varying(128) AS database_name,
    table_schema::character varying(128) AS schema_name,
    table_name::character varying(128) AS table_name,
    column_name::character varying(128) AS column_name,
    ordinal_position::integer AS ordinal_position,
    column_default::character varying(4000) AS column_default,
    is_nullable::character varying(3) AS is_nullable,
    data_type::character varying(128) AS data_type,
    character_maximum_length::integer AS character_maximum_length,
    numeric_precision::integer AS numeric_precision,
    numeric_scale::integer AS numeric_scale,
    NULL::character varying(256) AS remarks
FROM information_schema.columns;

-- 4. svv_tables: table catalog list
DROP VIEW IF EXISTS pg_catalog.svv_tables CASCADE;
CREATE OR REPLACE VIEW pg_catalog.svv_tables AS
SELECT
    table_catalog::text AS table_catalog,
    table_schema::text AS table_schema,
    table_name::text AS table_name,
    table_type::text AS table_type,
    NULL::text AS remarks
FROM information_schema.tables;

-- 5. stv_tbl_perm: physical table persistence information
DROP VIEW IF EXISTS pg_catalog.stv_tbl_perm CASCADE;
CREATE OR REPLACE VIEW pg_catalog.stv_tbl_perm AS
SELECT
    0::integer AS slice,
    c.oid::integer AS id,
    c.relname::character varying(72) AS name,
    COALESCE(c.reltuples::bigint, 0::bigint) AS rows,
    COALESCE(c.reltuples::bigint, 0::bigint) AS sorted_rows,
    CASE WHEN c.relpersistence = 't' THEN 1 ELSE 0 END::integer AS temp,
    d.oid::integer AS db_id,
    0::integer AS insert_pristine,
    0::integer AS delete_pristine,
    1::integer AS backup,
    0::integer AS dist_style,
    (pg_relation_size(c.oid) / 1048576)::integer AS block_count,
    0::bigint AS insert_prn,
    0::bigint AS delete_prn
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
    colname character varying(127),
    type character varying(10),
    col_length character varying(10),
    position integer,
    raw_line character varying(1024),
    raw_field_value character varying(1024),
    err_code integer,
    err_reason character varying(100),
    is_partial integer DEFAULT 0,
    start_offset bigint DEFAULT 0,
    copy_job_id bigint DEFAULT 0
);

-- 7. svl_qlog: dynamic query execution log view
DROP VIEW IF EXISTS pg_catalog.svl_qlog CASCADE;
CREATE OR REPLACE VIEW pg_catalog.svl_qlog AS
SELECT
    a.usesysid::integer AS userid,
    a.pid AS query,
    0::bigint AS xid,
    a.pid AS pid,
    COALESCE(a.query_start, now()) AS starttime,
    COALESCE(a.state_change, now()) AS endtime,
    COALESCE(EXTRACT(EPOCH FROM (COALESCE(a.state_change, now()) - a.query_start))::bigint * 1000000, 0::bigint) AS elapsed,
    0::integer AS aborted,
    0::integer AS insert_pristine,
    0::integer AS concurrency_scaling_status,
    0::integer AS source_query,
    'default'::character varying(320) AS label,
    SUBSTRING(COALESCE(a.query, ''), 1, 60)::character varying(60) AS substring,
    'Disabled'::text AS concurrency_scaling_status_txt,
    0::integer AS from_sp_call,
    0::integer AS insert_prn
FROM pg_catalog.pg_stat_activity a
WHERE a.query IS NOT NULL AND a.query != '';

-- 8. pg_user_info: user information view
DROP VIEW IF EXISTS pg_catalog.pg_user_info CASCADE;
CREATE OR REPLACE VIEW pg_catalog.pg_user_info AS
SELECT
    usename::name AS usename,
    usesysid::integer AS usesysid,
    usecreatedb::boolean AS usecreatedb,
    usesuper::boolean AS usesuper,
    false::boolean AS usecatupd,
    valuntil::timestamp without time zone AS valuntil,
    NULL::text AS useconfig,
    '-1'::text AS useconnlimit,
    'UNRESTRICTED'::text AS syslogaccess,
    NULL::timestamp without time zone AS last_ddl_ts,
    0::integer AS sessiontimeout,
    NULL::text AS external_id
FROM pg_catalog.pg_user;

-- 8b. svl_user_info: standard Redshift user information view
DROP VIEW IF EXISTS pg_catalog.svl_user_info CASCADE;
CREATE OR REPLACE VIEW pg_catalog.svl_user_info AS
SELECT
    usename::text AS usename,
    usesysid::integer AS usesysid,
    usecreatedb::boolean AS usecreatedb,
    usesuper::boolean AS usesuper,
    false::boolean AS usecatupd,
    '-1'::text AS useconnlimit,
    'UNRESTRICTED'::text AS syslogaccess,
    NULL::timestamp without time zone AS last_ddl_ts,
    0::integer AS sessiontimeout,
    NULL::text AS external_id
FROM pg_catalog.pg_user;

-- 9. stv_sessions: active database sessions
DROP VIEW IF EXISTS pg_catalog.stv_sessions CASCADE;
CREATE OR REPLACE VIEW pg_catalog.stv_sessions AS
SELECT
    backend_start AS starttime,
    pid AS process,
    usename::character varying(50) AS user_name,
    datname::character varying(50) AS db_name,
    0::integer AS timeout_sec,
    COALESCE(client_addr::text, '127.0.0.1')::character varying(45) AS remotehost,
    COALESCE(client_port::text, '0')::character varying(10) AS remoteport
FROM pg_catalog.pg_stat_activity
WHERE datname IS NOT NULL;

-- 10. stv_recents: current and recent queries
DROP VIEW IF EXISTS pg_catalog.stv_recents CASCADE;
CREATE OR REPLACE VIEW pg_catalog.stv_recents AS
SELECT
    a.usesysid::integer AS userid,
    CASE WHEN a.state = 'active' THEN 'Running'::character varying(20) ELSE 'Completed'::character varying(20) END AS status,
    COALESCE(a.query_start, now()) AS starttime,
    COALESCE(EXTRACT(EPOCH FROM (now() - a.query_start))::integer * 1000000, 0) AS duration,
    a.usename::character varying(50) AS user_name,
    a.datname::character varying(50) AS db_name,
    COALESCE(a.query, '')::character varying(4000) AS query,
    a.pid AS pid,
    a.pid AS process,
    a.state_change AS endtime
FROM pg_catalog.pg_stat_activity a
WHERE a.datname IS NOT NULL;

-- 11. pg_database_info: database metadata catalog
DROP VIEW IF EXISTS pg_catalog.pg_database_info CASCADE;
CREATE OR REPLACE VIEW pg_catalog.pg_database_info AS
SELECT
    datname,
    oid::integer AS datid,
    datdba::integer AS datdba,
    encoding,
    datconnlimit::text AS datconnlimit,
    datistemplate,
    datallowconn,
    dattablespace::integer AS dattablespace
FROM pg_catalog.pg_database;

-- 12. svv_columns: user-accessible table columns (full AWS Redshift contract)
DROP VIEW IF EXISTS pg_catalog.svv_columns CASCADE;
CREATE OR REPLACE VIEW pg_catalog.svv_columns AS
SELECT
    table_catalog::text AS table_catalog,
    table_schema::text AS table_schema,
    table_name::text AS table_name,
    column_name::text AS column_name,
    ordinal_position::integer AS ordinal_position,
    column_default::text AS column_default,
    is_nullable::text AS is_nullable,
    data_type::text AS data_type,
    character_maximum_length::integer AS character_maximum_length,
    numeric_precision::integer AS numeric_precision,
    numeric_precision_radix::integer AS numeric_precision_radix,
    numeric_scale::integer AS numeric_scale,
    datetime_precision::integer AS datetime_precision,
    interval_type::text AS interval_type,
    interval_precision::text AS interval_precision,
    character_set_catalog::text AS character_set_catalog,
    character_set_schema::text AS character_set_schema,
    character_set_name::text AS character_set_name,
    collation_catalog::text AS collation_catalog,
    collation_schema::text AS collation_schema,
    collation_name::text AS collation_name,
    domain_name::text AS domain_name,
    NULL::text AS remarks,
    table_schema::text AS schema_name
FROM information_schema.columns
WHERE table_schema NOT IN ('pg_catalog', 'information_schema', 'pg_toast');

-- 13. svv_transactions: active transactions and locks
DROP VIEW IF EXISTS pg_catalog.svv_transactions CASCADE;
CREATE OR REPLACE VIEW pg_catalog.svv_transactions AS
SELECT
    a.usename::text AS txn_owner,
    a.datname::text AS txn_db,
    COALESCE(NULLIF(a.backend_xid::text, '')::bigint, 0::bigint) AS xid,
    a.pid AS pid,
    a.xact_start AS txn_start,
    l.mode::text AS lock_mode,
    'relation'::text AS lockable_object_type,
    l.relation::integer AS relation,
    l.granted::boolean AS granted
FROM pg_catalog.pg_stat_activity a
JOIN pg_catalog.pg_locks l ON a.pid = l.pid
WHERE a.xact_start IS NOT NULL;

-- 14. stv_slices: cluster slice topology (single-node emulator)
DROP VIEW IF EXISTS pg_catalog.stv_slices CASCADE;
CREATE OR REPLACE VIEW pg_catalog.stv_slices AS
SELECT
    0::integer AS node,
    0::integer AS slice,
    0::integer AS localslice,
    'C'::character varying(1) AS type,
    1::integer AS cpu;

-- 15. stl_query: completed and running query execution log view
DROP VIEW IF EXISTS pg_catalog.stl_query CASCADE;
CREATE OR REPLACE VIEW pg_catalog.stl_query AS
SELECT
    a.usesysid::integer AS userid,
    a.pid AS query,
    'default'::character varying(320) AS label,
    0::bigint AS xid,
    a.pid AS pid,
    COALESCE(a.datname, current_database())::character varying(32) AS database,
    COALESCE(a.query, '')::character varying(4000) AS querytxt,
    COALESCE(a.query_start, now()) AS starttime,
    COALESCE(a.state_change, now()) AS endtime,
    CASE WHEN a.state = 'active' THEN 0 ELSE 0 END::integer AS aborted,
    0::integer AS insert_pristine,
    0::integer AS concurrency_scaling_status,
    COALESCE(EXTRACT(EPOCH FROM (COALESCE(a.state_change, now()) - a.query_start))::bigint * 1000000, 0::bigint) AS elapsed
FROM pg_catalog.pg_stat_activity a
WHERE a.query IS NOT NULL AND a.query != '';

-- 16. stv_wlm_query_state: WLM query state view
DROP VIEW IF EXISTS pg_catalog.stv_wlm_query_state CASCADE;
CREATE OR REPLACE VIEW pg_catalog.stv_wlm_query_state AS
SELECT
    0::bigint AS xid,
    0::integer AS task,
    a.pid AS query,
    1::integer AS service_class,
    1::integer AS slot_count,
    COALESCE(a.query_start, now()) AS wlm_start_time,
    CASE WHEN a.state = 'active' THEN 'Running'::character varying(16) ELSE 'Returning'::character varying(16) END AS state,
    0::bigint AS queue_time,
    COALESCE(EXTRACT(EPOCH FROM (now() - a.query_start))::bigint * 1000000, 0::bigint) AS exec_time,
    'Normal'::character varying(20) AS query_priority,
    COALESCE(a.query_start, now()) AS service_class_start_time
FROM pg_catalog.pg_stat_activity a
WHERE a.state = 'active';

-- 17. svv_diskusage: disk space usage per relation (full Redshift columns + compatibility aliases)
DROP VIEW IF EXISTS pg_catalog.svv_diskusage CASCADE;
CREATE OR REPLACE VIEW pg_catalog.svv_diskusage AS
SELECT
    d.oid::integer AS db_id,
    c.relname::character varying(72) AS name,
    0::integer AS slice,
    0::integer AS col,
    c.oid::integer AS tbl,
    0::integer AS blocknum,
    COALESCE(c.reltuples::integer, 0::integer) AS num_values,
    0::bigint AS minvalue,
    0::bigint AS maxvalue,
    0::integer AS sb_pos,
    0::integer AS pinned,
    0::integer AS on_disk,
    0::integer AS modified,
    0::integer AS hdr_modified,
    1::integer AS unsorted,
    0::integer AS tombstone,
    0::integer AS preferred_diskno,
    CASE WHEN c.relpersistence = 't' THEN 1 ELSE 0 END::integer AS temporary,
    0::integer AS newblock,
    current_database()::name AS "database",
    n.nspname::name AS "schema",
    c.oid::integer AS table_id,
    (pg_total_relation_size(c.oid) / 1048576)::bigint AS size,
    (pg_relation_size(c.oid) / 1048576)::bigint AS used
FROM pg_catalog.pg_class c
JOIN pg_catalog.pg_namespace n ON c.relnamespace = n.oid
CROSS JOIN (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database()) d
WHERE c.relkind IN ('r', 'm')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast');

-- Grant access to all database users (including IAM temporary users)
GRANT USAGE ON SCHEMA pg_catalog TO PUBLIC;
GRANT ALL ON TABLE pg_catalog.stl_load_errors TO PUBLIC;
GRANT SELECT ON ALL TABLES IN SCHEMA pg_catalog TO PUBLIC;
