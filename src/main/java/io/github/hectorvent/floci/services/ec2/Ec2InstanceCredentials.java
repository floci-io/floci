package io.github.hectorvent.floci.services.ec2;

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
    private final IamService iam;
    private final SecureRandom random = new SecureRandom();
    private final Map<Instance, List<Issued>> sessions = new IdentityHashMap<>();

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
    }

    synchronized void clear() {
        sessions.values().forEach(issued -> issued.forEach(this::revoke));
        sessions.clear();
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
        byte[] key = new byte[10];
        random.nextBytes(key);
        String accessKey = "ASIA" + HexFormat.of().withUpperCase().formatHex(key).substring(0, 16);
        SessionCredential session = new SessionCredential(accessKey, randomString(30), randomString(48),
                role.getArn(), now.plusSeconds(3600), null, account);
        session.setEc2InstanceId(instance.getInstanceId());
        iam.registerEc2InstanceSession(session);
        history.add(new Issued(session, role.getRoleId(), instance.getIamInstanceProfileArn()));
        return Optional.of(session);
    }

    private String randomString(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void revoke(Issued issued) {
        iam.unregisterSession(issued.session().getOriginAccountId(), issued.session().getAccessKeyId());
    }

    private record Issued(SessionCredential session, String roleId, String profileArn) {}
}
