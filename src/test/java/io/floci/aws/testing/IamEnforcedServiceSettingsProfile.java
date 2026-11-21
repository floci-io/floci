package io.floci.aws.testing;

import io.floci.aws.testutil.FreePorts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * IAM and SCP enforcement, with service settings that each reach only the class relying on them,
 * for IAM classes whose own settings would reach the classes on {@link IamEnforcementProfile}.
 * Quarkus builds the application once per distinct profile class, and keeps every one it built for
 * the rest of the test JVM, so they share this one rather than each declaring their own.
 *
 * <p>The overrides, and who relies on them:
 * <ul>
 *   <li>{@link IamEnforcementProfile#enforcementOverrides()} evaluates every request against the
 *   caller's IAM policies, and caps a member account by the SCPs its organization attaches. Every
 *   class here asserts a decision IAM enforcement makes. None creates an organization, so the SCP
 *   lookup finds no ceiling for any account they use.</li>
 *   <li>{@code floci.services.rds.mock=true} and {@code floci.services.docdb.mock=true} keep RDS and
 *   DocumentDB from starting database containers. {@code RdsResourceIamIntegrationTest} relies on
 *   them.</li>
 *   <li>{@code floci.services.dsql.mock=false} backs each DSQL cluster with a real database in
 *   Docker, so a connection is let through or refused as the cluster's policy decides.
 *   {@code DsqlClusterPolicyIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.lambda.ephemeral=true} removes a function's container once its
 *   invocation ends, rather than keeping it warm. {@code LambdaExecutionRoleDockerIntegrationTest}
 *   relies on it.</li>
 *   <li>{@code floci.services.s3.global-bucket-namespace=true} resolves a bucket name across
 *   accounts, so finding the bucket does not depend on the ambient account.
 *   {@code S3PresignedPostBucketPolicyAccountContextIntegrationTest} relies on it.</li>
 *   <li>{@code floci.services.ec2.image-catalog-path} names
 *   {@code src/test/resources/ec2/kubelet-image-catalog.yaml}, a full EC2 image catalog in place of
 *   the built-in one, whose kubelet worker image registers its node itself.
 *   {@code EksKubeletRegistrationIntegrationTest} relies on it. Its one request through the HTTP
 *   layer signs with a 12-digit account id as the access key, which IAM enforcement passes through,
 *   and it creates its cluster through the service directly, so enforcement never reaches it.</li>
 *   <li>The HTTP port, {@code floci.port} and {@code floci.base-url} name one free port, chosen once
 *   when the class loads, so a Lambda container calls back to the port the application listens on.
 *   {@code LambdaExecutionRoleDockerIntegrationTest} relies on it. The other classes send to the
 *   port RestAssured or the test-port setting reports, which is the same one.</li>
 * </ul>
 *
 * <p>Each service setting is inert for the other classes: none of them calls RDS, DocumentDB, DSQL
 * or Lambda, the only S3 call outside the S3 class is the function's ListBuckets in
 * {@code LambdaExecutionRoleDockerIntegrationTest}, which its execution role denies whichever way
 * bucket names resolve, and only the kubelet class launches an instance or looks up an image.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class IamEnforcedServiceSettingsProfile extends IamEnforcementProfile {

    private static final int TEST_PORT = freePort();

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(enforcementOverrides());
        overrides.put("floci.services.rds.mock", "true");
        overrides.put("floci.services.docdb.mock", "true");
        overrides.put("floci.services.dsql.mock", "false");
        overrides.put("floci.services.lambda.ephemeral", "true");
        overrides.put("floci.services.s3.global-bucket-namespace", "true");
        overrides.put("floci.services.ec2.image-catalog-path", "src/test/resources/ec2/kubelet-image-catalog.yaml");
        overrides.put("quarkus.http.test-port", String.valueOf(TEST_PORT));
        overrides.put("floci.port", String.valueOf(TEST_PORT));
        overrides.put("floci.base-url", "http://localhost:" + TEST_PORT);
        return overrides;
    }

    private static int freePort() {
        try {
            return FreePorts.anyInterfacePort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the IAM-enforced service settings test application", e);
        }
    }
}
