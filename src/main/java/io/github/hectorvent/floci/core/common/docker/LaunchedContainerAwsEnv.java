package io.github.hectorvent.floci.core.common.docker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the baseline AWS SDK environment for a container Floci launches, so the workload
 * inside it can reach the emulator with working credentials — the same variables regardless of
 * whether the container is a Lambda function or an ECS task.
 *
 * <p>Emulator-style, matching how a local AWS emulator satisfies the SDK credential-provider
 * chain: the SDK is pointed at Floci via {@code AWS_ENDPOINT_URL} and given placeholder
 * credentials (Floci does not verify request signatures). This mirrors how AWS itself supplies
 * credentials to a launched workload — Lambda via its execution role, an ECS task via its task
 * role — so that in either case the container starts with usable credentials rather than an
 * empty provider chain ({@code Could not load credentials from any providers}).
 */
@ApplicationScoped
public class LaunchedContainerAwsEnv {

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
     *                           credentials from there. Empty = inject placeholder credentials.
     */
    public List<String> sdkBaselineEnv(String region, Optional<String> awsConfigMountDir) {
        List<String> env = new ArrayList<>();
        env.add("AWS_DEFAULT_REGION=" + region);
        env.add("AWS_REGION=" + region);
        if (awsConfigMountDir.isPresent() && !awsConfigMountDir.get().isBlank()) {
            // ~/.aws is mounted — don't inject credentials, let the SDK discover them.
            // Set explicit file paths so discovery works regardless of container HOME.
            String dir = awsConfigMountDir.get();
            env.add("AWS_SHARED_CREDENTIALS_FILE=" + dir + "/credentials");
            env.add("AWS_CONFIG_FILE=" + dir + "/config");
        } else {
            // Use Floci's own env vars, falling back to placeholder credentials.
            String ak = System.getenv("AWS_ACCESS_KEY_ID");
            String sk = System.getenv("AWS_SECRET_ACCESS_KEY");
            String st = System.getenv("AWS_SESSION_TOKEN");
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
}
