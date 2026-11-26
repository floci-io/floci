package io.floci.aws.services.emr;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Trino on the primary node of a {@code docker} cluster whose {@code Applications} name it, with
 * {@code floci.services.emr.trino.enabled} on: no AWS image carries Trino, so its coordinator, which runs the queries
 * too, as a cluster of one node's does, runs from the Trino project's image of the version the release's page in the
 * Release Guide gives, in a container of its own that shares the primary node's network, as a daemon of that node.
 * "By default, Amazon EMR configures the Presto web interface on the Presto coordinator to use port 8889 (for PrestoDB
 * and Trino)", where it listens unless the cluster's {@code trino-config} sets {@code http-server.http.port}, which
 * classification changes "Trino's config.properties file". Its catalogs are the {@code hive} one and those the
 * cluster's {@code trino-connector-<catalog>} classifications name, each of which changes "Trino's
 * {@code <catalog>.properties} file". EMR installs {@code hcatalog-server} with Trino, so the catalogs whose tables
 * are in S3, {@code hive}, {@code delta}, {@code hudi} and {@code iceberg}, are on the cluster's Hive metastore, as
 * the Release Guide's Delta Lake and Iceberg tutorials have them at {@code thrift://localhost:9083}, unless the
 * classification puts them in the Glue Data Catalog, as "Launch an Amazon EMR cluster with Trino" does. It starts as
 * the cluster becomes ready, with the primary node, steps or none. Its steps run {@code trino-cli}, EMR's command for
 * Trino's CLI, which this class writes for the image they run in, and clients outside the cluster reach it through
 * Floci on a host of the cluster's own ({@link EmrTrinoRoutes}).
 */
final class EmrTrino {

    /** The application's name, as the Release Guide's list of applications spells it. */
    static final String APPLICATION = "Trino";
    /** The port Trino listens on, on the primary node, unless the cluster's {@code trino-config} names another. */
    static final int PORT = 8889;
    /** The prefix of the classifications that each change a catalog, the rest of whose name names it. */
    static final String CONNECTOR_PREFIX = "trino-connector-";
    /** The classification that changes Trino's {@code config.properties}. */
    static final String CONFIG_CLASSIFICATION = "trino-config";
    /** The classifications that change Trino's {@code node.properties} and {@code log.properties}. */
    static final String NODE_CLASSIFICATION = "trino-node";
    static final String LOG_CLASSIFICATION = "trino-log";
    /** The classification whose nested {@code export} gives Trino's environment, Trino's {@code trino-env.sh}. */
    static final String ENV_CLASSIFICATION = "trino-env";
    /** The catalog every Trino cluster has. */
    static final String HIVE = "hive";
    /**
     * A catalog's name as Floci takes it: Trino's own, letters, digits and underscores, which the entrypoint passes as
     * the name of a shell variable.
     */
    static final Pattern CATALOG_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    /** The component label of a cluster's Trino container, which goes with its primary node's. */
    static final String COMPONENT = "emr-trino";
    /**
     * The label under Floci's host name of the hosts clients outside the cluster reach a cluster's Trino at, one per
     * cluster ({@link #address}).
     */
    static final String HOST_LABEL = "emr-trino";
    /** The variable a step's {@code trino-cli} reads the coordinator's address from. */
    static final String SERVER_VARIABLE = "FLOCI_TRINO_SERVER";
    /** Trino's CLI in its image, an executable jar, which the cluster's image takes. */
    static final String IMAGE_CLI = "/usr/bin/trino";
    /** Where the cluster's image keeps that jar. */
    static final String CLI = "/usr/lib/trino/trino-cli";
    /** The folder of EMR's {@code trino-cli} command, and its name. */
    static final String COMMAND_FOLDER = "/usr/bin";
    static final String COMMAND_NAME = "trino-cli";
    /**
     * EMR's {@code trino-cli}: the CLI on the image's Java 17, which a release whose default is Java 8 has besides, to
     * the cluster's coordinator, unless the step names a server of its own.
     */
    static final String COMMAND = """
            #!/bin/bash
            java=/usr/lib/jvm/jre-17/bin/java
            [ -x "$java" ] || java=java
            for argument in "$@"; do
              case "$argument" in
                --server|--server=*) exec "$java" -jar %1$s "$@" ;;
              esac
            done
            exec "$java" -jar %1$s --server "${%2$s:-localhost:%3$d}" "$@"
            """.formatted(CLI, SERVER_VARIABLE, PORT);
    /** The variables the Trino container's entrypoint writes its configuration, node and log properties from. */
    static final String CONFIG_VARIABLE = "FLOCI_TRINO_CONFIG";
    static final String NODE_VARIABLE = "FLOCI_TRINO_NODE";
    static final String LOG_VARIABLE = "FLOCI_TRINO_LOG";
    /** The prefix of the variables it writes its catalogs from, one each, the rest of whose name names it. */
    static final String CATALOG_PREFIX = "FLOCI_TRINO_CATALOG_";
    /**
     * The Trino container's entrypoint: its configuration; the cluster's node and log properties after those of its
     * image's own files, which a property given twice takes the later of, so an image of the cluster's own keeps its
     * settings; and the catalogs Floci gives it alone, as EMR's Trino has the catalogs the cluster configures; then
     * Trino, as its image starts it. Where {@code node.properties} gives the node no id, its image names it after its
     * host, the primary node's, whose dots Trino refuses in a node's id, so Floci names it with hyphens instead.
     */
    static final String ENTRYPOINT = "printf '%s\\n' \"$" + CONFIG_VARIABLE + "\" > /etc/trino/config.properties"
            + " && { [ -z \"$" + NODE_VARIABLE + "\" ] || printf '\\n%s\\n' \"$" + NODE_VARIABLE + "\""
            + " >> /etc/trino/node.properties; }"
            + " && { [ -z \"$" + LOG_VARIABLE + "\" ] || printf '\\n%s\\n' \"$" + LOG_VARIABLE + "\""
            + " >> /etc/trino/log.properties; }"
            + " && rm -f /etc/trino/catalog/*.properties"
            + " && for catalog in ${!" + CATALOG_PREFIX + "@}; do printf '%s\\n' \"${!catalog}\""
            + " > \"/etc/trino/catalog/${catalog#" + CATALOG_PREFIX + "}.properties\" || exit; done"
            + " && { grep -q '^node\\.id=' /etc/trino/node.properties"
            + " || printf '\\nnode.id=%s\\n' \"${HOSTNAME//./-}\" >> /etc/trino/node.properties; }"
            + " && exec /usr/lib/trino/bin/run-trino";

    private static final String GLUE = "glue";
    private static final String METASTORE_URI = "hive.metastore.uri";
    // The catalogs of EMR's classifications whose connector Trino names otherwise; the others are named as theirs.
    private static final Map<String, String> CONNECTORS = Map.of("delta", "delta_lake", "raptor", "raptor_legacy");
    // The catalogs whose tables are in S3, their metadata in a Hive metastore or the Glue Data Catalog.
    private static final Set<String> LAKES = Set.of(HIVE, "delta", "hudi", "iceberg");

    private EmrTrino() {
    }

    /**
     * The image Trino runs from for a release: {@code template} with the release's Trino version for {@code %s}, as
     * its page in the Release Guide gives it without AWS's build suffix, or {@code template} itself when it has no
     * {@code %s}. Null for a release whose version Floci does not know, which then runs no Trino.
     */
    static String image(String template, String releaseLabel) {
        if (template == null || template.isBlank()) {
            return null;
        }
        if (!template.contains("%s")) {
            return template;
        }
        return EmrReleaseCatalog.release(releaseLabel)
                .flatMap(release -> release.applications().stream()
                        .filter(application -> APPLICATION.equals(application.name())).findFirst())
                .map(application -> template.formatted(application.version()))
                .orElse(null);
    }

    /** The port Trino listens on: the {@code http-server.http.port} of the cluster's {@code trino-config}, or EMR's. */
    static int port(Map<String, String> config) {
        String port = config.get("http-server.http.port");
        if (port != null) {
            try {
                return Integer.parseInt(port.trim());
            } catch (NumberFormatException ignored) {
                // Trino refuses it as it starts, and says so in its log.
            }
        }
        return PORT;
    }

    /** The address a step's {@code trino-cli} reaches the coordinator at, on the primary node named {@code host}. */
    static String server(String host, int port) {
        return "http://" + host + ":" + port;
    }

    /**
     * The address clients outside the cluster reach its Trino at, through Floci at {@code baseUrl}: a host of the
     * cluster's own under {@link #HOST_LABEL}, as EMR's clients reach the primary node's.
     */
    static String address(String baseUrl, String clusterId) {
        URI base = URI.create(baseUrl);
        return base.getScheme() + "://" + clusterId.toLowerCase(Locale.ROOT) + "." + HOST_LABEL + "."
                + base.getRawAuthority();
    }

    /** What tells the Trino container is ready: a query, run with the CLI of its image on {@code port}. */
    static List<String> readyQuery(int port) {
        return List.of(IMAGE_CLI, "--server", "localhost:" + port, "--execute", "SELECT 1");
    }

    /**
     * The coordinator's {@code config.properties}: a coordinator that runs the queries too, on its {@link #port}, its
     * discovery at that port unless the cluster's {@code trino-config} names another; the scheme a client reached it
     * over through Floci taken from {@code X-Forwarded-Proto}, as it names the query's next pages by the host and
     * scheme it was asked on; and that classification's properties after these, each in its place.
     */
    static Map<String, String> config(Map<String, String> classification) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("coordinator", "true");
        config.put("node-scheduler.include-coordinator", "true");
        config.put("http-server.http.port", String.valueOf(PORT));
        config.put("discovery.uri", "http://localhost:" + port(classification));
        config.put("http-server.process-forwarded", "true");
        config.putAll(classification);
        return config;
    }

    /**
     * Trino's catalogs, by name: {@code hive}, then each that {@code connectors}, the cluster's
     * {@code trino-connector-<catalog>} classifications' properties by catalog, name (see {@link #catalog}).
     */
    static Map<String, Map<String, String>> catalogs(Map<String, Map<String, String>> connectors, String endpoint,
                                                     String region) {
        Map<String, Map<String, String>> catalogs = new LinkedHashMap<>();
        catalogs.put(HIVE, catalog(HIVE, connectors.getOrDefault(HIVE, Map.of()), endpoint, region));
        connectors.forEach((name, classification) -> catalogs.computeIfAbsent(name,
                catalog -> catalog(catalog, classification, endpoint, region)));
        return catalogs;
    }

    /**
     * A catalog: the connector of its name, as the Release Guide's Delta Lake tutorial gives {@code delta} no
     * {@code connector.name} of its own; for {@code hive}, {@code delta}, {@code hudi} and {@code iceberg}, the
     * cluster's metastore, which runs beside Trino on the primary node, or Floci's Glue where the classification says
     * {@code glue} ({@code hive.metastore}, or Iceberg's {@code iceberg.catalog.type}), and Floci's S3 at
     * {@code endpoint} in {@code region}, by path, with the HDFS of the cluster's warehouse through Hadoop's file
     * system; for {@code delta}, writes to S3, which the tutorial makes with only a metastore configured; and the
     * classification's properties after these, each in its place. Credentials come from the container's environment,
     * as neither file system has a setting for a session's token.
     */
    static Map<String, String> catalog(String name, Map<String, String> classification, String endpoint,
                                       String region) {
        Map<String, String> catalog = new LinkedHashMap<>();
        catalog.put("connector.name", CONNECTORS.getOrDefault(name, name));
        if (LAKES.contains(name)) {
            String type = "iceberg".equals(name) ? classification.get("iceberg.catalog.type")
                    : classification.get("hive.metastore");
            if (GLUE.equalsIgnoreCase(type)) {
                catalog.put("iceberg".equals(name) ? "iceberg.catalog.type" : "hive.metastore", GLUE);
                catalog.put("hive.metastore.glue.region", region);
                catalog.put("hive.metastore.glue.endpoint-url", endpoint);
            } else if (type == null || "thrift".equalsIgnoreCase(type) || "hive_metastore".equalsIgnoreCase(type)) {
                catalog.put(METASTORE_URI, "thrift://localhost:" + EmrClusterStorage.HIVE_METASTORE_PORT);
            }
            catalog.put("fs.native-s3.enabled", "true");
            catalog.put("fs.hadoop.enabled", "true");
            catalog.put("s3.endpoint", endpoint);
            catalog.put("s3.region", region);
            catalog.put("s3.path-style-access", "true");
        }
        if ("delta".equals(name)) {
            // The tutorial's INSERT INTO a table in S3, which Trino refuses there unless told it may.
            catalog.put("delta.enable-non-concurrent-writes", "true");
        }
        catalog.putAll(classification);
        return catalog;
    }

    /** {@code properties} as the lines of a Java properties file, in their order. */
    static String properties(Map<String, String> properties) {
        return properties.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }
}
