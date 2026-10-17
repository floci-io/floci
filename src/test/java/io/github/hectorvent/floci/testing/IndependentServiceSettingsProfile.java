package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * One setting each for services that only one class in this group calls, for the classes that
 * test those settings. Quarkus builds the application once per distinct profile class, so they
 * share this one rather than each declaring their own.
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
 * </ul>
 *
 * <p>Each override is inert for the other classes. Each one is read by a single service: the
 * Bedrock AgentCore data plane on an invocation, IAM's account alias actions, Kinesis ListShards,
 * Neptune cluster creation, Route53 VPC association and authorization, CloudFront origin fetches,
 * Cognito app client validation, and the Scheduler dispatcher. Each class is the only one here
 * that calls its service, so no override reaches another class, and neither does anything a class
 * leaves behind.
 *
 * <p>A class joins only if no other class here calls its service. One that shares a service with
 * a class already here takes a profile of its own.
 */
public class IndependentServiceSettingsProfile implements QuarkusTestProfile {

    public static final String IAM_ACCOUNT_ALIAS = "seeded-alias";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.bedrock-agent-core.validate-runtime-exists", "true",
                "floci.services.iam.account-alias", IAM_ACCOUNT_ALIAS,
                "floci.services.kinesis.list-shards-next-token-ttl-millis", "0",
                "floci.services.neptune.db-type", "neo4j",
                "floci.services.route53.vpc-association-control-plane-delay-ms", "150",
                "floci.services.cloudfront.allowed-private-origin-hosts", "127.0.0.1",
                "floci.services.cognito.enforce-token-validity-limits", "false",
                "floci.services.scheduler.invocation-enabled", "false");
    }
}
