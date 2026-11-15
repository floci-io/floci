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

-- 6. stl_load_errors: private autonomous writer, with per-session catalog visibility.
-- Keep raw input outside the public catalog and expose only the filtered view.
CREATE SCHEMA IF NOT EXISTS floci_internal;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class
               WHERE oid = to_regclass('pg_catalog.stl_load_errors') AND relkind = 'r') THEN
        ALTER TABLE pg_catalog.stl_load_errors SET SCHEMA floci_internal;
        ALTER TABLE floci_internal.stl_load_errors RENAME TO copy_load_errors;
        REVOKE ALL ON TABLE floci_internal.copy_load_errors FROM PUBLIC;
    END IF;
END;
$$;
CREATE TABLE IF NOT EXISTS floci_internal.copy_load_errors (
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
REVOKE ALL ON TABLE floci_internal.copy_load_errors FROM PUBLIC;
CREATE OR REPLACE VIEW pg_catalog.stl_load_errors WITH (security_barrier = true) AS
SELECT * FROM floci_internal.copy_load_errors
WHERE userid = (SELECT oid::integer FROM pg_catalog.pg_roles WHERE rolname = session_user)
   OR (SELECT rolsuper FROM pg_catalog.pg_roles WHERE rolname = session_user);
REVOKE ALL ON TABLE pg_catalog.stl_load_errors FROM PUBLIC;
GRANT SELECT ON TABLE pg_catalog.stl_load_errors TO PUBLIC;

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
CREATE SCHEMA IF NOT EXISTS floci_internal;
CREATE TABLE IF NOT EXISTS floci_internal.external_catalog (
    schema_name text PRIMARY KEY,
    payload jsonb NOT NULL
);

CREATE OR REPLACE FUNCTION floci_internal.refresh_external_catalog(schema_name text, payload jsonb)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    INSERT INTO floci_internal.external_catalog VALUES ($1, $2)
    ON CONFLICT (schema_name) DO UPDATE SET payload = EXCLUDED.payload;
$$;

CREATE OR REPLACE FUNCTION floci_internal.purge_external_schema(schema_name text)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    DELETE FROM floci_internal.external_catalog WHERE schema_name = $1;
$$;

CREATE OR REPLACE VIEW pg_catalog.svv_external_schemas AS
SELECT n.oid AS esoid, 1::smallint AS eskind, n.nspname AS schemaname,
       n.nspowner::integer AS esowner, c.payload->>'databasename' AS databasename,
       c.payload->>'esoptions' AS esoptions
FROM floci_internal.external_catalog c
JOIN pg_catalog.pg_namespace n ON n.nspname = c.schema_name
WHERE has_schema_privilege(n.oid, 'USAGE');

CREATE OR REPLACE VIEW pg_catalog.svv_external_tables AS
SELECT current_database()::text AS redshift_database_name, c.schema_name AS schemaname,
       t->>'tablename' AS tablename, t->>'tabletype' AS tabletype, t->>'location' AS location,
       t->>'input_format' AS input_format, t->>'output_format' AS output_format,
       t->>'serialization_lib' AS serialization_lib, t->>'serde_parameters' AS serde_parameters,
       (t->>'compressed')::integer AS compressed, t->>'parameters' AS parameters
FROM floci_internal.external_catalog c
JOIN pg_catalog.pg_namespace n ON n.nspname = c.schema_name
CROSS JOIN LATERAL jsonb_array_elements(c.payload->'tables') t
WHERE has_schema_privilege(n.oid, 'USAGE');

CREATE OR REPLACE VIEW pg_catalog.svv_external_columns AS
SELECT current_database()::text AS redshift_database_name, c.schema_name AS schemaname,
       t->>'tablename' AS tablename, t->>'columnname' AS columnname, t->>'external_type' AS external_type,
       (t->>'columnnum')::integer AS columnnum, (t->>'part_key')::integer AS part_key,
       t->>'is_nullable' AS is_nullable
FROM floci_internal.external_catalog c
JOIN pg_catalog.pg_namespace n ON n.nspname = c.schema_name
CROSS JOIN LATERAL jsonb_array_elements(c.payload->'columns') t
WHERE has_schema_privilege(n.oid, 'USAGE');

CREATE OR REPLACE VIEW pg_catalog.svv_external_partitions AS
SELECT c.schema_name AS schemaname, t->>'tablename' AS tablename, t->>'values' AS values,
       t->>'location' AS location, t->>'input_format' AS input_format, t->>'output_format' AS output_format,
       t->>'serialization_lib' AS serialization_lib, t->>'serde_parameters' AS serde_parameters,
       (t->>'compressed')::integer AS compressed, t->>'parameters' AS parameters
FROM floci_internal.external_catalog c
JOIN pg_catalog.pg_namespace n ON n.nspname = c.schema_name
CROSS JOIN LATERAL jsonb_array_elements(c.payload->'partitions') t
WHERE has_schema_privilege(n.oid, 'USAGE');

CREATE TABLE IF NOT EXISTS floci_internal.copy_job (
    job_id bigserial PRIMARY KEY,
    job_name text NOT NULL UNIQUE,
    owner name NOT NULL,
    iam_role text NOT NULL,
    job_text text NOT NULL,
    is_auto boolean NOT NULL,
    target_table text NOT NULL,
    bucket text NOT NULL,
    prefix text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE PROCEDURE floci_internal.reject_copy_job(sqlstate text, message text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    RAISE EXCEPTION '%', message USING ERRCODE = sqlstate;
END;
$$;

CREATE TABLE IF NOT EXISTS floci_internal.copy_job_detail (
    job_id bigint NOT NULL REFERENCES floci_internal.copy_job(job_id) ON DELETE CASCADE,
    bucket text NOT NULL,
    key text NOT NULL,
    etag text,
    version_id text,
    file_size bigint NOT NULL DEFAULT 0,
    modification_time timestamptz NOT NULL DEFAULT clock_timestamp(),
    status character(1) NOT NULL DEFAULT 'I',
    loaded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (job_id, bucket, key)
);

CREATE OR REPLACE FUNCTION floci_internal.copy_job_for_run(name text)
RETURNS SETOF floci_internal.copy_job
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT j.* FROM floci_internal.copy_job j
      WHERE j.job_name = $1 AND (j.owner = session_user OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user))
      FOR UPDATE;
END;
$$;

CREATE OR REPLACE FUNCTION floci_internal.copy_job_loaded_keys(id bigint)
RETURNS TABLE(key text, status character(1), etag text, version_id text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    RETURN QUERY SELECT d.key, d.status, d.etag, d.version_id FROM floci_internal.copy_job_detail d
      JOIN floci_internal.copy_job j ON j.job_id = d.job_id
      WHERE d.job_id = $1 AND (j.owner = session_user OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user));
END;
$$;

CREATE OR REPLACE FUNCTION floci_internal.reserve_copy_job_file(
    id bigint, key text, etag text, version_id text, file_size bigint DEFAULT 0, modification_time timestamptz DEFAULT NULL)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    source_bucket text;
BEGIN
    SELECT j.bucket INTO source_bucket FROM floci_internal.copy_job j
      WHERE j.job_id = $1 AND (j.owner = session_user OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user))
      FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Copy job does not exist' USING ERRCODE = '42704';
    END IF;
    INSERT INTO floci_internal.copy_job_detail (job_id, bucket, key, etag, version_id, file_size, modification_time, status, loaded_at)
      VALUES ($1, source_bucket, $2, $3, $4, COALESCE($5, 0), COALESCE($6, clock_timestamp()), 'I', clock_timestamp())
      ON CONFLICT ON CONSTRAINT copy_job_detail_pkey DO UPDATE
        SET etag = EXCLUDED.etag,
            version_id = EXCLUDED.version_id,
            file_size = EXCLUDED.file_size,
            modification_time = EXCLUDED.modification_time,
            status = 'I',
            loaded_at = clock_timestamp()
        WHERE floci_internal.copy_job_detail.status = 'E'
          AND (floci_internal.copy_job_detail.etag IS DISTINCT FROM EXCLUDED.etag
               OR floci_internal.copy_job_detail.version_id IS DISTINCT FROM EXCLUDED.version_id);
    RETURN FOUND;
END;
$$;

CREATE OR REPLACE FUNCTION floci_internal.record_copy_job_file_error(
    id bigint, key text, etag text, version_id text, file_size bigint DEFAULT 0, modification_time timestamptz DEFAULT NULL)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    source_bucket text;
BEGIN
    SELECT j.bucket INTO source_bucket FROM floci_internal.copy_job j
      WHERE j.job_id = $1 AND (j.owner = session_user OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user))
      FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Copy job does not exist' USING ERRCODE = '42704';
    END IF;
    INSERT INTO floci_internal.copy_job_detail (job_id, bucket, key, etag, version_id, file_size, modification_time, status, loaded_at)
      VALUES ($1, source_bucket, $2, $3, $4, COALESCE($5, 0), COALESCE($6, clock_timestamp()), 'E', clock_timestamp())
      ON CONFLICT ON CONSTRAINT copy_job_detail_pkey DO UPDATE
        SET etag = EXCLUDED.etag,
            version_id = EXCLUDED.version_id,
            file_size = EXCLUDED.file_size,
            modification_time = EXCLUDED.modification_time,
            status = 'E',
            loaded_at = clock_timestamp()
        WHERE floci_internal.copy_job_detail.status <> 'I';
END;
$$;

CREATE OR REPLACE PROCEDURE floci_internal.create_copy_job(
    name text, iam_role text, job_text text, is_auto boolean, target_table text, bucket text, prefix text)
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    -- PostgreSQL rejects COMMIT inside an explicit transaction, including a saved Parse executed later.
    -- Keep transaction control outside SECURITY DEFINER and SET clauses, which forbid it unconditionally.
    COMMIT;
    CALL floci_internal.insert_copy_job(name, iam_role, job_text, is_auto, target_table, bucket, prefix);
END;
$$;

CREATE OR REPLACE PROCEDURE floci_internal.insert_copy_job(
    name text, iam_role text, job_text text, is_auto boolean, target_table text, bucket text, prefix text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    LOCK TABLE floci_internal.copy_job IN SHARE ROW EXCLUSIVE MODE;
    IF (SELECT count(*) FROM floci_internal.copy_job) >= 200 THEN
        RAISE EXCEPTION 'A cluster or workgroup can have at most 200 copy jobs' USING ERRCODE = '54000';
    END IF;
    BEGIN
        INSERT INTO floci_internal.copy_job (job_name, owner, iam_role, job_text, is_auto, target_table, bucket, prefix)
        VALUES (name, session_user, iam_role, job_text, is_auto, target_table, bucket, prefix);
    EXCEPTION WHEN unique_violation THEN
        RAISE EXCEPTION 'Copy job "%" already exists', name USING ERRCODE = '42710';
    END;
END;
$$;

CREATE OR REPLACE PROCEDURE floci_internal.alter_copy_job(name text, is_auto boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    UPDATE floci_internal.copy_job j SET is_auto = $2
     WHERE j.job_name = $1 AND (j.owner = session_user OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user));
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Copy job "%" does not exist', name USING ERRCODE = '42704';
    END IF;
END;
$$;

CREATE OR REPLACE PROCEDURE floci_internal.drop_copy_job(name text)
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    COMMIT;
    CALL floci_internal.delete_copy_job(name);
END;
$$;

CREATE OR REPLACE PROCEDURE floci_internal.delete_copy_job(name text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    DELETE FROM floci_internal.copy_job j
     WHERE j.job_name = $1 AND (j.owner = session_user OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user));
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Copy job "%" does not exist', name USING ERRCODE = '42704';
    END IF;
END;
$$;

CREATE OR REPLACE VIEW pg_catalog.sys_copy_job AS
SELECT j.job_id::bigint AS job_id,
       j.job_name::character(128) AS job_name,
       j.iam_role::character(128) AS iam_role,
       left(j.job_text, 256)::character(256) AS job_text,
       CASE WHEN j.is_auto THEN 1 ELSE 0 END::integer AS is_auto,
       0::integer AS on_error_suspend
FROM floci_internal.copy_job j
WHERE j.owner = session_user OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user);

CREATE OR REPLACE VIEW pg_catalog.sys_copy_job_detail AS
SELECT COALESCE((SELECT u.usesysid::integer FROM pg_catalog.pg_user u WHERE u.usename = j.owner), 0) AS user_id,
       current_database()::text AS database_name,
       j.job_id::bigint AS job_id,
       j.job_name::text AS job_name,
       ('s3://' || j.bucket || '/' || j.prefix)::text AS file_location,
       d.key::text AS file_name,
       d.file_size::bigint AS file_size,
       COALESCE(d.etag, '')::text AS file_etag,
       left(j.job_text, 256)::character(256) AS job_text,
       (d.modification_time AT TIME ZONE 'UTC')::timestamp without time zone AS modification_time,
       (d.loaded_at AT TIME ZONE 'UTC')::timestamp without time zone AS enqueue_time,
       d.status::character(1) AS status
FROM floci_internal.copy_job_detail d
JOIN floci_internal.copy_job j ON j.job_id = d.job_id
WHERE j.owner = session_user OR (SELECT rolsuper FROM pg_roles WHERE rolname = session_user);

GRANT USAGE ON SCHEMA pg_catalog TO PUBLIC;
GRANT USAGE ON SCHEMA floci_internal TO PUBLIC;
-- Schema access for COPY JOB must not expose the unrelated privileged catalog writers.
REVOKE EXECUTE ON FUNCTION floci_internal.refresh_external_catalog(text, jsonb) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION floci_internal.purge_external_schema(text) FROM PUBLIC;
GRANT SELECT ON ALL TABLES IN SCHEMA pg_catalog TO PUBLIC;
