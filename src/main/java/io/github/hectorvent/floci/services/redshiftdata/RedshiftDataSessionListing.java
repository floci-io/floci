package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataSessions.Scope;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataSessions.SessionInfo;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService.epochSeconds;

final class RedshiftDataSessionListing {
    private static final Pattern CLUSTER = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern WORKGROUP = Pattern.compile("[a-z0-9-]{3,63}");
    private static final Pattern WORKGROUP_RESOURCE = Pattern.compile(
            "workgroup/(?:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|[a-z0-9-]{1,63}/[a-z0-9-]{1,63})");
    private static final Set<String> STATUSES = Set.of("AVAILABLE", "BUSY", "CLOSED");

    private final ObjectMapper mapper;
    private final RedshiftDataSessions sessions;

    RedshiftDataSessionListing(ObjectMapper mapper, RedshiftDataSessions sessions) {
        this.mapper = mapper;
        this.sessions = sessions;
    }

    ObjectNode list(JsonNode request, Scope owner) {
        RedshiftDataSessions.validate(request);
        String id = text(request, "SessionId");
        String cluster = text(request, "ClusterIdentifier");
        String workgroup = text(request, "WorkgroupName");
        String database = text(request, "Database");
        String status = text(request, "Status");
        if (id != null && (cluster != null || workgroup != null || database != null || status != null)) {
            throw validation("SessionId cannot be combined with Status, ClusterIdentifier, WorkgroupName or Database.");
        }
        if (cluster != null && workgroup != null) {
            throw validation("ClusterIdentifier and WorkgroupName cannot both be specified.");
        }
        if (cluster != null && (cluster.length() > 63 || !CLUSTER.matcher(cluster).matches())) {
            throw validation("ClusterIdentifier is not valid.");
        }
        if (workgroup != null && !validWorkgroup(workgroup)) {
            throw validation("WorkgroupName is not valid.");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw validation("Status must be AVAILABLE, BUSY or CLOSED.");
        }
        JsonNode roleValue = request.get("RoleLevel");
        if (roleValue != null && !roleValue.isBoolean()) {
            throw validation("RoleLevel must be a boolean.");
        }
        boolean roleLevel = roleValue == null || roleValue.booleanValue();
        Integer maximum = Pagination.parseMaxResults(request, "MaxResults", "ValidationException");
        if (maximum != null && (maximum < 0 || maximum > 100)) {
            throw validation("MaxResults must be between 0 and 100.");
        }
        int limit = maximum == null || maximum == 0 ? 100 : maximum;
        String token = text(request, "NextToken");
        if (token != null && (token.isBlank() || token.length() > 8192)) {
            throw validation("NextToken is not valid.");
        }
        List<SessionInfo> matches = sessions.snapshots().stream()
                .filter(info -> visible(info.owner(), owner, roleLevel))
                .filter(info -> id == null || id.equals(info.id()))
                .filter(info -> id != null || (status == null ? !"CLOSED".equals(info.status()) : status.equals(info.status())))
                .filter(info -> cluster == null || cluster.equals(info.cluster()))
                .filter(info -> workgroup == null || workgroup.equals(info.workgroup()) || workgroup.equals(info.targetArn()))
                .filter(info -> database == null || database.equals(info.database()))
                .toList();
        if (id != null && matches.isEmpty()) {
            throw new AwsException("ResourceNotFoundException", "Session was not found.", 400);
        }
        ArrayNode scope = mapper.createArrayNode().add(owner.accountId()).add(owner.region())
                .add(roleLevel ? owner.principal() : owner.caller()).add(roleLevel)
                .add(id).add(cluster).add(workgroup).add(database).add(status);
        String namespace = "redshift-data-sessions-" + Digests.sha256Hex(scope.toString());
        PaginatedResult<SessionInfo> page = Pagination.paginate(matches,
                info -> info.createdAt().toString() + "/" + info.id(), limit, token, namespace,
                invalid -> validation("NextToken is not valid."));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode result = response.putArray("Sessions");
        for (SessionInfo info : page.items()) {
            ObjectNode node = result.addObject();
            node.put("SessionId", info.id());
            node.put("Status", info.status());
            node.put("CreatedAt", epochSeconds(info.createdAt()));
            node.put("UpdatedAt", epochSeconds(info.updatedAt()));
            node.put("SessionTtl", epochSeconds(info.ttl()));
            node.put("SessionAliveSeconds", info.keepAlive());
            node.put("Database", info.database());
            node.put("DbUser", info.user());
            if (info.cluster() != null) {
                node.put("ClusterIdentifier", info.cluster());
            } else {
                node.put("WorkgroupName", info.workgroup());
            }
            if (info.statementId() != null) {
                node.put("CurrentStatementId", info.statementId());
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    private static boolean visible(Scope actual, Scope requested, boolean roleLevel) {
        return actual.accountId().equals(requested.accountId()) && actual.region().equals(requested.region())
                && Objects.equals(roleLevel ? actual.principal() : actual.caller(),
                roleLevel ? requested.principal() : requested.caller());
    }

    private static boolean validWorkgroup(String value) {
        if (WORKGROUP.matcher(value).matches()) {
            return true;
        }
        if (value.length() > 128 || !AwsArnUtils.isArn(value)) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
        return "redshift-serverless".equals(arn.service()) && !arn.region().isBlank()
                && arn.accountId().matches("[0-9]{12}") && WORKGROUP_RESOURCE.matcher(arn.resource()).matches();
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
