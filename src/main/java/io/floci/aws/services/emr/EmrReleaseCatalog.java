package io.floci.aws.services.emr;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The EMR releases ListReleaseLabels, DescribeReleaseLabel and ListSupportedInstanceTypes report: every emr-7.x
 * release and every release of the AWS runtime for Apache Spark (emr-spark-*), with emr-6.15.0, emr-6.14.0 and
 * emr-5.36.2, not AWS's full list. Each release's applications and versions come from its page in the Amazon EMR
 * Release Guide. The names are spelled as that page's list of applications spells them, which is how AWS answers
 * them (Spark, HBase, ZooKeeper), with Trino for "Trino (PrestoSQL)". The versions drop AWS's build suffix
 * ({@code -amzn-N}), the API giving "the concise version of the application". Each release also has the two most
 * recent Amazon Linux releases its page lists, or none where the page lists none. The instance types are common
 * EMR families with their EC2 sizes, all of which these releases support.
 */
final class EmrReleaseCatalog {

    record Application(String name, String version) {}

    record Release(String label, List<Application> applications, List<String> osReleases) {}

    record InstanceType(String type, int vcpu, float memoryGb, int storageGb, int numberOfDisks,
                        boolean ebsStorageOnly, String instanceFamilyId, String architecture) {}

    // The two most recent Amazon Linux 2023 and Amazon Linux 2 releases, the same on every page that lists them.
    private static final List<String> AMAZON_LINUX_2023 = List.of("2023.12.20260803.3", "2023.12.20260727.0");
    private static final List<String> AMAZON_LINUX_2 = List.of("2.0.20260803.1", "2.0.20260727.0");

    /**
     * In ListReleaseLabels' order (see {@link #LISTING_ORDER}): every emr-7.x release, emr-6.15.0, emr-6.14.0 and
     * emr-5.36.2, then the AWS runtime for Apache Spark's releases.
     */
    static final List<Release> RELEASES = List.of(
            new Release("emr-7.14.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.3.2"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.4"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.2"), app("Hive", "3.1.3"),
                    app("Hudi", "1.0.2"), app("Hue", "4.11.0"), app("Iceberg", "1.10.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.3.0"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.8"), app("TensorFlow", "2.19.0"),
                    app("Tez", "0.10.2"), app("Trino", "479"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.3")),
                    List.of()),
            new Release("emr-7.13.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.3.2"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.4"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.2"), app("Hive", "3.1.3"),
                    app("Hudi", "1.0.2"), app("Hue", "4.11.0"), app("Iceberg", "1.10.0"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.3.0"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.6"), app("TensorFlow", "2.19.0"),
                    app("Tez", "0.10.2"), app("Trino", "479"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.3")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.12.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.3.2"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.2"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.1"), app("Hive", "3.1.3"),
                    app("Hudi", "1.0.2"), app("Hue", "4.11.0"), app("Iceberg", "1.10.0"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.1"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.6"), app("TensorFlow", "2.19.0"),
                    app("Tez", "0.10.2"), app("Trino", "476"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.3")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.11.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.3.2"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.2"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.1"), app("Hive", "3.1.3"),
                    app("Hudi", "1.0.2"), app("Hue", "4.11.0"), app("Iceberg", "1.9.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.1"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.6"), app("TensorFlow", "2.19.0"),
                    app("Tez", "0.10.2"), app("Trino", "475"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.3")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.10.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.3.0"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.2"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.1"), app("Hive", "3.1.3"),
                    app("Hudi", "0.15.0"), app("Hue", "4.11.0"), app("Iceberg", "1.8.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.1"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.5"), app("TensorFlow", "2.18.0"),
                    app("Tez", "0.10.2"), app("Trino", "470"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.3")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.9.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.3.0"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.2"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.1"), app("Hive", "3.1.3"),
                    app("Hudi", "0.15.0"), app("Hue", "4.11.0"), app("Iceberg", "1.7.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.1"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.5"), app("TensorFlow", "2.16.1"),
                    app("Tez", "0.10.2"), app("Trino", "467"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.3")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.8.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.3.0"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.1"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.1"), app("Hive", "3.1.3"),
                    app("Hudi", "0.15.0"), app("Hue", "4.11.0"), app("Iceberg", "1.7.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.1"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.4"), app("TensorFlow", "2.16.1"),
                    app("Tez", "0.10.2"), app("Trino", "467"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.3")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.7.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.2.1"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.1"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.0"), app("Hive", "3.1.3"),
                    app("Hudi", "0.15.0"), app("Hue", "4.11.0"), app("Iceberg", "1.7.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.1"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.3"), app("TensorFlow", "2.16.1"),
                    app("Tez", "0.10.2"), app("Trino", "467"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.2")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.6.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.2.1"), app("Flink", "1.20.0"),
                    app("HBase", "2.6.1"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.0"), app("Hive", "3.1.3"),
                    app("Hudi", "0.15.0"), app("Hue", "4.11.0"), app("Iceberg", "1.6.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.0"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.3"), app("TensorFlow", "2.16.1"),
                    app("Tez", "0.10.2"), app("Trino", "457"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.2")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.5.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.2.1"), app("Flink", "1.19.1"),
                    app("HBase", "2.5.10"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.0"), app("Hive", "3.1.3"),
                    app("Hudi", "0.15.0"), app("Hue", "4.11.0"), app("Iceberg", "1.6.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.0"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.2"), app("TensorFlow", "2.16.1"),
                    app("Tez", "0.10.2"), app("Trino", "446"), app("Zeppelin", "0.11.1"), app("ZooKeeper", "3.9.2")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.4.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.2.0"), app("Flink", "1.19.1"),
                    app("HBase", "2.5.5"), app("HCatalog", "3.1.3"), app("Hadoop", "3.4.0"), app("Hive", "3.1.3"),
                    app("Hudi", "0.15.0"), app("Hue", "4.11.0"), app("Iceberg", "1.6.1"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.2.0"),
                    app("Pig", "0.17.0"), app("Presto", "0.287"), app("Spark", "3.5.2"), app("Sqoop", "1.4.7"),
                    app("TensorFlow", "2.16.1"), app("Tez", "0.10.2"), app("Trino", "446"), app("Zeppelin", "0.11.1"),
                    app("ZooKeeper", "3.9.2")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.3.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.2.0"), app("Flink", "1.18.1"),
                    app("HBase", "2.4.17"), app("HCatalog", "3.1.3"), app("Hadoop", "3.3.6"), app("Hive", "3.1.3"),
                    app("Hudi", "0.15.0"), app("Hue", "4.11.0"), app("Iceberg", "1.5.2"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("Oozie", "5.2.1"), app("Phoenix", "5.1.3"),
                    app("Pig", "0.17.0"), app("Presto", "0.285"), app("Spark", "3.5.1"), app("Sqoop", "1.4.7"),
                    app("TensorFlow", "2.16.1"), app("Tez", "0.10.2"), app("Trino", "442"), app("Zeppelin", "0.11.1"),
                    app("ZooKeeper", "3.9.1")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.2.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.1.0"), app("Flink", "1.18.1"),
                    app("HBase", "2.4.17"), app("HCatalog", "3.1.3"), app("Hadoop", "3.3.6"), app("Hive", "3.1.3"),
                    app("Hudi", "0.14.1"), app("Hue", "4.11.0"), app("Iceberg", "1.5.0"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("MXNet", "1.9.1"), app("Oozie", "5.2.1"),
                    app("Phoenix", "5.1.3"), app("Pig", "0.17.0"), app("Presto", "0.285"), app("Spark", "3.5.1"),
                    app("Sqoop", "1.4.7"), app("TensorFlow", "2.11.0"), app("Tez", "0.10.2"), app("Trino", "436"),
                    app("Zeppelin", "0.10.1"), app("ZooKeeper", "3.9.1")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.1.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "3.0.0"), app("Flink", "1.18.1"),
                    app("HBase", "2.4.17"), app("HCatalog", "3.1.3"), app("Hadoop", "3.3.6"), app("Hive", "3.1.3"),
                    app("Hudi", "0.14.1"), app("Hue", "4.11.0"), app("Iceberg", "1.4.3"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.8.0-incubating"), app("MXNet", "1.9.1"), app("Oozie", "5.2.1"),
                    app("Phoenix", "5.1.3"), app("Pig", "0.17.0"), app("Presto", "0.284"), app("Spark", "3.5.0"),
                    app("Sqoop", "1.4.7"), app("TensorFlow", "2.11.0"), app("Tez", "0.10.2"), app("Trino", "435"),
                    app("Zeppelin", "0.10.1"), app("ZooKeeper", "3.9.1")),
                    AMAZON_LINUX_2023),
            new Release("emr-7.0.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300031.1"), app("Delta", "3.0.0"), app("Flink", "1.18.0"),
                    app("HBase", "2.4.17"), app("HCatalog", "3.1.3"), app("Hadoop", "3.3.6"), app("Hive", "3.1.3"),
                    app("Hudi", "0.14.0"), app("Hue", "4.11.0"), app("Iceberg", "1.4.2"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.5.0"),
                    app("Livy", "0.7.1-incubating"), app("MXNet", "1.9.1"), app("Oozie", "5.2.1"),
                    app("Phoenix", "5.1.3"), app("Pig", "0.17.0"), app("Presto", "0.283"), app("Spark", "3.5.0"),
                    app("Sqoop", "1.4.7"), app("TensorFlow", "2.11.0"), app("Tez", "0.10.2"), app("Trino", "426"),
                    app("Zeppelin", "0.10.1"), app("ZooKeeper", "3.5.10")),
                    AMAZON_LINUX_2023),
            new Release("emr-6.15.0", List.of(
                    app("Delta", "2.4.0"), app("Flink", "1.17.1"), app("Ganglia", "3.7.2"), app("HBase", "2.4.17"),
                    app("HCatalog", "3.1.3"), app("Hadoop", "3.3.6"), app("Hive", "3.1.3"), app("Hudi", "0.14.0"),
                    app("Hue", "4.11.0"), app("Iceberg", "1.4.0"), app("JupyterEnterpriseGateway", "2.6.0"),
                    app("JupyterHub", "1.5.0"), app("Livy", "0.7.1-incubating"), app("MXNet", "1.9.1"),
                    app("Oozie", "5.2.1"), app("Phoenix", "5.1.3"), app("Pig", "0.17.0"), app("Presto", "0.283"),
                    app("Spark", "3.4.1"), app("Sqoop", "1.4.7"), app("TensorFlow", "2.11.0"), app("Tez", "0.10.2"),
                    app("Trino", "426"), app("Zeppelin", "0.10.1"), app("ZooKeeper", "3.5.10")),
                    AMAZON_LINUX_2),
            new Release("emr-6.14.0", List.of(
                    app("Delta", "2.4.0"), app("Flink", "1.17.1"), app("Ganglia", "3.7.2"), app("HBase", "2.4.17"),
                    app("HCatalog", "3.1.3"), app("Hadoop", "3.3.3"), app("Hive", "3.1.3"), app("Hudi", "0.13.1"),
                    app("Hue", "4.11.0"), app("Iceberg", "1.3.1"), app("JupyterEnterpriseGateway", "2.6.0"),
                    app("JupyterHub", "1.5.0"), app("Livy", "0.7.1-incubating"), app("MXNet", "1.9.1"),
                    app("Oozie", "5.2.1"), app("Phoenix", "5.1.3"), app("Pig", "0.17.0"), app("Presto", "0.281"),
                    app("Spark", "3.4.1"), app("Sqoop", "1.4.7"), app("TensorFlow", "2.11.0"), app("Tez", "0.10.2"),
                    app("Trino", "422"), app("Zeppelin", "0.10.1"), app("ZooKeeper", "3.5.10")),
                    AMAZON_LINUX_2),
            new Release("emr-5.36.2", List.of(
                    app("Flink", "1.14.2"), app("Ganglia", "3.7.2"), app("HBase", "1.4.13"), app("HCatalog", "2.3.9"),
                    app("Hadoop", "2.10.1"), app("Hive", "2.3.9"), app("Hudi", "0.10.1"), app("Hue", "4.10.0"),
                    app("JupyterEnterpriseGateway", "2.6.0"), app("JupyterHub", "1.4.1"),
                    app("Livy", "0.7.1-incubating"), app("MXNet", "1.8.0"), app("Mahout", "0.13.0"),
                    app("Oozie", "5.2.1"), app("Phoenix", "4.14.3-HBase-1.4"), app("Pig", "0.17.0"),
                    app("Presto", "0.267"), app("Spark", "2.4.8"), app("Sqoop", "1.4.7"), app("TensorFlow", "2.4.1"),
                    app("Tez", "0.9.2"), app("Zeppelin", "0.10.0"), app("ZooKeeper", "3.4.14")),
                    AMAZON_LINUX_2),
            new Release("emr-spark-8.0.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300032.2"), app("Delta", "4.0.0"), app("Hudi", "1.1.0"),
                    app("Iceberg", "1.10.1"), app("JupyterEnterpriseGateway", "2.6.0"), app("Livy", "0.8.0-incubating"),
                    app("Spark", "4.0.2")),
                    AMAZON_LINUX_2023),
            new Release("emr-spark-8.1.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300034.0"), app("Delta", "4.2.0"), app("Hudi", "1.1.1"),
                    app("Iceberg", "1.11.0"), app("JupyterEnterpriseGateway", "2.6.0"), app("Livy", "0.9.0-incubating"),
                    app("Spark", "4.1.1")),
                    List.of()),
            new Release("emr-spark-8.2.0", List.of(
                    app("AmazonCloudWatchAgent", "1.300034.0"), app("Delta", "4.2.0"), app("Hudi", "1.1.1"),
                    app("Iceberg", "1.11.0"), app("JupyterEnterpriseGateway", "2.6.0"), app("Livy", "0.9.0-incubating"),
                    app("Spark", "4.1.3")),
                    List.of()));

    static final List<InstanceType> INSTANCE_TYPES = List.of(
            new InstanceType("c5.xlarge", 4, 8f, 0, 0, true, "c5", "X86_64"),
            new InstanceType("c5.2xlarge", 8, 16f, 0, 0, true, "c5", "X86_64"),
            new InstanceType("m5.xlarge", 4, 16f, 0, 0, true, "m5", "X86_64"),
            new InstanceType("m5.2xlarge", 8, 32f, 0, 0, true, "m5", "X86_64"),
            new InstanceType("m5.4xlarge", 16, 64f, 0, 0, true, "m5", "X86_64"),
            new InstanceType("m5d.xlarge", 4, 16f, 150, 1, false, "m5d", "X86_64"),
            new InstanceType("m5d.2xlarge", 8, 32f, 300, 1, false, "m5d", "X86_64"),
            new InstanceType("m6g.xlarge", 4, 16f, 0, 0, true, "m6g", "AARCH64"),
            new InstanceType("m6g.2xlarge", 8, 32f, 0, 0, true, "m6g", "AARCH64"),
            new InstanceType("r5.xlarge", 4, 32f, 0, 0, true, "r5", "X86_64"),
            new InstanceType("r5.2xlarge", 8, 64f, 0, 0, true, "r5", "X86_64"),
            new InstanceType("r6g.xlarge", 4, 32f, 0, 0, true, "r6g", "AARCH64"));

    private EmrReleaseCatalog() {
    }

    private static Application app(String name, String version) {
        return new Application(name, version);
    }

    static Optional<Release> release(String label) {
        return RELEASES.stream().filter(r -> r.label().equals(label)).findFirst();
    }

    // The AWS runtime for Apache Spark's releases, numbered from 8.0.0 on.
    private static final String SPARK_RUNTIME_PREFIX = "emr-spark-";

    /**
     * ListReleaseLabels' order. The numbered releases come newest first by number, emr-6.15.0 before emr-6.9.0, as
     * AWS lists them. AWS answered a recorded ListReleaseLabels for the AWS runtime for Apache Spark's releases with
     * emr-spark-8.0.0, then emr-spark-8.1.0, so those come oldest first; where AWS puts them among the numbered
     * releases is not recorded, and Floci lists them last.
     */
    static final Comparator<String> LISTING_ORDER = Comparator
            .comparing((String label) -> label.startsWith(SPARK_RUNTIME_PREFIX))
            .thenComparing((a, b) -> a.startsWith(SPARK_RUNTIME_PREFIX) ? byNumber(a, b) : byNumber(b, a));

    /** Two labels by their release numbers, lowest first. */
    private static int byNumber(String a, String b) {
        int[] x = versionKey(a);
        int[] y = versionKey(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int left = i < x.length ? x[i] : 0;
            int right = i < y.length ? y[i] : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return 0;
    }

    private static int[] versionKey(String label) {
        String[] parts = label.replaceFirst("^emr-(spark-)?", "").split("\\.");
        int[] key = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                key[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                key[i] = 0;
            }
        }
        return key;
    }
}
