package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * The CloudFormation settings that differ from the test defaults, and the settings of other classes
 * that share this application because none of them reaches a service another one changes. Quarkus
 * builds the application once per distinct profile class, and keeps every one it built for the rest
 * of the test JVM, so classes share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.cloudformation.allow-stub-unsupported-resource-types=false} fails a
 *   resource type Floci has no provisioner for instead of stubbing it.
 *   {@code CloudFormationUnsupportedResourceTypeStrictIntegrationTest} relies on it.
 *   {@code ApiGatewayV2DomainCfnIntegrationTest} runs under it too, so that a type nothing
 *   provisions would fail its stacks instead of passing as a stub; it passes without it.</li>
 *   <li>{@code floci.services.cloudformation.allow-stub-lambda-code=true} gives a Lambda function
 *   whose code in S3 cannot be read the built-in stub handler instead of failing it.
 *   {@code CloudFormationLambdaStubCodeAllowedIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.cloudformation.deleted-stack-retention-seconds=86400} keeps a deleted
 *   stack in ListStacks, and answering DescribeStacks by its stack id, for a day instead of 30
 *   seconds. {@code CloudFormationDeletedStackListingRaceTest} relies on it.</li>
 *   <li>{@code floci.services.timestream-influxdb.mock=false} backs each DB instance with an
 *   InfluxDB container, published on a host port from {@code host-port-base} to
 *   {@code host-port-max}, set here to 18186 and 18285. {@code TimestreamInfluxDbDockerIntegrationTest}
 *   relies on it.</li>
 *   <li>{@code floci.services.eks.imds=true} and {@code floci.services.eks.imds-pod-network=true}
 *   program the link-local IMDS endpoint into a cluster's container, for the node and for its pods.
 *   {@code EksImdsDockerIntegrationTest} relies on them.</li>
 *   <li>{@code floci.services.emr.runner-mode=docker} and
 *   {@code floci.services.emrserverless.runner-mode=docker} run a cluster's steps and an EMR
 *   Serverless application's job runs in containers of the release's image, and
 *   {@code floci.services.emrserverless.executor-containers=2} a Spark job run's executors each in a
 *   container of its own. {@code EmrDockerRunnerDockerIntegrationTest},
 *   {@code EmrServerlessHiveDockerIntegrationTest} and the EMR nightly checks rely on them.</li>
 *   <li>{@code floci.services.cloudfront.enabled=false} turns CloudFront off.
 *   {@code CloudFrontDisabledServingTest} relies on it.</li>
 *   <li>{@code floci.storage.services.dynamodb.mode=persistent}, with its items in the
 *   {@code per-table} layout split into four shards, keeps DynamoDB on disk under
 *   {@code floci.storage.persistent-path}, set here to {@value #DYNAMODB_PARTITIONED_STORAGE_DIR}.
 *   {@code DynamoDbPartitionedStorageIntegrationTest} relies on all of it.</li>
 *   <li>{@code floci.services.sagemaker.gpu.enabled=true} gives a GPU training job a device: in
 *   {@code cdi} mode with the device the {@value #SAGEMAKER_GPU_CDI_DEVICE_PROPERTY} system property
 *   names, or in {@code count} mode when it names none. {@code SageMakerGpuDockerIntegrationTest}
 *   relies on it.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes: each is read by a single service, CloudFormation,
 * the Timestream for InfluxDB data plane, an EKS cluster's container start, the EMR and EMR
 * Serverless runners, CloudFront, DynamoDB and SageMaker training, and no other class here calls
 * that service. The persistent path is the one setting every class sees: it moves where this
 * application writes what services keep on disk whatever their storage mode, Timestream backups and
 * Lambda code-volume markers among them, each under a directory of its own, and DynamoDB is the only
 * service here that stores its records there.
 *
 * <p>No profile here names a container namespace: an EMR cluster's node container name must stay
 * within the 63-character DNS label limit for its HDFS to resolve its datanodes, which is why these
 * classes stay out of {@link SidecarContainersProfile}.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class CloudFormationSettingsProfile implements QuarkusTestProfile {

    /** Where DynamoDB keeps its items, in shard files. */
    public static final String DYNAMODB_PARTITIONED_STORAGE_DIR = "target/dynamodb-partitioned-it";

    /** The system property that names a CDI GPU device for the SageMaker GPU class. */
    public static final String SAGEMAKER_GPU_CDI_DEVICE_PROPERTY = "gpu.integration.cdi-device";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(Map.ofEntries(
                Map.entry("floci.services.cloudformation.allow-stub-unsupported-resource-types", "false"),
                Map.entry("floci.services.cloudformation.allow-stub-lambda-code", "true"),
                Map.entry("floci.services.cloudformation.deleted-stack-retention-seconds", "86400"),
                Map.entry("floci.services.timestream-influxdb.mock", "false"),
                Map.entry("floci.services.timestream-influxdb.host-port-base", "18186"),
                Map.entry("floci.services.timestream-influxdb.host-port-max", "18285"),
                Map.entry("floci.services.eks.imds", "true"),
                Map.entry("floci.services.eks.imds-pod-network", "true"),
                Map.entry("floci.services.emr.runner-mode", "docker"),
                Map.entry("floci.services.emrserverless.runner-mode", "docker"),
                Map.entry("floci.services.emrserverless.executor-containers", "2"),
                Map.entry("floci.services.cloudfront.enabled", "false"),
                Map.entry("floci.storage.services.dynamodb.mode", "persistent"),
                Map.entry("floci.storage.services.dynamodb.item-layout", "per-table"),
                Map.entry("floci.storage.services.dynamodb.item-shards", "4"),
                Map.entry("floci.storage.persistent-path", DYNAMODB_PARTITIONED_STORAGE_DIR),
                Map.entry("floci.services.sagemaker.gpu.enabled", "true")));
        String device = System.getProperty(SAGEMAKER_GPU_CDI_DEVICE_PROPERTY);
        if (device != null && !device.isBlank()) {
            overrides.put("floci.services.sagemaker.gpu.mode", "cdi");
            overrides.put("floci.services.sagemaker.gpu.devices", device);
        } else {
            overrides.put("floci.services.sagemaker.gpu.mode", "count");
        }
        return overrides;
    }
}
