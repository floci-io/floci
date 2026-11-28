package io.floci.aws.testing;

import io.floci.aws.services.lambda.durable.ScriptedDurableFunctionInvoker;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Set;

/**
 * S3 request-signature enforcement, and the settings of other classes that share this application
 * because none of them reaches a service another one changes. Quarkus builds the application once
 * per distinct profile class, and keeps every one it built for the rest of the test JVM, so classes
 * share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.s3.enforce-auth=true} makes S3 verify every signature and run its own
 *   access checks. The {@code S3*AuthEnforcement}, {@code S3*BucketPolicy} and
 *   {@code S3Presigned*} classes, {@code CloudFrontS3AuthServingTest},
 *   {@code SesExportJobS3AuthEnforcementIntegrationTest} and
 *   {@code RedshiftInterceptorIamRoleEnforcedDockerNightlyIntegrationTest} rely on it.</li>
 *   <li>{@code floci.services.bedrock-runtime.backend=proxy}, with {@code proxy.url} on port
 *   {@value #BEDROCK_PROXY_PORT}, {@code proxy.model-mapping} for {@value #BEDROCK_PROXY_MAPPED_MODEL_ID}
 *   and a one-second {@code proxy.request-timeout-seconds}, send Bedrock Runtime calls to a stand-in
 *   that stalls, with passthrough off and no default model. {@code BedrockProxyTimeoutIntegrationTest}
 *   relies on them.</li>
 *   <li>{@code floci.services.emr.cluster-startup-delay-seconds}, {@code step-duration-seconds} and
 *   {@code cluster-termination-delay-seconds}, an hour each, keep a cluster STARTING and TERMINATING
 *   and its steps PENDING for the whole test. {@code EmrTimedLifecycleIntegrationTest} relies on
 *   them.</li>
 *   <li>{@code floci.services.iot.mqtt.auto-start=true} starts the plaintext MQTT broker at boot, on
 *   {@code floci.services.iot.mqtt.port}, a free loopback port chosen once when the class loads, so
 *   no other process holding a fixed port can stop this application from starting.
 *   {@code IotFleetIndexingConnectivityIntegrationTest} relies on it, and reads the port from the
 *   application's configuration.</li>
 *   <li>{@code floci.services.rds.mock=true} and {@code floci.services.rds.advertise-requested-port=true}
 *   keep RDS from starting database containers and report the port a client asked for.
 *   {@code RdsAdvertiseRequestedPortIntegrationTest} relies on them.</li>
 *   <li>{@link ScriptedDurableFunctionInvoker} replaces the Lambda container stack with a scripted
 *   stand-in. {@code LambdaDurableProtocolIntegrationTest} relies on it.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes: each is read by a single service, S3, Bedrock
 * Runtime, EMR, the IoT MQTT broker, RDS and Lambda, and no other class here calls that service.
 * A class that invokes a Lambda function cannot join, since the scripted stand-in answers it, and
 * neither can one that sends an unsigned S3 request.
 *
 * <p>{@code DynamoDbLocalBackendDockerNightlyIntegrationTest} stays apart: the DynamoDB Local backend refuses to
 * start without an endpoint, and that class only runs when its environment variable names one, so
 * an application it shared would fail to boot for every other class.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class S3EnforceAuthProfile implements QuarkusTestProfile {

    /** Port of the stalling stand-in {@code BedrockProxyTimeoutIntegrationTest} starts. */
    public static final int BEDROCK_PROXY_PORT = 18931;

    /** The model id the Bedrock proxy maps to {@code claude-3-haiku}. */
    public static final String BEDROCK_PROXY_MAPPED_MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0";

    /**
     * Port of the plaintext MQTT broker. Quarkus loads the profile apart from the test classes, so a
     * test reads the port from the application's configuration rather than from this field.
     */
    private static final int IOT_MQTT_PORT = freeLoopbackPort();

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("floci.services.s3.enforce-auth", "true"),
                Map.entry("floci.services.bedrock-runtime.backend", "proxy"),
                Map.entry("floci.services.bedrock-runtime.proxy.url", "http://127.0.0.1:" + BEDROCK_PROXY_PORT + "/v1"),
                Map.entry("floci.services.bedrock-runtime.proxy.model-mapping",
                        BEDROCK_PROXY_MAPPED_MODEL_ID + "=claude-3-haiku"),
                Map.entry("floci.services.bedrock-runtime.proxy.request-timeout-seconds", "1"),
                Map.entry("floci.services.emr.cluster-startup-delay-seconds", "3600"),
                Map.entry("floci.services.emr.step-duration-seconds", "3600"),
                Map.entry("floci.services.emr.cluster-termination-delay-seconds", "3600"),
                Map.entry("floci.services.iot.mqtt.auto-start", "true"),
                Map.entry("floci.services.iot.mqtt.port", Integer.toString(IOT_MQTT_PORT)),
                Map.entry("floci.services.rds.mock", "true"),
                Map.entry("floci.services.rds.advertise-requested-port", "true"));
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(ScriptedDurableFunctionInvoker.class);
    }

    private static int freeLoopbackPort() {
        try {
            return FreePorts.loopbackIpv4Port();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the MQTT broker of the S3 auth test application", e);
        }
    }
}
