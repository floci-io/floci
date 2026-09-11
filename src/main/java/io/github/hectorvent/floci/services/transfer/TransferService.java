package io.github.hectorvent.floci.services.transfer;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
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
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class TransferService {

    private static final String CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final AccountAwareStorageBackend<Server> serverStore;
    private final AccountAwareStorageBackend<User> userStore;
    private final AccountAwareStorageBackend<Map<String, String>> tagStore;
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
                               String domain,
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
        server.setDomain(domain != null ? domain : "S3");
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

        putServer(server);

        if (tags != null && !tags.isEmpty()) {
            putTags("server/" + serverId, tags);
        }

        return server;
    }

    public Server getServer(String serverId) {
        return findServer(serverId).orElseThrow(() ->
                new AwsException("ResourceNotFoundException",
                        "Server " + serverId + " does not exist.", 404));
    }

    public synchronized void deleteServer(String serverId) {
        // AWS deletes a server in any state: the DeleteServer API defines no
        // state precondition (and no ConflictException at all).
        Server server = getServer(serverId);
        deleteServerRecord(server);
        for (User user : userStore.scanForAccount(currentAccount(),
                k -> k.startsWith(scopedKey(currentRegion(), serverId) + "/"))) {
            deleteUserRecord(user);
        }
    }

    public List<Server> listServers(String nextToken, int maxResults) {
        List<Server> all = new ArrayList<>(serverStore.scanForAccount(currentAccount(),
                k -> k.startsWith(currentRegion() + "/")));
        for (Server legacy : serverStore.scanUnscopedLegacy(s -> owns(s, currentAccount(), currentRegion()))) {
            findServer(legacy.getServerId()).ifPresent(all::add);
        }
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
        putServer(server);
        return server;
    }

    public Server stopServer(String serverId) {
        Server server = getServer(serverId);
        if (!"ONLINE".equals(server.getState())) {
            throw new AwsException("ConflictException",
                    "Server is not in ONLINE state.", 409);
        }
        server.setState("OFFLINE");
        putServer(server);
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
        putServer(server);
        return server;
    }

    // ── Users ─────────────────────────────────────────────────────────────────

    public User createUser(String serverId, String region, String userName, String role,
                           String homeDirectory, String homeDirectoryType,
                           List<HomeDirectoryMapping> homeDirectoryMappings,
                           Map<String, String> tags) {
        getServer(serverId);
        String key = userKey(serverId, userName);
        if (findUser(serverId, userName).isPresent()) {
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

        putUser(user);

        if (tags != null && !tags.isEmpty()) {
            putTags("user/" + key, tags);
        }

        return user;
    }

    public User getUser(String serverId, String userName) {
        getServer(serverId);
        return findUser(serverId, userName).orElseThrow(() ->
                new AwsException("ResourceNotFoundException",
                        "User " + userName + " does not exist on server " + serverId + ".", 404));
    }

    public void deleteUser(String serverId, String userName) {
        deleteUserRecord(getUser(serverId, userName));
    }

    public List<User> listUsers(String serverId, String nextToken, int maxResults) {
        getServer(serverId);
        List<User> all = new ArrayList<>(userStore.scanForAccount(currentAccount(),
                k -> k.startsWith(scopedKey(currentRegion(), serverId) + "/")));
        for (User legacy : userStore.scanUnscopedLegacy(u -> owns(u, currentAccount(), currentRegion())
                && u.getArn().contains("/" + serverId + "/"))) {
            findUser(serverId, legacy.getUserName()).ifPresent(all::add);
        }
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
        putUser(user);
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
        putUser(user);
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
        putUser(user);
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public Map<String, String> listTagsForResource(String arn) {
        String key = arnToTagKey(arn);
        return tagStore.getForAccount(currentAccount(), scopedKey(currentRegion(), key)).orElse(new HashMap<>());
    }

    public void tagResource(String arn, Map<String, String> tags) {
        String key = arnToTagKey(arn);
        Map<String, String> existing = new HashMap<>(tagStore.getForAccount(currentAccount(), scopedKey(currentRegion(), key))
                .orElse(new HashMap<>()));
        existing.putAll(tags);
        putTags(key, existing);

        // Also sync tags into the resource object
        syncTagsToResource(arn, existing);
    }

    public void untagResource(String arn, List<String> tagKeys) {
        String key = arnToTagKey(arn);
        Map<String, String> existing = new HashMap<>(tagStore.getForAccount(currentAccount(), scopedKey(currentRegion(), key))
                .orElse(new HashMap<>()));
        tagKeys.forEach(existing::remove);
        putTags(key, existing);
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
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
        }
        if (!"transfer".equals(parsed.service())
                || !currentAccount().equals(parsed.accountId())
                || !currentRegion().equals(parsed.region())) {
            throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
        }
        String resource = parsed.resource();
        if (resource.startsWith("server/")) {
            getServer(resource.substring("server/".length()));
        } else if (resource.startsWith("user/")) {
            String[] parts = resource.substring("user/".length()).split("/", 2);
            if (parts.length != 2) {
                throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
            }
            getUser(parts[0], parts[1]);
        } else {
            throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
        }
        return resource;
    }

    private void syncTagsToResource(String arn, Map<String, String> tags) {
        String key = arnToTagKey(arn);
        if (key.startsWith("server/")) {
            String serverId = key.substring("server/".length());
            serverStore.getForAccount(currentAccount(), scopedKey(currentRegion(), serverId)).ifPresent(s -> {
                s.setTags(tags);
                putServer(s);
            });
        } else if (key.startsWith("user/")) {
            String userKey = key.substring("user/".length());
            userStore.getForAccount(currentAccount(), scopedKey(currentRegion(), userKey)).ifPresent(u -> {
                u.setTags(tags);
                putUser(u);
            });
        }
    }

    public int countUsers(String serverId) {
        return (int) userStore.scanForAccount(currentAccount(),
                k -> k.startsWith(scopedKey(currentRegion(), serverId) + "/")).stream().count();
    }

    private Optional<Server> findServer(String serverId) {
        String accountId = currentAccount();
        String region = currentRegion();
        return serverStore.getForAccountMigratingLegacyKeys(accountId, scopedKey(region, serverId),
                List.of(serverId), server -> owns(server, accountId, region));
    }

    private Optional<User> findUser(String serverId, String userName) {
        String accountId = currentAccount();
        String region = currentRegion();
        String key = userKey(serverId, userName);
        return userStore.getForAccountMigratingLegacyKeys(accountId, scopedKey(region, key),
                List.of(key), user -> owns(user, accountId, region));
    }

    private void putServer(Server server) {
        serverStore.putForAccount(currentAccount(), scopedKey(regionOf(server), server.getServerId()), server);
    }

    private void deleteServerRecord(Server server) {
        serverStore.deleteForAccount(currentAccount(), scopedKey(regionOf(server), server.getServerId()));
        tagStore.deleteForAccount(currentAccount(), scopedKey(regionOf(server), "server/" + server.getServerId()));
    }

    private void putUser(User user) {
        String[] resource = AwsArnUtils.parse(user.getArn()).resource().substring("user/".length()).split("/", 2);
        userStore.putForAccount(currentAccount(), scopedKey(regionOf(user), userKey(resource[0], resource[1])), user);
    }

    private void deleteUserRecord(User user) {
        String[] resource = AwsArnUtils.parse(user.getArn()).resource().substring("user/".length()).split("/", 2);
        String key = userKey(resource[0], resource[1]);
        userStore.deleteForAccount(currentAccount(), scopedKey(regionOf(user), key));
        tagStore.deleteForAccount(currentAccount(), scopedKey(regionOf(user), "user/" + key));
    }

    private void putTags(String key, Map<String, String> tags) {
        tagStore.putForAccount(currentAccount(), scopedKey(currentRegion(), key), new HashMap<>(tags));
    }

    private String currentAccount() {
        return regionResolver.getAccountId();
    }

    private String currentRegion() {
        return regionResolver.getRegion();
    }

    private static String scopedKey(String region, String key) {
        return region + "/" + key;
    }

    private static String userKey(String serverId, String userName) {
        return serverId + "/" + userName;
    }

    private static boolean owns(Server server, String accountId, String region) {
        return ownsArn(server.getArn(), accountId, region);
    }

    private static boolean owns(User user, String accountId, String region) {
        return ownsArn(user.getArn(), accountId, region);
    }

    private static boolean ownsArn(String arn, String accountId, String region) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            return "transfer".equals(parsed.service())
                    && accountId.equals(parsed.accountId())
                    && region.equals(parsed.region());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String regionOf(Server server) {
        return AwsArnUtils.parse(server.getArn()).region();
    }

    private static String regionOf(User user) {
        return AwsArnUtils.parse(user.getArn()).region();
    }
}
