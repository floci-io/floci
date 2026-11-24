package io.floci.aws.services.emr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trino on the primary node of a {@code docker} cluster whose {@code Applications} name it, with
 * {@code floci.services.emr.trino.enabled} on: no AWS image carries Trino, so its coordinator, which runs the queries
 * too, as a cluster of one node's does, runs from the Trino project's image of the version the release's page in the
 * Release Guide gives, in a container of its own that shares the primary node's network, as a daemon of that node.
 * "By default, Amazon EMR configures the Presto web interface on the Presto coordinator to use port 8889 (for PrestoDB
 * and Trino)", where it listens. EMR installs {@code hcatalog-server} with Trino, so its {@code hive} catalog is the
 * cluster's Hive metastore, unless the cluster's {@code trino-connector-hive} classification sets
 * {@code hive.metastore} to {@code glue}, as the Release Guide's "Launch an Amazon EMR cluster with Trino" does. Its
 * steps run {@code trino-cli}, EMR's command for Trino's CLI, which this class writes for the image they run in.
 */
final class EmrTrino {

    /** The application's name, as the Release Guide's list of applications spells it. */
    static final String APPLICATION = "Trino";
    /** The port Trino listens on, on the primary node. */
    static final int PORT = 8889;
    /** The classification whose properties go into the {@code hive} catalog. */
    static final String HIVE_CONNECTOR = "trino-connector-hive";
    /** The component label of a cluster's Trino container, which goes with its primary node's. */
    static final String COMPONENT = "emr-trino";
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
    /** What tells the Trino container is ready: a query, run with the CLI of its image. */
    static final List<String> READY_QUERY = List.of(IMAGE_CLI, "--server", "localhost:" + PORT, "--execute",
            "SELECT 1");
    /** The variables the Trino container's entrypoint writes its configuration from. */
    static final String CONFIG_VARIABLE = "FLOCI_TRINO_CONFIG";
    static final String CATALOG_VARIABLE = "FLOCI_TRINO_CATALOG_HIVE";
    /**
     * The Trino container's entrypoint: its configuration, and its {@code hive} catalog alone, as EMR's Trino has the
     * catalogs the cluster configures, then Trino, as its image starts it. Its image names the node after its host,
     * which is the primary node's, whose dots Trino refuses in a node's id, so the node is named with hyphens instead.
     */
    static final String ENTRYPOINT = "printf '%s\\n' \"$" + CONFIG_VARIABLE + "\" > /etc/trino/config.properties"
            + " && rm -f /etc/trino/catalog/*.properties"
            + " && printf '%s\\n' \"$" + CATALOG_VARIABLE + "\" > /etc/trino/catalog/hive.properties"
            + " && printf 'node.id=%s\\n' \"${HOSTNAME//./-}\" >> /etc/trino/node.properties"
            + " && exec /usr/lib/trino/bin/run-trino";
    /** A coordinator that runs the queries too, on {@link #PORT}. */
    static final String CONFIG = """
            coordinator=true
            node-scheduler.include-coordinator=true
            http-server.http.port=%1$d
            discovery.uri=http://localhost:%1$d""".formatted(PORT);

    private static final String GLUE = "glue";

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

    /** The address a step's {@code trino-cli} reaches the coordinator at, on the primary node named {@code host}. */
    static String server(String host) {
        return "http://" + host + ":" + PORT;
    }

    /**
     * The {@code hive} catalog: the cluster's metastore, which runs beside Trino on the primary node, or, with
     * {@code hive.metastore} set to {@code glue}, Floci's Glue; Floci's S3 at {@code endpoint} in {@code region}, by
     * path, the HDFS of the cluster's warehouse through Hadoop's file system; and the cluster's
     * {@code trino-connector-hive} properties after these, each in its place. Credentials come from the container's
     * environment, as neither file system has a setting for a session's token.
     */
    static Map<String, String> hiveCatalog(Map<String, String> classification, String endpoint, String region) {
        Map<String, String> catalog = new LinkedHashMap<>();
        catalog.put("connector.name", "hive");
        if (GLUE.equalsIgnoreCase(classification.get("hive.metastore"))) {
            catalog.put("hive.metastore", GLUE);
            catalog.put("hive.metastore.glue.region", region);
            catalog.put("hive.metastore.glue.endpoint-url", endpoint);
        } else {
            catalog.put("hive.metastore.uri", "thrift://localhost:" + EmrClusterStorage.HIVE_METASTORE_PORT);
        }
        catalog.put("fs.native-s3.enabled", "true");
        catalog.put("fs.hadoop.enabled", "true");
        catalog.put("s3.endpoint", endpoint);
        catalog.put("s3.region", region);
        catalog.put("s3.path-style-access", "true");
        catalog.putAll(classification);
        return catalog;
    }

    /** {@code properties} as the lines of a Java properties file, in their order. */
    static String properties(Map<String, String> properties) {
        return properties.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
    }
}
