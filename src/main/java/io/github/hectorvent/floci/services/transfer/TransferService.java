package io.github.hectorvent.floci.services.transfer;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.transfer.model.HomeDirectoryMapping;
import io.github.hectorvent.floci.services.transfer.model.Server;
import io.github.hectorvent.floci.services.transfer.model.SshPublicKey;
import io.github.hectorvent.floci.services.transfer.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class TransferService {

    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final StorageBackend<String, Server> serverStore;
    private final StorageBackend<String, User> userStore;
    private final StorageBackend<String, Map<String, String>> tagStore;
    private final RegionResolver regionResolver;

    @Inject
    public TransferService(StorageFactory factory, EmulatorConfig config, RegionResolver regionResolver) {
        this.serverStore = factory.create("transfer", "transfer-servers.json",
                new TypeReference<Map<String, Server>>() {});
        this.userStore = factory.create("transfer", "transfer-users.json",
                new TypeReference<Map<String, User>>() {});
        this.tagStore = factory.create("transfer", "transfer-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.regionResolver = regionResolver;
    }

    // ── Servers ───────────────────────────────────────────────────────────────

    public Server createServer(String region,
                               List<String> protocols,
                               String endpointType,
                               Map<String, Object> endpointDetails,
                               String identityProviderType,
                               Map<String, String> identityProviderDetails,
                               String loggingRole,
                               String securityPolicyName,
                               Map<String, String> tags) {
        String serverId = generateServerId();
        String arn = regionResolver.buildArn("transfer", region, "server/" + serverId);

        Server server = new Server();
        server.setServerId(serverId);
        server.setArn(arn);
        server.setState("ONLINE");
        server.setProtocols(protocols != null && !protocols.isEmpty() ? protocols : List.of("SFTP"));
        server.setEndpointType(endpointType != null ? endpointType : "PUBLIC");
        server.setEndpointDetails(endpointDetails);
        server.setIdentityProviderType(identityProviderType != null ? identityProviderType : "SERVICE_MANAGED");
        server.setIdentityProviderDetails(identityProviderDetails);
        server.setLoggingRole(loggingRole);
        server.setSecurityPolicyName(securityPolicyName != null ? securityPolicyName : "TransferSecurityPolicy-2020-06");
        server.setHostKeyFingerprint("SHA256:AAAAflociemulatedkey" + serverId.substring(2, 10));
        server.setTags(tags != null ? tags : new HashMap<>());
        server.setCreationTime(Instant.now());

        serverStore.put(serverId, server);

        if (tags != null && !tags.isEmpty()) {
            tagStore.put("server/" + serverId, new HashMap<>(tags));
        }

        return server;
    }

    public Server getServer(String serverId) {
        return serverStore.get(serverId).orElseThrow(() ->
                new AwsException("ResourceNotFoundException",
                        "Server " + serverId + " does not exist.", 404));
    }

    public synchronized void deleteServer(String serverId) {
        Server server = getServer(serverId);
        if (!"OFFLINE".equals(server.getState())) {
            throw new AwsException("ConflictException",
                    "Server must be in OFFLINE state to be deleted.", 409);
        }
        serverStore.delete(serverId);
        tagStore.delete("server/" + serverId);
        for (User user : userStore.scan(k -> k.startsWith(serverId + "/"))) {
            userStore.delete(serverId + "/" + user.getUserName());
            tagStore.delete("user/" + serverId + "/" + user.getUserName());
        }
    }

    public List<Server> listServers(String nextToken, int maxResults) {
        List<Server> all = new ArrayList<>(serverStore.scan(k -> true));
        all.sort((a, b) -> a.getServerId().compareTo(b.getServerId()));
        if (nextToken != null && !nextToken.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getServerId().equals(nextToken)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxResults > 0 && all.size() > maxResults) {
            return all.subList(0, maxResults);
        }
        return all;
    }

    public Server startServer(String serverId) {
        Server server = getServer(serverId);
        if (!"OFFLINE".equals(server.getState())) {
            throw new AwsException("ConflictException",
                    "Server is not in OFFLINE state.", 409);
        }
        server.setState("ONLINE");
        serverStore.put(serverId, server);
        return server;
    }

    public Server stopServer(String serverId) {
        Server server = getServer(serverId);
        if (!"ONLINE".equals(server.getState())) {
            throw new AwsException("ConflictException",
                    "Server is not in ONLINE state.", 409);
        }
        server.setState("OFFLINE");
        serverStore.put(serverId, server);
        return server;
    }

    public Server updateServer(String serverId,
                               List<String> protocols,
                               String endpointType,
                               Map<String, Object> endpointDetails,
                               String identityProviderDetails,
                               String loggingRole,
                               String securityPolicyName) {
        Server server = getServer(serverId);
        if (protocols != null && !protocols.isEmpty()) {
            server.setProtocols(protocols);
        }
        if (endpointType != null) {
            server.setEndpointType(endpointType);
        }
        if (endpointDetails != null) {
            server.setEndpointDetails(endpointDetails);
        }
        if (loggingRole != null) {
            server.setLoggingRole(loggingRole);
        }
        if (securityPolicyName != null) {
            server.setSecurityPolicyName(securityPolicyName);
        }
        serverStore.put(serverId, server);
        return server;
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    public User createUser(String serverId, String region, String userName, String role,
                           String homeDirectory, String homeDirectoryType,
                           List<HomeDirectoryMapping> homeDirectoryMappings,
                           Map<String, String> tags) {
        getServer(serverId);
        String key = serverId + "/" + userName;
        if (userStore.get(key).isPresent()) {
            throw new AwsException("ResourceExistsException",
                    "User " + userName + " already exists on server " + serverId + ".", 400);
        }

        String arn = regionResolver.buildArn("transfer", region, "user/" + serverId + "/" + userName);
        User user = new User();
        user.setUserName(userName);
        user.setArn(arn);
        user.setRole(role);
        user.setHomeDirectory(homeDirectory != null ? homeDirectory : "/");
        user.setHomeDirectoryType(homeDirectoryType != null ? homeDirectoryType : "PATH");
        user.setHomeDirectoryMappings(homeDirectoryMappings != null ? homeDirectoryMappings : List.of());
        user.setSshPublicKeys(new ArrayList<>());
        user.setTags(tags != null ? tags : new HashMap<>());

        userStore.put(key, user);

        if (tags != null && !tags.isEmpty()) {
            tagStore.put("user/" + key, new HashMap<>(tags));
        }

        return user;
    }

    public User getUser(String serverId, String userName) {
        getServer(serverId);
        return userStore.get(serverId + "/" + userName).orElseThrow(() ->
                new AwsException("ResourceNotFoundException",
                        "User " + userName + " does not exist on server " + serverId + ".", 404));
    }

    public void deleteUser(String serverId, String userName) {
        getUser(serverId, userName);
        String key = serverId + "/" + userName;
        userStore.delete(key);
        tagStore.delete("user/" + key);
    }

    public List<User> listUsers(String serverId, String nextToken, int maxResults) {
        getServer(serverId);
        List<User> all = new ArrayList<>(userStore.scan(k -> k.startsWith(serverId + "/")));
        all.sort((a, b) -> a.getUserName().compareTo(b.getUserName()));
        if (nextToken != null && !nextToken.isEmpty()) {
            int idx = 0;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getUserName().equals(nextToken)) {
                    idx = i + 1;
                    break;
                }
            }
            all = all.subList(idx, all.size());
        }
        if (maxResults > 0 && all.size() > maxResults) {
            return all.subList(0, maxResults);
        }
        return all;
    }

    public User updateUser(String serverId, String userName, String role,
                           String homeDirectory, String homeDirectoryType,
                           List<HomeDirectoryMapping> homeDirectoryMappings) {
        User user = getUser(serverId, userName);
        if (role != null) user.setRole(role);
        if (homeDirectory != null) user.setHomeDirectory(homeDirectory);
        if (homeDirectoryType != null) user.setHomeDirectoryType(homeDirectoryType);
        if (homeDirectoryMappings != null) user.setHomeDirectoryMappings(homeDirectoryMappings);
        userStore.put(serverId + "/" + userName, user);
        return user;
    }

    // ── SSH Keys ──────────────────────────────────────────────────────────────

    public SshPublicKey importSshPublicKey(String serverId, String userName, String sshPublicKeyBody) {
        User user = getUser(serverId, userName);
        String keyId = "key-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        SshPublicKey key = new SshPublicKey(keyId, sshPublicKeyBody, Instant.now());
        List<SshPublicKey> keys = new ArrayList<>(user.getSshPublicKeys() != null ? user.getSshPublicKeys() : List.of());
        keys.add(key);
        user.setSshPublicKeys(keys);
        userStore.put(serverId + "/" + userName, user);
        return key;
    }

    public void deleteSshPublicKey(String serverId, String userName, String sshPublicKeyId) {
        User user = getUser(serverId, userName);
        List<SshPublicKey> keys = new ArrayList<>(user.getSshPublicKeys() != null ? user.getSshPublicKeys() : List.of());
        boolean removed = keys.removeIf(k -> k.getSshPublicKeyId().equals(sshPublicKeyId));
        if (!removed) {
            throw new AwsException("ResourceNotFoundException",
                    "SSH public key " + sshPublicKeyId + " does not exist.", 404);
        }
        user.setSshPublicKeys(keys);
        userStore.put(serverId + "/" + userName, user);
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTagsForResource(String arn) {
        String key = arnToTagKey(arn);
        return tagStore.get(key).orElse(new HashMap<>());
    }

    public void tagResource(String arn, Map<String, String> tags) {
        String key = arnToTagKey(arn);
        Map<String, String> existing = new HashMap<>(tagStore.get(key).orElse(new HashMap<>()));
        existing.putAll(tags);
        tagStore.put(key, existing);

        // Also sync tags into the resource object
        syncTagsToResource(arn, existing);
    }

    public void untagResource(String arn, List<String> tagKeys) {
        String key = arnToTagKey(arn);
        Map<String, String> existing = new HashMap<>(tagStore.get(key).orElse(new HashMap<>()));
        tagKeys.forEach(existing::remove);
        tagStore.put(key, existing);
        syncTagsToResource(arn, existing);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String generateServerId() {
        StringBuilder sb = new StringBuilder("s-");
        String uuid = UUID.randomUUID().toString().replace("-", "");
        sb.append(uuid, 0, 17);
        return sb.toString();
    }

    private String arnToTagKey(String arn) {
        // arn:aws:transfer:region:account:server/s-xxx  → server/s-xxx
        // arn:aws:transfer:region:account:user/s-xxx/alice → user/s-xxx/alice
        int idx = arn.lastIndexOf(':');
        return idx >= 0 ? arn.substring(idx + 1) : arn;
    }

    private void syncTagsToResource(String arn, Map<String, String> tags) {
        String key = arnToTagKey(arn);
        if (key.startsWith("server/")) {
            String serverId = key.substring("server/".length());
            serverStore.get(serverId).ifPresent(s -> {
                s.setTags(tags);
                serverStore.put(serverId, s);
            });
        } else if (key.startsWith("user/")) {
            String userKey = key.substring("user/".length());
            userStore.get(userKey).ifPresent(u -> {
                u.setTags(tags);
                userStore.put(userKey, u);
            });
        }
    }

    public int countUsers(String serverId) {
        return (int) userStore.scan(k -> k.startsWith(serverId + "/")).stream().count();
    }
}
