package io.github.hectorvent.floci.services.quicksight;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.StoredResourceArns;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.quicksight.model.QuickSightResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@ApplicationScoped
public class QuickSightService implements Resettable, StoredResourceArns {
    private final ObjectMapper mapper;
    private final RegionResolver regions;
    private final AccountAwareStorageBackend<QuickSightResource> resources;
    private final AccountAwareStorageBackend<QuickSightResource> sessions;
    private final String embedBaseUrl;
    private final Clock clock;

    @Inject
    public QuickSightService(StorageFactory factory, ObjectMapper mapper, RegionResolver regions, EmulatorConfig config) {
        this(mapper, regions,
                factory.create("quicksight", "quicksight-resources.json", new TypeReference<>() {}),
                factory.create("quicksight", "quicksight-sessions.json", new TypeReference<>() {}),
                config.services().quicksight().embedBaseUrl().orElse(config.baseUrl()), Clock.systemUTC());
    }

    QuickSightService(ObjectMapper mapper, RegionResolver regions,
                      AccountAwareStorageBackend<QuickSightResource> resources,
                      AccountAwareStorageBackend<QuickSightResource> sessions, String embedBaseUrl, Clock clock) {
        this.clock = clock;
        this.mapper = mapper;
        this.regions = regions;
        this.resources = resources;
        this.sessions = sessions;
        this.embedBaseUrl = embedBaseUrl.replaceAll("/$", "");
    }

    public synchronized ObjectNode create(String account, String kind, String id, JsonNode request) {
        ResourceKind resourceKind = ResourceKind.fromPath(kind);
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,2048}")) {
            throw invalid("Resource identifier is required and must contain letters, numbers, underscores or hyphens.");
        }
        String key = key(account, kind, id);
        if (resources.getForAccount(account, key).isPresent()) {
            throw new AwsException("ResourceExistsException", "Resource already exists: " + id, 409);
        }
        String idField = resourceKind.idField;
        String name = required(request, "Name");
        ObjectNode resource = mapper.createObjectNode();
        resource.put(idField, id).put("Name", name).put("Arn", arn(account, kind, id));
        double now = clock.instant().toEpochMilli() / 1000.0;
        resource.put("CreatedTime", now).put("LastUpdatedTime", now);
        resource.set("Permissions", request.path("Permissions").isArray() ? request.path("Permissions").deepCopy() : mapper.createArrayNode());
        switch (resourceKind) {
            case DASHBOARD -> {
                JsonNode definition = request.path("Definition");
                if (!definition.isObject()) {
                    throw invalid("Definition is required; template-based creation is not supported.");
                }
                ArrayNode dataSets = mapper.createArrayNode();
                for (JsonNode declaration : definition.path("DataSetIdentifierDeclarations")) {
                    String dataSetArn = required(declaration, "DataSetArn");
                    String dataSetId = dataSetArn.substring(dataSetArn.lastIndexOf('/') + 1);
                    if (!arn(account, "dataset", dataSetId).equals(dataSetArn)) {
                        throw invalid("DataSetArn must reference a data set in this account and region.");
                    }
                    get(account, "dataset", dataSetId);
                    dataSets.add(dataSetArn);
                }
                ObjectNode version = mapper.createObjectNode().put("VersionNumber", 1).put("Status", "CREATION_SUCCESSFUL").put("CreatedTime", now);
                version.set("DataSetArns", dataSets);
                resource.set("Version", version);
                resource.set("Definition", definition.deepCopy());
                resource.put("LastPublishedTime", now).put("PublishedVersionNumber", 1);
            }
            case DATA_SET -> {
                String mode = required(request, "ImportMode");
                if (!List.of("SPICE", "DIRECT_QUERY").contains(mode)) {
                    throw invalid("ImportMode must be SPICE or DIRECT_QUERY.");
                }
                if (!request.path("PhysicalTableMap").isObject() || request.path("PhysicalTableMap").isEmpty()) {
                    throw invalid("PhysicalTableMap is required.");
                }
                resource.put("ImportMode", mode);
                resource.set("PhysicalTableMap", request.path("PhysicalTableMap").deepCopy());
                resource.set("LogicalTableMap", request.path("LogicalTableMap").deepCopy());
            }
            case DATA_SOURCE -> {
                resource.put("Type", required(request, "Type")).put("Status", "CREATION_SUCCESSFUL");
                resource.set("DataSourceParameters", request.path("DataSourceParameters").deepCopy());
            }
        }
        resources.putForAccount(account, key, new QuickSightResource(resource));
        ObjectNode response = response(resourceKind.createStatus).put("Arn", resource.path("Arn").asText()).put(idField, id);
        if (resourceKind == ResourceKind.DASHBOARD) {
            response.put("CreationStatus", "CREATION_SUCCESSFUL").put("VersionArn", resource.path("Arn").asText() + "/version/1");
        } else if (resourceKind == ResourceKind.DATA_SOURCE) {
            response.put("CreationStatus", "CREATION_SUCCESSFUL");
        }
        return response;
    }

    public ObjectNode describe(String account, String kind, String id) {
        ResourceKind resourceKind = ResourceKind.fromPath(kind);
        ObjectNode resource = get(account, kind, id);
        resource.remove("Permissions");
        resource.remove("Definition");
        return response(200).set(resourceKind.responseField, resource);
    }

    public synchronized ObjectNode delete(String account, String kind, String id) {
        ResourceKind resourceKind = ResourceKind.fromPath(kind);
        ObjectNode resource = get(account, kind, id);
        resources.deleteForAccount(account, key(account, kind, id));
        if (resourceKind == ResourceKind.DATA_SET) {
            String prefix = key(account, "ingestion", id + "/");
            resources.keysForAccount(account).stream().filter(value -> value.startsWith(prefix))
                    .forEach(value -> resources.deleteForAccount(account, value));
        }
        return response(200).put("Arn", resource.path("Arn").asText()).put(resourceKind.idField, id);
    }

    public synchronized ObjectNode registerUser(String account, String namespace, JsonNode request) {
        String name = required(request, "UserName");
        if (!List.of("READER", "AUTHOR", "ADMIN").contains(required(request, "UserRole"))) {
            throw invalid("Unsupported UserRole.");
        }
        if (!"QUICKSIGHT".equals(required(request, "IdentityType"))) {
            throw invalid("Only QUICKSIGHT identity registration is supported.");
        }
        String id = namespace + "/" + name;
        if (resources.getForAccount(account, key(account, "user", id)).isPresent()) {
            throw new AwsException("ResourceExistsException", "User already registered.", 409);
        }
        ObjectNode user = mapper.createObjectNode().put("Arn", arn(account, "user", id))
                .put("UserName", name).put("Email", required(request, "Email"))
                .put("Role", request.path("UserRole").asText()).put("IdentityType", "QUICKSIGHT")
                .put("Active", true).put("PrincipalId", UUID.randomUUID().toString());
        resources.putForAccount(account, key(account, "user", id), new QuickSightResource(user));
        return response(201).set("User", user.deepCopy());
    }

    public ObjectNode describeUser(String account, String namespace, String name) {
        return response(200).set("User", get(account, "user", namespace + "/" + name));
    }

    public ObjectNode searchDashboards(String account, JsonNode request) {
        JsonNode filters = request.path("Filters");
        if (!filters.isArray() || filters.size() != 1
                || !"QUICKSIGHT_USER".equals(filters.path(0).path("Name").asText())
                || !"StringEquals".equals(filters.path(0).path("Operator").asText())) {
            throw invalid("Search requires one QUICKSIGHT_USER StringEquals filter.");
        }
        String principal = required(filters.path(0), "Value");
        requireRegisteredUser(account, principal);
        String prefix = key(account, "dashboard", "");
        List<ObjectNode> found = resources.scanForAccount(account, value -> value.startsWith(prefix)).stream()
                .map(QuickSightResource::document).filter(value -> canRead(value, principal))
                .map(value -> {
                    ObjectNode summary = value.deepCopy();
                    summary.remove(List.of("Version", "Definition", "Permissions"));
                    return summary;
                }).toList();
        Integer maximum = Pagination.parseMaxResults(request, "MaxResults", "InvalidParameterValueException");
        return page(found, maximum == null ? 100 : maximum,
                Pagination.parseNextToken(request, "NextToken", "InvalidNextTokenException"),
                account + ":" + regions.getRegion() + ":dashboards:" + Base64Url.encode(principal),
                "DashboardSummaryList", value -> value.path("DashboardId").asText());
    }

    public synchronized ObjectNode createIngestion(String account, String dataSetId, String id, JsonNode request) {
        ObjectNode dataSet = get(account, "dataset", dataSetId);
        if (!"SPICE".equals(dataSet.path("ImportMode").asText())) {
            throw invalid("Ingestions require a SPICE data set.");
        }
        String ingestionKey = key(account, "ingestion", dataSetId + "/" + id);
        if (resources.getForAccount(account, ingestionKey).isPresent()) {
            throw new AwsException("ResourceExistsException", "Ingestion already exists.", 409);
        }
        String type = request.path("IngestionType").asText("FULL_REFRESH");
        if (!List.of("FULL_REFRESH", "INCREMENTAL_REFRESH").contains(type)) {
            throw invalid("Unsupported IngestionType.");
        }
        ObjectNode ingestion = mapper.createObjectNode().put("IngestionId", id)
                .put("Arn", arn(account, "dataset", dataSetId) + "/ingestion/" + id)
                .put("CreatedTime", clock.instant().toEpochMilli() / 1000.0).put("IngestionStatus", "COMPLETED")
                .put("IngestionTimeInSeconds", 0).put("IngestionSizeInBytes", 0)
                .put("RequestType", type)
                .put("RequestSource", "MANUAL");
        ingestion.set("RowInfo", mapper.createObjectNode().put("RowsIngested", 0).put("RowsDropped", 0).put("TotalRowsInDataset", 0));
        resources.putForAccount(account, ingestionKey, new QuickSightResource(ingestion));
        return response(201).put("Arn", ingestion.path("Arn").asText()).put("IngestionId", id).put("IngestionStatus", "COMPLETED");
    }

    public ObjectNode listIngestions(String account, String id, int maximum, String token) {
        get(account, "dataset", id);
        String prefix = key(account, "ingestion", id + "/");
        List<ObjectNode> found = resources.scanForAccount(account, value -> value.startsWith(prefix)).stream()
                .map(QuickSightResource::document)
                .map(ObjectNode::deepCopy).toList();
        return page(found, maximum, token, account + ":" + regions.getRegion() + ":ingestions:" + id,
                "Ingestions", QuickSightService::ingestionCursor);
    }

    public ObjectNode describeIngestion(String account, String dataSetId, String id) {
        get(account, "dataset", dataSetId);
        return response(200).set("Ingestion", get(account, "ingestion", dataSetId + "/" + id));
    }

    @Override
    public String namespace() {
        return "quicksight";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if ("user".equals(type) && names.size() == 3) {
            return Optional.of(regions.buildArn("quicksight", region, names.getFirst(),
                    "user/" + names.get(1) + "/" + names.get(2)));
        }
        return Optional.empty();
    }

    public synchronized ObjectNode generateEmbedUrl(String account, JsonNode request) {
        String principal = required(request, "UserArn");
        requireRegisteredUser(account, principal);
        String id = required(request.path("ExperienceConfiguration").path("Dashboard"), "InitialDashboardId");
        ObjectNode dashboard = get(account, "dashboard", id);
        if (!canRead(dashboard, principal)) {
            throw new AwsException("AccessDeniedException", "User does not have permission to view this dashboard.", 401);
        }
        long lifetime = request.path("SessionLifetimeInMinutes").asLong(600);
        if (lifetime < 15 || lifetime > 600) {
            throw new AwsException("SessionLifetimeInMinutesInvalidException", "SessionLifetimeInMinutes must be between 15 and 600.", 400);
        }
        ArrayNode domains = mapper.createArrayNode();
        JsonNode requested = request.path("AllowedDomains");
        if (!requested.isMissingNode() && (!requested.isArray() || requested.isEmpty() || requested.size() > 3)) {
            throw invalid("AllowedDomains must contain between one and three origins.");
        }
        for (JsonNode domain : requested) {
            URI origin;
            try {
                origin = URI.create(domain.asText());
            } catch (IllegalArgumentException exception) {
                throw invalid("Invalid allowed domain.");
            }
            String normalized = QuickSightOrigins.normalize(origin);
            if (normalized.isEmpty() || !("https".equalsIgnoreCase(origin.getScheme())
                    || "http".equalsIgnoreCase(origin.getScheme()) && "localhost".equalsIgnoreCase(origin.getHost()))
                    || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || origin.getPath() != null && !origin.getPath().isEmpty() && !origin.getPath().equals("/")) {
                throw invalid("AllowedDomains must contain HTTPS origins or HTTP localhost.");
            }
            domains.add(normalized);
        }
        String token = account + "-" + UUID.randomUUID();
        ObjectNode session = mapper.createObjectNode().put("account", account).put("region", regions.getRegion())
                .put("dashboard", id).put("user", principal).put("redeemUntil", clock.instant().getEpochSecond() + 300)
                .put("lifetime", lifetime).put("redeemed", false);
        session.set("domains", domains);
        sessions.putForAccount(account, token, new QuickSightResource(session));
        return response(200).put("EmbedUrl", embedBaseUrl + "/embed/" + token + "/dashboards/" + id + "?code=" + token);
    }

    public synchronized ObjectNode redeem(String token, String code, String id, String origin, String sessionCookie) {
        String account = token.length() > 12 ? token.substring(0, 12) : "";
        ObjectNode session = sessions.getForAccount(account, token).map(QuickSightResource::document).map(ObjectNode::deepCopy)
                .orElseThrow(() -> denied("Invalid embedding session."));
        long now = clock.instant().getEpochSecond();
        if (!id.equals(session.path("dashboard").asText())) {
            throw denied("Dashboard does not match embedding session.");
        }
        if (!session.path("domains").isEmpty() && !originAllowed(session.path("domains"), origin)) {
            throw denied("Embedding origin is not allowed.");
        }
        ObjectNode dashboard = resources.getForAccount(account, session.path("region").asText() + "/" + account
                + "/dashboard/" + id).map(QuickSightResource::document).map(ObjectNode::deepCopy)
                .orElseThrow(() -> missing(id));
        if (!canRead(dashboard, session.path("user").asText())) {
            throw denied("User does not have permission to view this dashboard.");
        }
        if (session.path("redeemed").asBoolean()) {
            if (!session.path("cookie").asText().equals(sessionCookie) || now >= session.path("expires").asLong()) {
                throw denied("Embedding URL has already been redeemed or session expired.");
            }
        } else {
            if (!token.equals(code) || now >= session.path("redeemUntil").asLong()) {
                throw denied("Embedding URL has expired.");
            }
            session.put("redeemed", true).put("cookie", UUID.randomUUID().toString())
                    .put("expires", now + session.path("lifetime").asInt() * 60L);
            sessions.putForAccount(account, token, new QuickSightResource(session));
        }
        ArrayNode allowedOrigins = mapper.createArrayNode();
        for (JsonNode domain : session.path("domains")) {
            String normalized = QuickSightOrigins.fromUrl(domain.asText());
            if (!normalized.isEmpty()) {
                allowedOrigins.add(normalized);
            }
        }
        return dashboard.put("_SessionCookie", session.path("cookie").asText()).set("_AllowedOrigins", allowedOrigins);
    }

    public synchronized int cleanupExpiredSessions(Instant now) {
        long deadline = now.getEpochSecond();
        return sessions.deleteAllAccountsMatching(resource -> {
            ObjectNode session = resource.document();
            String expiryField = session.path("redeemed").asBoolean() ? "expires" : "redeemUntil";
            return session.path(expiryField).asLong() <= deadline;
        });
    }

    private void requireRegisteredUser(String account, String principal) {
        String userPrefix = arn(account, "user", "");
        if (!principal.startsWith(userPrefix)) {
            throw new AwsException("AccessDeniedException", "UserArn must belong to this account and region.", 401);
        }
        get(account, "user", principal.substring(userPrefix.length()));
    }

    private boolean originAllowed(JsonNode domains, String origin) {
        String normalized = QuickSightOrigins.fromUrl(origin);
        for (JsonNode domain : domains) {
            if (!normalized.isEmpty() && QuickSightOrigins.fromUrl(domain.asText()).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean canRead(ObjectNode resource, String principal) {
        for (JsonNode permission : resource.path("Permissions")) {
            if (principal.equals(permission.path("Principal").asText())) {
                for (JsonNode action : permission.path("Actions")) {
                    if ("quicksight:DescribeDashboard".equals(action.asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private ObjectNode page(List<ObjectNode> found, int maximum, String token, String scope, String field,
                            Function<ObjectNode, String> cursorOf) {
        if (maximum < 1 || maximum > 100) {
            throw invalid("MaxResults must be between 1 and 100.");
        }
        PaginatedResult<ObjectNode> page = Pagination.paginate(found, cursorOf, maximum, token, scope,
                value -> new AwsException("InvalidNextTokenException", "NextToken is invalid for this request.", 400));
        ObjectNode result = response(200);
        result.set(field, mapper.valueToTree(page.items()));
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    private static String ingestionCursor(ObjectNode ingestion) {
        // Invert epoch milliseconds so the shared ascending cursor lists newest first.
        long descendingTime = Long.MAX_VALUE - Math.round(ingestion.path("CreatedTime").asDouble() * 1000);
        return "%019d:%s".formatted(descendingTime, ingestion.path("IngestionId").asText());
    }

    private ObjectNode get(String account, String kind, String id) {
        return resources.getForAccount(account, key(account, kind, id)).map(QuickSightResource::document).map(ObjectNode::deepCopy)
                .orElseThrow(() -> missing(id));
    }

    private String key(String account, String kind, String id) {
        if (account == null || !account.matches("[0-9]{12}")) {
            throw invalid("AwsAccountId must contain 12 digits.");
        }
        if (id == null || id.contains("..")) {
            throw invalid("Invalid resource identifier.");
        }
        return regions.getRegion() + "/" + account + "/" + kind + "/" + id;
    }

    private String arn(String account, String kind, String id) {
        return regions.buildArn("quicksight", regions.getRegion(), account, kind + "/" + id);
    }

    private enum ResourceKind {
        DASHBOARD("dashboard", "DashboardId", "Dashboard", 202),
        DATA_SET("dataset", "DataSetId", "DataSet", 201),
        DATA_SOURCE("datasource", "DataSourceId", "DataSource", 202);

        private final String path;
        private final String idField;
        private final String responseField;
        private final int createStatus;

        ResourceKind(String path, String idField, String responseField, int createStatus) {
            this.path = path;
            this.idField = idField;
            this.responseField = responseField;
            this.createStatus = createStatus;
        }

        private static ResourceKind fromPath(String path) {
            for (ResourceKind kind : values()) {
                if (kind.path.equals(path)) {
                    return kind;
                }
            }
            throw invalid("Unsupported resource kind.");
        }
    }

    private ObjectNode response(int status) {
        return mapper.createObjectNode().put("Status", status).put("RequestId", UUID.randomUUID().toString());
    }

    private static String required(JsonNode input, String name) {
        JsonNode value = input.path(name);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid(name + " is required.");
        }
        return value.asText();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    private static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 403);
    }

    private static AwsException missing(String id) {
        return new AwsException("ResourceNotFoundException", "Resource not found: " + id, 404);
    }

    @Override
    public synchronized void clear() {
        resources.clear();
        sessions.clear();
    }
}
