package io.github.hectorvent.floci.services.identitystore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.identitystore.model.Group;
import io.github.hectorvent.floci.services.identitystore.model.Membership;
import io.github.hectorvent.floci.services.identitystore.model.User;
import io.github.hectorvent.floci.services.ssoadmin.SsoAdminService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
@Path("/{tenantId}/scim/v2")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ScimController {
    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    private static final String ENTERPRISE_USER_SCHEMA = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
    private static final String IDENTITYSTORE_ENTERPRISE_EXTENSION = "aws:identitystore:enterprise";
    private static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
    private static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    private static final String RESOURCE_TYPE_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";
    private static final String SERVICE_PROVIDER_CONFIG_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";
    private static final String PATCH_OP_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static final Pattern CURSOR_PATTERN = Pattern.compile("[-a-zA-Z0-9+=/:_]*");
    private static final Pattern SINGLE_GROUP_FILTER = Pattern.compile(
            "^(displayName|externalId|members\\.value|id) eq \\\"([^\\\"]*)\\\"$");
    private static final Pattern DOUBLE_GROUP_FILTER = Pattern.compile(
            "^(id|member) eq \\\"([^\\\"]*)\\\" and (id|member) eq \\\"([^\\\"]*)\\\"$");
    private static final Pattern SINGLE_USER_FILTER = Pattern.compile(
            "^(userName|externalId|groups\\.value|id) eq \\\"([^\\\"]*)\\\"$");
    private static final Pattern DOUBLE_USER_FILTER = Pattern.compile(
            "^(id|manager) eq \\\"([^\\\"]*)\\\" and (id|manager) eq \\\"([^\\\"]*)\\\"$");
    private static final List<String> PATCH_USER_ATTRIBUTES = List.of(
            "userName", "active", "externalId", "displayName", "nickName", "profileUrl", "title", "userType",
            "preferredLanguage", "locale", "timezone", "name", "enterprise", "emails", "addresses", "phoneNumbers");
    private static final Pattern PREFIXED_TENANT = Pattern.compile(
            "([0-9a-f]{10})-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_TENANT = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);

    private final IdentityStoreService identityStoreService;
    private final SsoAdminService ssoAdminService;
    private final ObjectMapper mapper;
    private final byte[] scimBearerToken;

    @Inject
    public ScimController(IdentityStoreService identityStoreService, SsoAdminService ssoAdminService,
                          ObjectMapper mapper, EmulatorConfig config) {
        this.identityStoreService = identityStoreService;
        this.ssoAdminService = ssoAdminService;
        this.mapper = mapper;
        this.scimBearerToken = config.services().identitystore().scimBearerToken().getBytes(StandardCharsets.UTF_8);
    }

    @GET
    @Path("/ServiceProviderConfig")
    public Response serviceProviderConfig(@PathParam("tenantId") String tenantId,
                                          @HeaderParam("Authorization") String authorization) {
        try {
            requireBearer(authorization);
            resolveIdentityStore(tenantId);
            ObjectNode response = mapper.createObjectNode();
            response.putArray("schemas").add(SERVICE_PROVIDER_CONFIG_SCHEMA);
            response.put("documentationUri", "https://docs.aws.amazon.com/singlesignon/latest/userguide/manage-your-identity-source-idp.html");
            ObjectNode auth = response.putArray("authenticationSchemes").addObject();
            auth.put("type", "oauthbearertoken");
            auth.put("name", "OAuth Bearer Token");
            auth.put("description", "Authentication scheme using the OAuth Bearer Token Standard");
            auth.put("specUri", "https://www.rfc-editor.org/info/rfc6750");
            auth.put("documentationUri", "https://docs.aws.amazon.com/singlesignon/latest/userguide/provision-automatically.html");
            auth.put("primary", true);
            response.putObject("patch").put("supported", true);
            ObjectNode bulk = response.putObject("bulk");
            bulk.put("supported", false);
            bulk.put("maxOperations", 1);
            bulk.put("maxPayloadSize", 1048576);
            ObjectNode filter = response.putObject("filter");
            filter.put("supported", true);
            filter.put("maxResults", 50);
            response.putObject("changePassword").put("supported", false);
            response.putObject("sort").put("supported", false);
            response.putObject("etag").put("supported", false);
            return Response.ok(response).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @GET
    @Path("/ResourceTypes")
    public Response listResourceTypes(@PathParam("tenantId") String tenantId,
                                      @HeaderParam("Authorization") String authorization,
                                      @Context UriInfo uriInfo) {
        try {
            requireBearer(authorization);
            resolveIdentityStore(tenantId);
            ObjectNode response = mapper.createObjectNode();
            response.putArray("schemas").add(LIST_SCHEMA);
            response.put("totalResults", 2);
            response.put("itemsPerPage", 2);
            response.put("startIndex", 1);
            ArrayNode resources = response.putArray("Resources");
            resources.add(resourceType("User", "/Users", "User Account", USER_SCHEMA, true, tenantId, uriInfo));
            resources.add(resourceType("Group", "/Groups", "Group", GROUP_SCHEMA, false, tenantId, uriInfo));
            return Response.ok(response).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @GET
    @Path("/Schemas")
    public Response listSchemas(@PathParam("tenantId") String tenantId,
                                @HeaderParam("Authorization") String authorization) {
        try {
            requireBearer(authorization);
            resolveIdentityStore(tenantId);
            ObjectNode response = mapper.createObjectNode();
            response.putArray("schemas").add(LIST_SCHEMA);
            response.put("totalResults", 3);
            response.put("itemsPerPage", 3);
            response.put("startIndex", 1);
            ArrayNode resources = response.putArray("Resources");
            resources.add(userSchema());
            resources.add(enterpriseUserSchema());
            resources.add(groupSchema());
            return Response.ok(response).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @GET
    @Path("/Schemas/{schemaId}")
    public Response getSchema(@PathParam("tenantId") String tenantId,
                              @PathParam("schemaId") String schemaId,
                              @HeaderParam("Authorization") String authorization) {
        try {
            requireBearer(authorization);
            resolveIdentityStore(tenantId);
            ObjectNode schema = scimSchema(schemaId);
            if (schema == null) {
                return scimError(404, "Schema not found: " + schemaId);
            }
            return Response.ok(schema).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @GET
    @Path("/Users")
    public Response listUsers(@PathParam("tenantId") String tenantId,
                              @HeaderParam("Authorization") String authorization,
                              @QueryParam("filter") String filter,
                              @QueryParam("count") String countValue,
                              @QueryParam("cursor") String cursor,
                              @Context UriInfo uriInfo) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            validateListQueryParameters(uriInfo, List.of("filter", "count", "cursor"));
            int count = scimCount(countValue);
            boolean cursorPresent = uriInfo.getQueryParameters().containsKey("cursor");
            List<User> matching = filterScimUsers(identityStoreId, filter);
            int offset = cursorPresent ? decodeCursor(cursor, filter) : 0;
            if (offset > matching.size()) {
                throw validation("cursor is invalid.");
            }
            int end = Math.min(offset + count, matching.size());

            ObjectNode response = mapper.createObjectNode();
            response.putArray("schemas").add(LIST_SCHEMA);
            ArrayNode resources = response.putArray("Resources");
            for (User user : matching.subList(offset, end)) {
                resources.add(userResponse(user));
            }
            response.put("itemsPerPage", end - offset);
            if (cursorPresent) {
                if (end < matching.size()) {
                    response.put("nextCursor", encodeCursor(end, filter));
                }
            } else {
                response.put("totalResults", matching.size());
                response.put("startIndex", 1);
            }
            return Response.ok(response).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @GET
    @Path("/Users/{userId}")
    public Response getUser(@PathParam("tenantId") String tenantId,
                            @PathParam("userId") String userId,
                            @HeaderParam("Authorization") String authorization) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            ObjectNode request = mapper.createObjectNode();
            request.put("IdentityStoreId", identityStoreId);
            request.put("UserId", userId);
            return Response.ok(userResponse(identityStoreService.describeUser(request))).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @GET
    @Path("/Groups")
    public Response listGroups(@PathParam("tenantId") String tenantId,
                               @HeaderParam("Authorization") String authorization,
                               @QueryParam("filter") String filter,
                               @QueryParam("count") String countValue,
                               @QueryParam("cursor") String cursor,
                               @Context UriInfo uriInfo) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            validateListQueryParameters(uriInfo, List.of("filter", "count", "cursor"));
            int count = scimCount(countValue);
            boolean cursorPresent = uriInfo.getQueryParameters().containsKey("cursor");
            List<Group> matching = filterScimGroups(identityStoreId, filter);
            int offset = cursorPresent ? decodeCursor(cursor, filter) : 0;
            if (offset > matching.size()) {
                throw validation("cursor is invalid.");
            }
            int end = Math.min(offset + count, matching.size());

            ObjectNode response = mapper.createObjectNode();
            response.putArray("schemas").add(LIST_SCHEMA);
            ArrayNode resources = response.putArray("Resources");
            for (Group group : matching.subList(offset, end)) {
                resources.add(groupResponse(group));
            }
            response.put("itemsPerPage", end - offset);
            if (cursorPresent) {
                if (end < matching.size()) {
                    response.put("nextCursor", encodeCursor(end, filter));
                }
            } else {
                response.put("totalResults", matching.size());
                response.put("startIndex", 1);
            }
            return Response.ok(response).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @GET
    @Path("/Groups/{groupId}")
    public Response getGroup(@PathParam("tenantId") String tenantId,
                             @PathParam("groupId") String groupId,
                             @HeaderParam("Authorization") String authorization) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            ObjectNode request = mapper.createObjectNode();
            request.put("IdentityStoreId", identityStoreId);
            request.put("GroupId", groupId);
            return Response.ok(groupResponse(identityStoreService.describeGroup(request))).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @DELETE
    @Path("/Users/{userId}")
    public Response deleteUser(@PathParam("tenantId") String tenantId,
                               @PathParam("userId") String userId,
                               @HeaderParam("Authorization") String authorization) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            ObjectNode request = mapper.createObjectNode();
            request.put("IdentityStoreId", identityStoreId);
            request.put("UserId", userId);
            identityStoreService.deleteUser(request);
            return Response.noContent().build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @PATCH
    @Path("/Users/{userId}")
    public Response patchUser(@PathParam("tenantId") String tenantId,
                              @PathParam("userId") String userId,
                              @HeaderParam("Authorization") String authorization,
                              String body) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            JsonNode request = parseObject(body);
            validatePatchSchema(request);
            validatePatchUser(identityStoreId, userId, request);
            applyPatchUser(identityStoreId, userId, request);
            ObjectNode describe = mapper.createObjectNode();
            describe.put("IdentityStoreId", identityStoreId);
            describe.put("UserId", userId);
            return Response.ok(userResponse(identityStoreService.describeUser(describe))).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @PATCH
    @Path("/Groups/{groupId}")
    public Response patchGroup(@PathParam("tenantId") String tenantId,
                               @PathParam("groupId") String groupId,
                               @HeaderParam("Authorization") String authorization,
                               String body) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            JsonNode request = parseObject(body);
            validatePatchSchema(request);
            validatePatchGroup(identityStoreId, groupId, request);
            applyPatchGroup(identityStoreId, groupId, request);
            return Response.noContent().build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @DELETE
    @Path("/Groups/{groupId}")
    public Response deleteGroup(@PathParam("tenantId") String tenantId,
                                @PathParam("groupId") String groupId,
                                @HeaderParam("Authorization") String authorization) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            ObjectNode request = mapper.createObjectNode();
            request.put("IdentityStoreId", identityStoreId);
            request.put("GroupId", groupId);
            identityStoreService.deleteGroup(request);
            return Response.noContent().build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @PUT
    @Path("/Users/{userId}")
    public Response putUser(@PathParam("tenantId") String tenantId,
                            @PathParam("userId") String userId,
                            @HeaderParam("Authorization") String authorization,
                            String body) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            JsonNode request = parseObject(body);
            validateCreateUser(request);
            User user = identityStoreService.replaceUserForScim(
                    identityStoreId, userId, toIdentityStoreUser(identityStoreId, request));
            return Response.status(Response.Status.CREATED).entity(userResponse(user)).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @POST
    @Path("/Users")
    public Response createUser(@PathParam("tenantId") String tenantId,
                               @HeaderParam("Authorization") String authorization,
                               String body) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            JsonNode request = parseObject(body);
            validateCreateUser(request);
            User user = identityStoreService.createUser(toIdentityStoreUser(identityStoreId, request));
            return Response.status(Response.Status.CREATED).entity(userResponse(user)).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    @POST
    @Path("/Groups")
    public Response createGroup(@PathParam("tenantId") String tenantId,
                                @HeaderParam("Authorization") String authorization,
                                String body) {
        try {
            requireBearer(authorization);
            String identityStoreId = resolveIdentityStore(tenantId);
            JsonNode request = parseObject(body);
            String displayName = requiredText(request, "displayName");

            JsonNode members = request.get("members");
            if (members != null && !members.isNull() && (!members.isArray() || members.size() > 100)) {
                throw validation("members must be an array containing at most 100 users.");
            }

            ObjectNode createRequest = mapper.createObjectNode();
            createRequest.put("IdentityStoreId", identityStoreId);
            createRequest.put("DisplayName", displayName);
            String externalId = optionalText(request, "externalId");
            if (externalId != null) {
                createRequest.putArray("ExternalIds").addObject().put("Issuer", GROUP_SCHEMA).put("Id", externalId);
            }

            Group group = identityStoreService.createGroup(createRequest);
            try {
                if (members != null && members.isArray()) {
                    for (JsonNode member : members) {
                        createMembership(identityStoreId, group.groupId(), member);
                    }
                }
            } catch (RuntimeException failure) {
                ObjectNode deleteRequest = mapper.createObjectNode();
                deleteRequest.put("IdentityStoreId", identityStoreId);
                deleteRequest.put("GroupId", group.groupId());
                identityStoreService.deleteGroup(deleteRequest);
                throw failure;
            }

            return Response.status(Response.Status.CREATED).entity(groupResponse(group)).build();
        } catch (AwsException exception) {
            return scimError(scimStatus(exception), exception.getMessage());
        }
    }

    private void validateCreateUser(JsonNode request) {
        requiredText(request, "userName");
        requiredText(request, "displayName");
        JsonNode name = request.get("name");
        if (name == null || !name.isObject()) {
            throw validation("name is required.");
        }
        requiredText(name, "givenName");
        requiredText(name, "familyName");

        if (request.has("groups")) {
            throw validation("groups cannot be specified when creating a user.");
        }
        for (String unsupported : java.util.List.of("ims", "photos", "x509Certificates", "entitlements", "password")) {
            if (request.has(unsupported)) {
                throw validation(unsupported + " is not supported.");
            }
        }

        validateSingleValueArray(request, "emails", true, true);
        validateSingleValueArray(request, "addresses", false, true);
        validateSingleValueArray(request, "phoneNumbers", false, true);
        validateSingleValueArray(request, "roles", false, false);

        JsonNode roles = request.get("roles");
        if (roles != null && roles.isArray() && !roles.isEmpty() && roles.get(0).has("display")) {
            throw validation("roles.display is not supported.");
        }
        JsonNode enterprise = request.get(ENTERPRISE_USER_SCHEMA);
        if (enterprise != null && !enterprise.isNull()) {
            if (!enterprise.isObject()) {
                throw validation(ENTERPRISE_USER_SCHEMA + " must be an object.");
            }
            JsonNode manager = enterprise.get("manager");
            if (manager != null && manager.isObject() && manager.has("displayName")) {
                throw validation("manager.displayName is not supported.");
            }
        }
        JsonNode active = request.get("active");
        if (active != null && !active.isBoolean()) {
            throw validation("active must be a boolean.");
        }
    }

    private void validateSingleValueArray(JsonNode request, String field, boolean requirePrimary, boolean rejectDisplay) {
        JsonNode values = request.get(field);
        if (values == null || values.isNull()) {
            return;
        }
        if (!values.isArray() || values.size() != 1 || !values.get(0).isObject()) {
            throw validation(field + " must contain exactly one value when specified.");
        }
        JsonNode value = values.get(0);
        if (rejectDisplay && value.has("display")) {
            throw validation(field + ".display is not supported.");
        }
        if (requirePrimary && (!value.path("primary").isBoolean() || !value.path("primary").booleanValue())) {
            throw validation(field + " must be marked primary.");
        }
    }

    private ObjectNode toIdentityStoreUser(String identityStoreId, JsonNode request) {
        ObjectNode out = mapper.createObjectNode();
        out.put("IdentityStoreId", identityStoreId);
        copyText(request, out, "userName", "UserName");
        copyText(request, out, "displayName", "DisplayName");
        copyText(request, out, "nickName", "NickName");
        copyText(request, out, "profileUrl", "ProfileUrl");
        copyText(request, out, "userType", "UserType");
        copyText(request, out, "title", "Title");
        copyText(request, out, "preferredLanguage", "PreferredLanguage");
        copyText(request, out, "locale", "Locale");
        copyText(request, out, "timezone", "Timezone");
        copyText(request, out, "birthdate", "Birthdate");
        out.put("UserStatus", request.path("active").isBoolean() && !request.path("active").booleanValue()
                ? "DISABLED" : "ENABLED");

        String externalId = optionalText(request, "externalId");
        if (externalId != null) {
            out.putArray("ExternalIds").addObject().put("Issuer", USER_SCHEMA).put("Id", externalId);
        }

        ObjectNode sourceName = (ObjectNode) request.get("name");
        ObjectNode targetName = out.putObject("Name");
        copyText(sourceName, targetName, "formatted", "Formatted");
        copyText(sourceName, targetName, "familyName", "FamilyName");
        copyText(sourceName, targetName, "givenName", "GivenName");
        copyText(sourceName, targetName, "middleName", "MiddleName");
        copyText(sourceName, targetName, "honorificPrefix", "HonorificPrefix");
        copyText(sourceName, targetName, "honorificSuffix", "HonorificSuffix");

        copyObjectArray(request, out, "emails", "Emails",
                Map.of("value", "Value", "type", "Type", "primary", "Primary"));
        copyObjectArray(request, out, "addresses", "Addresses",
                Map.ofEntries(
                        Map.entry("formatted", "Formatted"),
                        Map.entry("streetAddress", "StreetAddress"),
                        Map.entry("locality", "Locality"),
                        Map.entry("region", "Region"),
                        Map.entry("postalCode", "PostalCode"),
                        Map.entry("country", "Country"),
                        Map.entry("type", "Type"),
                        Map.entry("primary", "Primary")));
        copyObjectArray(request, out, "phoneNumbers", "PhoneNumbers",
                Map.of("value", "Value", "type", "Type", "primary", "Primary"));
        copyObjectArray(request, out, "roles", "Roles",
                Map.of("value", "Value", "type", "Type", "primary", "Primary"));

        JsonNode enterprise = request.get(ENTERPRISE_USER_SCHEMA);
        if (enterprise != null && enterprise.isObject()) {
            out.putObject("Extensions").set(IDENTITYSTORE_ENTERPRISE_EXTENSION, enterprise.deepCopy());
        }
        return out;
    }

    private ObjectNode userResponse(User user) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode schemas = response.putArray("schemas");
        schemas.add(USER_SCHEMA);
        response.put("id", user.userId());
        String externalId = scimExternalId(user.attributes().get("ExternalIds"), USER_SCHEMA);
        if (externalId != null) {
            response.put("externalId", externalId);
        }
        ObjectNode meta = response.putObject("meta");
        meta.put("resourceType", "User");
        meta.put("created", user.createdAt());
        meta.put("lastModified", user.updatedAt());

        copyTextBack(user.attributes(), response, "UserName", "userName");
        copyTextBack(user.attributes(), response, "DisplayName", "displayName");
        copyTextBack(user.attributes(), response, "NickName", "nickName");
        copyTextBack(user.attributes(), response, "ProfileUrl", "profileUrl");
        copyTextBack(user.attributes(), response, "UserType", "userType");
        copyTextBack(user.attributes(), response, "Title", "title");
        copyTextBack(user.attributes(), response, "PreferredLanguage", "preferredLanguage");
        copyTextBack(user.attributes(), response, "Locale", "locale");
        copyTextBack(user.attributes(), response, "Timezone", "timezone");
        copyTextBack(user.attributes(), response, "Birthdate", "birthdate");
        response.put("active", !"DISABLED".equals(optionalText(user.attributes(), "UserStatus")));

        JsonNode name = user.attributes().get("Name");
        if (name != null && name.isObject()) {
            ObjectNode targetName = response.putObject("name");
            copyTextBack(name, targetName, "Formatted", "formatted");
            copyTextBack(name, targetName, "FamilyName", "familyName");
            copyTextBack(name, targetName, "GivenName", "givenName");
            copyTextBack(name, targetName, "MiddleName", "middleName");
            copyTextBack(name, targetName, "HonorificPrefix", "honorificPrefix");
            copyTextBack(name, targetName, "HonorificSuffix", "honorificSuffix");
        }
        copyObjectArrayBack(user.attributes(), response, "Emails", "emails",
                Map.of("Value", "value", "Type", "type", "Primary", "primary"));
        copyObjectArrayBack(user.attributes(), response, "Addresses", "addresses",
                Map.ofEntries(
                        Map.entry("Formatted", "formatted"),
                        Map.entry("StreetAddress", "streetAddress"),
                        Map.entry("Locality", "locality"),
                        Map.entry("Region", "region"),
                        Map.entry("PostalCode", "postalCode"),
                        Map.entry("Country", "country"),
                        Map.entry("Type", "type"),
                        Map.entry("Primary", "primary")));
        copyObjectArrayBack(user.attributes(), response, "PhoneNumbers", "phoneNumbers",
                Map.of("Value", "value", "Type", "type", "Primary", "primary"));
        copyObjectArrayBack(user.attributes(), response, "Roles", "roles",
                Map.of("Value", "value", "Type", "type", "Primary", "primary"));

        JsonNode extensions = user.attributes().get("Extensions");
        if (extensions != null && extensions.isObject()) {
            JsonNode enterprise = extensions.get(IDENTITYSTORE_ENTERPRISE_EXTENSION);
            if (enterprise != null && enterprise.isObject()) {
                schemas.add(ENTERPRISE_USER_SCHEMA);
                ObjectNode enterpriseResponse = enterprise.deepCopy();
                JsonNode manager = enterpriseResponse.get("manager");
                if (manager instanceof ObjectNode managerObject) {
                    managerObject.remove("$ref");
                    managerObject.remove("displayName");
                }
                response.set(ENTERPRISE_USER_SCHEMA, enterpriseResponse);
            }
        }
        return response;
    }

    private void createMembership(String identityStoreId, String groupId, JsonNode member) {
        if (member == null || !member.isObject()) {
            throw validation("Each members entry must be an object.");
        }
        String userId = requiredText(member, "value");
        String type = optionalText(member, "type");
        if (type != null && !"User".equals(type)) {
            throw validation("members.type must be User when specified.");
        }
        ObjectNode membershipRequest = mapper.createObjectNode();
        membershipRequest.put("IdentityStoreId", identityStoreId);
        membershipRequest.put("GroupId", groupId);
        membershipRequest.putObject("MemberId").put("UserId", userId);
        identityStoreService.createMembership(membershipRequest);
    }

    private ObjectNode groupResponse(Group group) {
        ObjectNode response = mapper.createObjectNode();
        response.putArray("schemas").add(GROUP_SCHEMA);
        response.put("id", group.groupId());
        response.put("displayName", group.displayName());
        response.putArray("members");
        String externalId = scimExternalId(group.attributes().get("ExternalIds"), GROUP_SCHEMA);
        if (externalId != null) {
            response.put("externalId", externalId);
        }
        ObjectNode meta = response.putObject("meta");
        meta.put("resourceType", "Group");
        meta.put("created", group.createdAt());
        meta.put("lastModified", group.updatedAt());
        return response;
    }

    private String resolveIdentityStore(String tenantId) {
        if (tenantId == null) {
            throw unauthorized("Authorization header is invalid or tenant ID is incorrect.");
        }
        String identityStoreId;
        Matcher prefixed = PREFIXED_TENANT.matcher(tenantId);
        if (prefixed.matches()) {
            identityStoreId = "d-" + prefixed.group(1).toLowerCase(java.util.Locale.ROOT);
        } else if (LEGACY_TENANT.matcher(tenantId).matches()) {
            identityStoreId = tenantId.toLowerCase(java.util.Locale.ROOT);
        } else {
            throw unauthorized("Authorization header is invalid or tenant ID is incorrect.");
        }
        if (!ssoAdminService.hasIdentityStore(identityStoreId)) {
            throw unauthorized("Authorization header is invalid or tenant ID is incorrect.");
        }
        return identityStoreId;
    }

    private void requireBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw unauthorized("Authorization header is invalid or missing.");
        }
        byte[] presented = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        if (presented.length == 0 || !MessageDigest.isEqual(scimBearerToken, presented)) {
            throw unauthorized("Authorization header is invalid or missing.");
        }
    }

    private JsonNode parseObject(String body) {
        try {
            JsonNode node = mapper.readTree(body == null ? "" : body);
            if (node == null || !node.isObject()) {
                throw validation("Request is unparsable, syntactically incorrect, or violates schema.");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw validation("Request is unparsable, syntactically incorrect, or violates schema.");
        }
    }

    private void copyText(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        String value = optionalText(source, sourceField);
        if (value != null) {
            target.put(targetField, value);
        }
    }

    private static void copyTextBack(JsonNode source, ObjectNode target, String sourceField, String targetField) {
        JsonNode value = source == null ? null : source.get(sourceField);
        if (value != null && value.isTextual()) {
            target.put(targetField, value.textValue());
        }
    }

    private void copyObjectArray(JsonNode source, ObjectNode target, String sourceField, String targetField,
                                 Map<String, String> fields) {
        JsonNode array = source.get(sourceField);
        if (array == null || !array.isArray()) {
            return;
        }
        ArrayNode targetArray = target.putArray(targetField);
        for (JsonNode item : array) {
            ObjectNode targetItem = targetArray.addObject();
            for (var field : fields.entrySet()) {
                JsonNode value = item.get(field.getKey());
                if (value != null && !value.isNull()) {
                    targetItem.set(field.getValue(), value.deepCopy());
                }
            }
        }
    }

    private void copyObjectArrayBack(JsonNode source, ObjectNode target, String sourceField, String targetField,
                                     Map<String, String> fields) {
        JsonNode array = source.get(sourceField);
        if (array == null || !array.isArray()) {
            return;
        }
        ArrayNode targetArray = target.putArray(targetField);
        for (JsonNode item : array) {
            ObjectNode targetItem = targetArray.addObject();
            for (var field : fields.entrySet()) {
                JsonNode value = item.get(field.getKey());
                if (value != null && !value.isNull()) {
                    targetItem.set(field.getValue(), value.deepCopy());
                }
            }
        }
    }

    private void validatePatchSchema(JsonNode request) {
        JsonNode schemas = request.get("schemas");
        if (schemas == null || !schemas.isArray() || schemas.size() != 1
                || !PATCH_OP_SCHEMA.equals(schemas.get(0).textValue())) {
            throw validation("schemas must contain only the SCIM PatchOp schema.");
        }
    }

    private void validatePatchUser(String identityStoreId, String userId, JsonNode request) {
        ObjectNode describe = mapper.createObjectNode();
        describe.put("IdentityStoreId", identityStoreId);
        describe.put("UserId", userId);
        identityStoreService.describeUser(describe);

        JsonNode operations = request.get("Operations");
        if (operations == null || !operations.isArray() || operations.isEmpty()) {
            throw validation("Operations must contain at least one patch operation.");
        }
        int userNameChanges = 0;
        int activeChanges = 0;
        for (JsonNode operation : operations) {
            if (operation == null || !operation.isObject()) {
                throw validation("Each patch operation must be an object.");
            }
            String op = requiredText(operation, "op");
            if (!List.of("add", "replace", "remove").contains(op)) {
                throw validation("Unsupported patch operation: " + op);
            }
            String path = optionalText(operation, "path");
            JsonNode value = operation.get("value");
            if ("remove".equals(op) && (path == null || path.isBlank())) {
                throw validation("path is required for remove operations.");
            }
            if (!"remove".equals(op) && (value == null || value.isNull())) {
                throw validation("value is required for add and replace operations.");
            }
            if (path == null) {
                if (!value.isObject() || value.isEmpty()) {
                    throw validation("A patch operation without path must provide an object value.");
                }
                var fields = value.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    validatePatchUserAttribute(identityStoreId, userId, op, entry.getKey(), entry.getValue());
                    if ("userName".equals(entry.getKey())) {
                        userNameChanges++;
                    } else if ("active".equals(entry.getKey())) {
                        activeChanges++;
                    }
                }
            } else {
                validatePatchUserAttribute(identityStoreId, userId, op, path, value);
                if ("userName".equals(path)) {
                    userNameChanges++;
                } else if ("active".equals(path)) {
                    activeChanges++;
                }
            }
        }
        if (userNameChanges > 1 || activeChanges > 1) {
            throw validation("Multiple patch operations on userName or active are not supported.");
        }
    }

    private void validatePatchUserAttribute(String identityStoreId, String userId, String op, String path, JsonNode value) {
        if (!PATCH_USER_ATTRIBUTES.contains(path)) {
            throw validation("The user attribute cannot be patched: " + path);
        }
        if ("remove".equals(op) && ("userName".equals(path) || "active".equals(path))) {
            throw validation(path + " cannot be removed.");
        }
        if ("active".equals(path)) {
            patchBoolean(value, "active");
            return;
        }
        if ("userName".equals(path)) {
            String userName = requirePatchText(value, "userName");
            boolean duplicate = identityStoreService.listUsersForScim(identityStoreId).stream()
                    .anyMatch(user -> userName.equals(user.userName()) && !userId.equals(user.userId()));
            if (duplicate) {
                throw new AwsException("ConflictException", "A user with UserName " + userName + " already exists.", 400);
            }
            return;
        }
        if ("externalId".equals(path)) {
            if (!"remove".equals(op)) {
                requirePatchText(value, "externalId");
            }
            return;
        }
        if (List.of("displayName", "nickName", "profileUrl", "title", "userType", "preferredLanguage", "locale", "timezone")
                .contains(path)) {
            if (!"remove".equals(op)) {
                requirePatchText(value, path);
            }
            return;
        }
        if ("name".equals(path)) {
            if (!"remove".equals(op)) {
                validatePatchName(value);
            }
            return;
        }
        if ("enterprise".equals(path)) {
            if (!"remove".equals(op)) {
                validatePatchEnterprise(value);
            }
            return;
        }
        if (List.of("emails", "addresses", "phoneNumbers").contains(path) && !"remove".equals(op)) {
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.set(path, value.deepCopy());
            validateSingleValueArray(wrapper, path, "emails".equals(path), true);
        }
    }

    private void validatePatchName(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw validation("name must be an object.");
        }
        for (String field : List.of("formatted", "familyName", "givenName", "middleName", "honorificPrefix", "honorificSuffix")) {
            JsonNode fieldValue = value.get(field);
            if (fieldValue != null && !fieldValue.isNull() && !fieldValue.isTextual()) {
                throw validation("name." + field + " must be a string.");
            }
        }
    }

    private void validatePatchEnterprise(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw validation("enterprise must be an object.");
        }
        JsonNode manager = value.get("manager");
        if (manager != null && !manager.isNull()) {
            if (!manager.isObject()) {
                throw validation("enterprise.manager must be an object.");
            }
            if (manager.has("displayName")) {
                throw validation("manager.displayName is not supported.");
            }
        }
    }

    private void applyPatchUser(String identityStoreId, String userId, JsonNode request) {
        for (JsonNode operation : request.get("Operations")) {
            String op = operation.path("op").textValue();
            String path = optionalText(operation, "path");
            JsonNode value = operation.get("value");
            if (path == null) {
                var fields = value.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    applyPatchUserAttribute(identityStoreId, userId, op, entry.getKey(), entry.getValue());
                }
            } else {
                applyPatchUserAttribute(identityStoreId, userId, op, path, value);
            }
        }
    }

    private void applyPatchUserAttribute(String identityStoreId, String userId, String op, String path, JsonNode value) {
        ObjectNode update = mapper.createObjectNode();
        update.put("IdentityStoreId", identityStoreId);
        update.put("UserId", userId);
        ObjectNode attributeOperation = update.putArray("Operations").addObject();
        switch (path) {
            case "active" -> {
                attributeOperation.put("AttributePath", "userStatus");
                attributeOperation.put("AttributeValue", patchBoolean(value, "active") ? "ENABLED" : "DISABLED");
            }
            case "externalId" -> {
                attributeOperation.put("AttributePath", "externalIds");
                if ("remove".equals(op)) {
                    attributeOperation.putNull("AttributeValue");
                } else {
                    ArrayNode externalIds = mapper.createArrayNode();
                    externalIds.addObject().put("Issuer", USER_SCHEMA).put("Id", value.textValue());
                    attributeOperation.set("AttributeValue", externalIds);
                }
            }
            case "name" -> {
                attributeOperation.put("AttributePath", "name");
                attributeOperation.set("AttributeValue", "remove".equals(op) ? mapper.nullNode() : scimNameToIdentityStore(value));
            }
            case "enterprise" -> {
                attributeOperation.put("AttributePath", IDENTITYSTORE_ENTERPRISE_EXTENSION);
                attributeOperation.set("AttributeValue", "remove".equals(op) ? mapper.nullNode() : value.deepCopy());
            }
            case "emails", "addresses", "phoneNumbers" -> {
                attributeOperation.put("AttributePath", path);
                if ("remove".equals(op)) {
                    attributeOperation.putNull("AttributeValue");
                } else {
                    ObjectNode source = mapper.createObjectNode();
                    source.set(path, value.deepCopy());
                    ObjectNode converted = mapper.createObjectNode();
                    if ("emails".equals(path)) {
                        copyObjectArray(source, converted, path, "Emails",
                                Map.of("value", "Value", "type", "Type", "primary", "Primary"));
                    } else if ("addresses".equals(path)) {
                        copyObjectArray(source, converted, path, "Addresses",
                                Map.ofEntries(Map.entry("formatted", "Formatted"), Map.entry("streetAddress", "StreetAddress"),
                                        Map.entry("locality", "Locality"), Map.entry("region", "Region"),
                                        Map.entry("postalCode", "PostalCode"), Map.entry("country", "Country"),
                                        Map.entry("type", "Type"), Map.entry("primary", "Primary")));
                    } else {
                        copyObjectArray(source, converted, path, "PhoneNumbers",
                                Map.of("value", "Value", "type", "Type", "primary", "Primary"));
                    }
                    attributeOperation.set("AttributeValue", converted.elements().next());
                }
            }
            default -> {
                attributeOperation.put("AttributePath", path);
                attributeOperation.set("AttributeValue", "remove".equals(op) ? mapper.nullNode() : value.deepCopy());
            }
        }
        identityStoreService.updateUser(update);
    }

    private ObjectNode scimNameToIdentityStore(JsonNode value) {
        ObjectNode out = mapper.createObjectNode();
        copyText(value, out, "formatted", "Formatted");
        copyText(value, out, "familyName", "FamilyName");
        copyText(value, out, "givenName", "GivenName");
        copyText(value, out, "middleName", "MiddleName");
        copyText(value, out, "honorificPrefix", "HonorificPrefix");
        copyText(value, out, "honorificSuffix", "HonorificSuffix");
        return out;
    }

    private static boolean patchBoolean(JsonNode value, String field) {
        if (value != null && value.isBoolean()) {
            return value.booleanValue();
        }
        if (value != null && value.isTextual() && ("true".equals(value.textValue()) || "false".equals(value.textValue()))) {
            return Boolean.parseBoolean(value.textValue());
        }
        throw validation(field + " patch value must be true or false.");
    }

    private void validatePatchGroup(String identityStoreId, String groupId, JsonNode request) {
        ObjectNode describe = mapper.createObjectNode();
        describe.put("IdentityStoreId", identityStoreId);
        describe.put("GroupId", groupId);
        identityStoreService.describeGroup(describe);

        JsonNode operations = request.get("Operations");
        if (operations == null || !operations.isArray() || operations.isEmpty()) {
            throw validation("Operations must contain at least one patch operation.");
        }
        int membershipChanges = 0;
        for (JsonNode operation : operations) {
            if (operation == null || !operation.isObject()) {
                throw validation("Each patch operation must be an object.");
            }
            String op = requiredText(operation, "op");
            String path = requiredText(operation, "path");
            if (!List.of("add", "replace", "remove").contains(op)) {
                throw validation("Unsupported patch operation: " + op);
            }
            if (!List.of("displayName", "members", "externalId").contains(path)) {
                throw validation("Only displayName, members, and externalId can be patched on groups.");
            }
            JsonNode value = operation.get("value");
            if ("members".equals(path)) {
                if ("replace".equals(op)) {
                    throw validation("Replacing all group memberships in one request is not supported.");
                }
                if (value == null || !value.isArray() || value.isEmpty()) {
                    throw validation("members patch value must contain at least one member.");
                }
                membershipChanges += value.size();
                if (membershipChanges > 100) {
                    throw validation("A maximum of 100 membership changes are allowed in one request.");
                }
                for (JsonNode member : value) {
                    if (member == null || !member.isObject()) {
                        throw validation("Each members value must be an object.");
                    }
                    String userId = requiredText(member, "value");
                    String type = optionalText(member, "type");
                    if (type != null && !"User".equals(type)) {
                        throw validation("members.type must be User when specified.");
                    }
                    requireScimUser(identityStoreId, userId);
                }
                continue;
            }
            if ("displayName".equals(path)) {
                if ("remove".equals(op)) {
                    throw validation("displayName cannot be removed.");
                }
                String displayName = requirePatchText(value, "displayName");
                boolean duplicate = identityStoreService.listGroupsForScim(identityStoreId).stream()
                        .anyMatch(group -> displayName.equals(group.displayName()) && !groupId.equals(group.groupId()));
                if (duplicate) {
                    throw new AwsException("ConflictException", "A group with DisplayName " + displayName + " already exists.", 400);
                }
                continue;
            }
            if (!"remove".equals(op)) {
                requirePatchText(value, "externalId");
            }
        }
    }

    private void applyPatchGroup(String identityStoreId, String groupId, JsonNode request) {
        for (JsonNode operation : request.get("Operations")) {
            String op = operation.path("op").textValue();
            String path = operation.path("path").textValue();
            if ("members".equals(path)) {
                for (JsonNode member : operation.get("value")) {
                    String userId = member.path("value").textValue();
                    Membership existing = identityStoreService.listMembershipsForScim(identityStoreId).stream()
                            .filter(candidate -> groupId.equals(candidate.groupId()) && userId.equals(candidate.userId()))
                            .findFirst().orElse(null);
                    if ("add".equals(op) && existing == null) {
                        ObjectNode membership = mapper.createObjectNode();
                        membership.put("IdentityStoreId", identityStoreId);
                        membership.put("GroupId", groupId);
                        membership.putObject("MemberId").put("UserId", userId);
                        identityStoreService.createMembership(membership);
                    } else if ("remove".equals(op) && existing != null) {
                        ObjectNode deletion = mapper.createObjectNode();
                        deletion.put("IdentityStoreId", identityStoreId);
                        deletion.put("MembershipId", existing.membershipId());
                        identityStoreService.deleteMembership(deletion);
                    }
                }
                continue;
            }

            ObjectNode update = mapper.createObjectNode();
            update.put("IdentityStoreId", identityStoreId);
            update.put("GroupId", groupId);
            ObjectNode attributeOperation = update.putArray("Operations").addObject();
            if ("displayName".equals(path)) {
                attributeOperation.put("AttributePath", "displayName");
                attributeOperation.set("AttributeValue", operation.get("value").deepCopy());
            } else {
                attributeOperation.put("AttributePath", "externalIds");
                if ("remove".equals(op)) {
                    attributeOperation.putNull("AttributeValue");
                } else {
                    ArrayNode externalIds = mapper.createArrayNode();
                    externalIds.addObject().put("Issuer", GROUP_SCHEMA).put("Id", operation.get("value").textValue());
                    attributeOperation.set("AttributeValue", externalIds);
                }
            }
            identityStoreService.updateGroup(update);
        }
    }

    private static String requirePatchText(JsonNode value, String field) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw validation(field + " patch value must be a non-empty string.");
        }
        return value.textValue();
    }

    private ObjectNode resourceType(String id, String endpoint, String description, String schemaId,
                                    boolean enterpriseExtension, String tenantId, UriInfo uriInfo) {
        ObjectNode resourceType = mapper.createObjectNode();
        resourceType.putArray("schemas").add(RESOURCE_TYPE_SCHEMA);
        resourceType.put("id", id);
        resourceType.put("name", id);
        resourceType.put("endpoint", endpoint);
        resourceType.put("description", description);
        resourceType.put("schema", schemaId);
        if (enterpriseExtension) {
            ObjectNode extension = resourceType.putArray("schemaExtensions").addObject();
            extension.put("schema", ENTERPRISE_USER_SCHEMA);
            extension.put("required", true);
        }
        ObjectNode meta = resourceType.putObject("meta");
        meta.put("resourceType", "ResourceType");
        meta.put("location", uriInfo.getBaseUriBuilder()
                .path(tenantId).path("scim").path("v2").path("ResourceTypes").path(id).build().toString());
        return resourceType;
    }

    private ObjectNode scimSchema(String schemaId) {
        return switch (schemaId) {
            case USER_SCHEMA -> userSchema();
            case GROUP_SCHEMA -> groupSchema();
            case ENTERPRISE_USER_SCHEMA -> enterpriseUserSchema();
            default -> null;
        };
    }

    private ObjectNode userSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("id", USER_SCHEMA);
        schema.put("name", "User");
        schema.put("description", "User Schema");
        ArrayNode attributes = schema.putArray("attributes");
        attributes.add(scimAttribute("userName", "string", false, true, "readWrite", "default", "server"));

        ObjectNode name = scimAttribute("name", "complex", false, false, "readWrite", "default", "none");
        ArrayNode subAttributes = name.putArray("subAttributes");
        subAttributes.add(scimAttribute("formatted", "string", false, false, "readWrite", "default", "none"));
        subAttributes.add(scimAttribute("familyName", "string", false, true, "readWrite", "default", "none"));
        subAttributes.add(scimAttribute("givenName", "string", false, true, "readWrite", "default", "none"));
        subAttributes.add(scimAttribute("middleName", "string", false, false, "readWrite", "default", "none"));
        subAttributes.add(scimAttribute("honorificPrefix", "string", false, false, "readWrite", "default", "none"));
        subAttributes.add(scimAttribute("honorificSuffix", "string", false, false, "readWrite", "default", "none"));
        attributes.add(name);

        attributes.add(scimAttribute("displayName", "string", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("nickName", "string", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("profileUrl", "reference", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("title", "string", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("userType", "string", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("preferredLanguage", "string", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("locale", "string", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("timezone", "string", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("active", "boolean", false, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("emails", "complex", true, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("phoneNumbers", "complex", true, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("addresses", "complex", true, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("roles", "complex", true, false, "readWrite", "default", "none"));
        attributes.add(scimAttribute("groups", "complex", true, false, "readOnly", "default", "none"));
        return schema;
    }

    private ObjectNode groupSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("id", GROUP_SCHEMA);
        schema.put("name", "Group");
        schema.put("description", "Group");
        ArrayNode attributes = schema.putArray("attributes");
        attributes.add(scimAttribute("displayName", "string", false, true, "readWrite", "default", "server"));
        attributes.add(scimAttribute("members", "complex", true, false, "readWrite", "default", "none"));
        return schema;
    }

    private ObjectNode enterpriseUserSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("id", ENTERPRISE_USER_SCHEMA);
        schema.put("name", "EnterpriseUser");
        schema.put("description", "Enterprise User");
        ArrayNode attributes = schema.putArray("attributes");
        for (String name : List.of("employeeNumber", "costCenter", "organization", "division", "department")) {
            attributes.add(scimAttribute(name, "string", false, false, "readWrite", "default", "none"));
        }
        attributes.add(scimAttribute("manager", "complex", false, false, "readWrite", "default", "none"));
        return schema;
    }

    private ObjectNode scimAttribute(String name, String type, boolean multiValued, boolean required,
                                     String mutability, String returned, String uniqueness) {
        ObjectNode attribute = mapper.createObjectNode();
        attribute.put("name", name);
        attribute.put("type", type);
        attribute.put("multiValued", multiValued);
        attribute.put("required", required);
        attribute.put("caseExact", false);
        attribute.put("mutability", mutability);
        attribute.put("returned", returned);
        attribute.put("uniqueness", uniqueness);
        return attribute;
    }

    private List<User> filterScimUsers(String identityStoreId, String filter) {
        List<User> users = identityStoreService.listUsersForScim(identityStoreId);
        if (filter == null || filter.isBlank()) {
            if (filter != null && !filter.isEmpty()) {
                throw validation("filter is invalid.");
            }
            return users;
        }

        Matcher single = SINGLE_USER_FILTER.matcher(filter);
        if (single.matches()) {
            String attribute = single.group(1);
            String value = single.group(2);
            return switch (attribute) {
                case "userName" -> users.stream()
                        .filter(user -> value.equals(user.userName()))
                        .toList();
                case "externalId" -> users.stream()
                        .filter(user -> value.equals(scimExternalId(user.attributes().get("ExternalIds"), USER_SCHEMA)))
                        .toList();
                case "id" -> users.stream()
                        .filter(user -> value.equals(user.userId()))
                        .toList();
                case "groups.value" -> usersForGroup(identityStoreId, users, value);
                default -> throw validation("filter is invalid.");
            };
        }

        Matcher combined = DOUBLE_USER_FILTER.matcher(filter);
        if (!combined.matches() || combined.group(1).equals(combined.group(3))) {
            throw validation("filter is invalid.");
        }
        String userId = "id".equals(combined.group(1)) ? combined.group(2) : combined.group(4);
        String managerId = "manager".equals(combined.group(1)) ? combined.group(2) : combined.group(4);
        return users.stream()
                .filter(user -> userId.equals(user.userId()) && managerId.equals(scimManagerId(user)))
                .toList();
    }

    private List<User> usersForGroup(String identityStoreId, List<User> users, String groupId) {
        java.util.Set<String> userIds = identityStoreService.listMembershipsForScim(identityStoreId).stream()
                .filter(membership -> groupId.equals(membership.groupId()))
                .map(Membership::userId)
                .collect(java.util.stream.Collectors.toSet());
        return users.stream().filter(user -> userIds.contains(user.userId())).toList();
    }

    private static String scimManagerId(User user) {
        JsonNode extensions = user.attributes().get("Extensions");
        if (extensions == null || !extensions.isObject()) {
            return null;
        }
        JsonNode enterprise = extensions.get(IDENTITYSTORE_ENTERPRISE_EXTENSION);
        if (enterprise == null || !enterprise.isObject()) {
            return null;
        }
        JsonNode manager = enterprise.get("manager");
        return manager != null && manager.isObject() ? optionalText(manager, "value") : null;
    }

    private List<Group> filterScimGroups(String identityStoreId, String filter) {
        List<Group> groups = identityStoreService.listGroupsForScim(identityStoreId);
        if (filter == null || filter.isBlank()) {
            if (filter != null && !filter.isEmpty()) {
                throw validation("filter is invalid.");
            }
            return groups;
        }

        Matcher single = SINGLE_GROUP_FILTER.matcher(filter);
        if (single.matches()) {
            String attribute = single.group(1);
            String value = single.group(2);
            return switch (attribute) {
                case "displayName" -> groups.stream()
                        .filter(group -> value.equals(group.displayName()))
                        .toList();
                case "externalId" -> groups.stream()
                        .filter(group -> value.equals(scimExternalId(group.attributes().get("ExternalIds"), GROUP_SCHEMA)))
                        .toList();
                case "id" -> groups.stream()
                        .filter(group -> value.equals(group.groupId()))
                        .toList();
                case "members.value" -> groupsForMember(identityStoreId, groups, value);
                default -> throw validation("filter is invalid.");
            };
        }

        Matcher combined = DOUBLE_GROUP_FILTER.matcher(filter);
        if (!combined.matches() || combined.group(1).equals(combined.group(3))) {
            throw validation("filter is invalid.");
        }
        String groupId = "id".equals(combined.group(1)) ? combined.group(2) : combined.group(4);
        String memberId = "member".equals(combined.group(1)) ? combined.group(2) : combined.group(4);
        requireScimUser(identityStoreId, memberId);
        boolean member = identityStoreService.listMembershipsForScim(identityStoreId).stream()
                .anyMatch(membership -> groupId.equals(membership.groupId()) && memberId.equals(membership.userId()));
        return member ? groups.stream().filter(group -> groupId.equals(group.groupId())).toList() : List.of();
    }

    private List<Group> groupsForMember(String identityStoreId, List<Group> groups, String memberId) {
        requireScimUser(identityStoreId, memberId);
        java.util.Set<String> groupIds = identityStoreService.listMembershipsForScim(identityStoreId).stream()
                .filter(membership -> memberId.equals(membership.userId()))
                .map(Membership::groupId)
                .collect(java.util.stream.Collectors.toSet());
        return groups.stream().filter(group -> groupIds.contains(group.groupId())).toList();
    }

    private void requireScimUser(String identityStoreId, String userId) {
        ObjectNode request = mapper.createObjectNode();
        request.put("IdentityStoreId", identityStoreId);
        request.put("UserId", userId);
        identityStoreService.describeUser(request);
    }

    private static int scimCount(String countValue) {
        if (countValue == null) {
            return 100;
        }
        try {
            int count = Integer.parseInt(countValue);
            if (count < 1 || count > 100) {
                throw validation("count must be between 1 and 100.");
            }
            return count;
        } catch (NumberFormatException exception) {
            throw validation("count must be between 1 and 100.");
        }
    }

    private static String encodeCursor(int offset, String filter) {
        String fingerprint = filter == null ? "" : filter;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (offset + "\n" + fingerprint).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeCursor(String cursor, String filter) {
        if (cursor == null || cursor.isEmpty()) {
            return 0;
        }
        if (!CURSOR_PATTERN.matcher(cursor).matches()) {
            throw validation("cursor is invalid.");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('\n');
            if (separator <= 0) {
                throw validation("cursor is invalid.");
            }
            int offset = Integer.parseInt(decoded.substring(0, separator));
            String expectedFilter = filter == null ? "" : filter;
            if (offset < 0 || !expectedFilter.equals(decoded.substring(separator + 1))) {
                throw validation("cursor is invalid or filter parameters changed between pagination requests.");
            }
            return offset;
        } catch (IllegalArgumentException exception) {
            throw validation("cursor is invalid.");
        }
    }

    private static void validateListQueryParameters(UriInfo uriInfo, List<String> allowed) {
        for (String parameter : uriInfo.getQueryParameters().keySet()) {
            if (!allowed.contains(parameter)) {
                throw validation("Unsupported query parameter: " + parameter);
            }
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw validation(field + " must be a string.");
        }
        return value.textValue();
    }

    private static String scimExternalId(JsonNode externalIds, String issuer) {
        if (externalIds == null || !externalIds.isArray()) {
            return null;
        }
        for (JsonNode externalId : externalIds) {
            if (issuer.equals(optionalText(externalId, "Issuer"))) {
                return optionalText(externalId, "Id");
            }
        }
        return null;
    }

    private static int scimStatus(AwsException exception) {
        return switch (exception.getErrorCode()) {
            case "ValidationException" -> 400;
            case "UnauthorizedException" -> 401;
            case "AccessDeniedException" -> 403;
            case "ResourceNotFoundException" -> 404;
            case "ConflictException" -> 409;
            case "ThrottlingException" -> 429;
            case "InternalServerException" -> 500;
            default -> exception.getHttpStatus();
        };
    }

    private Response scimError(int status, String detail) {
        ObjectNode error = mapper.createObjectNode();
        ArrayNode schemas = error.putArray("schemas");
        schemas.add(ERROR_SCHEMA);
        error.put("detail", detail);
        error.put("status", Integer.toString(status));
        return Response.status(status).entity(error).build();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException unauthorized(String message) {
        return new AwsException("UnauthorizedException", message, 401);
    }
}
