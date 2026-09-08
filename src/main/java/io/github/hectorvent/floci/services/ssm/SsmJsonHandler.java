package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import io.github.hectorvent.floci.services.ssm.model.InstanceInformation;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.PatchBaselineIdentity;
import io.github.hectorvent.floci.services.ssm.model.ServiceSetting;
import io.github.hectorvent.floci.services.ssm.model.SsmAssociation;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class SsmJsonHandler {

    private final SsmService ssmService;
    private final SsmCommandService commandService;
    private final ObjectMapper objectMapper;

    @Inject
    public SsmJsonHandler(SsmService ssmService, SsmCommandService commandService, ObjectMapper objectMapper) {
        this.ssmService = ssmService;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            // Parameter Store
            case "PutParameter" -> handlePutParameter(request, region);
            case "GetParameter" -> handleGetParameter(request, region);
            case "GetParameters" -> handleGetParameters(request, region);
            case "GetParametersByPath" -> handleGetParametersByPath(request, region);
            case "DeleteParameter" -> handleDeleteParameter(request, region);
            case "DeleteParameters" -> handleDeleteParameters(request, region);
            case "GetParameterHistory" -> handleGetParameterHistory(request, region);
            case "DescribeParameters" -> handleDescribeParameters(request, region);
            case "LabelParameterVersion" -> handleLabelParameterVersion(request, region);
            case "AddTagsToResource" -> handleAddTagsToResource(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "RemoveTagsFromResource" -> handleRemoveTagsFromResource(request, region);
            // Run Command (public API)
            case "SendCommand" -> handleSendCommand(request, region);
            case "GetCommandInvocation" -> handleGetCommandInvocation(request, region);
            case "ListCommands" -> handleListCommands(request, region);
            case "ListCommandInvocations" -> handleListCommandInvocations(request, region);
            case "CancelCommand" -> handleCancelCommand(request, region);
            case "DescribeInstanceInformation" -> handleDescribeInstanceInformation(request, region);
            // Service settings (LZA ssm-block-public-document-sharing)
            case "GetServiceSetting" -> handleGetServiceSetting(request, region);
            case "UpdateServiceSetting" -> handleUpdateServiceSetting(request, region);
            case "ResetServiceSetting" -> handleResetServiceSetting(request, region);
            // Patch Manager (read-only: AWS-owned predefined baselines)
            case "DescribePatchBaselines" -> handleDescribePatchBaselines(request, region);
            case "GetDefaultPatchBaseline" -> handleGetDefaultPatchBaseline(request, region);
            // Documents
            case "GetDocument" -> handleGetDocument(request, region);
            case "DescribeDocument" -> handleDescribeDocument(request, region);
            case "DeleteDocument" -> handleDeleteDocument(request, region);
            case "CreateDocument" -> handleCreateDocument(request, region);
            case "UpdateDocument" -> handleUpdateDocument(request, region);
            // Document share permissions
            case "ModifyDocumentPermission" -> handleModifyDocumentPermission(request, region);
            case "DescribeDocumentPermission" -> handleDescribeDocumentPermission(request, region);
            case "ListDocuments" -> handleListDocuments(request, region);
            // Associations
            case "CreateAssociation" -> handleCreateAssociation(request, region);
            case "UpdateAssociation" -> handleUpdateAssociation(request, region);
            case "DescribeAssociation" -> handleDescribeAssociation(request, region);
            case "DeleteAssociation" -> handleDeleteAssociation(request, region);
            case "ListAssociations" -> handleListAssociations(request, region);
            // Read-only list operations (resources not modeled: empty results)
            case "DescribeMaintenanceWindows" -> handleDescribeMaintenanceWindows(request, region);
            // Agent registration (internal, not in public SDK)
            case "UpdateInstanceInformation" -> handleUpdateInstanceInformation(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    @RegisterForReflection
    record PutParameterResponse(@JsonProperty("Version") long version) {
    }

    private Response handlePutParameter(JsonNode request, String region) {
        String name = request.path("Name").asText();
        String value = request.path("Value").asText();
        String type = request.path("Type").asText("String");
        String description = request.has("Description") ? request.path("Description").asText() : null;
        boolean overwrite = request.path("Overwrite").asBoolean(false);

        long version = ssmService.putParameter(name, value, type, description, overwrite, region);

        return Response.ok(new PutParameterResponse(version)).build();
    }

    private Response handleGetParameter(JsonNode request, String region) {
        String name = request.path("Name").asText();
        Parameter param = ssmService.getParameter(name, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Parameter", parameterToNode(param));
        return Response.ok(response).build();
    }

    private Response handleGetParameters(JsonNode request, String region) {
        List<String> names = new ArrayList<>();
        request.path("Names").forEach(n -> names.add(n.asText()));

        List<Parameter> params = ssmService.getParameters(names, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode parametersArray = objectMapper.createArrayNode();
        for (Parameter p : params) {
            parametersArray.add(parameterToNode(p));
        }
        response.set("Parameters", parametersArray);
        response.set("InvalidParameters", invalidParameterNames(names,
                params.stream().map(Parameter::getName).toList()));
        return Response.ok(response).build();
    }

    private Response handleGetParametersByPath(JsonNode request, String region) {
        String path = request.path("Path").asText();
        boolean recursive = request.path("Recursive").asBoolean(false);

        List<Parameter> params = ssmService.getParametersByPath(path, recursive, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode parametersArray = objectMapper.createArrayNode();
        for (Parameter p : params) {
            parametersArray.add(parameterToNode(p));
        }
        response.set("Parameters", parametersArray);
        return Response.ok(response).build();
    }

    private Response handleDeleteParameter(JsonNode request, String region) {
        String name = request.path("Name").asText();
        ssmService.deleteParameter(name, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteParameters(JsonNode request, String region) {
        List<String> names = new ArrayList<>();
        request.path("Names").forEach(n -> names.add(n.asText()));

        List<String> deleted = ssmService.deleteParameters(names, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode deletedArray = objectMapper.createArrayNode();
        deleted.forEach(deletedArray::add);
        response.set("DeletedParameters", deletedArray);
        response.set("InvalidParameters", invalidParameterNames(names, deleted));
        return Response.ok(response).build();
    }

    private ArrayNode invalidParameterNames(List<String> requestedNames, List<String> returnedNames) {
        Set<String> returnedNameSet = new HashSet<>(returnedNames);
        ArrayNode invalidNames = objectMapper.createArrayNode();
        requestedNames.stream()
                .filter(name -> !returnedNameSet.contains(name))
                .forEach(invalidNames::add);
        return invalidNames;
    }

    private Response handleGetParameterHistory(JsonNode request, String region) {
        String name = request.path("Name").asText();
        List<ParameterHistory> history = ssmService.getParameterHistory(name, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode historyArray = objectMapper.createArrayNode();
        for (ParameterHistory h : history) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Name", h.getName());
            node.put("Version", h.getVersion());
            node.put("Value", h.getValue());
            node.put("Type", h.getType());
            node.put("LastModifiedDate", h.getLastModifiedDate().toEpochMilli() / 1000.0);
            if (h.getDescription() != null) {
                node.put("Description", h.getDescription());
            }
            if (h.getLabels() != null && !h.getLabels().isEmpty()) {
                ArrayNode labelsArray = objectMapper.createArrayNode();
                h.getLabels().forEach(labelsArray::add);
                node.set("Labels", labelsArray);
            }
            historyArray.add(node);
        }
        response.set("Parameters", historyArray);
        return Response.ok(response).build();
    }

    private Response handleDescribeParameters(JsonNode request, String region) {
        List<String> nameFilters = new ArrayList<>();
        JsonNode filters = request.path("ParameterFilters");
        if (filters.isArray()) {
            for (JsonNode f : filters) {
                String key = f.path("Key").asText("");
                String option = f.path("Option").asText("Equals");
                if ("Name".equals(key) && "Equals".equals(option)) {
                    f.path("Values").forEach(v -> nameFilters.add(v.asText()));
                }
            }
        }
        List<Parameter> params = ssmService.describeParameters(nameFilters, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode parametersArray = objectMapper.createArrayNode();
        for (Parameter p : params) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Name", p.getName());
            node.put("Type", p.getType());
            node.put("Version", p.getVersion());
            node.put("LastModifiedDate", p.getLastModifiedDate().toEpochMilli() / 1000.0);
            if (p.getDescription() != null) {
                node.put("Description", p.getDescription());
            }
            node.put("DataType", p.getDataType());
            parametersArray.add(node);
        }
        response.set("Parameters", parametersArray);
        return Response.ok(response).build();
    }

    private Response handleDescribePatchBaselines(JsonNode request, String region) {
        Map<String, List<String>> filters = new HashMap<>();
        JsonNode filtersNode = request.path("Filters");
        if (filtersNode.isArray()) {
            for (JsonNode f : filtersNode) {
                String key = f.path("Key").asText("");
                List<String> values = new ArrayList<>();
                f.path("Values").forEach(v -> values.add(v.asText()));
                if (!key.isEmpty()) {
                    filters.put(key, values);
                }
            }
        }

        List<PatchBaselineIdentity> baselines = ssmService.describePatchBaselines(filters);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode identities = objectMapper.createArrayNode();
        for (PatchBaselineIdentity b : baselines) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("BaselineId", b.baselineId());
            node.put("BaselineName", b.baselineName());
            node.put("OperatingSystem", b.operatingSystem());
            node.put("BaselineDescription", b.baselineDescription());
            node.put("DefaultBaseline", b.defaultBaseline());
            identities.add(node);
        }
        response.set("BaselineIdentities", identities);
        return Response.ok(response).build();
    }

    private Response handleGetDefaultPatchBaseline(JsonNode request, String region) {
        String operatingSystem = request.has("OperatingSystem") ? request.path("OperatingSystem").asText() : null;
        String baselineId = ssmService.getDefaultPatchBaseline(operatingSystem);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("BaselineId", baselineId);
        response.put("OperatingSystem", (operatingSystem == null || operatingSystem.isBlank()) ? "WINDOWS" : operatingSystem);
        return Response.ok(response).build();
    }

    /**
     * Reads the required {@code Name} member of a document operation.
     *
     * <p>{@code JsonNode.path(...).asText()} yields {@code ""} — not null — for an absent
     * field, so without this guard a request with {@code Name} omitted builds the storage
     * key {@code "<region>|"} and surfaces a not-found error for a blank document name.
     * Botocore constrains {@code DocumentName} to {@code ^[a-zA-Z0-9_\-.]{3,128}$}, so a
     * blank name is a constraint violation, which AWS reports as {@code ValidationException}
     * rather than any per-operation error (none of these operations model one that fits:
     * {@code InvalidDocument} means "does not exist", and {@code CreateDocument} does not
     * model it at all).
     */
    /** Botocore's {@code DocumentName} pattern, verbatim from the model. */
    private static final Pattern DOCUMENT_NAME = Pattern.compile("^[a-zA-Z0-9_\\-.]{3,128}$");

    /**
     * {@code GetDocument} and {@code DescribeDocument} model a wider {@code Name} pattern than
     * the other five document operations: botocore's {@code Name} member on these two allows
     * {@code :} and {@code /}, because {@code DescribeDocument} accepts the document's full ARN
     * when reading a document shared from another account. The other five reject that shape.
     */
    private static final Pattern DOCUMENT_NAME_OR_ARN = Pattern.compile("^[a-zA-Z0-9_\\-.:/]{3,128}$");

    private String requireDocumentName(JsonNode request) {
        return requireDocumentName(request, DOCUMENT_NAME);
    }

    private String requireDocumentNameOrArn(JsonNode request) {
        return requireDocumentName(request, DOCUMENT_NAME_OR_ARN);
    }

    private String requireDocumentName(JsonNode request, Pattern pattern) {
        JsonNode nameNode = request.path("Name");
        String name = nameNode.asText();
        if (!nameNode.isTextual() || !pattern.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + name + "' at 'name' failed to satisfy constraint: "
                            + "Member must satisfy regular expression pattern: " + pattern.pattern(),
                    400);
        }
        return name;
    }

    /**
     * Reads the required {@code PermissionType} member of the two document-permission
     * operations. Botocore models {@code DocumentPermissionType} as an enum with the single
     * value {@code Share} and models {@code InvalidPermissionType} on both operations for
     * anything else. An absent value is a missing required member, which AWS reports as
     * {@code ValidationException} — the same treatment as an absent {@code Name}.
     */
    private void requireSharePermissionType(JsonNode request) {
        String permissionType = request.path("PermissionType").asText();
        if (permissionType == null || permissionType.isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'permissionType' failed to satisfy "
                            + "constraint: Member must not be null",
                    400);
        }
        if (!"Share".equals(permissionType)) {
            throw new AwsException("InvalidPermissionType",
                    "The permission type isn't supported. Share is the only supported permission type.",
                    400);
        }
    }

    /**
     * The {@code DocumentType} enum, verbatim from the model. The value is stored on the document
     * and echoed back by GetDocument, DescribeDocument and the create/update responses, so an
     * unmodelled one is not merely accepted — it becomes the document's type for good.
     */
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "Command", "Policy", "Automation", "Session", "Package",
            "ApplicationConfiguration", "ApplicationConfigurationSchema", "DeploymentStrategy",
            "ChangeCalendar", "Automation.ChangeTemplate", "ProblemAnalysis",
            "ProblemAnalysisTemplate", "CloudFormation", "ConformancePackTemplate",
            "QuickSetup", "ManualApprovalPolicy", "AutoApprovalPolicy");

    /** {@code AccountIds} members are twelve digits or the case-insensitive wildcard {@code all}. */
    private static final Pattern ACCOUNT_ID = Pattern.compile("(?i)all|[0-9]{12}");

    /** {@code AccountIdsToAdd} and {@code AccountIdsToRemove} are both capped at 20 members. */
    private static final int MAX_ACCOUNT_IDS = 20;

    /**
     * Reads the optional {@code DocumentType}, defaulting to AWS's own {@code Command}. Anything
     * outside the enum is a ValidationException rather than a stored document type nothing else
     * in the emulator — or in AWS — will ever recognise.
     */
    private String requireDocumentType(JsonNode request) {
        String documentType = request.path("DocumentType").asText("Command");
        if (!DOCUMENT_TYPES.contains(documentType)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + documentType + "' at 'documentType' failed to "
                            + "satisfy constraint: Member must satisfy enum value set: " + DOCUMENT_TYPES,
                    400);
        }
        return documentType;
    }

    /**
     * Reads one of the two {@code AccountIds} lists on ModifyDocumentPermission. The members are
     * written straight into the document's share list, so an id that is not an account id would
     * be reported back by DescribeDocumentPermission as though the share had happened.
     */
    private List<String> accountIds(JsonNode request, String field) {
        JsonNode fieldNode = request.path(field);
        if (!fieldNode.isMissingNode() && !fieldNode.isNull() && !fieldNode.isArray()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + decapitalize(field) + "' failed to satisfy "
                            + "constraint: Member must be a list",
                    400);
        }
        List<String> accountIds = new ArrayList<>();
        fieldNode.forEach(node -> accountIds.add(node.asText()));
        if (accountIds.size() > MAX_ACCOUNT_IDS) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at '" + decapitalize(field) + "' failed to satisfy "
                            + "constraint: Member must have length less than or equal to " + MAX_ACCOUNT_IDS,
                    400);
        }
        for (String accountId : accountIds) {
            if (!ACCOUNT_ID.matcher(accountId).matches()) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value '" + accountId + "' at '" + decapitalize(field)
                                + "' failed to satisfy constraint: Member must satisfy regular expression "
                                + "pattern: (?i)all|[0-9]{12}",
                        400);
            }
        }
        return accountIds;
    }

    private static String decapitalize(String field) {
        return Character.toLowerCase(field.charAt(0)) + field.substring(1);
    }

    private Response handleGetDocument(JsonNode request, String region) {
        String name = requireDocumentNameOrArn(request);
        SsmDocument document = ssmService.getDocument(name, region);

        String requestedVersion = request.hasNonNull("DocumentVersion") ? request.path("DocumentVersion").asText() : null;
        String effectiveVersion = String.valueOf(document.getDocumentVersion());
        String effectiveContent = document.getContent();

        if (requestedVersion != null && !requestedVersion.isBlank()
                && !"$LATEST".equals(requestedVersion) && !"$DEFAULT".equals(requestedVersion)) {
            if (!document.hasRetainedContent(requestedVersion)) {
                throw new AwsException("InvalidDocumentVersion",
                        "The document version is not valid or does not exist.", 400);
            }
            effectiveVersion = requestedVersion;
            effectiveContent = document.getContentForVersion(requestedVersion);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", document.getName());
        response.put("DocumentType", document.getDocumentType());
        response.put("DocumentVersion", effectiveVersion);
        response.put("Content", effectiveContent);
        response.put("Status", document.getStatus());
        return Response.ok(response).build();
    }

    private Response handleCreateDocument(JsonNode request, String region) {
        String name = requireDocumentName(request);
        String content = requireDocumentContent(request);
        String documentType = requireDocumentType(request);

        SsmDocument document = ssmService.createDocument(name, content, documentType, region);
        return Response.ok(documentDescriptionResponse(document)).build();
    }

    /**
     * Reads the required {@code Content} member of CreateDocument/UpdateDocument. Botocore
     * models {@code DocumentContent} as a string with {@code min: 1}, and both operations
     * require it. {@code JsonNode.path(...).asText()} silently yields {@code ""} for either
     * an absent member or a non-textual one (e.g. a JSON object sent instead of a JSON- or
     * YAML-encoded string), so without this guard a malformed request stores or overwrites a
     * document's content with an empty string instead of failing.
     */
    private String requireDocumentContent(JsonNode request) {
        JsonNode contentNode = request.path("Content");
        if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'content' failed to satisfy "
                            + "constraint: Member must not be null",
                    400);
        }
        return contentNode.asText();
    }

    private Response handleUpdateDocument(JsonNode request, String region) {
        String name = requireDocumentName(request);
        String content = requireDocumentContent(request);

        SsmDocument document = ssmService.updateDocument(name, content, region);
        return Response.ok(documentDescriptionResponse(document)).build();
    }

    private ObjectNode documentDescriptionResponse(SsmDocument document) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode description = objectMapper.createObjectNode();
        description.put("Name", document.getName());
        description.put("DocumentType", document.getDocumentType());
        description.put("DocumentVersion", String.valueOf(document.getDocumentVersion()));
        description.put("Status", document.getStatus());
        description.put("LatestVersion", String.valueOf(document.getDocumentVersion()));
        description.put("DefaultVersion", String.valueOf(document.getDocumentVersion()));
        response.set("DocumentDescription", description);
        return response;
    }

    private Response handleModifyDocumentPermission(JsonNode request, String region) {
        String name = requireDocumentName(request);
        requireSharePermissionType(request);
        List<String> accountIdsToAdd = accountIds(request, "AccountIdsToAdd");
        List<String> accountIdsToRemove = accountIds(request, "AccountIdsToRemove");
        if (accountIdsToAdd.isEmpty() && accountIdsToRemove.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'accountIdsToAdd' failed to satisfy "
                            + "constraint: Member must not be null, or a value must be specified for "
                            + "'accountIdsToRemove'",
                    400);
        }

        ssmService.modifyDocumentPermission(name, accountIdsToAdd, accountIdsToRemove, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeDocumentPermission(JsonNode request, String region) {
        String name = requireDocumentName(request);
        requireSharePermissionType(request);
        List<String> accountIds = ssmService.describeDocumentPermission(name, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ids = objectMapper.createArrayNode();
        accountIds.forEach(ids::add);
        response.set("AccountIds", ids);
        ArrayNode sharingInfo = objectMapper.createArrayNode();
        for (String accountId : accountIds) {
            ObjectNode info = objectMapper.createObjectNode();
            info.put("AccountId", accountId);
            info.put("SharedDocumentVersion", "$DEFAULT");
            sharingInfo.add(info);
        }
        response.set("AccountSharingInfoList", sharingInfo);
        return Response.ok(response).build();
    }

    private Response handleListDocuments(JsonNode request, String region) {
        Map<String, List<String>> filters = new HashMap<>();
        if (request.has("Filters") && request.path("Filters").isArray()) {
            for (JsonNode f : request.path("Filters")) {
                String key = f.path("Key").asText("");
                List<String> values = new ArrayList<>();
                if (f.has("Values") && f.path("Values").isArray()) {
                    f.path("Values").forEach(v -> values.add(v.asText()));
                } else if (f.has("Value")) {
                    values.add(f.path("Value").asText());
                }
                if (!key.isEmpty() && !values.isEmpty()) {
                    filters.computeIfAbsent(key, k -> new ArrayList<>()).addAll(values);
                }
            }
        }
        if (request.has("DocumentFilterList") && request.path("DocumentFilterList").isArray()) {
            for (JsonNode f : request.path("DocumentFilterList")) {
                String key = f.has("key") ? f.path("key").asText("") : f.path("Key").asText("");
                List<String> values = new ArrayList<>();
                if (f.has("Values") && f.path("Values").isArray()) {
                    f.path("Values").forEach(v -> values.add(v.asText()));
                } else if (f.has("value")) {
                    values.add(f.path("value").asText());
                } else if (f.has("Value")) {
                    values.add(f.path("Value").asText());
                }
                if (!key.isEmpty() && !values.isEmpty()) {
                    filters.computeIfAbsent(key, k -> new ArrayList<>()).addAll(values);
                }
            }
        }

        List<SsmDocument> documents = ssmService.listDocuments(region, filters.isEmpty() ? null : filters);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode docArray = objectMapper.createArrayNode();
        for (SsmDocument d : documents) {
            ObjectNode docNode = objectMapper.createObjectNode();
            docNode.put("Name", d.getName());
            if (d.getCreatedDate() != null) {
                docNode.put("CreatedDate", d.getCreatedDate().toEpochMilli() / 1000.0);
            }
            docNode.put("DocumentType", d.getDocumentType());
            docNode.put("DocumentVersion", String.valueOf(d.getDocumentVersion()));
            if (d.getOwner() != null) {
                docNode.put("Owner", d.getOwner());
            }
            if (d.getPlatformTypes() != null) {
                ArrayNode platforms = objectMapper.createArrayNode();
                d.getPlatformTypes().forEach(platforms::add);
                docNode.set("PlatformTypes", platforms);
            }
            if (d.getSchemaVersion() != null) {
                docNode.put("SchemaVersion", d.getSchemaVersion());
            }
            if (d.getDocumentFormat() != null) {
                docNode.put("DocumentFormat", d.getDocumentFormat());
            }
            docArray.add(docNode);
        }
        response.set("DocumentIdentifiers", docArray);
        return Response.ok(response).build();
    }

    private List<SsmAssociation.Target> parseTargets(JsonNode request) {
        if (!request.hasNonNull("Targets") || !request.path("Targets").isArray()) {
            return null;
        }
        List<SsmAssociation.Target> targets = new ArrayList<>();
        for (JsonNode t : request.path("Targets")) {
            String key = t.path("Key").asText();
            List<String> values = new ArrayList<>();
            if (t.has("Values") && t.path("Values").isArray()) {
                t.path("Values").forEach(v -> values.add(v.asText()));
            }
            targets.add(new SsmAssociation.Target(key, values));
        }
        return targets;
    }

    private Map<String, List<String>> parseParameters(JsonNode request) {
        if (!request.hasNonNull("Parameters") || !request.path("Parameters").isObject()) {
            return null;
        }
        Map<String, List<String>> parameters = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = request.path("Parameters").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            List<String> values = new ArrayList<>();
            if (entry.getValue().isArray()) {
                entry.getValue().forEach(v -> values.add(v.asText()));
            } else if (entry.getValue().isTextual()) {
                values.add(entry.getValue().asText());
            }
            parameters.put(entry.getKey(), values);
        }
        return parameters;
    }

    private Response handleCreateAssociation(JsonNode request, String region) {
        JsonNode nameNode = request.path("Name");
        if (!nameNode.isTextual() || nameNode.asText().isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'name' failed to satisfy constraint: Member must not be null",
                    400);
        }
        String name = nameNode.asText();

        String associationName = request.hasNonNull("AssociationName") ? request.path("AssociationName").asText() : null;
        String documentVersion = request.hasNonNull("DocumentVersion") ? request.path("DocumentVersion").asText() : null;
        String instanceId = request.hasNonNull("InstanceId") ? request.path("InstanceId").asText() : null;
        String scheduleExpression = request.hasNonNull("ScheduleExpression") ? request.path("ScheduleExpression").asText() : null;
        String maxErrors = request.hasNonNull("MaxErrors") ? request.path("MaxErrors").asText() : null;
        String maxConcurrency = request.hasNonNull("MaxConcurrency") ? request.path("MaxConcurrency").asText() : null;
        String complianceSeverity = request.hasNonNull("ComplianceSeverity") ? request.path("ComplianceSeverity").asText() : null;

        List<SsmAssociation.Target> targets = parseTargets(request);
        Map<String, List<String>> parameters = parseParameters(request);

        boolean hasInstanceId = instanceId != null && !instanceId.isBlank();
        boolean hasTargets = targets != null && !targets.isEmpty();
        if (!hasInstanceId && !hasTargets) {
            throw new AwsException("ValidationException",
                    "Either InstanceId or Targets must be specified.", 400);
        }

        SsmAssociation assoc = ssmService.createAssociation(
                name, associationName, documentVersion, instanceId, targets, parameters, scheduleExpression,
                maxErrors, maxConcurrency, complianceSeverity, region);

        return Response.ok(associationDescriptionResponse(assoc)).build();
    }

    private Response handleUpdateAssociation(JsonNode request, String region) {
        String associationId = request.hasNonNull("AssociationId") ? request.path("AssociationId").asText() : null;
        if (associationId == null || associationId.isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'associationId' failed to satisfy constraint: Member must not be null",
                    400);
        }

        String associationName = request.hasNonNull("AssociationName") ? request.path("AssociationName").asText() : null;
        String documentVersion = request.hasNonNull("DocumentVersion") ? request.path("DocumentVersion").asText() : null;
        String scheduleExpression = request.hasNonNull("ScheduleExpression") ? request.path("ScheduleExpression").asText() : null;
        String maxErrors = request.hasNonNull("MaxErrors") ? request.path("MaxErrors").asText() : null;
        String maxConcurrency = request.hasNonNull("MaxConcurrency") ? request.path("MaxConcurrency").asText() : null;
        String complianceSeverity = request.hasNonNull("ComplianceSeverity") ? request.path("ComplianceSeverity").asText() : null;
        List<SsmAssociation.Target> targets = parseTargets(request);
        Map<String, List<String>> parameters = parseParameters(request);

        SsmAssociation assoc = ssmService.updateAssociation(
                associationId, associationName, documentVersion, targets, parameters, scheduleExpression,
                maxErrors, maxConcurrency, complianceSeverity, region);

        return Response.ok(associationDescriptionResponse(assoc)).build();
    }

    private Response handleListAssociations(JsonNode request, String region) {
        Map<String, List<String>> filters = new HashMap<>();
        if (request.has("AssociationFilterList") && request.path("AssociationFilterList").isArray()) {
            for (JsonNode f : request.path("AssociationFilterList")) {
                String key = f.has("key") ? f.path("key").asText("") : f.path("Key").asText("");
                List<String> values = new ArrayList<>();
                if (f.has("value")) {
                    values.add(f.path("value").asText());
                } else if (f.has("Value")) {
                    values.add(f.path("Value").asText());
                } else if (f.has("Values") && f.path("Values").isArray()) {
                    f.path("Values").forEach(v -> values.add(v.asText()));
                }
                if (!key.isEmpty() && !values.isEmpty()) {
                    filters.computeIfAbsent(key, k -> new ArrayList<>()).addAll(values);
                }
            }
        }

        List<SsmAssociation> associations = ssmService.listAssociations(region, filters);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode associationsArray = objectMapper.createArrayNode();
        for (SsmAssociation assoc : associations) {
            associationsArray.add(associationSummaryToNode(assoc));
        }
        response.set("Associations", associationsArray);
        return Response.ok(response).build();
    }

    private Response handleDescribeAssociation(JsonNode request, String region) {
        String associationId = request.hasNonNull("AssociationId") ? request.path("AssociationId").asText() : null;
        String name = request.hasNonNull("Name") ? request.path("Name").asText() : null;
        String instanceId = request.hasNonNull("InstanceId") ? request.path("InstanceId").asText() : null;

        boolean hasAssocId = associationId != null && !associationId.isBlank();
        boolean hasNameAndInstance = (name != null && !name.isBlank()) && (instanceId != null && !instanceId.isBlank());
        if (!hasAssocId && !hasNameAndInstance) {
            throw new AwsException("ValidationException",
                    "Either AssociationId or both Name and InstanceId must be specified.", 400);
        }

        SsmAssociation assoc = ssmService.describeAssociation(associationId, name, instanceId, region);
        return Response.ok(associationDescriptionResponse(assoc)).build();
    }

    private Response handleDeleteAssociation(JsonNode request, String region) {
        String associationId = request.hasNonNull("AssociationId") ? request.path("AssociationId").asText() : null;
        String name = request.hasNonNull("Name") ? request.path("Name").asText() : null;
        String instanceId = request.hasNonNull("InstanceId") ? request.path("InstanceId").asText() : null;

        boolean hasAssocId = associationId != null && !associationId.isBlank();
        boolean hasNameAndInstance = (name != null && !name.isBlank()) && (instanceId != null && !instanceId.isBlank());
        if (!hasAssocId && !hasNameAndInstance) {
            throw new AwsException("ValidationException",
                    "Either AssociationId or both Name and InstanceId must be specified.", 400);
        }

        ssmService.deleteAssociation(associationId, name, instanceId, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ArrayNode targetsToArray(List<SsmAssociation.Target> targets) {
        ArrayNode targetsArray = objectMapper.createArrayNode();
        for (SsmAssociation.Target t : targets) {
            ObjectNode targetNode = objectMapper.createObjectNode();
            targetNode.put("Key", t.getKey());
            ArrayNode valuesArray = objectMapper.createArrayNode();
            if (t.getValues() != null) {
                t.getValues().forEach(valuesArray::add);
            }
            targetNode.set("Values", valuesArray);
            targetsArray.add(targetNode);
        }
        return targetsArray;
    }

    private ObjectNode associationSummaryToNode(SsmAssociation assoc) {
        ObjectNode node = objectMapper.createObjectNode();
        if (assoc.getAssociationId() != null) {
            node.put("AssociationId", assoc.getAssociationId());
        }
        if (assoc.getName() != null) {
            node.put("Name", assoc.getName());
        }
        if (assoc.getAssociationName() != null) {
            node.put("AssociationName", assoc.getAssociationName());
        }
        if (assoc.getAssociationVersion() != null) {
            node.put("AssociationVersion", assoc.getAssociationVersion());
        }
        if (assoc.getDocumentVersion() != null) {
            node.put("DocumentVersion", assoc.getDocumentVersion());
        }
        if (assoc.getInstanceId() != null) {
            node.put("InstanceId", assoc.getInstanceId());
        }
        if (assoc.getTargets() != null) {
            node.set("Targets", targetsToArray(assoc.getTargets()));
        }
        if (assoc.getOverview() != null) {
            ObjectNode overviewNode = objectMapper.createObjectNode();
            overviewNode.put("Status", assoc.getOverview().getStatus());
            overviewNode.put("DetailedStatus", assoc.getOverview().getDetailedStatus());
            node.set("Overview", overviewNode);
        }
        if (assoc.getScheduleExpression() != null) {
            node.put("ScheduleExpression", assoc.getScheduleExpression());
        }
        if (assoc.getLastExecutionDate() != null) {
            node.put("LastExecutionDate", assoc.getLastExecutionDate().toEpochMilli() / 1000.0);
        }
        return node;
    }

    private ObjectNode associationDescriptionResponse(SsmAssociation assoc) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode desc = objectMapper.createObjectNode();

        if (assoc.getAssociationId() != null) {
            desc.put("AssociationId", assoc.getAssociationId());
        }
        if (assoc.getName() != null) {
            desc.put("Name", assoc.getName());
        }
        if (assoc.getAssociationName() != null) {
            desc.put("AssociationName", assoc.getAssociationName());
        }
        if (assoc.getDocumentVersion() != null) {
            desc.put("DocumentVersion", assoc.getDocumentVersion());
        }
        if (assoc.getInstanceId() != null) {
            desc.put("InstanceId", assoc.getInstanceId());
        }
        if (assoc.getAssociationVersion() != null) {
            desc.put("AssociationVersion", assoc.getAssociationVersion());
        }
        if (assoc.getTargets() != null) {
            desc.set("Targets", targetsToArray(assoc.getTargets()));
        }
        if (assoc.getParameters() != null) {
            ObjectNode paramsNode = objectMapper.createObjectNode();
            for (Map.Entry<String, List<String>> entry : assoc.getParameters().entrySet()) {
                ArrayNode valuesArray = objectMapper.createArrayNode();
                if (entry.getValue() != null) {
                    entry.getValue().forEach(valuesArray::add);
                }
                paramsNode.set(entry.getKey(), valuesArray);
            }
            desc.set("Parameters", paramsNode);
        }
        if (assoc.getScheduleExpression() != null) {
            desc.put("ScheduleExpression", assoc.getScheduleExpression());
        }
        if (assoc.getStatus() != null) {
            ObjectNode statusNode = objectMapper.createObjectNode();
            statusNode.put("Name", assoc.getStatus().getName());
            if (assoc.getStatus().getMessage() != null) {
                statusNode.put("Message", assoc.getStatus().getMessage());
            }
            if (assoc.getStatus().getDate() != null) {
                statusNode.put("Date", assoc.getStatus().getDate().toEpochMilli() / 1000.0);
            }
            if (assoc.getStatus().getAdditionalInfo() != null) {
                statusNode.put("AdditionalInfo", assoc.getStatus().getAdditionalInfo());
            }
            desc.set("Status", statusNode);
        }
        if (assoc.getOverview() != null) {
            ObjectNode overviewNode = objectMapper.createObjectNode();
            overviewNode.put("Status", assoc.getOverview().getStatus());
            overviewNode.put("DetailedStatus", assoc.getOverview().getDetailedStatus());
            desc.set("Overview", overviewNode);
        }
        if (assoc.getCreatedDate() != null) {
            desc.put("Date", assoc.getCreatedDate().toEpochMilli() / 1000.0);
        }
        if (assoc.getLastExecutionDate() != null) {
            desc.put("LastExecutionDate", assoc.getLastExecutionDate().toEpochMilli() / 1000.0);
        }
        if (assoc.getComplianceSeverity() != null) {
            desc.put("ComplianceSeverity", assoc.getComplianceSeverity());
        }
        if (assoc.getMaxConcurrency() != null) {
            desc.put("MaxConcurrency", assoc.getMaxConcurrency());
        }
        if (assoc.getMaxErrors() != null) {
            desc.put("MaxErrors", assoc.getMaxErrors());
        }

        response.set("AssociationDescription", desc);
        return response;
    }

    private Response handleDescribeMaintenanceWindows(JsonNode request, String region) {
        // Maintenance windows are not modeled: return an empty list.
        ObjectNode response = objectMapper.createObjectNode();
        response.set("WindowIdentities", objectMapper.createArrayNode());
        return Response.ok(response).build();
    }

    private Response handleLabelParameterVersion(JsonNode request, String region) {
        String name = request.path("Name").asText();
        long parameterVersion = request.path("ParameterVersion").asLong();
        List<String> labels = new ArrayList<>();
        request.path("Labels").forEach(l -> labels.add(l.asText()));

        ssmService.labelParameterVersion(name, parameterVersion, labels, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("InvalidLabels", objectMapper.createArrayNode());
        response.put("ParameterVersion", parameterVersion);
        return Response.ok(response).build();
    }

    private Response handleAddTagsToResource(JsonNode request, String region) {
        String resourceId = request.path("ResourceId").asText();
        Map<String, String> tags = new HashMap<>();
        request.path("Tags").forEach(t ->
                tags.put(t.path("Key").asText(), t.path("Value").asText()));

        ssmService.addTagsToResource(resourceId, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String resourceId = request.path("ResourceId").asText();
        Map<String, String> tags = ssmService.listTagsForResource(resourceId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagsArray = objectMapper.createArrayNode();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", entry.getKey());
            tagNode.put("Value", entry.getValue());
            tagsArray.add(tagNode);
        }
        response.set("TagList", tagsArray);
        return Response.ok(response).build();
    }

    private Response handleRemoveTagsFromResource(JsonNode request, String region) {
        String resourceId = request.path("ResourceId").asText();
        List<String> tagKeys = new ArrayList<>();
        request.path("TagKeys").forEach(k -> tagKeys.add(k.asText()));

        ssmService.removeTagsFromResource(resourceId, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode parameterToNode(Parameter p) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", p.getName());
        node.put("Value", p.getValue());
        node.put("Type", p.getType());
        node.put("Version", p.getVersion());
        node.put("LastModifiedDate", p.getLastModifiedDate().toEpochMilli() / 1000.0);
        node.put("ARN", p.getArn());
        node.put("DataType", p.getDataType());
        return node;
    }

    // ── Service settings ───────────────────────────────────────────────────

    /**
     * Reads the required {@code SettingId} member shared by Get/Update/ResetServiceSetting.
     * Botocore requires it on all three operations; an absent or blank value is a missing
     * required member (ValidationException), not an unknown setting (ServiceSettingNotFound) —
     * the same distinction the document operations draw between a missing {@code Name} and one
     * that does not resolve.
     */
    private String requireSettingId(JsonNode request) {
        String settingId = request.path("SettingId").asText();
        if (settingId == null || settingId.isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'settingId' failed to satisfy "
                            + "constraint: Member must not be null",
                    400);
        }
        return settingId;
    }

    /**
     * Reads the required {@code SettingValue} member of UpdateServiceSetting. Botocore models
     * {@code ServiceSettingValue} as a string with {@code min: 1, max: 4096}; an absent or blank
     * value silently stores an empty customized setting rather than failing, and an oversized one
     * would sit in the store forever since GetServiceSetting just echoes it back.
     */
    private static final int MAX_SETTING_VALUE_LENGTH = 4096;

    private String requireSettingValue(JsonNode request) {
        JsonNode valueNode = request.path("SettingValue");
        String settingValue = valueNode.asText();
        if (!valueNode.isTextual() || settingValue.isEmpty()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'settingValue' failed to satisfy "
                            + "constraint: Member must not be null",
                    400);
        }
        if (settingValue.length() > MAX_SETTING_VALUE_LENGTH) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'settingValue' failed to satisfy "
                            + "constraint: Member must have length less than or equal to "
                            + MAX_SETTING_VALUE_LENGTH,
                    400);
        }
        return settingValue;
    }

    private Response handleGetServiceSetting(JsonNode request, String region) {
        String settingId = requireSettingId(request);
        ServiceSetting setting = ssmService.getServiceSetting(settingId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("ServiceSetting", serviceSettingToNode(setting));
        return Response.ok(response).build();
    }

    private Response handleUpdateServiceSetting(JsonNode request, String region) {
        String settingId = requireSettingId(request);
        String settingValue = requireSettingValue(request);
        ssmService.updateServiceSetting(settingId, settingValue, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleResetServiceSetting(JsonNode request, String region) {
        String settingId = requireSettingId(request);
        ServiceSetting setting = ssmService.resetServiceSetting(settingId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("ServiceSetting", serviceSettingToNode(setting));
        return Response.ok(response).build();
    }

    private ObjectNode serviceSettingToNode(ServiceSetting s) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SettingId", s.getSettingId());
        node.put("SettingValue", s.getSettingValue());
        node.put("LastModifiedDate", s.getLastModifiedDate().toEpochMilli() / 1000.0);
        node.put("LastModifiedUser", s.getLastModifiedUser());
        node.put("ARN", s.getArn());
        node.put("Status", s.getStatus());
        return node;
    }

    // ── Agent registration ─────────────────────────────────────────────────

    private Response handleUpdateInstanceInformation(JsonNode request, String region) {
        commandService.updateInstanceInformation(request, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ── Run Command public API ─────────────────────────────────────────────

    private Response handleSendCommand(JsonNode request, String region) {
        Command command = commandService.sendCommand(request, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Command", commandToNode(command));
        return Response.ok(response).build();
    }

    private Response handleGetCommandInvocation(JsonNode request, String region) {
        String commandId = request.path("CommandId").asText();
        String instanceId = request.path("InstanceId").asText();
        CommandInvocation inv = commandService.getCommandInvocation(commandId, instanceId, region);
        return Response.ok(invocationToDetailNode(inv)).build();
    }

    private Response handleListCommands(JsonNode request, String region) {
        String commandId = request.has("CommandId") ? request.path("CommandId").asText() : null;
        String instanceId = request.has("InstanceId") ? request.path("InstanceId").asText() : null;
        List<Command> commands = commandService.listCommands(commandId, instanceId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode commandsArray = objectMapper.createArrayNode();
        for (Command c : commands) {
            commandsArray.add(commandToNode(c));
        }
        response.set("Commands", commandsArray);
        return Response.ok(response).build();
    }

    private Response handleListCommandInvocations(JsonNode request, String region) {
        String commandId = request.has("CommandId") ? request.path("CommandId").asText() : null;
        String instanceId = request.has("InstanceId") ? request.path("InstanceId").asText() : null;
        List<CommandInvocation> invocations = commandService.listCommandInvocations(commandId, instanceId, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode invArray = objectMapper.createArrayNode();
        for (CommandInvocation inv : invocations) {
            invArray.add(invocationToNode(inv));
        }
        response.set("CommandInvocations", invArray);
        return Response.ok(response).build();
    }

    private Response handleCancelCommand(JsonNode request, String region) {
        String commandId = request.path("CommandId").asText();
        List<String> instanceIds = new ArrayList<>();
        request.path("InstanceIds").forEach(n -> instanceIds.add(n.asText()));
        commandService.cancelCommand(commandId, instanceIds, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeInstanceInformation(JsonNode request, String region) {
        List<InstanceInformation> instances = commandService.describeInstanceInformation(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = objectMapper.createArrayNode();
        for (InstanceInformation info : instances) {
            list.add(instanceInfoToNode(info));
        }
        response.set("InstanceInformationList", list);
        return Response.ok(response).build();
    }

    // ── Serialisation helpers ──────────────────────────────────────────────

    private ObjectNode commandToNode(Command c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CommandId", c.getCommandId());
        node.put("DocumentName", c.getDocumentName());
        if (c.getDocumentVersion() != null) node.put("DocumentVersion", c.getDocumentVersion());
        if (c.getComment() != null) node.put("Comment", c.getComment());
        if (c.getRequestedDateTime() != null) node.put("RequestedDateTime", c.getRequestedDateTime().toEpochMilli() / 1000.0);
        if (c.getExpiresAfter() != null) node.put("ExpiresAfter", c.getExpiresAfter().toEpochMilli() / 1000.0);
        // c is the same live, shared Command object SsmCommandService's writers (updateCommandStatus,
        // cancelCommand) synchronize on before touching Status/StatusDetails together. Reading the
        // pair here without the same lock would let this read straddle a concurrent writer's update -
        // one field read before it, the other after - producing a Status/StatusDetails combination
        // that was never actually true at any single instant.
        synchronized (c) {
            node.put("Status", c.getStatus());
            node.put("StatusDetails", c.getStatusDetails());
        }
        node.put("TargetCount", c.getTargetCount());
        node.put("CompletedCount", c.getCompletedCount());
        node.put("ErrorCount", c.getErrorCount());
        node.put("TimeoutSeconds", c.getTimeoutSeconds());
        if (c.getInstanceIds() != null) {
            ArrayNode ids = objectMapper.createArrayNode();
            c.getInstanceIds().forEach(ids::add);
            node.set("InstanceIds", ids);
        }
        if (c.getParameters() != null) {
            ObjectNode params = objectMapper.createObjectNode();
            c.getParameters().forEach((k, v) -> {
                ArrayNode arr = objectMapper.createArrayNode();
                v.forEach(arr::add);
                params.set(k, arr);
            });
            node.set("Parameters", params);
        }
        return node;
    }

    private ObjectNode invocationToNode(CommandInvocation inv) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CommandId", inv.getCommandId());
        node.put("InstanceId", inv.getInstanceId());
        if (inv.getComment() != null) node.put("Comment", inv.getComment());
        node.put("DocumentName", inv.getDocumentName());
        if (inv.getDocumentVersion() != null) node.put("DocumentVersion", inv.getDocumentVersion());
        if (inv.getRequestedDateTime() != null) node.put("RequestedDateTime", inv.getRequestedDateTime().toEpochMilli() / 1000.0);
        // Same reasoning as commandToNode: inv is the same live, shared CommandInvocation object its
        // writers (cancelCommand, direct/agent completion, timeout sweeps) now synchronize on.
        synchronized (inv) {
            node.put("Status", inv.getStatus());
            node.put("StatusDetails", inv.getStatusDetails());
        }
        return node;
    }

    private ObjectNode invocationToDetailNode(CommandInvocation inv) {
        ObjectNode node = invocationToNode(inv);
        node.put("StandardOutputContent", inv.getStandardOutputContent() != null ? inv.getStandardOutputContent() : "");
        node.put("StandardErrorContent", inv.getStandardErrorContent() != null ? inv.getStandardErrorContent() : "");
        node.put("ResponseCode", inv.getResponseCode());
        if (inv.getExecutionStartDateTime() != null) {
            node.put("ExecutionStartDateTime", inv.getExecutionStartDateTime().toString());
        }
        if (inv.getExecutionEndDateTime() != null) {
            node.put("ExecutionEndDateTime", inv.getExecutionEndDateTime().toString());
        }
        return node;
    }

    private ObjectNode instanceInfoToNode(InstanceInformation info) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("InstanceId", info.getInstanceId());
        node.put("PingStatus", info.getPingStatus());
        node.put("AgentVersion", info.getAgentVersion());
        if (info.getPlatformType() != null) node.put("PlatformType", info.getPlatformType());
        if (info.getPlatformName() != null) node.put("PlatformName", info.getPlatformName());
        if (info.getPlatformVersion() != null) node.put("PlatformVersion", info.getPlatformVersion());
        if (info.getIpAddress() != null) node.put("IPAddress", info.getIpAddress());
        if (info.getComputerName() != null) node.put("ComputerName", info.getComputerName());
        node.put("ResourceType", info.getResourceType());
        if (info.getLastPingDateTime() != null) node.put("LastPingDateTime", info.getLastPingDateTime().toEpochMilli() / 1000.0);
        if (info.getRegistrationDate() != null) node.put("RegistrationDate", info.getRegistrationDate().toEpochMilli() / 1000.0);
        return node;
    }

    private Response handleDescribeDocument(JsonNode request, String region) {
        String name = requireDocumentNameOrArn(request);
        SsmDocument document = ssmService.getDocument(name, region);

        String requestedVersion = request.hasNonNull("DocumentVersion") ? request.path("DocumentVersion").asText() : null;
        String effectiveVersion = String.valueOf(document.getDocumentVersion());
        String effectiveContent = document.getContent();

        if (requestedVersion != null && !requestedVersion.isBlank()
                && !"$LATEST".equals(requestedVersion) && !"$DEFAULT".equals(requestedVersion)) {
            if (!document.hasRetainedContent(requestedVersion)) {
                throw new AwsException("InvalidDocumentVersion",
                        "The document version is not valid or does not exist.", 400);
            }
            effectiveVersion = requestedVersion;
            effectiveContent = document.getContentForVersion(requestedVersion);
        }

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode documentNode = objectMapper.createObjectNode();
        documentNode.put("Name", document.getName());
        documentNode.put("DocumentType", document.getDocumentType());
        documentNode.put("DocumentVersion", effectiveVersion);
        documentNode.put("Content", effectiveContent);
        documentNode.put("Status", document.getStatus());
        if (document.getCreatedDate() != null) {
            documentNode.put("CreatedDate", document.getCreatedDate().toString());
        }
        response.set("Document", documentNode);
        return Response.ok(response).build();
    }

    private Response handleDeleteDocument(JsonNode request, String region) {
        String name = requireDocumentName(request);
        ssmService.deleteDocument(name, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
