package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Container-backed EC2, which the test defaults mock, and the settings of other classes that share
 * this application because none of them reaches a service another one changes. Quarkus builds the
 * application once per distinct profile class, and keeps every one it built for the rest of the
 * test JVM, so classes share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.ec2.mock=false} runs EC2 through its container manager, which the mock
 *   mode skips entirely. {@code Ec2CapturedImageReclaimIntegrationTest},
 *   {@code Ec2GetConsoleOutputIntegrationTest} and {@code Ec2CreateKeyPairLaunchIntegrationTest}
 *   rely on it, each with the container manager mocked, and so does
 *   {@code Ec2RestoreEveryAccountIntegrationTest}, whose restore resolves an instance's security
 *   groups because their enforcement is on by test default.</li>
 *   <li>{@code floci.security.extra-cors-allowed-origins=}{@value #EXTRA_CORS_ORIGIN} allows one
 *   extra CORS origin and leaves every other CORS setting at its default, private network access
 *   included. {@code ApiGatewayPreflightRoutingIntegrationTest}, {@code CognitoCorsIntegrationTest}
 *   and {@code GlobalCorsFilterPrivateNetworkDefaultIntegrationTest} rely on it.</li>
 *   <li>{@code floci.services.lambda.accept-external-layer-arns=true} records a foreign-account layer
 *   ARN verbatim, {@code floci.services.lambda.hot-reload.allowed-paths=/home/ci/code} limits
 *   hot-reload mounts to that allow-list, and {@code floci.services.lambda.reactive-s3-sync.async=true}
 *   re-deploys a function in the background rather than inside the upload of its code.
 *   {@code LambdaExternalLayerArnAcceptedIntegrationTest}, {@code LambdaHotReloadAllowListIntegrationTest}
 *   and {@code LambdaAsyncReactiveSyncIntegrationTest} each rely on one of them.</li>
 *   <li>{@code floci.services.sqs.endpoint-strategy=aws} gives queue URLs the AWS form.
 *   {@code SqsAwsEndpointStrategyIntegrationTest} relies on it.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes: EC2 mode only reaches EC2 requests, the extra
 * origin only a request that sends that {@code Origin}, the Lambda settings only the code a function
 * is created or updated with, and the endpoint strategy only SQS queue URLs, and no other class here
 * makes such a request.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class ContainerBackedEc2Profile implements QuarkusTestProfile {

    /** The one extra CORS origin allowed. */
    public static final String EXTRA_CORS_ORIGIN = "http://localhost:3000";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.ec2.mock", "false",
                "floci.security.extra-cors-allowed-origins", EXTRA_CORS_ORIGIN,
                "floci.services.lambda.accept-external-layer-arns", "true",
                "floci.services.lambda.hot-reload.allowed-paths", "/home/ci/code",
                "floci.services.lambda.reactive-s3-sync.async", "true",
                "floci.services.sqs.endpoint-strategy", "aws");
    }
}
