package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Turns {@code floci.auth.validate-signatures} on, and carries the settings of two ECS classes that
 * share the application. Quarkus builds the application once per distinct profile class, and keeps
 * every one it built for the rest of the test JVM, so classes share this one rather than each
 * declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.auth.validate-signatures=true} checks every request's SigV4 signature. The
 *   signature, STS, presigned URL and SigV4a classes here rely on it.</li>
 *   <li>{@code floci.services.ecs.mock=false} runs ECS tasks as real containers, and
 *   {@code floci.network.security-group-enforcement.enabled=false} puts security groups back to
 *   their shipped default of off, since the enforcement helper needs rootful Docker.
 *   {@code EcsServiceDiscoveryDockerNightlyIntegrationTest} and {@code EcsLeftoverContainersDockerIntegrationTest}
 *   rely on them.</li>
 * </ul>
 *
 * <p>Each override is inert for the other classes: the ECS classes drive ECS through its injected
 * services, so no request of theirs is signed or checked, and no signature class starts an ECS task
 * or a container that security groups would govern.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class ValidateSignaturesProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.auth.validate-signatures", "true",
                "floci.services.ecs.mock", "false",
                "floci.network.security-group-enforcement.enabled", "false");
    }
}
