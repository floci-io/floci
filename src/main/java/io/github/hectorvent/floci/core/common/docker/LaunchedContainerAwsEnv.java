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

    private final ContainerReachableEndpoint reachableEndpoint;

    @Inject
    public LaunchedContainerAwsEnv(ContainerReachableEndpoint reachableEndpoint) {
        this.reachableEndpoint = reachableEndpoint;
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
        return sdkBaselineEnv(region, awsConfigMountDir, Optional.empty());
    }

    /**
     * Builds the baseline environment with optional workload-specific session credentials.
     * Mounted AWS configuration takes precedence over both injected and fallback credentials.
     */
    public List<String> sdkBaselineEnv(String region, Optional<String> awsConfigMountDir,
                                       Optional<SessionCreds> injectedCredentials) {
        List<String> env = new ArrayList<>();
        env.add("AWS_DEFAULT_REGION=" + region);
        env.add("AWS_REGION=" + region);
        if (awsConfigMountDir.isPresent() && !awsConfigMountDir.get().isBlank()) {
            // ~/.aws is mounted, so don't inject credentials. Let the SDK discover them.
            // Set explicit file paths so discovery works regardless of container HOME.
            String dir = awsConfigMountDir.get();
            env.add("AWS_SHARED_CREDENTIALS_FILE=" + dir + "/credentials");
            env.add("AWS_CONFIG_FILE=" + dir + "/config");
        } else if (injectedCredentials.isPresent()) {
            SessionCreds credentials = injectedCredentials.get();
            env.add("AWS_ACCESS_KEY_ID=" + credentials.accessKeyId());
            env.add("AWS_SECRET_ACCESS_KEY=" + credentials.secretAccessKey());
            env.add("AWS_SESSION_TOKEN=" + credentials.sessionToken());
        } else {
            // Use Floci's own env vars, falling back to placeholder credentials.
            String ak = System.getenv("AWS_ACCESS_KEY_ID");
            String sk = System.getenv("AWS_SECRET_ACCESS_KEY");
            String st = System.getenv("AWS_SESSION_TOKEN");
            if ((ak != null || sk != null || st != null) && hostCredentialsWarned.compareAndSet(false, true)) {
                // The container runs workload code, so surface that it is being handed the
                // credentials Floci itself was started with (an exported key pair, aws-vault, a
                // CI runner) rather than placeholders. Mount ~/.aws via aws-config-path, or give
                // the workload a role Floci knows, to keep host credentials out of it.
                // Once per process: ephemeral containers relaunch per invocation, and a warning
                // repeated on every launch is one people learn to scroll past.
                LOG.warnf("Forwarding Floci's own AWS credentials from the environment "
                        + "(AWS_ACCESS_KEY_ID=%s...) into launched containers", abbreviate(ak));
            }
            env.add("AWS_ACCESS_KEY_ID=" + (ak != null ? ak : "test"));
            env.add("AWS_SECRET_ACCESS_KEY=" + (sk != null ? sk : "test"));
            env.add("AWS_SESSION_TOKEN=" + (st != null ? st : "test"));
        }
        String flociEndpoint = reachableEndpoint.baseUrl();
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
