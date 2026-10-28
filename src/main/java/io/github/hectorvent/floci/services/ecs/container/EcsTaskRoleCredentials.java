package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mints, rotates, and revokes the IAM sessions vended through the ECS task-role credential
 * endpoint. Modeled on {@code Ec2InstanceCredentials}: unlike a persisted STS session, a task
 * credential is owned by one exact task and cannot outlive it or a Floci restart.
 */
@ApplicationScoped
public class EcsTaskRoleCredentials {

    private static final String UPPER_ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /**
     * A session is rotated once less than this much of its lifetime remains, so an in-flight SDK
     * request never races a credential that expires mid-call. AWS rotates task credentials well
     * ahead of their advertised expiry for the same reason.
     */
    private static final long ROTATE_WITHIN_SECONDS = 300;

    private final IamService iamService;
    private final long ttlSeconds;

    /** taskArn -> the task's current issued session and the relative URI it was published at. */
    private final Map<String, Issued> byTaskArn = new ConcurrentHashMap<>();
    /** relative URI path -> the session it resolves to, for the credentials endpoint's lookup. */
    private final Map<String, Issued> byPath = new ConcurrentHashMap<>();

    @Inject
    public EcsTaskRoleCredentials(IamService iamService, EmulatorConfig config) {
        this.iamService = iamService;
        this.ttlSeconds = config.services().ecs().taskRoleCredentials().ttlSeconds();
    }

    /**
     * Issues a session for the task's role, or returns its current one if it has not entered its
     * rotation window. Returns empty, minting nothing, for a role that does not exist under the
     * task's account: real ECS would fail the task launch outright on an unassumable role, but
     * this provider fails closed on the credential surface instead, since RunTask has already
     * created the task by the time containers are started.
     */
    public synchronized Optional<String> issue(String taskArn, String roleArn, String accountId, Instant now) {
        Issued current = taskArn == null ? null : byTaskArn.get(taskArn);
        if (current != null && (!current.session.getRoleArn().equals(roleArn)
                || !current.session.getOriginAccountId().equals(accountId))) {
            revoke(current);
            current = null;
        }
        if (taskArn == null || taskArn.isBlank() || roleArn == null || roleArn.isBlank()
                || accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        Optional<IamRole> role = resolveRole(roleArn, accountId);
        if (role.isEmpty() || (current != null && !Objects.equals(current.roleId, role.get().getRoleId()))) {
            if (current != null) {
                revoke(current);
            }
            return Optional.empty();
        }
        if (current != null) {
            current.previous.removeIf(session -> {
                if (!session.getExpiration().isAfter(now)) {
                    iamService.unregisterSession(accountId, session.getAccessKeyId());
                    return true;
                }
                return false;
            });
            long rotationWindow = Math.min(ROTATE_WITHIN_SECONDS, ttlSeconds / 10);
            if (current.session.getExpiration().isAfter(now.plusSeconds(rotationWindow))) {
                return Optional.of(current.path);
            }
        }

        SessionCredential session = new SessionCredential(
                "ASIA" + random(UPPER_ALPHANUMERIC, 16), randomBase64(30), randomBase64(48),
                roleArn, now.plusSeconds(ttlSeconds), null, accountId);
        session.setEcsTaskArn(taskArn);
        String taskSessionName = taskArn.contains("/") ? taskArn.substring(taskArn.lastIndexOf('/') + 1) : taskArn;
        session.setRoleSessionName(taskSessionName);
        if (role.get().getRoleId() != null) {
            session.setAssumedRoleId(role.get().getRoleId() + ":" + taskSessionName);
        }
        iamService.registerEcsTaskRoleSession(session);

        String path = current == null ? "/v2/credentials/" + UUID.randomUUID() : current.path;
        List<SessionCredential> previous = current == null ? new ArrayList<>() : current.previous;
        if (current != null) {
            if (current.session.getExpiration().isAfter(now)) {
                previous.add(current.session);
            } else {
                iamService.unregisterSession(accountId, current.session.getAccessKeyId());
            }
        }
        Issued issued = new Issued(session, path, role.get().getRoleId(), previous);
        byPath.put(path, issued);
        byTaskArn.put(taskArn, issued);
        return Optional.of(path);
    }

    /** Resolves the active session addressed by its relative URI path, for the endpoint handler. */
    public synchronized Optional<SessionCredential> resolveByPath(String path, Instant now) {
        Issued issued = path == null ? null : byPath.get(path);
        if (issued == null) {
            return Optional.empty();
        }
        return issue(issued.session.getEcsTaskArn(), issued.session.getRoleArn(),
                issued.session.getOriginAccountId(), now).map(renewedPath -> byPath.get(renewedPath).session);
    }

    /** Revokes the task's credentials on task stop, exit, or a launch that failed part way through. */
    public synchronized void revoke(String taskArn) {
        Issued issued = taskArn == null ? null : byTaskArn.get(taskArn);
        if (issued != null) {
            revoke(issued);
        }
    }

    private void revoke(Issued issued) {
        byTaskArn.remove(issued.session.getEcsTaskArn(), issued);
        byPath.remove(issued.path, issued);
        iamService.unregisterSession(issued.session.getOriginAccountId(), issued.session.getAccessKeyId());
        for (SessionCredential previous : issued.previous) {
            iamService.unregisterSession(previous.getOriginAccountId(), previous.getAccessKeyId());
        }
    }

    private Optional<IamRole> resolveRole(String roleArn, String accountId) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!"iam".equals(parsed.service()) || !parsed.accountId().equals(accountId)
                || !parsed.resource().startsWith("role/")) {
            return Optional.empty();
        }
        String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        if (roleName.isBlank()) {
            return Optional.empty();
        }
        return iamService.findRole(accountId, roleName).filter(role -> roleArn.equals(role.getArn()));
    }

    private static String random(String characters, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(characters.charAt(SECURE_RANDOM.nextInt(characters.length())));
        }
        return value.toString();
    }

    private static String randomBase64(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private record Issued(SessionCredential session, String path, String roleId, List<SessionCredential> previous) {}
}
