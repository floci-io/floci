-- Aurora DSQL's dialect inside a cluster's PostgreSQL, installed by Floci as the superuser dbowner
-- every time the database starts. Every statement is idempotent. What DSQL answers was measured on
-- AWS (eu-west-1, 2026-10-16); the messages and SQLSTATEs below are its own. Event triggers do not
-- fire for a session in the replica role, so the dialect's own DDL is not checked against it.
SET session_replication_role = replica;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'admin') THEN
        CREATE ROLE admin WITH LOGIN CREATEROLE CREATEDB;
    END IF;
END
$$;
GRANT CREATE ON DATABASE postgres TO admin;
ALTER SCHEMA public OWNER TO admin;
ALTER DATABASE postgres SET default_transaction_isolation TO 'repeatable read';

CREATE SCHEMA IF NOT EXISTS sys AUTHORIZATION dbowner;
GRANT USAGE ON SCHEMA sys TO PUBLIC;

-- ── Identifiers: a UUID as 26 characters of lowercase base32 ──────────────────

CREATE OR REPLACE FUNCTION sys.uuid_to_base32_str(u uuid) RETURNS text
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    alphabet constant text := 'abcdefghijklmnopqrstuvwxyz234567';
    bytes bytea := uuid_send(u);
    buffer bigint := 0;
    bits int := 0;
    result text := '';
BEGIN
    FOR i IN 0..15 LOOP
        buffer := (buffer << 8) | get_byte(bytes, i);
        bits := bits + 8;
        WHILE bits >= 5 LOOP
            result := result || substr(alphabet, ((buffer >> (bits - 5)) & 31)::int + 1, 1);
            bits := bits - 5;
        END LOOP;
        buffer := buffer & ((1::bigint << bits) - 1);
    END LOOP;
    RETURN result || substr(alphabet, ((buffer << (5 - bits)) & 31)::int + 1, 1);
END
$$;

CREATE OR REPLACE FUNCTION sys.base32_str_to_uuid(s text) RETURNS uuid
LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    alphabet constant text := 'abcdefghijklmnopqrstuvwxyz234567';
    value int;
    buffer bigint := 0;
    bits int := 0;
    hex text := '';
BEGIN
    IF length(s) <> 26 THEN
        RAISE EXCEPTION USING ERRCODE = '22P02', MESSAGE = 'Unable to convert text to UUID';
    END IF;
    FOR i IN 1..26 LOOP
        value := strpos(alphabet, substr(lower(s), i, 1)) - 1;
        IF value < 0 THEN
            RAISE EXCEPTION USING ERRCODE = '22P02', MESSAGE = 'Unable to convert text to UUID';
        END IF;
        buffer := (buffer << 5) | value;
        bits := bits + 5;
        IF bits >= 8 THEN
            hex := hex || lpad(to_hex((buffer >> (bits - 8)) & 255), 2, '0');
            bits := bits - 8;
            buffer := buffer & ((1::bigint << bits) - 1);
        END IF;
    END LOOP;
    IF buffer <> 0 THEN
        RAISE EXCEPTION USING ERRCODE = '22P02', MESSAGE = 'Unable to convert text to UUID';
    END IF;
    RETURN hex::uuid;
END
$$;

-- ── Sessions and the cluster ──────────────────────────────────────────────────

-- Each connection's IAM principal and session id, written by Floci's endpoint before the client's
-- first statement, keyed by the backend serving it. Only the definer functions read it, so a client
-- cannot change what its session reports.
CREATE TABLE IF NOT EXISTS sys.session_identity (
    pid integer PRIMARY KEY,
    iam_session_user text NOT NULL,
    session_id text NOT NULL
);
REVOKE ALL ON sys.session_identity FROM PUBLIC;

-- name, as DSQL's answers it (measured: an ARN past 63 bytes is cut there).
CREATE OR REPLACE FUNCTION sys.iam_session_user() RETURNS name
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$ SELECT iam_session_user::name FROM sys.session_identity WHERE pid = pg_backend_pid() $$;

CREATE OR REPLACE FUNCTION sys.current_session_id() RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$ SELECT session_id FROM sys.session_identity WHERE pid = pg_backend_pid() $$;

CREATE OR REPLACE FUNCTION sys.dsql_major_version() RETURNS integer
LANGUAGE sql IMMUTABLE AS $$ SELECT 1 $$;

CREATE OR REPLACE FUNCTION sys.cluster_size_limit() RETURNS bigint
LANGUAGE sql IMMUTABLE AS $$ SELECT 10995116277760::bigint $$;

CREATE OR REPLACE FUNCTION sys.cluster_size() RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$ SELECT pg_database_size('postgres') $$;

CREATE OR REPLACE VIEW sys.roles AS
SELECT rolname, rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin, rolreplication,
       rolconnlimit, '********'::text AS rolpassword, rolvaliduntil, rolbypassrls,
       s.setconfig AS rolconfig, pg_authid.oid
FROM pg_authid
LEFT JOIN pg_db_role_setting s ON pg_authid.oid = s.setrole AND s.setdatabase = 0::oid
WHERE pg_authid.rolname !~~ 'pg_%';
GRANT SELECT ON sys.roles TO PUBLIC;

-- ── A transaction's limits: one DDL statement, no DDL with DML, 3,000 rows ────

-- What each session's open transaction has done so far. Only the definer functions below write it,
-- so a client cannot reset its counts, as it could a setting of its own session.
CREATE TABLE IF NOT EXISTS sys.transaction_usage (
    pid integer PRIMARY KEY,
    xid xid8 NOT NULL,
    ddl_count bigint NOT NULL,
    dml_count bigint NOT NULL,
    row_count bigint NOT NULL
);
REVOKE ALL ON sys.transaction_usage FROM PUBLIC;

CREATE OR REPLACE FUNCTION sys._usage() RETURNS sys.transaction_usage
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    used sys.transaction_usage;
BEGIN
    INSERT INTO sys.transaction_usage AS u VALUES (pg_backend_pid(), pg_current_xact_id(), 0, 0, 0)
    ON CONFLICT (pid) DO UPDATE SET xid = excluded.xid, ddl_count = 0, dml_count = 0, row_count = 0
        WHERE u.xid <> excluded.xid;
    SELECT * INTO used FROM sys.transaction_usage WHERE pid = pg_backend_pid();
    RETURN used;
END
$$;
REVOKE EXECUTE ON FUNCTION sys._usage() FROM PUBLIC;

CREATE OR REPLACE FUNCTION sys._count_ddl() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    used sys.transaction_usage := sys._usage();
BEGIN
    IF used.ddl_count >= 1 THEN
        RAISE EXCEPTION USING ERRCODE = '0A000',
            MESSAGE = 'multiple ddl statements not supported in a transaction';
    END IF;
    IF used.dml_count >= 1 THEN
        RAISE EXCEPTION USING ERRCODE = '0A000',
            MESSAGE = 'ddl and dml are not supported in the same transaction',
            DETAIL = format('ddl count: %s, dml count: %s', used.ddl_count + 1, used.dml_count);
    END IF;
    UPDATE sys.transaction_usage SET ddl_count = ddl_count + 1 WHERE pid = pg_backend_pid();
END
$$;

CREATE OR REPLACE FUNCTION sys._count_dml() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    changed bigint;
    used sys.transaction_usage := sys._usage();
BEGIN
    SELECT count(*) INTO changed FROM dsql_rows;
    IF used.ddl_count >= 1 THEN
        RAISE EXCEPTION USING ERRCODE = '0A000',
            MESSAGE = 'ddl and dml are not supported in the same transaction',
            DETAIL = format('ddl count: %s, dml count: %s', used.ddl_count, used.dml_count + 1);
    END IF;
    IF used.row_count + changed > 3000 THEN
        RAISE EXCEPTION USING ERRCODE = '54000', MESSAGE = 'transaction row limit exceeded';
    END IF;
    UPDATE sys.transaction_usage SET dml_count = dml_count + 1, row_count = row_count + changed
    WHERE pid = pg_backend_pid();
    RETURN NULL;
END
$$;

-- Whether the statement running was issued by the sys function named, a pattern for its name and
-- arguments. The call stack says, and a client can put no PL/pgSQL function on it, since DSQL
-- refuses the language, nor change it, as it could a setting.
CREATE OR REPLACE FUNCTION sys._issued_by(function_pattern text) RETURNS boolean
LANGUAGE plpgsql AS $$
DECLARE
    stack text;
BEGIN
    GET DIAGNOSTICS stack = PG_CONTEXT;
    RETURN stack ~ ('(^|\n)PL/pgSQL function (sys\.)?' || function_pattern || ' line ');
END
$$;

-- Every table a client creates gets three statement triggers that count its writes, and gets them
-- back, enabled, after any ALTER TABLE that disabled them. It runs as the table's owner, the only
-- role that can create or alter it.
CREATE OR REPLACE FUNCTION sys._watch_table(relation regclass) RETURNS void
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    watcher record;
    enabled "char";
BEGIN
    FOR watcher IN SELECT * FROM (VALUES ('dsql_rows_inserted', 'INSERT', 'NEW'), ('dsql_rows_updated', 'UPDATE', 'NEW'),
                                         ('dsql_rows_deleted', 'DELETE', 'OLD')) AS w (name, event, transition) LOOP
        SELECT t.tgenabled INTO enabled FROM pg_trigger t WHERE t.tgrelid = relation AND t.tgname = watcher.name;
        IF enabled IS NULL THEN
            EXECUTE format('CREATE TRIGGER %I AFTER %s ON %s REFERENCING %s TABLE AS dsql_rows'
                ' FOR EACH STATEMENT EXECUTE FUNCTION sys._count_dml()', watcher.name, watcher.event, relation,
                watcher.transition);
        ELSIF enabled <> 'O' THEN
            EXECUTE format('ALTER TABLE %s ENABLE TRIGGER %I', relation, watcher.name);
        END IF;
    END LOOP;
END
$$;

CREATE OR REPLACE FUNCTION sys._reject(state text, message text, detail text DEFAULT NULL) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF detail IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = state, MESSAGE = message;
    END IF;
    RAISE EXCEPTION USING ERRCODE = state, MESSAGE = message, DETAIL = detail;
END
$$;

-- ── What DDL DSQL refuses, read from the catalog ───────────────────────────────

CREATE OR REPLACE FUNCTION sys._on_ddl_start() RETURNS event_trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF sys._issued_by('_watch_table\(regclass\)') THEN
        RETURN;
    END IF;
    IF tg_tag = 'CREATE INDEX' AND NOT sys._issued_by('create_index_async\(text,text,text\)') THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'unsupported mode. please use CREATE INDEX ASYNC.';
    END IF;
    IF tg_tag IN ('CREATE TABLE AS', 'SELECT INTO', 'CREATE MATERIALIZED VIEW') THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'unsupported statement: CreateTableAs';
    END IF;
    IF tg_tag = 'CREATE TRIGGER' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'CREATE TRIGGER not supported';
    END IF;
    IF tg_tag = 'CREATE TYPE' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'CREATE TYPE not supported';
    END IF;
    IF tg_tag = 'CREATE EXTENSION' THEN
        RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = 'unsupported statement: CreateExtension';
    END IF;
    PERFORM sys._count_ddl();
END
$$;

-- The type a domain stands for, through every domain it is built on; any other type itself.
CREATE OR REPLACE FUNCTION sys._base_type(type_oid oid) RETURNS oid
LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    WITH RECURSIVE chain AS (
        SELECT t.oid, t.typtype, t.typbasetype FROM pg_type t WHERE t.oid = type_oid
        UNION ALL
        SELECT t.oid, t.typtype, t.typbasetype FROM pg_type t JOIN chain c ON t.oid = c.typbasetype
        WHERE c.typtype = 'd'
    )
    SELECT oid FROM chain WHERE typtype <> 'd'
$$;

CREATE OR REPLACE FUNCTION sys._on_ddl_end() RETURNS event_trigger
LANGUAGE plpgsql AS $$
DECLARE
    command record;
    relation record;
    key_type text;
BEGIN
    IF sys._issued_by('_watch_table\(regclass\)') THEN
        RETURN;
    END IF;
    FOR command IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP
        IF command.object_type IN ('table', 'index') THEN
            SELECT c.relkind, c.relpersistence INTO relation FROM pg_class c WHERE c.oid = command.objid;
            IF relation.relpersistence = 't' THEN
                RAISE EXCEPTION USING ERRCODE = '0A000',
                    MESSAGE = 'TEMPORARY or TEMP table not supported for CREATE TABLE';
            END IF;
            IF relation.relkind = 'p' THEN
                RAISE EXCEPTION USING ERRCODE = '0A000',
                    MESSAGE = 'PARTITION BY clause not supported for CREATE TABLE';
            END IF;
            IF command.object_type = 'table' THEN
                SELECT format_type(a.atttypid, a.atttypmod) INTO key_type
                FROM pg_attribute a JOIN pg_type t ON t.oid = a.atttypid
                WHERE a.attrelid = command.objid AND a.attnum > 0 AND NOT a.attisdropped AND t.typcategory = 'A'
                LIMIT 1;
                IF key_type IS NOT NULL THEN
                    RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = format('datatype %s not supported', key_type);
                END IF;
            END IF;
            -- A key may not hold jsonb or bytea: the table's own unique keys, or the index created. The
            -- index's own columns say, expressions included, named as declared, a domain checked
            -- through to its base type (measured).
            SELECT format_type(a.atttypid, a.atttypmod) INTO key_type
            FROM pg_index i
            JOIN pg_attribute a ON a.attrelid = i.indexrelid AND a.attnum BETWEEN 1 AND i.indnkeyatts
            WHERE (i.indrelid = command.objid OR i.indexrelid = command.objid)
              AND sys._base_type(a.atttypid) IN ('jsonb'::regtype, 'bytea'::regtype)
            LIMIT 1;
            IF key_type IS NOT NULL THEN
                RAISE EXCEPTION USING ERRCODE = '0A000', MESSAGE = format('datatype %s is not supported in a key', key_type);
            END IF;
            IF command.object_type = 'table' AND relation.relkind = 'r'
               AND command.command_tag IN ('CREATE TABLE', 'ALTER TABLE') THEN
                PERFORM sys._watch_table(command.objid::regclass);
            END IF;
        ELSIF command.object_type IN ('function', 'procedure') THEN
            IF EXISTS (SELECT 1 FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang
                       WHERE p.oid = command.objid AND l.lanname = 'plpgsql') THEN
                RAISE EXCEPTION USING ERRCODE = '0A000',
                    MESSAGE = format('CREATE %s with language plpgsql not supported', upper(command.object_type));
            END IF;
        END IF;
    END LOOP;
END
$$;

DROP EVENT TRIGGER IF EXISTS dsql_ddl_start;
CREATE EVENT TRIGGER dsql_ddl_start ON ddl_command_start EXECUTE FUNCTION sys._on_ddl_start();
DROP EVENT TRIGGER IF EXISTS dsql_ddl_end;
CREATE EVENT TRIGGER dsql_ddl_end ON ddl_command_end EXECUTE FUNCTION sys._on_ddl_end();

-- The triggers that count a table's writes do not exist for a client, as no trigger does on DSQL.
CREATE OR REPLACE FUNCTION sys._on_sql_drop() RETURNS event_trigger
LANGUAGE plpgsql AS $$
DECLARE
    dropped record;
BEGIN
    FOR dropped IN SELECT * FROM pg_event_trigger_dropped_objects()
                   WHERE original AND object_type = 'trigger' AND address_names[3] LIKE 'dsql\_rows\_%' LOOP
        RAISE EXCEPTION USING ERRCODE = '42704',
            MESSAGE = format('trigger "%s" for table "%s" does not exist', dropped.address_names[3],
                             dropped.address_names[2]);
    END LOOP;
END
$$;

DROP EVENT TRIGGER IF EXISTS dsql_sql_drop;
CREATE EVENT TRIGGER dsql_sql_drop ON sql_drop EXECUTE FUNCTION sys._on_sql_drop();

-- ── Asynchronous jobs: CREATE INDEX ASYNC and its job ─────────────────────────

CREATE TABLE IF NOT EXISTS sys.job (
    job_id uuid PRIMARY KEY,
    status "char" NOT NULL,
    details text,
    job_type text,
    class_id oid,
    object_id oid,
    object_name text,
    start_time timestamptz,
    update_time timestamptz
);
GRANT SELECT ON sys.job TO PUBLIC;

CREATE OR REPLACE VIEW sys.jobs AS
SELECT sys.uuid_to_base32_str(job_id) AS job_id,
       CASE status WHEN 's' THEN 'submitted' WHEN 'p' THEN 'processing' WHEN 'c' THEN 'completed'
                   WHEN 'f' THEN 'failed' WHEN 'x' THEN 'cancelled' END AS status,
       details, job_type, class_id, object_id, object_name, start_time, update_time
FROM sys.job;
GRANT SELECT ON sys.jobs TO PUBLIC;

-- The job and the invalid index below are written as dbowner, so each first checks that the caller
-- owns the index it names: a client can record or invalidate only what it built itself.
CREATE OR REPLACE FUNCTION sys._require_index_owner(index_oid oid) RETURNS void
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF index_oid IS NULL OR NOT coalesce(pg_has_role(session_user,
            (SELECT c.relowner FROM pg_class c WHERE c.oid = index_oid AND c.relkind = 'i'), 'USAGE'), false) THEN
        RAISE EXCEPTION USING ERRCODE = '42501', MESSAGE = 'must be owner of index';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION sys._record_job(job uuid, state "char", detail text, kind text, object oid,
                                           object_label text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM sys._require_index_owner(object);
    INSERT INTO sys.job VALUES (job, state, detail, kind, 1259, object, object_label, now(), clock_timestamp());
END
$$;

-- An index whose build failed stays, invalid, as DSQL leaves it.
CREATE OR REPLACE FUNCTION sys._invalidate_index(index_oid oid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM sys._require_index_owner(index_oid);
    UPDATE pg_index SET indisvalid = false WHERE indexrelid = index_oid;
END
$$;

-- CREATE INDEX ASYNC, which Floci's endpoint hands here with ASYNC taken out: the index is built at
-- once, and a job records it. A unique index over duplicate keys fails its job.
CREATE OR REPLACE FUNCTION sys.create_index_async(statement text, index_name text, table_name text)
RETURNS TABLE (job_id text)
LANGUAGE plpgsql AS $$
DECLARE
    job uuid := gen_random_uuid();
    target regclass := to_regclass(table_name);
    schema_name text;
    index_oid oid;
BEGIN
    -- Floci's rewrite of CREATE INDEX ASYNC hands one index statement here; anything else, a
    -- client calling this function itself included, is not one, and EXECUTE would run every
    -- statement a ; separates.
    IF statement !~* '^\s*CREATE\s+(UNIQUE\s+)?INDEX\s' OR position(';' IN statement) > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '42883',
            MESSAGE = 'function sys.create_index_async(text, text, text) does not exist';
    END IF;
    SELECT n.nspname INTO schema_name FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.oid = target;
    BEGIN
        EXECUTE statement;
        SELECT i.indexrelid INTO index_oid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE i.indrelid = target AND (index_name IS NULL OR c.relname = index_name)
        ORDER BY i.indexrelid DESC LIMIT 1;
        PERFORM sys._record_job(job, 'c', NULL, 'INDEX_BUILD', index_oid,
            coalesce(schema_name, current_schema()) || '.' || coalesce(index_name, (SELECT relname FROM pg_class WHERE oid = index_oid)));
    EXCEPTION WHEN unique_violation THEN
        EXECUTE regexp_replace(statement, '^\s*CREATE\s+UNIQUE\s+INDEX', 'CREATE INDEX', 'i');
        SELECT i.indexrelid INTO index_oid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE i.indrelid = target AND (index_name IS NULL OR c.relname = index_name)
        ORDER BY i.indexrelid DESC LIMIT 1;
        PERFORM sys._invalidate_index(index_oid);
        PERFORM sys._record_job(job, 'f', 'found duplicate key(s) while validating index uniqueness',
            'INDEX_BUILD', index_oid,
            coalesce(schema_name, current_schema()) || '.' || coalesce(index_name, (SELECT relname FROM pg_class WHERE oid = index_oid)));
    END;
    RETURN QUERY SELECT sys.uuid_to_base32_str(job);
END
$$;

-- DSQL's own procedure, as its catalog holds it.
CREATE OR REPLACE PROCEDURE sys.wait_for_job(IN job_uuid_base32 text, INOUT succeeded boolean DEFAULT false)
LANGUAGE plpgsql AS $$
DECLARE
job_uuid uuid := sys.base32_str_to_uuid(job_uuid_base32);
BEGIN
LOOP
    IF EXISTS (SELECT 1 FROM sys.job WHERE job_id = job_uuid AND status = 'c') THEN
        RAISE NOTICE 'Job execution succeeded for job with id %', job_uuid_base32;
        succeeded := true;
        EXIT;
    ELSIF (SELECT 1 FROM sys.job WHERE job_id = job_uuid AND status = 'f') THEN
        RAISE NOTICE 'Job execution failed for job with id %', job_uuid_base32;
        succeeded := false;
        EXIT;
    ELSIF EXISTS (SELECT 1 FROM sys.job WHERE job_id = job_uuid AND status = 'x') THEN
        RAISE NOTICE 'Job execution was cancelled for job with id %', job_uuid_base32;
        succeeded := false;
        EXIT;
    END IF;
    PERFORM pg_sleep(1);
    COMMIT;
END LOOP;
RETURN;
END;
$$;

-- ── AWS IAM GRANT and REVOKE: which IAM role may log in as which database role ─

CREATE TABLE IF NOT EXISTS sys.iam_identity (
    oid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    arn text COLLATE "C" NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS sys.iam_auth_member (
    member bigint NOT NULL REFERENCES sys.iam_identity (oid) ON DELETE CASCADE,
    roleid oid NOT NULL,
    grantor oid NOT NULL,
    PRIMARY KEY (member, roleid)
);

CREATE OR REPLACE VIEW sys.iam_pg_role_mappings AS
SELECT i.oid::oid AS iam_oid, i.arn, m.roleid AS pg_role_oid, a.rolname AS pg_role_name,
       m.grantor AS grantor_pg_role_oid, b.rolname AS grantor_pg_role_name
FROM sys.iam_identity i
JOIN sys.iam_auth_member m ON i.oid = m.member
JOIN pg_authid a ON a.oid = m.roleid
LEFT JOIN pg_authid b ON b.oid = m.grantor;
GRANT SELECT ON sys.iam_pg_role_mappings TO PUBLIC;

-- The role an IAM role may be trusted with: one that exists, is no superuser, and on which the caller
-- holds the ADMIN option, which admin holds on every role it created and on none other, itself
-- included.
CREATE OR REPLACE FUNCTION sys._trusted_role(role_name text, verb text, preposition text) RETURNS oid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    superuser boolean;
BEGIN
    SELECT oid, rolsuper INTO role_oid, superuser FROM pg_roles WHERE rolname = role_name;
    IF role_oid IS NULL THEN
        RAISE EXCEPTION USING ERRCODE = '42704', MESSAGE = format('role "%s" does not exist', role_name);
    END IF;
    IF superuser THEN
        RAISE EXCEPTION USING ERRCODE = '0LP01', MESSAGE = format('cannot establish trust with role "%s"', role_name),
            DETAIL = 'cannot GRANT/REVOKE IAM for roles with the SUPERUSER attribute';
    END IF;
    IF NOT pg_has_role(session_user, role_oid, 'MEMBER WITH ADMIN OPTION') THEN
        RAISE EXCEPTION USING ERRCODE = '42501',
            MESSAGE = format('permission denied to %s IAM trust with role "%s"', verb, role_name),
            DETAIL = format('Only roles with the ADMIN option on role "%s" may %s IAM trust %s this role.',
                            role_name, verb, preposition);
    END IF;
    RETURN role_oid;
END
$$;

-- The mappings of a role dropped where Floci could not refuse it (a DO block or a function body),
-- and the identities left with no mapping, go with the next grant or revoke.
CREATE OR REPLACE PROCEDURE sys._forget_dropped_roles()
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    DELETE FROM sys.iam_auth_member m WHERE NOT EXISTS (SELECT 1 FROM pg_authid a WHERE a.oid = m.roleid);
    DELETE FROM sys.iam_identity i WHERE NOT EXISTS (SELECT 1 FROM sys.iam_auth_member m WHERE m.member = i.oid);
$$;

CREATE OR REPLACE PROCEDURE sys.aws_iam_grant(role_name text, iam_arn text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    identity bigint;
    granted_by text;
BEGIN
    PERFORM sys._count_ddl();
    IF iam_arn !~ '^arn:aws[a-z-]*:iam::[0-9]{12}:(role|user)/.+' THEN
        RAISE EXCEPTION USING ERRCODE = '0P000', MESSAGE = 'IAM role must look like an IAM ARN';
    END IF;
    role_oid := sys._trusted_role(role_name, 'grant', 'to');
    CALL sys._forget_dropped_roles();
    INSERT INTO sys.iam_identity (arn) VALUES (iam_arn) ON CONFLICT (arn) DO NOTHING;
    SELECT oid INTO identity FROM sys.iam_identity WHERE arn = iam_arn;
    IF EXISTS (SELECT 1 FROM sys.iam_auth_member m WHERE m.member = identity AND m.roleid = role_oid) THEN
        SELECT coalesce(b.rolname::text, m.grantor::text) INTO granted_by FROM sys.iam_auth_member m
        LEFT JOIN pg_authid b ON b.oid = m.grantor
        WHERE m.member = identity AND m.roleid = role_oid;
        RAISE NOTICE 'IAM role "%" has already been granted access to role "%" by role "%"',
            iam_arn, role_name, granted_by;
        RETURN;
    END IF;
    INSERT INTO sys.iam_auth_member (member, roleid, grantor)
    VALUES (identity, role_oid, (SELECT oid FROM pg_roles WHERE rolname = session_user));
END
$$;

CREATE OR REPLACE PROCEDURE sys.aws_iam_revoke(role_name text, iam_arn text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    role_oid oid;
    identity bigint;
BEGIN
    PERFORM sys._count_ddl();
    IF iam_arn !~ '^arn:aws[a-z-]*:iam::[0-9]{12}:(role|user)/.+' THEN
        RAISE EXCEPTION USING ERRCODE = '0P000', MESSAGE = 'IAM role must look like an IAM ARN';
    END IF;
    role_oid := sys._trusted_role(role_name, 'revoke', 'from');
    SELECT oid INTO identity FROM sys.iam_identity WHERE arn = iam_arn;
    IF identity IS NULL OR NOT EXISTS (SELECT 1 FROM sys.iam_auth_member WHERE member = identity AND roleid = role_oid) THEN
        RAISE EXCEPTION USING ERRCODE = '42704', MESSAGE = format('IAM role "%s" does not exist', iam_arn);
    END IF;
    DELETE FROM sys.iam_auth_member WHERE member = identity AND roleid = role_oid;
    CALL sys._forget_dropped_roles();
END
$$;

-- A role an IAM role or user is mapped to cannot be dropped, as on DSQL (measured): one detail line
-- per mapping, in the order they were granted.
CREATE OR REPLACE FUNCTION sys._trust_relationships(role_name text) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT string_agg(format('privileges for trust relationship of role %s with arn %s', role_name, i.arn),
                      E'\n' ORDER BY i.oid)
    FROM sys.iam_auth_member m
    JOIN sys.iam_identity i ON i.oid = m.member
    JOIN pg_roles r ON r.oid = m.roleid
    WHERE r.rolname = role_name
$$;

-- A statement on roles, which DSQL counts as DDL (measured) though PostgreSQL fires no event trigger
-- for one: CREATE or ALTER ROLE, USER or GROUP, and GRANT or REVOKE of a role, which Floci's
-- endpoint hands here, counted, then run as the caller. Anything else is not one of them.
CREATE OR REPLACE PROCEDURE sys.run_role_ddl(statement text)
LANGUAGE plpgsql AS $$
BEGIN
    IF statement !~* '^\s*((CREATE|ALTER)\s+(ROLE|USER|GROUP)|GRANT|REVOKE)\s' OR position(';' IN statement) > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '42883', MESSAGE = 'procedure sys.run_role_ddl(text) does not exist';
    END IF;
    PERFORM sys._count_ddl();
    EXECUTE statement;
END
$$;

-- DROP ROLE, USER or GROUP, which Floci's endpoint hands here: refused for a mapped role, otherwise
-- counted as DDL and run as the caller.
CREATE OR REPLACE PROCEDURE sys.drop_roles(role_names text[], missing_ok boolean)
LANGUAGE plpgsql AS $$
DECLARE
    role_name text;
    trust text;
BEGIN
    PERFORM sys._count_ddl();
    FOREACH role_name IN ARRAY role_names LOOP
        trust := sys._trust_relationships(role_name);
        IF trust IS NOT NULL THEN
            RAISE EXCEPTION USING ERRCODE = '2BP01',
                MESSAGE = format('role "%s" cannot be dropped because some objects depend on it', role_name),
                DETAIL = trust;
        END IF;
    END LOOP;
    EXECUTE format('DROP ROLE %s%s', CASE WHEN missing_ok THEN 'IF EXISTS ' ELSE '' END,
                   (SELECT string_agg(quote_ident(name), ', ') FROM unnest(role_names) AS name));
END
$$;

REVOKE ALL ON sys.iam_identity, sys.iam_auth_member FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION sys._trusted_role(text, text, text) FROM PUBLIC;
REVOKE EXECUTE ON PROCEDURE sys._forget_dropped_roles() FROM PUBLIC;

-- A table created before these triggers existed counts its writes from now on too.
DO $$
DECLARE
    relation regclass;
BEGIN
    FOR relation IN SELECT c.oid::regclass FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'sys')
                      AND n.nspname NOT LIKE 'pg_toast%' AND n.nspname NOT LIKE 'pg_temp%' LOOP
        PERFORM sys._watch_table(relation);
    END LOOP;
END
$$;
RESET session_replication_role;
