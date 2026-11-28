package io.floci.aws.testing;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * The webhook and TLS settings the EKS Pod Identity forward needs, for the classes that ask the
 * Pod Identity address for credentials from inside a container. Quarkus builds the application once
 * per distinct profile class, and keeps every one it built for the rest of the test JVM, so they
 * share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.eks.pod-identity-webhook=true}, TLS on with no AWS HTTPS port of its
 *   own, the test ports 4510 and 4511, and {@code floci.port} 4595 with the base URL on it, are what
 *   the Pod Identity forward reads. {@code EksPodIdentityDockerIntegrationTest} and
 *   {@code EksPodIdentityAgentDockerNightlyIntegrationTest} rely on them, and the second checks that the
 *   forward targets port 4595.</li>
 *   <li>{@code floci.services.eks.mock=false} runs EKS clusters for real.
 *   {@code EksPodIdentityAgentDockerNightlyIntegrationTest} starts a k3s node and relies on it.</li>
 * </ul>
 *
 * <p>{@code floci.services.eks.mock} never reaches {@code EksPodIdentityDockerIntegrationTest}: it
 * stores its cluster record and add-on through the services directly, never through CreateCluster,
 * and nothing it stores is persisted for a boot to restore, so EKS never starts or stops a cluster
 * for it.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class EksPodIdentityProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.services.eks.mock", "false",
                "floci.services.eks.pod-identity-webhook", "true",
                "floci.tls.enabled", "true",
                "floci.tls.aws-https-port", "0",
                "quarkus.http.test-port", "4510",
                "quarkus.http.test-ssl-port", "4511",
                "floci.port", "4595",
                "floci.base-url", "http://localhost:4595");
    }
}
