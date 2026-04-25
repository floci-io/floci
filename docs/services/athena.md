# Athena

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon Athena with **real SQL execution** powered by a [floci-duck](https://hub.docker.com/r/floci/floci-duck) sidecar container running DuckDB. When a query is submitted, Floci spins up the sidecar on first use, injects `CREATE OR REPLACE VIEW` statements for each Glue-registered table pointing to S3 data, then executes the SQL and stores results as CSV in S3.

## Supported Actions

| Action | Description |
|---|---|
| `StartQueryExecution` | Submits a SQL query; executed asynchronously via DuckDB |
| `GetQueryExecution` | Returns query status (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`) |
| `GetQueryResults` | Returns the result set for a completed query |
| `ListQueryExecutions` | Returns a list of past query executions |
| `StopQueryExecution` | Cancels a running query |
| `CreateWorkGroup` | Creates a new workgroup |
| `GetWorkGroup` | Returns information about a workgroup |
| `ListWorkGroups` | Lists all workgroups |

## How it works

1. **Lazy sidecar start**: On the first `StartQueryExecution` call, Floci checks for a local `floci/floci-duck:latest` image and starts the container. Subsequent queries reuse the running container.
2. **Glue DDL injection**: Floci reads all Glue tables for the target database and generates `CREATE OR REPLACE VIEW` statements mapping each table name to its S3 location via DuckDB's `read_parquet`, `read_json_auto`, or `read_csv_auto` functions — chosen based on the table's `InputFormat` or SerDe serialization library.
3. **Query execution**: The user's SQL is wrapped in `COPY (...) TO 's3://...' (FORMAT CSV, HEADER)` and executed. Results are written directly to the output S3 path.
4. **Results retrieval**: `GetQueryResults` reads the CSV back from S3 and returns it in the standard Athena `ResultSet` shape.

## Format inference

The DuckDB read function is chosen from the Glue table's `StorageDescriptor`:

| Condition | Read function |
|---|---|
| `InputFormat` or `SerializationLibrary` contains `parquet` | `read_parquet` |
| `InputFormat` or `SerializationLibrary` contains `json` | `read_json_auto` |
| `InputFormat` contains `hive` | `read_json_auto` |
| Anything else | `read_csv_auto` |

## Configuration

| Property | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ATHENA_MOCK` | `false` | Set to `true` to disable DuckDB execution — queries immediately succeed with empty results |
| `FLOCI_SERVICES_ATHENA_DEFAULT_IMAGE` | `floci/floci-duck:latest` | DuckDB sidecar image |
| `FLOCI_SERVICES_ATHENA_DUCK_URL` | *(unset)* | Point to an existing floci-duck instance and skip container management |

## Example — simple query

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Start a query
QUERY_ID=$(aws athena start-query-execution \
  --query-string "SELECT 42 AS answer" \
  --query 'QueryExecutionId' \
  --output text)

# Wait for completion
aws athena get-query-execution --query-execution-id $QUERY_ID

# Get results
aws athena get-query-results --query-execution-id $QUERY_ID
```

## Example — data lake query (S3 + Glue + Athena)

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# 1. Create S3 bucket and upload data
aws s3 mb s3://my-data-lake
echo '{"id":1,"amount":10.0}
{"id":2,"amount":20.0}
{"id":3,"amount":30.0}' | aws s3 cp - s3://my-data-lake/orders/data.json

# 2. Register table in Glue
aws glue create-database --database-input '{"Name":"analytics"}'

aws glue create-table \
  --database-name analytics \
  --table-input '{
    "Name": "orders",
    "StorageDescriptor": {
      "Location": "s3://my-data-lake/orders/",
      "InputFormat": "org.apache.hadoop.mapred.TextInputFormat",
      "OutputFormat": "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat",
      "SerdeInfo": {
        "SerializationLibrary": "org.openx.data.jsonserde.JsonSerDe"
      },
      "Columns": [
        {"Name": "id",     "Type": "int"},
        {"Name": "amount", "Type": "double"}
      ]
    }
  }'

# 3. Run Athena query
QUERY_ID=$(aws athena start-query-execution \
  --query-string "SELECT sum(amount) AS total FROM orders" \
  --query-execution-context Database=analytics \
  --query 'QueryExecutionId' \
  --output text)

# 4. Poll until done
while true; do
  STATE=$(aws athena get-query-execution \
    --query-execution-id $QUERY_ID \
    --query 'QueryExecution.Status.State' \
    --output text)
  [ "$STATE" = "SUCCEEDED" ] && break
  [ "$STATE" = "FAILED" ] && echo "Query failed" && exit 1
  sleep 1
done

# 5. Fetch results
aws athena get-query-results --query-execution-id $QUERY_ID
```

## Mock mode

Set `FLOCI_SERVICES_ATHENA_MOCK=true` to skip DuckDB entirely. In this mode queries transition to `SUCCEEDED` immediately with an empty result set — useful for unit tests that only exercise the Athena state machine, not the query results.
