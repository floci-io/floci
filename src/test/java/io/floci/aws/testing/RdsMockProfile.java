package io.floci.aws.testing;

import io.floci.aws.services.dynamodb.backend.RefusingDynamoDbBackendLifecycle;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;

/**
 * Mocks the RDS container layer, and carries the settings of the other classes that share this
 * application because none of them reaches a service another one changes. Quarkus builds the
 * application once per distinct profile class, and keeps every one it built for the rest of the
 * test JVM, so classes share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.rds.mock=true} keeps RDS, Aurora and the CloudFormation RDS resources
 *   from starting database containers. The {@code Rds*IntegrationTest} classes and the
 *   {@code CloudFormationRds*} and {@code CloudFormationSecretTargetAttachment} classes rely on
 *   it.</li>
 *   <li>{@code floci.services.elbv2.mock=false} runs the real ELBv2 data plane. The
 *   {@code ElbV2*IntegrationTest} data-plane classes, {@code ApiGatewayV2AlbIntegrationTest} and
 *   {@code CloudFrontWebSocketIntegrationTest} rely on it.</li>
 *   <li>{@code floci.services.cloudfront.allowed-private-origin-hosts=127.0.0.1} lets a distribution
 *   use the ALB listener at 127.0.0.1 as its custom origin, which CloudFront otherwise refuses as a
 *   private address. {@code CloudFrontWebSocketIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.eks.mock=false} runs EKS clusters as real k3s containers.
 *   {@code EksRegistryHostsDockerNightlyIntegrationTest} relies on it, since it creates its cluster and
 *   node group through the EKS API. {@code EksStorageClassDockerNightlyIntegrationTest} and
 *   {@code EksNodeMountPropagationDockerNightlyIntegrationTest} start theirs through
 *   {@code EksClusterManager}, which runs a k3s container either way.</li>
 *   <li>{@code floci.services.cur.emit-mode=synchronous} and
 *   {@code floci.services.bcm-data-exports.emit-mode=synchronous} write billing reports inline,
 *   and {@code quarkus.http.test-port} and {@code quarkus.http.port} set to one free port, chosen
 *   once when the class loads, give the DuckDB sidecar a real port to reach Floci S3 on, since the
 *   endpoint a container is handed is built from {@code quarkus.http.port}.
 *   {@code CurEmissionDockerNightlyIntegrationTest} and {@code CurNonDefaultAccountEmissionDockerNightlyIntegrationTest} rely
 *   on them. The other classes only see a listener on that port instead of another random one.</li>
 *   <li>{@code floci.services.iot.enabled=false} turns IoT off, so its data-plane host routing never
 *   sees a request. {@code ApiGatewayCustomDomainIamAuthIntegrationTest} relies on it, to check the
 *   custom-domain invocation path with IoT disabled.</li>
 *   <li>{@code floci.services.emr.default-release-label=emr-7.15.0} sets a default release the
 *   catalogue does not describe. {@code EmrReleaseCatalogCustomDefaultIntegrationTest} relies on
 *   it.</li>
 *   <li>{@code floci.services.firehose.flush-record-count=2} delivers a batch of two records inline.
 *   {@code FirehoseLambdaTransformIntegrationTest} relies on it: each of its tests puts a batch of
 *   two.</li>
 *   <li>{@code floci.services.elasticache.default-image=}{@link #ELASTICACHE_SHELL_LESS_IMAGE},
 *   {@code floci.services.elasticache.shell-less-backend=true} and
 *   {@code floci.services.elasticache.shell-less-backend-dbfilename=dump.kdb} run the ElastiCache
 *   backend the way an image with no shell and no CLI needs, against the public KiviDB image, which
 *   is distroless and names its snapshot file {@code dump.kdb} without reporting it as
 *   {@code dbfilename}. {@code ElastiCacheShellLessBackendIntegrationTest} relies on them.</li>
 *   <li>{@link RefusingDynamoDbBackendLifecycle} replaces the DynamoDB backend lifecycle with one that
 *   starts nothing and refuses every emulator reset. {@code DynamoDbBackendResetIntegrationTest}
 *   relies on it. The refusal comes from the reset preflight, before any service is cleared.
 *   DynamoDB itself never starts in this application: its state and its TTL sweeper are set up by
 *   the lifecycle this one replaces.</li>
 *   <li>{@code floci.protocols.reject-unknown-service-scope=false} turns off two refusals that
 *   would otherwise answer with {@code UnknownOperationException}: a REST request signed for a
 *   scope the catalog does not list, and a known REST JSON service's request that lands on S3's
 *   path-style wildcard route or on another service's colliding route. Either now falls through to
 *   the route it matched. {@code UnknownServiceScopeGuardDisabledIntegrationTest} relies on it, and
 *   checks both.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes. Each is read by a single service: RDS, the
 * ELBv2 data plane, CloudFront origin fetches, EKS, the CUR and BCM Data Exports emitters, IoT, the
 * EMR release catalogue, Firehose delivery, ElastiCache and the DynamoDB backend. No other class
 * here calls the service an override changes, none calls DynamoDB, and no class here posts an
 * emulator reset apart from {@code DynamoDbBackendResetIntegrationTest}, whose reset is the refused
 * one. The two refusals the guard setting turns off only answer a request signed for an unlisted
 * scope or a known REST JSON service's request on a route that is not its own, and only
 * {@code UnknownServiceScopeGuardDisabledIntegrationTest} sends either.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here. It must
 * also neither reset the emulator nor call DynamoDB, directly or through a CloudFormation stack, nor
 * expect either refusal above, so a class that needs RDS mocked and nothing else can join on those
 * terms.
 */
public class RdsMockProfile implements QuarkusTestProfile {

    /** KiviDB v1.2.2, the multi-arch index digest. Bumping it is a deliberate change. */
    public static final String ELASTICACHE_SHELL_LESS_IMAGE =
            "ghcr.io/kividbio/kividb:v1.2.2@sha256:8853c7b1e73e604502fd584533440cb1205ae311fe805d520543b4e85a4b71e9";

    private static final int TEST_PORT = freePort();

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("floci.services.rds.mock", "true"),
                Map.entry("floci.services.elbv2.mock", "false"),
                Map.entry("floci.services.cloudfront.allowed-private-origin-hosts", "127.0.0.1"),
                Map.entry("floci.services.eks.mock", "false"),
                Map.entry("floci.services.cur.emit-mode", "synchronous"),
                Map.entry("floci.services.bcm-data-exports.emit-mode", "synchronous"),
                Map.entry("quarkus.http.test-port", String.valueOf(TEST_PORT)),
                Map.entry("quarkus.http.port", String.valueOf(TEST_PORT)),
                Map.entry("floci.services.iot.enabled", "false"),
                Map.entry("floci.services.emr.default-release-label", "emr-7.15.0"),
                Map.entry("floci.services.firehose.flush-record-count", "2"),
                Map.entry("floci.services.elasticache.default-image", ELASTICACHE_SHELL_LESS_IMAGE),
                Map.entry("floci.services.elasticache.shell-less-backend", "true"),
                Map.entry("floci.services.elasticache.shell-less-backend-dbfilename", "dump.kdb"),
                Map.entry("floci.protocols.reject-unknown-service-scope", "false"));
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(RefusingDynamoDbBackendLifecycle.class);
    }

    private static int freePort() {
        try {
            return FreePorts.anyInterfacePort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the RDS mock test application", e);
        }
    }
}
