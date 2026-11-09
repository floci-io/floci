package io.floci.aws.services.codebuild;

import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.ecs.container.EcsCredentialsProxy;
import io.floci.aws.services.ecs.container.EcsTaskLinkLocalAddresses;
import io.floci.aws.services.ecs.container.EcsTaskRoleCredentials;
import io.floci.aws.services.ecs.container.EcsTaskRoleCredentialsServer;
import io.floci.aws.services.iam.model.SessionCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Hands a build a session of its project's service role, as CodeBuild does: "CodeBuild uses the
 * service role for all operations that are performed on your behalf" (AWS CodeBuild User Guide,
 * Allow CodeBuild to interact with other AWS services). CodeBuild hands it through the container
 * credentials provider, one variable, {@code AWS_CONTAINER_CREDENTIALS_RELATIVE_URI}, whose path
 * the SDKs read at {@code http://169.254.170.2}, and no keys in the build's environment.
 *
 * <p>The session is the role's, minted in the project's account and named
 * {@code AWSCodeBuild-<the build id after the project's name>}, as CodeBuild names it, so
 * {@code GetCallerIdentity} answers its {@code assumed-role} ARN and IAM enforcement evaluates the
 * role's policies. It is served by the same credentials server and per-network proxy as an ECS
 * task's role, which start on a build's need whatever ECS's own setting says. On Docker's default
 * bridge no container can hold the link-local address that reaches the proxy, so there the same
 * session is handed as keys instead, and the build still runs as its role.
 *
 * <p>The role's trust policy is not evaluated: like an ECS task's, the session is minted for any
 * role that exists.
 */
@ApplicationScoped
public class CodeBuildRoleCredentials {

    static final String RELATIVE_URI = "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI";
    private static final String SESSION_PREFIX = "AWSCodeBuild-";
    private static final long SERVER_START_SECONDS = 30;

    private final EcsTaskRoleCredentials sessions;
    private final EcsTaskRoleCredentialsServer server;
    private final EcsCredentialsProxy proxy;
    private final EcsTaskLinkLocalAddresses addresses;

    @Inject
    public CodeBuildRoleCredentials(EcsTaskRoleCredentials sessions, EcsTaskRoleCredentialsServer server,
                                    EcsCredentialsProxy proxy, EcsTaskLinkLocalAddresses addresses) {
        this.sessions = sessions;
        this.server = server;
        this.proxy = proxy;
        this.addresses = addresses;
    }

    /**
     * Mints the build's session and readies what serves it on the build's network, before the
     * build's container is made.
     *
     * @param buildArn the build's ARN, which owns the session and its address until {@link #release}
     * @param network  the Docker network the build's container joins, empty for the default bridge
     * @throws RoleNotFoundException when the role names no role in the account; nothing is minted
     * @throws IllegalStateException when the credentials endpoint cannot be served on the network
     */
    public BuildCredentials provision(String buildArn, String buildId, String roleArn, String accountId,
                                      Optional<String> network) {
        String sessionName = SESSION_PREFIX + buildId.substring(buildId.indexOf(':') + 1);
        Optional<String> path = sessions.issue(buildArn, roleArn, accountId, sessionName, Instant.now());
        if (path.isEmpty()) {
            throw new RoleNotFoundException("The project's service role " + roleArn
                    + " was not found in account " + accountId + ", so the build ran none of its commands");
        }
        Optional<String> endpointNetwork = network.filter(ContainerSpec::acceptsLinkLocalIps);
        if (endpointNetwork.isEmpty()) {
            SessionCredential session = sessions.resolveByPath(path.get(), Instant.now()).orElseThrow(() ->
                    new IllegalStateException("The session of " + roleArn + " was revoked as it was minted"));
            Map<String, String> env = new LinkedHashMap<>();
            env.put("AWS_ACCESS_KEY_ID", session.getAccessKeyId());
            env.put("AWS_SECRET_ACCESS_KEY", session.getSecretAccessKey());
            env.put("AWS_SESSION_TOKEN", session.getSessionToken());
            return new BuildCredentials(env, null);
        }
        startServer();
        proxy.ensureProxyOn(endpointNetwork.get());
        String address = addresses.allocate(endpointNetwork.get(), buildArn);
        return new BuildCredentials(Map.of(RELATIVE_URI, path.get()), address);
    }

    /** Revokes every generation of the build's session, however the build ended. */
    public void revoke(String buildArn) {
        sessions.revoke(buildArn);
    }

    /** Returns the build's address to its network's pool, once its container is gone. */
    public void releaseAddress(String buildArn) {
        addresses.releaseAll(buildArn);
    }

    private void startServer() {
        try {
            server.start().get(SERVER_START_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the container credentials server", e);
        } catch (ExecutionException | TimeoutException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("The container credentials server did not start: " + cause.getMessage(), e);
        }
    }

    /**
     * What the build's container is given: the variables that hand it its session, and its own
     * link-local address on the network, null on the default bridge.
     */
    public record BuildCredentials(Map<String, String> env, String linkLocalAddress) {
    }

    /** A project's service role that names no role in the project's account. */
    public static final class RoleNotFoundException extends RuntimeException {
        RoleNotFoundException(String message) {
            super(message);
        }
    }
}
