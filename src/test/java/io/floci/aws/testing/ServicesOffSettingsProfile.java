package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Ten services switched off, with settings that each reach only the class relying on them, for
 * classes that call none of those services. Quarkus builds the application once per distinct
 * profile class, and keeps every one it built for the rest of the test JVM, so they share this one
 * rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code enabled=false} for ACM, CodeArtifact, DynamoDB, ECS, FIS, Lambda, OpenSearch, RDS
 *   Data, SQS and SWF. {@code ServiceEnablementIntegrationTest} relies on them, to check how a
 *   disabled service answers.</li>
 *   <li>{@code floci.services.iam.partner-permission-boundaries-path} names
 *   {@code src/test/resources/partner-permission-boundaries.json}, which registers one permissions
 *   boundary for the delegation partner account {@code 000000000001}.
 *   {@code DelegationRoleBoundaryIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.s3.restore-completion-delay-seconds=60} keeps a restored object's
 *   temporary copy in progress for a minute. {@code S3RestoreStateIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.ec2.enforce-vcpu-quotas=true}, with
 *   {@code floci.services.servicequotas.standard-on-demand-vcpus=2}, refuses a launch that would run
 *   more than two Standard On-Demand vCPUs. {@code Ec2VcpuAdmissionIntegrationTest} relies on
 *   them.</li>
 *   <li>{@code floci.services.iot.mqtt.port=}{@value #MQTT_PORT} moves the MQTT broker off the
 *   shipped 1883, which a developer's running Floci may hold. The test config leaves
 *   {@code auto-start} off, so the broker listens only once something needs it.
 *   {@code IotMqttLazyStartupIntegrationTest} and {@code IotMqttWebSocketLazyStartIntegrationTest}
 *   rely on it: they check what starts it, and stop it before and after each test.</li>
 * </ul>
 *
 * <p>Each is inert for the other classes: none of them calls a service switched off here, files a
 * delegation request as the partner account, restores an S3 object, launches an instance or
 * reaches the MQTT broker. Their requests go to IAM, CloudFormation for an IAM role, S3, EC2,
 * Service Quotas and IoT, and to the disabled services only from
 * {@code ServiceEnablementIntegrationTest}. It stays apart from
 * {@link ScopedServiceSettingsProfile}, whose classes call DynamoDB, ECS and FIS.
 *
 * <p>A class joins if it calls none of the services switched off here, if every other setting here
 * is either one it relies on or one that never reaches its requests, and if any setting it adds
 * never reaches the requests of a class already here.
 */
public class ServicesOffSettingsProfile implements QuarkusTestProfile {

    /** The port the MQTT broker listens on once something starts it. */
    public static final int MQTT_PORT = 18838;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("floci.services.acm.enabled", "false"),
                Map.entry("floci.services.codeartifact.enabled", "false"),
                Map.entry("floci.services.dynamodb.enabled", "false"),
                Map.entry("floci.services.ecs.enabled", "false"),
                Map.entry("floci.services.fis.enabled", "false"),
                Map.entry("floci.services.lambda.enabled", "false"),
                Map.entry("floci.services.opensearch.enabled", "false"),
                Map.entry("floci.services.rds-data.enabled", "false"),
                Map.entry("floci.services.sqs.enabled", "false"),
                Map.entry("floci.services.swf.enabled", "false"),
                Map.entry("floci.services.iam.partner-permission-boundaries-path",
                        "src/test/resources/partner-permission-boundaries.json"),
                Map.entry("floci.services.s3.restore-completion-delay-seconds", "60"),
                Map.entry("floci.services.ec2.enforce-vcpu-quotas", "true"),
                Map.entry("floci.services.servicequotas.standard-on-demand-vcpus", "2"),
                Map.entry("floci.services.iot.mqtt.port", Integer.toString(MQTT_PORT)));
    }
}
