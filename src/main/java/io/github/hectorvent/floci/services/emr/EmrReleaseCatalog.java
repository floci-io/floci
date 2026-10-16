package io.github.hectorvent.floci.services.emr;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The EMR releases ListReleaseLabels, DescribeReleaseLabel and ListSupportedInstanceTypes report: a
 * curated set of recent releases, not AWS's full list. Each release's applications and versions come
 * from its page in the Amazon EMR Release Guide (application names lowercased and versions without
 * AWS's {@code -amzn-N} build suffix, as the API reports them), with the two most recent Amazon Linux
 * releases that page lists. The instance types are common EMR families with their EC2 sizes, all of
 * which these releases support.
 */
final class EmrReleaseCatalog {

    record Application(String name, String version) {}

    record Release(String label, List<Application> applications, List<String> osReleases) {}

    record InstanceType(String type, int vcpu, float memoryGb, int storageGb, int numberOfDisks,
                        boolean ebsStorageOnly, String instanceFamilyId, String architecture) {}

    /** Newest first, as ListReleaseLabels returns them. */
    static final List<Release> RELEASES = List.of(
            new Release("emr-7.5.0", List.of(
                    app("amazoncloudwatchagent", "1.300032.2"),
                    app("delta", "3.2.1"),
                    app("flink", "1.19.1"),
                    app("hadoop", "3.4.0"),
                    app("hbase", "2.5.10"),
                    app("hcatalog", "3.1.3"),
                    app("hive", "3.1.3"),
                    app("hudi", "0.15.0"),
                    app("hue", "4.11.0"),
                    app("iceberg", "1.6.1"),
                    app("jupyterenterprisegateway", "2.6.0"),
                    app("jupyterhub", "1.5.0"),
                    app("livy", "0.8.0-incubating"),
                    app("oozie", "5.2.1"),
                    app("phoenix", "5.2.0"),
                    app("pig", "0.17.0"),
                    app("presto", "0.287"),
                    app("spark", "3.5.2"),
                    app("tensorflow", "2.16.1"),
                    app("tez", "0.10.2"),
                    app("trino", "446"),
                    app("zeppelin", "0.11.1"),
                    app("zookeeper", "3.9.2")),
                    List.of("2023.12.20260803.3", "2023.12.20260727.0")),
            new Release("emr-7.4.0", List.of(
                    app("amazoncloudwatchagent", "1.300032.2"),
                    app("delta", "3.2.0"),
                    app("flink", "1.19.1"),
                    app("hadoop", "3.4.0"),
                    app("hbase", "2.5.5"),
                    app("hcatalog", "3.1.3"),
                    app("hive", "3.1.3"),
                    app("hudi", "0.15.0"),
                    app("hue", "4.11.0"),
                    app("iceberg", "1.6.1"),
                    app("jupyterenterprisegateway", "2.6.0"),
                    app("jupyterhub", "1.5.0"),
                    app("livy", "0.8.0-incubating"),
                    app("oozie", "5.2.1"),
                    app("phoenix", "5.2.0"),
                    app("pig", "0.17.0"),
                    app("presto", "0.287"),
                    app("spark", "3.5.2"),
                    app("sqoop", "1.4.7"),
                    app("tensorflow", "2.16.1"),
                    app("tez", "0.10.2"),
                    app("trino", "446"),
                    app("zeppelin", "0.11.1"),
                    app("zookeeper", "3.9.2")),
                    List.of("2023.12.20260803.3", "2023.12.20260727.0")),
            new Release("emr-6.15.0", List.of(
                    app("delta", "2.4.0"),
                    app("flink", "1.17.1"),
                    app("ganglia", "3.7.2"),
                    app("hadoop", "3.3.6"),
                    app("hbase", "2.4.17"),
                    app("hcatalog", "3.1.3"),
                    app("hive", "3.1.3"),
                    app("hudi", "0.14.0"),
                    app("hue", "4.11.0"),
                    app("iceberg", "1.4.0"),
                    app("jupyterenterprisegateway", "2.6.0"),
                    app("jupyterhub", "1.5.0"),
                    app("livy", "0.7.1-incubating"),
                    app("mxnet", "1.9.1"),
                    app("oozie", "5.2.1"),
                    app("phoenix", "5.1.3"),
                    app("pig", "0.17.0"),
                    app("presto", "0.283"),
                    app("spark", "3.4.1"),
                    app("sqoop", "1.4.7"),
                    app("tensorflow", "2.11.0"),
                    app("tez", "0.10.2"),
                    app("trino", "426"),
                    app("zeppelin", "0.10.1"),
                    app("zookeeper", "3.5.10")),
                    List.of("2.0.20260803.1", "2.0.20260727.0")),
            new Release("emr-6.14.0", List.of(
                    app("delta", "2.4.0"),
                    app("flink", "1.17.1"),
                    app("ganglia", "3.7.2"),
                    app("hadoop", "3.3.3"),
                    app("hbase", "2.4.17"),
                    app("hcatalog", "3.1.3"),
                    app("hive", "3.1.3"),
                    app("hudi", "0.13.1"),
                    app("hue", "4.11.0"),
                    app("iceberg", "1.3.1"),
                    app("jupyterenterprisegateway", "2.6.0"),
                    app("jupyterhub", "1.5.0"),
                    app("livy", "0.7.1-incubating"),
                    app("mxnet", "1.9.1"),
                    app("oozie", "5.2.1"),
                    app("phoenix", "5.1.3"),
                    app("pig", "0.17.0"),
                    app("presto", "0.281"),
                    app("spark", "3.4.1"),
                    app("sqoop", "1.4.7"),
                    app("tensorflow", "2.11.0"),
                    app("tez", "0.10.2"),
                    app("trino", "422"),
                    app("zeppelin", "0.10.1"),
                    app("zookeeper", "3.5.10")),
                    List.of("2.0.20260803.1", "2.0.20260727.0")),
            new Release("emr-5.36.2", List.of(
                    app("flink", "1.14.2"),
                    app("ganglia", "3.7.2"),
                    app("hadoop", "2.10.1"),
                    app("hbase", "1.4.13"),
                    app("hcatalog", "2.3.9"),
                    app("hive", "2.3.9"),
                    app("hudi", "0.10.1"),
                    app("hue", "4.10.0"),
                    app("jupyterenterprisegateway", "2.6.0"),
                    app("jupyterhub", "1.4.1"),
                    app("livy", "0.7.1-incubating"),
                    app("mahout", "0.13.0"),
                    app("mxnet", "1.8.0"),
                    app("oozie", "5.2.1"),
                    app("phoenix", "4.14.3-HBase-1.4"),
                    app("pig", "0.17.0"),
                    app("presto", "0.267"),
                    app("spark", "2.4.8"),
                    app("sqoop", "1.4.7"),
                    app("tensorflow", "2.4.1"),
                    app("tez", "0.9.2"),
                    app("zeppelin", "0.10.0"),
                    app("zookeeper", "3.4.14")),
                    List.of("2.0.20260803.1", "2.0.20260727.0")));

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

    /** Numeric release order, so emr-6.15.0 sorts after emr-6.9.0. */
    static final Comparator<String> NEWEST_FIRST = Comparator.comparing(EmrReleaseCatalog::versionKey,
            (a, b) -> {
                for (int i = 0; i < Math.max(a.length, b.length); i++) {
                    int x = i < a.length ? a[i] : 0;
                    int y = i < b.length ? b[i] : 0;
                    if (x != y) {
                        return Integer.compare(y, x);
                    }
                }
                return 0;
            });

    private static int[] versionKey(String label) {
        String[] parts = label.replaceFirst("^emr-", "").split("\\.");
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
