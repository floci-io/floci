package io.floci.aws.testing;

import java.util.HashMap;
import java.util.Map;

/**
 * IAM enforcement and signature validation on, over the sidecar containers' namespace and port, for
 * the classes that check what a role may do when AppSync or EventBridge acts as it. Quarkus builds
 * the application once per distinct profile class, and keeps every one it built for the rest of the
 * test JVM, so they share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@link SidecarContainersProfile#containerOverrides()} names the containers and points a
 *   launched container's callbacks at the port the application listens on.
 *   {@code AppSyncHttpDataSourceSidecarIntegrationTest} and
 *   {@code EventBridgeAppSyncSidecarIntegrationTest} run the GraphQL sidecar and rely on it.</li>
 *   <li>{@code floci.services.iam.enforcement-enabled=true} evaluates every request against the
 *   caller's policies. All three classes check a decision it makes: the role an HTTP data source
 *   signs as, and the role an EventBridge target and a DynamoDB data source act as.</li>
 *   <li>{@code floci.auth.validate-signatures=true} rejects a request whose SigV4 signature does not
 *   match its credentials. {@code AppSyncHttpDataSourceSidecarIntegrationTest} and
 *   {@code AwsIamHttpDataSourceIntegrationTest} check the signature an AWS_IAM HTTP data source
 *   sends with it.</li>
 * </ul>
 *
 * <p>The namespace never reaches {@code AwsIamHttpDataSourceIntegrationTest}, which starts no
 * container, and the port is the one it already sends to. {@code EventBridgeAppSyncSidecarIntegrationTest}
 * sends every request through an SDK client, which signs it, so signature validation passes it.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if it signs every request it sends.
 */
public class SidecarIamEnforcementProfile extends SidecarContainersProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(containerOverrides());
        overrides.put("floci.services.iam.enforcement-enabled", "true");
        overrides.put("floci.auth.validate-signatures", "true");
        return overrides;
    }
}
