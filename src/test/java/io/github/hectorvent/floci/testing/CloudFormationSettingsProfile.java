package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * The CloudFormation settings that differ from the test defaults, for the classes that test them.
 * Quarkus builds the application once per distinct profile class, so they share this one rather
 * than each declaring their own.
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
 * </ul>
 *
 * <p>Each override is inert for the other classes. Every type their templates declare has a
 * provisioner, so the strict gate never fires for them. Only the stub test declares a Lambda
 * function. Retention only changes what ListStacks and DescribeStacks by stack id return after a
 * delete, and the other classes look a stack up only by name, which stops answering as soon as the
 * stack is deleted.
 */
public class CloudFormationSettingsProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.cloudformation.allow-stub-unsupported-resource-types", "false",
                "floci.services.cloudformation.allow-stub-lambda-code", "true",
                "floci.services.cloudformation.deleted-stack-retention-seconds", "86400");
    }
}
