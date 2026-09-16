package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.marketplace.model.MarketplaceChangeSetRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class MarketplaceService implements Resettable {
    private static final String CATALOG = "AWSMarketplace";
    private static final String REGION = "us-east-1";
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[!-~]{1,64}");
    private static final Pattern CHANGE_SET_NAME = Pattern.compile("[\\w\\s+=.:@-]{1,100}");
    private static final Set<String> SUPPORTED_CHANGE_TYPES = Set.of(
            "AddDeliveryOptions", "AddDimension", "AddDimensions", "AddInstanceTypes", "AddRegions",
            "AddRepositories", "AddRevisions", "AllowProductProcurement", "AssociateAudience",
            "AssociateOffers", "AssociateSolution", "CreateBrandingSettings", "CreateExperience",
            "CreateOffer", "CreateOfferSet", "CreateOfferUsingResaleAuthorization", "CreateProcurementPolicy",
            "CreateProduct", "CreateReplacementOffer", "CreateReplacementOfferUsingResaleAuthorization",
            "CreateResaleAuthorization", "CreateSolution", "DenyProductProcurement", "DisassociateAudience",
            "DisassociateOffers", "DisassociateSolution", "ReleaseOffer", "ReleaseOfferSet", "ReleaseProduct",
            "ReleaseResaleAuthorization", "ReleaseSolution", "RestrictDeliveryOptions", "RestrictDimension",
            "RestrictExperience", "RestrictInstanceTypes", "RestrictRegions", "RestrictResaleAuthorization",
            "ReviveExperience", "UpdateAvailability", "UpdateBrandingSettings", "UpdateBuyerEngagementOptions",
            "UpdateBuyerTargetingTerms", "UpdateBuyerValidityTerms", "UpdateCustomerVerificationTerms",
            "UpdateDeliveryOptions", "UpdateDeliveryOptionsVisibility", "UpdateDimension", "UpdateExperience",
            "UpdateFutureRegionSupport", "UpdateInformation", "UpdateLegalTerms", "UpdateMarkup",
            "UpdatePaymentScheduleTerms", "UpdatePricingTerms", "UpdateProcurementPolicy", "UpdateRelatedProducts",
            "UpdateRenewalTerms", "UpdateSupportTerms", "UpdateTargeting", "UpdateValidityTerms", "UpdateVisibility");
    private static final Logger LOG = Logger.getLogger(MarketplaceService.class);

    private final ObjectMapper mapper;
    private final AccountAwareStorageBackend<JsonNode> entities;
    private final AccountAwareStorageBackend<JsonNode> changeSets;
    private final AccountAwareStorageBackend<JsonNode> resourcePolicies;
    private final AccountAwareStorageBackend<JsonNode> tags;
    private final AccountAwareStorageBackend<JsonNode> assessments;
    private final RequestContext requestContext;

    @Inject
    public MarketplaceService(StorageFactory factory, ObjectMapper mapper, RequestContext requestContext) {
        this(mapper,
                factory.create("marketplace", "marketplace-entities.json", type()),
                factory.create("marketplace", "marketplace-change-sets.json", type()),
                factory.create("marketplace", "marketplace-resource-policies.json", type()),
                factory.create("marketplace", "marketplace-tags.json", type()),
                factory.create("marketplace", "marketplace-assessments.json", type()),
                requestContext);
    }

    MarketplaceService(ObjectMapper mapper,
                       AccountAwareStorageBackend<JsonNode> entities,
                       AccountAwareStorageBackend<JsonNode> changeSets,
                       AccountAwareStorageBackend<JsonNode> resourcePolicies,
                       AccountAwareStorageBackend<JsonNode> tags,
                       AccountAwareStorageBackend<JsonNode> assessments,
                       RequestContext requestContext) {
        this.mapper = mapper;
        this.entities = entities;
        this.changeSets = changeSets;
        this.resourcePolicies = resourcePolicies;
        this.tags = tags;
        this.assessments = assessments;
        this.requestContext = requestContext;
    }

    private static TypeReference<Map<String, JsonNode>> type() {
        return new TypeReference<>() {};
    }

    public ObjectNode batchDescribeEntities(JsonNode request, String region) {
        validateRegion(region);
        ArrayNode inputs = requireArray(request, "EntityRequestList");
        if (inputs.isEmpty() || inputs.size() > 20) {
            throw validation("EntityRequestList must contain between 1 and 20 items.");
        }
        completePending();
        ObjectNode out = mapper.createObjectNode();
        ObjectNode details = out.putObject("EntityDetails");
        ObjectNode errors = out.putObject("Errors");
        for (JsonNode input : inputs) {
            String catalog = text(input, "Catalog", true);
            String id = text(input, "EntityId", true);
            if (!CATALOG.equals(catalog)) {
                errors.set(id, errorDetail("ValidationException", "Catalog must be AWSMarketplace."));
                continue;
            }
            JsonNode entity = entities.get(id).orElse(null);
            if (entity == null) {
                errors.set(id, errorDetail("ResourceNotFoundException", "Entity not found."));
            } else {
                details.set(id, entity.deepCopy());
            }
        }
        return out;
    }

    public ObjectNode startChangeSet(JsonNode request, String region) {
        validateRegion(region);
        requireCatalog(text(request, "Catalog", true));
        ArrayNode changes = requireArray(request, "ChangeSet");
        if (changes.isEmpty() || changes.size() > 20) {
            throw validation("ChangeSet must contain between 1 and 20 changes.");
        }
        String token = text(request, "ClientRequestToken", false);
        if (token != null && !CLIENT_TOKEN.matcher(token).matches()) {
            throw validation("ClientRequestToken must contain 1 to 64 visible ASCII characters.");
        }
        String name = text(request, "ChangeSetName", false);
        if (name != null && !CHANGE_SET_NAME.matcher(name).matches()) {
            throw validation("ChangeSetName is invalid.");
        }
        String intent = request.path("Intent").asText("APPLY");
        if (!Set.of("APPLY", "VALIDATE").contains(intent)) {
            throw validation("Intent must be APPLY or VALIDATE.");
        }

        JsonNode idempotencyPayload = request.deepCopy();
        MarketplaceChangeSetRequest retry = new MarketplaceChangeSetRequest(token, idempotencyPayload);
        if (token != null) {
            for (JsonNode existing : changeSets.scan(key -> true)) {
                if (!token.equals(existing.path("ClientRequestToken").asText(null))) {
                    continue;
                }
                if (!retry.matches(existing.path("_IdempotencyRequest"))) {
                    throw validation("ClientRequestToken was already used with different parameters.");
                }
                return ids(existing);
            }
        }

        ArrayNode normalized = normalizeChanges(changes, region);
        validateNoDuplicateChanges(normalized);
        String id = "cs-" + compactId();
        String arn = arn(region, "AWSMarketplace/ChangeSet/" + id);
        ObjectNode cs = mapper.createObjectNode();
        cs.put("ChangeSetId", id);
        cs.put("ChangeSetArn", arn);
        if (name != null) {
            cs.put("ChangeSetName", name);
        }
        cs.put("Intent", intent);
        cs.put("StartTime", Instant.now().toString());
        cs.put("Status", "PREPARING");
        cs.put("_Region", region);
        cs.set("ChangeSet", normalized);
        if (token != null) {
            cs.put("ClientRequestToken", token);
            cs.set("_IdempotencyRequest", idempotencyPayload);
        }
        changeSets.put(id, cs);
        if (request.path("ChangeSetTags").isArray()) {
            tags.put(arn, request.path("ChangeSetTags").deepCopy());
        }
        return ids(cs);
    }

    public ObjectNode cancelChangeSet(String catalog, String id, String region) {
        validateRegion(region);
        requireCatalog(catalog);
        ObjectNode cs = changeSet(id);
        ensureStoredRegion(cs, region);
        String status = cs.path("Status").asText();
        if (!"PREPARING".equals(status) && !"APPLYING".equals(status)) {
            throw new AwsException("ResourceInUseException",
                    "Change set cannot be cancelled in status " + status + ".", 423);
        }
        cs.put("Status", "CANCELLED");
        cs.put("EndTime", Instant.now().toString());
        changeSets.put(id, cs);
        return ids(cs);
    }

    public ObjectNode describeChangeSet(String catalog, String id, String region) {
        validateRegion(region);
        requireCatalog(catalog);
        ObjectNode cs = changeSet(id);
        ensureStoredRegion(cs, region);
        if ("PREPARING".equals(cs.path("Status").asText())) {
            applyChangeSet(cs);
            changeSets.put(id, cs);
        }
        return publicChangeSet(cs);
    }

    public ObjectNode listChangeSets(JsonNode request, String region) {
        validateRegion(region);
        requireCatalog(text(request, "Catalog", true));
        completePending();
        List<JsonNode> all = new ArrayList<>(changeSets.scan(key -> true));
        all.removeIf(cs -> !region.equals(cs.path("_Region").asText(REGION)));
        all.removeIf(cs -> !matchesChangeSetFilters(cs, request.get("FilterList")));
        sortChangeSets(all, request.get("Sort"));
        return page("ChangeSetSummaryList", all.stream().map(this::changeSummary).toList(), request, 20, 1, 20);
    }

    public ObjectNode describeEntity(String catalog, String id, String region) {
        validateRegion(region);
        requireCatalog(catalog);
        completePending();
        JsonNode entity = entities.get(id).orElseThrow(() -> notFound("Entity", id));
        return (ObjectNode) entity.deepCopy();
    }

    public ObjectNode listEntities(JsonNode request, String region) {
        validateRegion(region);
        requireCatalog(text(request, "Catalog", true));
        String type = text(request, "EntityType", true);
        String ownership = request.path("OwnershipType").asText("SELF");
        if (!Set.of("SELF", "SHARED").contains(ownership)) {
            throw validation("OwnershipType must be SELF or SHARED.");
        }
        completePending();
        if ("SHARED".equals(ownership) || requestsSharedScope(request.get("FilterList"))) {
            return page("EntitySummaryList", List.of(), request, 20, 1, 50);
        }
        if (request.hasNonNull("EntityTypeFilters")) {
            throw validation("EntityTypeFilters are not supported by this local catalog implementation.");
        }
        if (request.hasNonNull("EntityTypeSort")) {
            throw validation("EntityTypeSort is not supported by this local catalog implementation.");
        }
        List<JsonNode> list = new ArrayList<>(entities.scan(key -> true));
        list.removeIf(entity -> !type.equals(baseEntityType(entity.path("EntityType").asText())));
        list.removeIf(entity -> !matchesEntityFilters(entity, request.get("FilterList")));
        sortEntities(list, request.get("Sort"));
        return page("EntitySummaryList", list.stream().map(this::entitySummary).toList(), request, 20, 1, 50);
    }

    public ObjectNode catalogListTags(JsonNode request, String region) {
        validateRegion(region);
        String arn = text(request, "ResourceArn", true);
        ensureResource(arn);
        ObjectNode out = mapper.createObjectNode();
        out.put("ResourceArn", arn);
        out.set("Tags", tags.get(arn).map(node -> (JsonNode) node.deepCopy()).orElseGet(mapper::createArrayNode));
        return out;
    }

    public ObjectNode catalogTagResource(JsonNode request, String region) {
        validateRegion(region);
        String arn = text(request, "ResourceArn", true);
        ensureResource(arn);
        ArrayNode incoming = requireArray(request, "Tags");
        Map<String, String> merged = new LinkedHashMap<>();
        tags.get(arn).ifPresent(old -> old.forEach(tag ->
                merged.put(tag.path("Key").asText(), tag.path("Value").asText())));
        for (JsonNode tag : incoming) {
            merged.put(text(tag, "Key", true), text(tag, "Value", true));
        }
        if (merged.size() > 50) {
            throw validation("A resource can have at most 50 tags.");
        }
        ArrayNode out = mapper.createArrayNode();
        merged.forEach((key, value) -> out.addObject().put("Key", key).put("Value", value));
        tags.put(arn, out);
        return mapper.createObjectNode();
    }

    public ObjectNode catalogUntagResource(JsonNode request, String region) {
        validateRegion(region);
        String arn = text(request, "ResourceArn", true);
        ensureResource(arn);
        ArrayNode keys = requireArray(request, "TagKeys");
        Set<String> remove = new HashSet<>();
        keys.forEach(key -> remove.add(key.asText()));
        ArrayNode out = mapper.createArrayNode();
        tags.get(arn).ifPresent(old -> old.forEach(tag -> {
            if (!remove.contains(tag.path("Key").asText())) {
                out.add(tag.deepCopy());
            }
        }));
        tags.put(arn, out);
        return mapper.createObjectNode();
    }

    public ObjectNode putResourcePolicy(JsonNode request, String region) {
        validateRegion(region);
        String arn = text(request, "ResourceArn", true);
        ensureResource(arn);
        String policy = text(request, "Policy", true);
        try {
            mapper.readTree(policy);
        } catch (Exception e) {
            throw validation("Policy must be valid JSON.");
        }
        resourcePolicies.put(arn, mapper.getNodeFactory().textNode(policy));
        return mapper.createObjectNode();
    }

    public ObjectNode getResourcePolicy(String arn, String region) {
        validateRegion(region);
        if (arn == null || arn.isBlank()) {
            throw validation("ResourceArn is required.");
        }
        ensureResource(arn);
        JsonNode policy = resourcePolicies.get(arn).orElseThrow(() -> notFound("Resource policy", arn));
        return mapper.createObjectNode().put("Policy", policy.asText());
    }

    public ObjectNode deleteResourcePolicy(String arn, String region) {
        validateRegion(region);
        if (arn == null || arn.isBlank()) {
            throw validation("ResourceArn is required.");
        }
        ensureResource(arn);
        if (resourcePolicies.get(arn).isEmpty()) {
            throw notFound("Resource policy", arn);
        }
        resourcePolicies.delete(arn);
        return mapper.createObjectNode();
    }

    public ObjectNode listAssessments(JsonNode request, String region) {
        validateRegion(region);
        requireCatalog(text(request, "Catalog", true));
        List<JsonNode> list = new ArrayList<>(assessments.scan(key -> true));
        String frameworkId = text(request, "FrameworkId", false);
        if (frameworkId != null) {
            list.removeIf(value -> !frameworkId.equals(value.path("FrameworkId").asText()));
        }
        JsonNode target = request.get("AssessmentTargetFilter");
        if (target != null && !target.isNull()) {
            if (!target.isObject()) {
                throw validation("AssessmentTargetFilter must be an object.");
            }
            String entityId = text(target, "EntityId", false);
            String changeSetId = text(target, "ChangeSetId", false);
            if (entityId != null && changeSetId != null) {
                throw validation("AssessmentTargetFilter can specify only one target.");
            }
            if (entityId != null) {
                list.removeIf(value -> !entityId.equals(value.path("EntityId").asText()));
            }
            if (changeSetId != null) {
                list.removeIf(value -> !changeSetId.equals(value.path("ChangeSetId").asText()));
            }
        }
        if (request.hasNonNull("FrameworkFilters")) {
            JsonNode filters = request.get("FrameworkFilters");
            if (!filters.isObject() || filters.size() != 1) {
                throw validation("FrameworkFilters must contain exactly one framework filter.");
            }
        }
        list.sort(Comparator.comparing(value -> value.path("CreatedAt").asText(), Comparator.reverseOrder()));
        return page("AssessmentSummaryList", list.stream().map(this::assessmentSummary).toList(), request, 20, 1, 100);
    }

    public ObjectNode describeAssessment(JsonNode request, String region) {
        validateRegion(region);
        requireCatalog(text(request, "Catalog", true));
        String id = text(request, "AssessmentIdentifier", true);
        JsonNode assessment = assessments.get(id).orElse(null);
        if (assessment == null && id.contains("/")) {
            assessment = assessments.get(id.substring(id.lastIndexOf('/') + 1)).orElse(null);
        }
        if (assessment == null) {
            throw notFound("Assessment", id);
        }
        ObjectNode out = (ObjectNode) assessment.deepCopy();
        JsonNode controls = out.get("ControlEvaluations");
        if (controls != null && controls.isArray()) {
            ObjectNode paged = page("ControlEvaluations", toList(controls), request, 20, 1, 100);
            out.set("ControlEvaluations", paged.path("ControlEvaluations"));
            if (paged.has("NextToken")) {
                out.set("NextToken", paged.get("NextToken"));
            } else {
                out.remove("NextToken");
            }
        }
        return out;
    }

    private ArrayNode normalizeChanges(ArrayNode changes, String region) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode raw : changes) {
            if (!raw.isObject()) {
                throw validation("Each ChangeSet entry must be an object.");
            }
            ObjectNode change = (ObjectNode) raw.deepCopy();
            String changeType = text(change, "ChangeType", true);
            if (!SUPPORTED_CHANGE_TYPES.contains(changeType)) {
                throw validation("Unsupported ChangeType: " + changeType + ".");
            }
            JsonNode entity = change.get("Entity");
            if (entity == null || !entity.isObject()) {
                throw validation("Each change requires Entity.");
            }
            String type = text(entity, "Type", true);
            String identifier = text(entity, "Identifier", false);
            if (identifier == null || "@1".equals(identifier)) {
                identifier = idForType(type);
                ((ObjectNode) entity).put("Identifier", identifier);
            }
            change.put("EntityArn", arn(region,
                    "AWSMarketplace/" + arnEntityType(type) + "/" + identifier));
            out.add(change);
        }
        return out;
    }

    private void validateNoDuplicateChanges(ArrayNode changes) {
        Set<String> seen = new HashSet<>();
        for (JsonNode change : changes) {
            String key = change.path("ChangeType").asText() + "|"
                    + change.path("Entity").path("Identifier").asText();
            if (!seen.add(key)) {
                throw validation("A change set cannot contain the same change type for the same entity more than once.");
            }
        }
    }

    private void applyChangeSet(ObjectNode changeSet) {
        changeSet.put("Status", "APPLYING");
        if (!"VALIDATE".equals(changeSet.path("Intent").asText())) {
            for (JsonNode change : changeSet.path("ChangeSet")) {
                String changeType = change.path("ChangeType").asText();
                JsonNode reference = change.path("Entity");
                String type = reference.path("Type").asText();
                String id = reference.path("Identifier").asText();
                if (changeType.toLowerCase(Locale.ROOT).startsWith("delete")) {
                    entities.delete(id);
                    continue;
                }
                ObjectNode entity = entities.get(id)
                        .filter(JsonNode::isObject)
                        .map(node -> (ObjectNode) node.deepCopy())
                        .orElseGet(mapper::createObjectNode);
                entity.put("EntityType", type);
                entity.put("EntityIdentifier", id);
                entity.put("EntityId", id);
                entity.put("EntityArn", arn(changeSet.path("_Region").asText(REGION),
                        "AWSMarketplace/" + arnEntityType(type) + "/" + id));
                entity.put("LastModifiedDate", Instant.now().toString());
                JsonNode details = change.get("DetailsDocument");
                if (details != null && !details.isNull()) {
                    entity.set("DetailsDocument", details.deepCopy());
                }
                JsonNode legacy = change.get("Details");
                if (legacy != null && !legacy.isNull()) {
                    entity.put("Details", legacy.asText());
                    if (!entity.has("DetailsDocument")) {
                        try {
                            entity.set("DetailsDocument", mapper.readTree(legacy.asText()));
                        } catch (Exception parseError) {
                            LOG.debug("Legacy Marketplace Details is not JSON; preserving it as an opaque string.",
                                    parseError);
                        }
                    }
                }
                entities.put(id, entity);
            }
        }
        changeSet.put("Status", "SUCCEEDED");
        changeSet.put("EndTime", Instant.now().toString());
    }

    private void completePending() {
        for (String key : changeSets.keys()) {
            JsonNode node = changeSets.get(key).orElse(null);
            if (node instanceof ObjectNode objectNode
                    && REGION.equals(objectNode.path("_Region").asText(REGION))
                    && "PREPARING".equals(objectNode.path("Status").asText())) {
                applyChangeSet(objectNode);
                changeSets.put(key, objectNode);
            }
        }
    }

    private boolean matchesChangeSetFilters(JsonNode changeSet, JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return true;
        }
        validateFilterList(filters);
        for (JsonNode filter : filters) {
            String name = text(filter, "Name", true);
            ArrayNode values = requireArray(filter, "ValueList");
            if (values.isEmpty() || values.size() > 10) {
                throw validation("Filter ValueList must contain between 1 and 10 values.");
            }
            boolean match = switch (name) {
                case "ChangeSetName" -> contains(values, changeSet.path("ChangeSetName").asText());
                case "Status" -> contains(values, changeSet.path("Status").asText());
                case "EntityId" -> anyChangeEntityMatches(changeSet, values);
                case "BeforeStartTime" -> compareTime(changeSet.path("StartTime").asText(null), values, true);
                case "AfterStartTime" -> compareTime(changeSet.path("StartTime").asText(null), values, false);
                case "BeforeEndTime" -> compareTime(changeSet.path("EndTime").asText(null), values, true);
                case "AfterEndTime" -> compareTime(changeSet.path("EndTime").asText(null), values, false);
                case "Scope" -> !contains(values, "SharedWithMe");
                default -> throw validation("Unsupported ListChangeSets filter: " + name + ".");
            };
            if (!match) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesEntityFilters(JsonNode entity, JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return true;
        }
        validateFilterList(filters);
        for (JsonNode filter : filters) {
            String name = text(filter, "Name", true);
            ArrayNode values = requireArray(filter, "ValueList");
            if (values.isEmpty() || values.size() > 10) {
                throw validation("Filter ValueList must contain between 1 and 10 values.");
            }
            if ("Scope".equals(name)) {
                if (contains(values, "SharedWithMe")) {
                    return false;
                }
                continue;
            }
            if (!"EntityId".equals(name)) {
                throw validation("Unsupported ListEntities filter: " + name + ".");
            }
            if (!contains(values, entity.path("EntityId").asText())) {
                return false;
            }
        }
        return true;
    }

    private static void validateFilterList(JsonNode filters) {
        if (!filters.isArray() || filters.isEmpty() || filters.size() > 8) {
            throw validation("FilterList must contain between 1 and 8 filters.");
        }
    }

    private static boolean requestsSharedScope(JsonNode filters) {
        if (filters == null || !filters.isArray()) {
            return false;
        }
        for (JsonNode filter : filters) {
            if ("Scope".equals(filter.path("Name").asText())) {
                for (JsonNode value : filter.path("ValueList")) {
                    if ("SharedWithMe".equals(value.asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void sortChangeSets(List<JsonNode> values, JsonNode sort) {
        String sortBy = sort == null || sort.isNull() ? "StartTime" : sort.path("SortBy").asText("StartTime");
        if (!Set.of("StartTime", "EndTime").contains(sortBy)) {
            throw validation("ListChangeSets SortBy must be StartTime or EndTime.");
        }
        boolean ascending = sort != null && "ASCENDING".equals(sort.path("SortOrder").asText());
        validateSortOrder(sort);
        Comparator<JsonNode> comparator = Comparator.comparing(value -> value.path(sortBy).asText(""));
        values.sort(ascending ? comparator : comparator.reversed());
    }

    private void sortEntities(List<JsonNode> values, JsonNode sort) {
        String sortBy = sort == null || sort.isNull() ? "LastModifiedDate" : sort.path("SortBy").asText("LastModifiedDate");
        if (!Set.of("LastModifiedDate", "EntityId").contains(sortBy)) {
            throw validation("ListEntities SortBy must be LastModifiedDate or EntityId.");
        }
        boolean ascending = sort != null && "ASCENDING".equals(sort.path("SortOrder").asText());
        validateSortOrder(sort);
        Comparator<JsonNode> comparator = Comparator.comparing(value -> value.path(sortBy).asText(""));
        values.sort(ascending ? comparator : comparator.reversed());
    }

    private static void validateSortOrder(JsonNode sort) {
        if (sort == null || sort.isNull() || !sort.hasNonNull("SortOrder")) {
            return;
        }
        String order = sort.path("SortOrder").asText();
        if (!Set.of("ASCENDING", "DESCENDING").contains(order)) {
            throw validation("SortOrder must be ASCENDING or DESCENDING.");
        }
    }

    private static boolean anyChangeEntityMatches(JsonNode changeSet, ArrayNode values) {
        for (JsonNode change : changeSet.path("ChangeSet")) {
            if (contains(values, change.path("Entity").path("Identifier").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(ArrayNode values, String actual) {
        for (JsonNode value : values) {
            if (value.isTextual() && value.asText().equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compareTime(String actualValue, ArrayNode values, boolean before) {
        if (actualValue == null) {
            return false;
        }
        Instant actual;
        try {
            actual = Instant.parse(actualValue);
        } catch (DateTimeParseException e) {
            return false;
        }
        for (JsonNode value : values) {
            try {
                Instant boundary = Instant.parse(value.asText());
                if (before ? actual.isBefore(boundary) : actual.isAfter(boundary)) {
                    return true;
                }
            } catch (DateTimeParseException e) {
                throw validation("Time filters must use ISO-8601 timestamps.");
            }
        }
        return false;
    }

    private ObjectNode publicChangeSet(JsonNode changeSet) {
        ObjectNode copy = (ObjectNode) changeSet.deepCopy();
        copy.remove(List.of("ClientRequestToken", "_IdempotencyRequest", "_Region"));
        return copy;
    }

    private ObjectNode ids(JsonNode changeSet) {
        return mapper.createObjectNode()
                .put("ChangeSetId", changeSet.path("ChangeSetId").asText())
                .put("ChangeSetArn", changeSet.path("ChangeSetArn").asText());
    }

    private ObjectNode changeSet(String id) {
        if (id == null || id.isBlank()) {
            throw validation("ChangeSetId is required.");
        }
        JsonNode node = changeSets.get(id).orElseThrow(() -> notFound("Change set", id));
        if (!node.isObject()) {
            throw new AwsException("InternalServiceException", "Stored change set is invalid.", 500);
        }
        return (ObjectNode) node.deepCopy();
    }

    private JsonNode changeSummary(JsonNode changeSet) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("ChangeSetId", "ChangeSetArn", "ChangeSetName", "StartTime", "EndTime", "Status")) {
            if (changeSet.has(field)) {
                out.set(field, changeSet.get(field));
            }
        }
        ArrayNode entityIds = out.putArray("EntityIdList");
        Set<String> unique = new java.util.LinkedHashSet<>();
        changeSet.path("ChangeSet").forEach(change -> unique.add(change.path("Entity").path("Identifier").asText()));
        unique.forEach(entityIds::add);
        return out;
    }

    private JsonNode entitySummary(JsonNode entity) {
        ObjectNode out = mapper.createObjectNode();
        out.put("Name", entityName(entity));
        out.put("EntityType", baseEntityType(entity.path("EntityType").asText()));
        out.put("EntityId", entity.path("EntityId").asText());
        out.put("EntityArn", entity.path("EntityArn").asText());
        out.put("LastModifiedDate", entity.path("LastModifiedDate").asText());
        return out;
    }

    private static String entityName(JsonNode entity) {
        JsonNode details = entity.path("DetailsDocument");
        for (String field : List.of("ProductTitle", "OfferName", "OfferSetName", "Name", "Title")) {
            if (details.hasNonNull(field) && !details.path(field).asText().isBlank()) {
                return details.path(field).asText();
            }
        }
        return entity.path("EntityIdentifier").asText();
    }

    private JsonNode assessmentSummary(JsonNode assessment) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("AssessmentArn", "AssessmentId", "FrameworkId",
                "AssessmentResult", "CreatedAt", "ExpiresAt")) {
            if (assessment.has(field)) {
                out.set(field, assessment.get(field));
            }
        }
        return out;
    }

    private ObjectNode page(String name, List<JsonNode> values, JsonNode request,
                            int defaultSize, int min, int max) {
        int size = request.path("MaxResults").isInt() ? request.path("MaxResults").asInt() : defaultSize;
        if (size < min || size > max) {
            throw validation("MaxResults is out of range.");
        }
        int offset = decodeToken(request.path("NextToken").asText(null));
        if (offset > values.size()) {
            throw validation("NextToken is invalid.");
        }
        int end = Math.min(values.size(), offset + size);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(name);
        values.subList(offset, end).forEach(value -> array.add(value.deepCopy()));
        if (end < values.size()) {
            out.put("NextToken", Integer.toString(end));
        }
        return out;
    }

    private static int decodeToken(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(token);
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw validation("NextToken is invalid.");
        }
    }

    private ObjectNode errorDetail(String code, String message) {
        return mapper.createObjectNode().put("ErrorCode", code).put("ErrorMessage", message);
    }

    private void ensureResource(String arn) {
        boolean exists = entities.scan(key -> true).stream()
                .anyMatch(entity -> arn.equals(entity.path("EntityArn").asText()))
                || changeSets.scan(key -> true).stream()
                .anyMatch(changeSet -> arn.equals(changeSet.path("ChangeSetArn").asText()));
        if (!exists) {
            throw notFound("Resource", arn);
        }
    }

    private static ArrayNode requireArray(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isArray()) {
            throw validation(field + " is required and must be an array.");
        }
        return (ArrayNode) value;
    }

    private static String text(JsonNode node, String field, boolean required) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            if (required) {
                throw validation(field + " is required.");
            }
            return null;
        }
        return value.asText();
    }

    private static void requireCatalog(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            throw validation("Catalog is required.");
        }
        if (!CATALOG.equals(catalog)) {
            throw validation("Catalog must be AWSMarketplace.");
        }
    }

    private static void validateRegion(String region) {
        if (!REGION.equals(region)) {
            throw validation("AWS Marketplace Catalog API is available only in us-east-1.");
        }
    }

    private static void ensureStoredRegion(JsonNode changeSet, String region) {
        if (!region.equals(changeSet.path("_Region").asText(REGION))) {
            throw notFound("Change set", changeSet.path("ChangeSetId").asText());
        }
    }

    private static String baseEntityType(String type) {
        int version = type.indexOf('@');
        return version < 0 ? type : type.substring(0, version);
    }

    private static String idForType(String type) {
        String prefix = type.toLowerCase(Locale.ROOT).contains("offer") ? "offer-" : "prod-";
        return prefix + compactId();
    }

    private static String arnEntityType(String type) {
        return baseEntityType(type);
    }

    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String arn(String region, String resource) {
        return "arn:aws:aws-marketplace:" + region + ":" + accountId() + ":" + resource;
    }

    private String accountId() {
        return requestContext == null || requestContext.getAccountId() == null
                ? "000000000000" : requestContext.getAccountId();
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 422);
    }

    private static AwsException notFound(String kind, String id) {
        return new AwsException("ResourceNotFoundException", kind + " " + id + " was not found.", 404);
    }

    @Override
    public void clear() {
        entities.clear();
        changeSets.clear();
        resourcePolicies.clear();
        tags.clear();
        assessments.clear();
    }
}
