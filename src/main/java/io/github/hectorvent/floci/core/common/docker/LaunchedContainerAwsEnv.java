package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Builds the baseline AWS SDK environment for a container Floci launches, so the workload
 * inside it can reach the emulator with working credentials. It uses the same variables
 * regardless of whether the container is a Lambda function or an ECS task.
 *
 * <p>Emulator-style, matching how a local AWS emulator satisfies the SDK credential-provider
 * chain: the SDK is pointed at Floci via {@code AWS_ENDPOINT_URL} and given injected workload,
 * host, or placeholder credentials. This mirrors how AWS itself supplies credentials to a
 * launched workload, so the container starts with usable credentials rather than an
 * empty provider chain ({@code Could not load credentials from any providers}).
 */
@ApplicationScoped
public class LaunchedContainerAwsEnv {

    private static final Logger LOG = Logger.getLogger(LaunchedContainerAwsEnv.class);

    /** Host-credential passthrough is a property of the process, so it is worth saying once. */
    private static final AtomicBoolean hostCredentialsWarned = new AtomicBoolean();

    /** AccountResolver reads a 12-digit access-key ID as the caller's account directly. */
    private static final Pattern ACCOUNT_ID_PATTERN = Pattern.compile("\\d{12}");

    private final ContainerReachableEndpoint reachableEndpoint;
    private final UnaryOperator<String> hostEnv;

    @Inject
    public LaunchedContainerAwsEnv(ContainerReachableEndpoint reachableEndpoint) {
        this(reachableEndpoint, System::getenv);
    }

    /** Lets tests supply a host environment; Java cannot mutate its own process env. */
    LaunchedContainerAwsEnv(ContainerReachableEndpoint reachableEndpoint, UnaryOperator<String> hostEnv) {
        this.reachableEndpoint = reachableEndpoint;
        this.hostEnv = hostEnv;
    }

    /**
     * The baseline {@code "KEY=value"} AWS SDK environment entries for a launched container:
     * region, credentials, and the Floci endpoint the SDK should target.
     *
     * @param region            the AWS region the container should use
     * @param awsConfigMountDir  in-container directory of a mounted AWS config/credentials
     *                           directory (as in {@code ~/.aws}); when present the SDK discovers
     *                           credentials from there. Empty = inject host or placeholder credentials.
     */
    public List<String> sdkBaselineEnv(String region, Optional<String> awsConfigMountDir) {
        return sdkBaselineEnv(region, awsConfigMountDir, reachableEndpoint.baseUrl(), Optional.empty());
    }

    /**
     * Variant for launchers whose workloads reach Floci at an address other than the
     * Docker-reachable one (e.g. Kubernetes pods reaching Floci's pod IP).
     */
    public List<String> sdkBaselineEnv(String region, Optional<String> awsConfigMountDir, String flociEndpoint) {
        return sdkBaselineEnv(region, awsConfigMountDir, flociEndpoint, Optional.empty());
    }

    /**
     * Builds the baseline environment with optional workload-specific session credentials.
     * Mounted AWS configuration takes precedence over both injected and fallback credentials.
     */
    public List<String> sdkBaselineEnv(String region, Optional<String> awsConfigMountDir,
                                       Optional<SessionCreds> injectedCredentials) {
        return sdkBaselineEnv(region, awsConfigMountDir, reachableEndpoint.baseUrl(), injectedCredentials);
    }

    /**
     * Variant for a workload whose owning AWS account is known. When it falls through to
     * placeholder credentials — no mounted config, no session credentials, which is every
     * Lambda that never assumes an execution role — a 12-digit {@code ownerAccountId} is
     * injected as {@code AWS_ACCESS_KEY_ID}, and it wins over Floci's own ambient
     * {@code AWS_ACCESS_KEY_ID}. The server process's credentials describe the server, not the
     * container it launches, so they must never override a known owning account: AccountResolver
     * reads a 12-digit access-key ID as the caller's account directly, and the literal
     * {@code "test"} it would otherwise see resolves every such container to the emulator's
     * default (management) account. Account-partitioned reads and writes — EC2 security groups,
     * IAM service-linked roles — then collide or miss whenever the real resource lives elsewhere.
     */
    public List<String> sdkBaselineEnv(String region, Optional<String> awsConfigMountDir,
                                       Optional<SessionCreds> injectedCredentials, String ownerAccountId) {
        return sdkBaselineEnv(region, awsConfigMountDir, reachableEndpoint.baseUrl(),
                injectedCredentials, ownerAccountId);
    }

    /**
     * Full variant taking both the Floci endpoint the workload should target and optional
     * workload-specific session credentials.
     */
    public List<String> sdkBaselineEnv(String region, Optional<String> awsConfigMountDir,
                                       String flociEndpoint, Optional<SessionCreds> injectedCredentials) {
        return sdkBaselineEnv(region, awsConfigMountDir, flociEndpoint, injectedCredentials, null);
    }

    /** Full variant, additionally taking the workload's owning account. */
    public List<String> sdkBaselineEnv(String region, Optional<String> awsConfigMountDir,
                                       String flociEndpoint, Optional<SessionCreds> injectedCredentials,
                                       String ownerAccountId) {
        var env = new ArrayList<String>();
        env.add("AWS_DEFAULT_REGION=" + region);
        env.add("AWS_REGION=" + region);
        if (awsConfigMountDir.isPresent() && !awsConfigMountDir.get().isBlank()) {
            // ~/.aws is mounted, so don't inject credentials. Let the SDK discover them.
            // Set explicit file paths so discovery works regardless of container HOME.
            var dir = awsConfigMountDir.get();
            env.add("AWS_SHARED_CREDENTIALS_FILE=" + dir + "/credentials");
            env.add("AWS_CONFIG_FILE=" + dir + "/config");
        } else if (injectedCredentials.isPresent()) {
            var credentials = injectedCredentials.get();
            env.add("AWS_ACCESS_KEY_ID=" + credentials.accessKeyId());
            env.add("AWS_SECRET_ACCESS_KEY=" + credentials.secretAccessKey());
            env.add("AWS_SESSION_TOKEN=" + credentials.sessionToken());
        } else {
            // The launched container is a distinct principal from the Floci server process, so
            // its identity comes from the workload's owning account when we know it; Floci's own
            // env vars are the fallback for launch paths that don't (ownerAccountId == null).
            var ak = hostEnv.apply("AWS_ACCESS_KEY_ID");
            var sk = hostEnv.apply("AWS_SECRET_ACCESS_KEY");
            var st = hostEnv.apply("AWS_SESSION_TOKEN");
            boolean hasOwnerAccountId = ownerAccountId != null && ACCOUNT_ID_PATTERN.matcher(ownerAccountId).matches();
            if (!hasOwnerAccountId && (ak != null || sk != null || st != null)
                    && hostCredentialsWarned.compareAndSet(false, true)) {
                // The container runs workload code, so surface that it is being handed the
                // credentials Floci itself was started with (an exported key pair, aws-vault, a
                // CI runner) rather than placeholders. Mount ~/.aws via aws-config-path, or give
                // the workload a role Floci knows, to keep host credentials out of it.
                // Once per process: ephemeral containers relaunch per invocation, and a warning
                // repeated on every launch is one people learn to scroll past. Only fires when
                // Floci's own ambient credentials are actually forwarded — the owner-account
                // branch below never forwards them, so the warning would otherwise be false.
                LOG.warnf("Forwarding Floci's own AWS credentials from the environment "
                        + "(AWS_ACCESS_KEY_ID=%s...) into launched containers", abbreviate(ak));
            }
            // The numeric owner-account access key is only ever validated (by the S3, RDS and
            // ElastiCache SigV4 checks) when paired with the literal "test" secret and session
            // token — never with Floci's own ambient secret, which those checks know nothing
            // about and which would produce a credential pair nothing can verify.
            env.add("AWS_ACCESS_KEY_ID=" + (hasOwnerAccountId ? ownerAccountId : (ak != null ? ak : "test")));
            env.add("AWS_SECRET_ACCESS_KEY=" + (hasOwnerAccountId ? "test" : (sk != null ? sk : "test")));
            env.add("AWS_SESSION_TOKEN=" + (hasOwnerAccountId ? "test" : (st != null ? st : "test")));
        }
        env.add("FLOCI_HOSTNAME=" + URI.create(flociEndpoint).getHost());
        env.add("FLOCI_ENDPOINT=" + flociEndpoint);
        env.add("AWS_ENDPOINT_URL=" + flociEndpoint);
        return env;
    }

    /** Enough of an access key to identify which credentials leaked, without logging the key. */
    private static String abbreviate(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank()) {
            return "<unset>";
        }
        return accessKeyId.length() <= 4 ? accessKeyId : accessKeyId.substring(0, 4);
    }
}
