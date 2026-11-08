package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Runs EKS clusters as real k3s containers, which the test defaults mock, for the classes that test
 * what happens inside one. Quarkus builds the application once per distinct profile class, so they
 * share this one rather than each declaring their own.
 *
 * <p>{@code floci.services.eks.mock=false} is the only override.
 * {@code EksRegistryHostsDockerIntegrationTest} relies on it: it creates its cluster and node group
 * through the EKS API, which starts no container while EKS is mocked.
 * {@code EksStorageClassDockerIntegrationTest} passes with it off, since it starts its cluster
 * through {@code EksClusterManager} directly, which runs a k3s container either way. It shares
 * the profile because it runs a real cluster too. Each class removes the clusters it starts, so the
 * next one finds no container left behind.
 *
 * <p>{@code EksIrsaDockerIntegrationTest} keeps a profile of its own: it also turns on IAM
 * enforcement, which would decide every request the other classes send.
 */
public class RealEksProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.services.eks.mock", "false");
    }
}
