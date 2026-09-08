package io.github.hectorvent.floci.services.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.services.transfer.model.HomeDirectoryMapping;
import io.github.hectorvent.floci.services.transfer.model.Server;
import io.github.hectorvent.floci.services.transfer.model.SshPublicKey;
import io.github.hectorvent.floci.services.transfer.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TransferHandler {

    private final TransferService service;
    private final ObjectMapper objectMapper;

    @Inject
    public TransferHandler(TransferService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        try {
            return switch (action) {
                case "CreateServer"       -> createServer(request, region);
                case "DescribeServer"     -> describeServer(request);
                case "DeleteServer"       -> deleteServer(request);
                case "ListServers"        -> listServers(request);
                case "StartServer"        -> startServer(request);
                case "StopServer"         -> stopServer(request);
                case "UpdateServer"       -> updateServer(request);
                case "CreateUser"         -> createUser(request, region);
                case "DescribeUser"       -> describeUser(request);
                case "DeleteUser"         -> deleteUser(request);
                case "ListUsers"          -> listUsers(request);
                case "UpdateUser"         -> updateUser(request);
                case "ImportSshPublicKey" -> importSshPublicKey(request);
                case "DeleteSshPublicKey" -> deleteSshPublicKey(request);
                case "TagResource"        -> tagResource(request);
                case "UntagResource"      -> untagResource(request);
                case "ListTagsForResource" -> listTagsForResource(request);
                default -> JsonErrorResponseUtils.createUnknownOperationErrorResponse("AmazonTransfer." + action);
            };
        } catch (AwsException e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            return JsonErrorResponseUtils.createErrorResponse(e);
        }
    }

    // ── Server handlers ───────────────────────────────────────────────────────

    private Response createServer(JsonNode req, String region) {
        String domain = textOrNull(req, "Domain");
        List<String> protocols = jsonStringList(req.path("Protocols"));
        String endpointType = textOrNull(req, "EndpointType");
        Map<String, Object> endpointDetails = jsonObjectMap(req.path("EndpointDetails"));
        String identityProviderType = textOrNull(req, "IdentityProviderType");
        Map<String, String> identityProviderDetails = jsonStringMap(req.path("IdentityProviderDetails"));
        String loggingRole = textOrNull(req, "LoggingRole");
        String securityPolicyName = textOrNull(req, "SecurityPolicyName");
        Map<String, String> tags = parseTags(req.path("Tags"));

        Server server = service.createServer(region, domain, protocols, endpointType, endpointDetails,
                identityProviderType, identityProviderDetails, loggingRole, securityPolicyName, tags);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("ServerId", server.getServerId());
        return Response.ok(resp).build();
    }

    private Response describeServer(JsonNode req) {
        String serverId = req.path("ServerId").asText();
        Server server = service.getServer(serverId);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("Server", buildServerNode(server));
        return Response.ok(resp).build();
    }

    private Response deleteServer(JsonNode req) {
        service.deleteServer(req.path("ServerId").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listServers(JsonNode req) {
        String nextToken = textOrNull(req, "NextToken");
        int maxResults = req.path("MaxResults").asInt(100);
        List<Server> servers = service.listServers(nextToken, maxResults);

        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode arr = resp.putArray("Servers");
        for (Server s : servers) {
            arr.add(buildServerListEntry(s));
        }
        if (servers.size() == maxResults) {
            resp.put("NextToken", servers.get(servers.size() - 1).getServerId());
        }
        return Response.ok(resp).build();
    }

    private Response startServer(JsonNode req) {
        service.startServer(req.path("ServerId").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response stopServer(JsonNode req) {
        service.stopServer(req.path("ServerId").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response updateServer(JsonNode req) {
        String serverId = req.path("ServerId").asText();
        List<String> protocols = jsonStringList(req.path("Protocols"));
        String endpointType = textOrNull(req, "EndpointType");
        Map<String, Object> endpointDetails = jsonObjectMap(req.path("EndpointDetails"));
        String identityProviderDetails = textOrNull(req, "IdentityProviderDetails");
        String loggingRole = textOrNull(req, "LoggingRole");
        String securityPolicyName = textOrNull(req, "SecurityPolicyName");

        Server server = service.updateServer(serverId, protocols, endpointType, endpointDetails,
                identityProviderDetails, loggingRole, securityPolicyName);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("ServerId", server.getServerId());
        return Response.ok(resp).build();
    }

    // ── User handlers ─────────────────────────────────────────────────────────

    private Response createUser(JsonNode req, String region) {
        String serverId = req.path("ServerId").asText();
        String userName = req.path("UserName").asText();
        String role = textOrNull(req, "Role");
        String homeDirectory = textOrNull(req, "HomeDirectory");
        String homeDirectoryType = textOrNull(req, "HomeDirectoryType");
        List<HomeDirectoryMapping> mappings = parseHomeDirectoryMappings(req.path("HomeDirectoryMappings"));
        Map<String, String> tags = parseTags(req.path("Tags"));

        if (userName == null || userName.isEmpty()) {
            throw new AwsException("InvalidRequestException", "UserName is required.", 400);
        }
        if (role == null || role.isEmpty()) {
            throw new AwsException("InvalidRequestException", "Role is required.", 400);
        }

        User user = service.createUser(serverId, region, userName, role, homeDirectory,
                homeDirectoryType, mappings, tags);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("ServerId", serverId);
        resp.put("UserName", user.getUserName());
        return Response.ok(resp).build();
    }

    private Response describeUser(JsonNode req) {
        String serverId = req.path("ServerId").asText();
        String userName = req.path("UserName").asText();
        User user = service.getUser(serverId, userName);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("ServerId", serverId);
        resp.set("User", buildUserNode(user));
        return Response.ok(resp).build();
    }

    private Response deleteUser(JsonNode req) {
        service.deleteUser(req.path("ServerId").asText(), req.path("UserName").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listUsers(JsonNode req) {
        String serverId = req.path("ServerId").asText();
        String nextToken = textOrNull(req, "NextToken");
        int maxResults = req.path("MaxResults").asInt(100);
        List<User> users = service.listUsers(serverId, nextToken, maxResults);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("ServerId", serverId);
        ArrayNode arr = resp.putArray("Users");
        for (User u : users) {
            arr.add(buildUserListEntry(u));
        }
        if (users.size() == maxResults) {
            resp.put("NextToken", users.get(users.size() - 1).getUserName());
        }
        return Response.ok(resp).build();
    }

    private Response updateUser(JsonNode req) {
        String serverId = req.path("ServerId").asText();
        String userName = req.path("UserName").asText();
        String role = textOrNull(req, "Role");
        String homeDirectory = textOrNull(req, "HomeDirectory");
        String homeDirectoryType = textOrNull(req, "HomeDirectoryType");
        List<HomeDirectoryMapping> mappings = parseHomeDirectoryMappings(req.path("HomeDirectoryMappings"));

        User user = service.updateUser(serverId, userName, role, homeDirectory, homeDirectoryType,
                mappings.isEmpty() ? null : mappings);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("ServerId", serverId);
        resp.put("UserName", user.getUserName());
        return Response.ok(resp).build();
    }

    // ── SSH key handlers ──────────────────────────────────────────────────────

    private Response importSshPublicKey(JsonNode req) {
        String serverId = req.path("ServerId").asText();
        String userName = req.path("UserName").asText();
        String body = req.path("SshPublicKeyBody").asText();

        SshPublicKey key = service.importSshPublicKey(serverId, userName, body);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("ServerId", serverId);
        resp.put("SshPublicKeyId", key.getSshPublicKeyId());
        resp.put("UserName", userName);
        return Response.ok(resp).build();
    }

    private Response deleteSshPublicKey(JsonNode req) {
        service.deleteSshPublicKey(
                req.path("ServerId").asText(),
                req.path("UserName").asText(),
                req.path("SshPublicKeyId").asText());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ── Tag handlers ──────────────────────────────────────────────────────────

    private Response tagResource(JsonNode req) {
        String arn = req.path("Arn").asText();
        Map<String, String> tags = parseTags(req.path("Tags"));
        service.tagResource(arn, tags);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode req) {
        String arn = req.path("Arn").asText();
        List<String> keys = new ArrayList<>();
        req.path("TagKeys").forEach(n -> keys.add(n.asText()));
        service.untagResource(arn, keys);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listTagsForResource(JsonNode req) {
        String arn = req.path("Arn").asText();
        Map<String, String> tags = service.listTagsForResource(arn);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("Arn", arn);
        ArrayNode arr = resp.putArray("Tags");
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", k);
            tag.put("Value", v);
            arr.add(tag);
        });
        return Response.ok(resp).build();
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    private ObjectNode buildServerNode(Server s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ServerId", s.getServerId());
        node.put("Arn", s.getArn());
        node.put("State", s.getState());
        node.put("Domain", s.getDomain());
        node.put("EndpointType", s.getEndpointType());
        node.put("IdentityProviderType", s.getIdentityProviderType());
        node.put("SecurityPolicyName", s.getSecurityPolicyName());
        node.put("HostKeyFingerprint", s.getHostKeyFingerprint());
        node.put("UserCount", service.countUsers(s.getServerId()));
        if (s.getLoggingRole() != null) {
            node.put("LoggingRole", s.getLoggingRole());
        }
        if (s.getProtocols() != null) {
            ArrayNode protocols = node.putArray("Protocols");
            s.getProtocols().forEach(protocols::add);
        }
        if (s.getTags() != null && !s.getTags().isEmpty()) {
            ArrayNode tags = node.putArray("Tags");
            s.getTags().forEach((k, v) -> {
                ObjectNode tag = objectMapper.createObjectNode();
                tag.put("Key", k);
                tag.put("Value", v);
                tags.add(tag);
            });
        }
        return node;
    }

    private ObjectNode buildServerListEntry(Server s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", s.getArn());
        node.put("Domain", s.getDomain());
        node.put("EndpointType", s.getEndpointType());
        node.put("IdentityProviderType", s.getIdentityProviderType());
        node.put("ServerId", s.getServerId());
        node.put("State", s.getState());
        node.put("UserCount", service.countUsers(s.getServerId()));
        if (s.getLoggingRole() != null) {
            node.put("LoggingRole", s.getLoggingRole());
        }
        return node;
    }

    private ObjectNode buildUserNode(User u) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("UserName", u.getUserName());
        node.put("Arn", u.getArn());
        node.put("HomeDirectory", u.getHomeDirectory());
        node.put("HomeDirectoryType", u.getHomeDirectoryType());
        if (u.getRole() != null) node.put("Role", u.getRole());
        if (u.getHomeDirectoryMappings() != null && !u.getHomeDirectoryMappings().isEmpty()) {
            ArrayNode arr = node.putArray("HomeDirectoryMappings");
            for (HomeDirectoryMapping m : u.getHomeDirectoryMappings()) {
                ObjectNode entry = objectMapper.createObjectNode();
                entry.put("Entry", m.getEntry());
                entry.put("Target", m.getTarget());
                arr.add(entry);
            }
        }
        ArrayNode keys = node.putArray("SshPublicKeys");
        if (u.getSshPublicKeys() != null) {
            for (SshPublicKey k : u.getSshPublicKeys()) {
                ObjectNode kNode = objectMapper.createObjectNode();
                kNode.put("SshPublicKeyId", k.getSshPublicKeyId());
                kNode.put("SshPublicKeyBody", k.getSshPublicKeyBody());
                if (k.getDateImported() != null) {
                    kNode.put("DateImported", k.getDateImported().toString());
                }
                keys.add(kNode);
            }
        }
        if (u.getTags() != null && !u.getTags().isEmpty()) {
            ArrayNode tags = node.putArray("Tags");
            u.getTags().forEach((k, v) -> {
                ObjectNode tag = objectMapper.createObjectNode();
                tag.put("Key", k);
                tag.put("Value", v);
                tags.add(tag);
            });
        }
        return node;
    }

    private ObjectNode buildUserListEntry(User u) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("UserName", u.getUserName());
        node.put("Arn", u.getArn());
        node.put("HomeDirectory", u.getHomeDirectory());
        node.put("HomeDirectoryType", u.getHomeDirectoryType());
        if (u.getRole() != null) node.put("Role", u.getRole());
        node.put("SshPublicKeyCount", u.getSshPublicKeys() != null ? u.getSshPublicKeys().size() : 0);
        return node;
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.path(field);
        return child.isMissingNode() || child.isNull() ? null : child.asText();
    }

    private List<String> jsonStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private Map<String, String> jsonStringMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    private Map<String, Object> jsonObjectMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        return map.isEmpty() ? null : map;
    }

    private Map<String, String> parseTags(JsonNode node) {
        Map<String, String> tags = new HashMap<>();
        if (node != null && node.isArray()) {
            node.forEach(t -> {
                String key = t.path("Key").asText(null);
                String value = t.path("Value").asText("");
                if (key != null) tags.put(key, value);
            });
        }
        return tags;
    }

    private List<HomeDirectoryMapping> parseHomeDirectoryMappings(JsonNode node) {
        List<HomeDirectoryMapping> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(m -> {
                String entry = m.path("Entry").asText(null);
                String target = m.path("Target").asText(null);
                if (entry != null && target != null) {
                    list.add(new HomeDirectoryMapping(entry, target));
                }
            });
        }
        return list;
    }
}
