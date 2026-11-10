package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.config.source.yaml.YamlConfigSource;

import java.util.HashMap;
import java.util.Map;

/**
 * The settings of services that only one class in this group calls, for the classes that test
 * those settings. Quarkus builds the application once per distinct profile class, so they share
 * this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.bedrock-agent-core.validate-runtime-exists=true} answers an
 *   invocation of an unknown runtime with 404. {@code BedrockAgentCoreInvokeValidateIntegrationTest}
 *   relies on it.</li>
 *   <li>{@code floci.services.iam.account-alias=}{@value #IAM_ACCOUNT_ALIAS} seeds the account alias
 *   at startup. {@code IamSeededAccountAliasIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.kinesis.list-shards-next-token-ttl-millis=0} expires a ListShards
 *   token as soon as it is issued. {@code KinesisListShardsTokenExpiryIntegrationTest} relies on
 *   it.</li>
 *   <li>{@code floci.services.neptune.db-type=neo4j} backs a new cluster with Neo4j rather than
 *   Gremlin Server. {@code NeptuneOpenCypherIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.route53.vpc-association-control-plane-delay-ms=150} keeps a hosted
 *   zone busy for 150 ms after a VPC association or authorization change, so an overlapping change
 *   is refused.
 *   {@code Route53VpcAssociationTransientErrorIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.cloudfront.allowed-private-origin-hosts=127.0.0.1} lets a distribution
 *   fetch from a custom origin at 127.0.0.1, which is otherwise refused as a private address.
 *   {@code CloudFrontCustomOriginServingTest} relies on it.</li>
 *   <li>{@code floci.services.cognito.enforce-token-validity-limits=false} lets an app client set a
 *   token validity outside the range AWS allows. {@code CognitoTokenValidityLimitsOffIntegrationTest}
 *   relies on it.</li>
 *   <li>{@code floci.services.scheduler.invocation-enabled=false} keeps the background schedule
 *   dispatcher off, so a schedule fires only when a test ticks a dispatcher of its own.
 *   {@code SchedulerRateFirstOccurrenceIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.rds-data.database-resuming-exception-enabled=true} answers a
 *   statement sent to a paused Aurora Serverless cluster with DatabaseResumingException, and
 *   {@code floci.services.rds.aurora-resume-delay-millis=5000} keeps the cluster resuming for five
 *   seconds. {@code RdsDataResumingIntegrationTest} relies on both.</li>
 *   <li>{@code floci.services.firehose.flush-record-count=1} delivers every record to S3 as soon as
 *   it arrives. {@code FirehoseFlushRecordCountIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.lambda.runtime-images."python3.12"} pins the {@code python3.12}
 *   runtime to {@link #LAMBDA_PYTHON_312_IMAGE}, set through a quoted YAML key.
 *   {@code ImageResolverConfigIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.iot.mqtt.enabled=false} keeps the MQTT broker off.
 *   {@code IotMqttDisabledIntegrationTest} relies on it, and checks that nothing listens where the
 *   broker would have, which {@code floci.services.iot.mqtt.host=127.0.0.1} and
 *   {@code floci.services.iot.mqtt.port=}{@value #IOT_MQTT_PORT} pin to an address it knows.</li>
 *   <li>{@code floci.services.ec2.mock=false} runs instances as real containers, and
 *   {@code floci.services.ec2.volume-block-devices=true} attaches EBS volumes to them as block
 *   devices. {@code Ec2VolumeBlockDeviceDockerIntegrationTest} relies on both.</li>
 *   <li>{@code floci.services.sagemaker.gpu.enabled=true} gives a training job on a GPU instance
 *   type a GPU, and fails a job whose GPU request Floci cannot honour rather than training it on
 *   CPU. {@code SageMakerGpuIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.bedrock-runtime.backend=proxy} and
 *   {@code floci.services.bedrock-runtime.proxy.url=not a valid url} send Bedrock Runtime calls to
 *   a proxy whose URL does not parse, and
 *   {@code floci.services.bedrock-runtime.proxy.passthrough=true} passes the model id through
 *   unmapped, so a call gets as far as that URL.
 *   {@code BedrockProxyInvalidUrlIntegrationTest} relies on all three.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes. Each one is read by a single service: the
 * Bedrock AgentCore data plane on an invocation, IAM's account alias actions, Kinesis ListShards,
 * Neptune cluster creation, Route53 VPC association and authorization, CloudFront origin fetches,
 * Cognito app client validation, the Scheduler dispatcher, the RDS Data API and Aurora auto-pause,
 * Firehose delivery, the Lambda runtime image resolver, the IoT MQTT broker, EC2, SageMaker
 * training jobs and Bedrock Runtime. Each class is the only one here that calls its service, so no
 * override reaches another class. Route53 and RDS do look VPCs and subnets up in EC2, but only
 * read what EC2 has stored, which does not change with EC2 mocked or not.
 *
 * <p>Each class also deletes what it creates, so a class that joins later finds nothing left
 * behind. The one exception is the training jobs {@code SageMakerGpuIntegrationTest} starts:
 * SageMaker has no way to delete a training job, and no other class here calls SageMaker.
 *
 * <p>A class joins only if no other class here calls its service, and it deletes what it creates.
 * One that shares a service with a class already here takes a profile of its own.
 */
public class IndependentServiceSettingsProfile implements QuarkusTestProfile {

    public static final String IAM_ACCOUNT_ALIAS = "seeded-alias";

    public static final String LAMBDA_PYTHON_312_IMAGE =
            "public.ecr.aws/lambda/python:3.12@sha256:" + "a".repeat(64);

    public static final int IOT_MQTT_PORT = 18830;

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(Map.ofEntries(
                Map.entry("floci.services.bedrock-agent-core.validate-runtime-exists", "true"),
                Map.entry("floci.services.iam.account-alias", IAM_ACCOUNT_ALIAS),
                Map.entry("floci.services.kinesis.list-shards-next-token-ttl-millis", "0"),
                Map.entry("floci.services.neptune.db-type", "neo4j"),
                Map.entry("floci.services.route53.vpc-association-control-plane-delay-ms", "150"),
                Map.entry("floci.services.cloudfront.allowed-private-origin-hosts", "127.0.0.1"),
                Map.entry("floci.services.cognito.enforce-token-validity-limits", "false"),
                Map.entry("floci.services.scheduler.invocation-enabled", "false"),
                Map.entry("floci.services.rds-data.database-resuming-exception-enabled", "true"),
                Map.entry("floci.services.rds.aurora-resume-delay-millis", "5000"),
                Map.entry("floci.services.firehose.flush-record-count", "1"),
                Map.entry("floci.services.iot.mqtt.enabled", "false"),
                Map.entry("floci.services.iot.mqtt.host", "127.0.0.1"),
                Map.entry("floci.services.iot.mqtt.port", Integer.toString(IOT_MQTT_PORT)),
                Map.entry("floci.services.ec2.mock", "false"),
                Map.entry("floci.services.ec2.volume-block-devices", "true"),
                Map.entry("floci.services.sagemaker.gpu.enabled", "true"),
                Map.entry("floci.services.bedrock-runtime.backend", "proxy"),
                Map.entry("floci.services.bedrock-runtime.proxy.url", "not a valid url"),
                Map.entry("floci.services.bedrock-runtime.proxy.passthrough", "true")));
        overrides.putAll(new YamlConfigSource("runtime-images", """
                floci:
                  services:
                    lambda:
                      runtime-images:
                        "python3.12": "%s"
                """.formatted(LAMBDA_PYTHON_312_IMAGE)).getProperties());
        return overrides;
    }
}
