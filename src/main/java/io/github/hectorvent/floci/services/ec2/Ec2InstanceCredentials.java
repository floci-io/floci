package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Owns credentials for the lifetime of each registered EC2 guest. */
final class Ec2InstanceCredentials {
    private static final String IDENTITY_ROLE_NAME = "aws:ec2-instance";

    private final IamService iam;
    private final SecureRandom random = new SecureRandom();
    private final Map<Instance, List<Issued>> sessions = new IdentityHashMap<>();
    private final Map<Instance, List<SessionCredential>> identitySessions = new IdentityHashMap<>();

    Ec2InstanceCredentials(IamService iam) {
        this.iam = iam;
    }

    synchronized void register(Instance instance) {
        sessions.computeIfAbsent(instance, ignored -> new ArrayList<>());
    }

    synchronized void unregister(Instance instance) {
        List<Issued> issued = sessions.remove(instance);
        if (issued != null) {
            issued.forEach(this::revoke);
        }
        List<SessionCredential> identities = identitySessions.remove(instance);
        if (identities != null) {
            identities.forEach(this::revoke);
        }
    }

    synchronized void clear() {
        sessions.values().forEach(issued -> issued.forEach(this::revoke));
        sessions.clear();
        identitySessions.values().forEach(identities -> identities.forEach(this::revoke));
        identitySessions.clear();
    }

    /**
     * Credentials of the instance identity role, which every registered instance has whether or not
     * it carries an instance profile. They are a session of the {@code aws:ec2-instance} role named
     * after the instance. No IAM role can carry that name, so no policy ever grants the session
     * anything: as on AWS, it only identifies the instance. Its user id is the principal id AWS
     * records for these credentials, {@code <account>:aws:ec2-instance:<instance-id>}.
     */
    synchronized Optional<SessionCredential> identity(Instance instance, String accountId, Instant now) {
        if (!sessions.containsKey(instance)) {
            return Optional.empty();
        }
        List<SessionCredential> history = identitySessions.computeIfAbsent(instance, ignored -> new ArrayList<>());
        history.removeIf(issued -> {
            boolean expired = !issued.getExpiration().isAfter(now);
            if (expired) {
                revoke(issued);
            }
            return expired;
        });
        if (!history.isEmpty() && history.getLast().getExpiration().isAfter(now.plusSeconds(300))) {
            return Optional.of(history.getLast());
        }
        String partition = AwsRegions.partitionFor(instance.getRegion());
        String roleArn = AwsArnUtils.Arn.global(partition, "iam", accountId, "role/" + IDENTITY_ROLE_NAME).toString();
        SessionCredential session = new SessionCredential(newAccessKey(), randomString(30), randomString(48),
                roleArn, now.plusSeconds(3600), null, accountId);
        session.setRoleSessionName(instance.getInstanceId());
        session.setEc2InstanceId(instance.getInstanceId());
        session.setAssumedRoleId(accountId + ":" + IDENTITY_ROLE_NAME + ":" + instance.getInstanceId());
        iam.registerEc2InstanceSession(session);
        history.add(session);
        return Optional.of(session);
    }

    synchronized Optional<IamRole> role(Instance instance) {
        String arn = instance.getIamInstanceProfileArn();
        if (!sessions.containsKey(instance) || arn == null) {
            return Optional.empty();
        }
        String[] parts = arn.split(":", 6);
        if (parts.length != 6 || !"arn".equals(parts[0]) || !"iam".equals(parts[2])
                || !parts[3].isEmpty() || !parts[4].matches("[0-9]{12}")
                || !parts[5].startsWith("instance-profile/")) {
            return Optional.empty();
        }
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        return iam.findInstanceProfile(parts[4], name)
                .filter(profile -> arn.equals(profile.getArn()))
                .filter(profile -> profile.getRoleNames() != null && profile.getRoleNames().size() == 1)
                .flatMap(profile -> iam.findRole(parts[4], profile.getRoleNames().getFirst()))
                .filter(role -> role.getArn() != null && role.getArn().startsWith(
                        "arn:" + parts[1] + ":iam::" + parts[4] + ":role/"));
    }

    synchronized Optional<SessionCredential> get(Instance instance, String roleName, Instant now) {
        List<Issued> history = sessions.get(instance);
        if (history == null) {
            return Optional.empty();
        }
        Optional<IamRole> resolved = role(instance);
        history.removeIf(issued -> {
            boolean obsolete = !issued.session().getExpiration().isAfter(now) || resolved.isEmpty()
                    || !Objects.equals(issued.roleId(), resolved.get().getRoleId())
                    || !Objects.equals(issued.session().getRoleArn(), resolved.get().getArn())
                    || !Objects.equals(issued.profileArn(), instance.getIamInstanceProfileArn());
            if (obsolete) {
                revoke(issued);
            }
            return obsolete;
        });
        if (resolved.isEmpty() || !resolved.get().getRoleName().equals(roleName)) {
            return Optional.empty();
        }
        if (!history.isEmpty()) {
            SessionCredential current = history.getLast().session();
            if (current.getExpiration().isAfter(now.plusSeconds(300))) {
                return Optional.of(current);
            }
        }
        IamRole role = resolved.get();
        String account = role.getArn().split(":", 6)[4];
        SessionCredential session = new SessionCredential(newAccessKey(), randomString(30), randomString(48),
                role.getArn(), now.plusSeconds(3600), null, account);
        session.setEc2InstanceId(instance.getInstanceId());
        session.setEc2RoleId(role.getRoleId());
        session.setAssumedRoleId(role.getRoleId() + ":" + instance.getInstanceId());
        iam.registerEc2InstanceSession(session);
        history.add(new Issued(session, role.getRoleId(), instance.getIamInstanceProfileArn()));
        return Optional.of(session);
    }

    private String newAccessKey() {
        byte[] key = new byte[10];
        random.nextBytes(key);
        return "ASIA" + HexFormat.of().withUpperCase().formatHex(key).substring(0, 16);
    }

    private String randomString(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void revoke(Issued issued) {
        revoke(issued.session());
    }

    private void revoke(SessionCredential session) {
        iam.unregisterSession(session.getOriginAccountId(), session.getAccessKeyId());
    }

    private record Issued(SessionCredential session, String roleId, String profileArn) {}
}
