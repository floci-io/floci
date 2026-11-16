package io.floci.aws.services.emr;

/**
 * Hive and Spark steps that share a cluster's metastore, for the tests that run them for real: a Hive step makes
 * tables over CSV rows in S3 and in HDFS, a Spark step reads one and makes another, and a later Hive step reads both.
 */
final class HiveCatalogJobs {

    /** The rows the first Hive step reads, at {@code ${INPUT}}. */
    static final String ROWS = "a,1\nb,2\nc,3\n";

    /** Makes {@code kv} over {@code ${INPUT}}, {@code scaled} from it in Hive's warehouse, and {@code pinned} in HDFS. */
    static final String LOAD_QUERY = """
            create external table kv (k string, v int)
              row format delimited fields terminated by ',' location '${INPUT}';
            create table scaled as select k, v * 10 as v from kv;
            create table pinned (k string) location 'hdfs:///floci/pinned';
            insert into pinned select k from kv;
            """;

    /** Writes {@code scaled} to {@code ${OUTPUT}} and selects it and the Spark step's {@code from_spark}. */
    static final String REPORT_QUERY = """
            insert overwrite directory '${OUTPUT}' row format delimited fields terminated by ','
              select k, v from scaled order by k;
            select k, v from scaled order by k;
            select k from from_spark order by k;
            """;

    /** Reads {@code scaled} through the metastore, writes its rows to its first argument, and makes {@code from_spark}. */
    static final String CATALOG_JOB = """
            import sys
            from pyspark.sql import SparkSession
            spark = SparkSession.builder.enableHiveSupport().getOrCreate()
            rows = sorted(f"{row.k}{row.v}" for row in spark.sql("select k, v from scaled").collect())
            spark.createDataFrame([(" ".join(rows),)], ["value"]).coalesce(1).write.text(sys.argv[1])
            spark.sql("create table from_spark stored as textfile as select upper(k) as k from scaled")
            """;

    /** What {@link #CATALOG_JOB} writes. */
    static final String CATALOG_OUTPUT = "a10 b20 c30\n";

    /** What {@link #REPORT_QUERY} writes to {@code ${OUTPUT}}. */
    static final String REPORT_OUTPUT = "a,10\nb,20\nc,30\n";

    private HiveCatalogJobs() {
    }
}
