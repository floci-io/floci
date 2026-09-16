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
import io.github.hectorvent.floci.services.marketplace.model.AgreementProposalContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class MarketplaceAgreementService implements Resettable {
    private static final String REGION = "us-east-1";
    private static final String CATALOG = "AWSMarketplace";
    private static final Pattern PROPOSAL_ID = Pattern.compile("(at-|ap-)[A-Za-z0-9]+");
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[a-zA-Z0-9-]{1,64}");
    private static final Set<String> SEARCH_SORTS = Set.of("EndTime", "StartTime", "LastUpdateTime");

    private final ObjectMapper mapper;
    private final AccountAwareStorageBackend<JsonNode> agreements;
    private final AccountAwareStorageBackend<JsonNode> agreementRequests;
    private final AccountAwareStorageBackend<JsonNode> cancellationRequests;
    private final AccountAwareStorageBackend<JsonNode> paymentRequests;
    private final AccountAwareStorageBackend<JsonNode> billingAdjustments;
    private final AccountAwareStorageBackend<JsonNode> catalogEntities;
    private final RequestContext requestContext;

    @Inject
    public MarketplaceAgreementService(StorageFactory factory, ObjectMapper mapper, RequestContext requestContext) {
        this(mapper,
                factory.create("marketplace", "marketplace-agreements.json", type()),
                factory.create("marketplace", "marketplace-agreement-requests.json", type()),
                factory.create("marketplace", "marketplace-cancellation-requests.json", type()),
                factory.create("marketplace", "marketplace-payment-requests.json", type()),
                factory.create("marketplace", "marketplace-billing-adjustments.json", type()),
                factory.create("marketplace", "marketplace-entities.json", type()),
                requestContext);
    }

    MarketplaceAgreementService(ObjectMapper mapper,
                                AccountAwareStorageBackend<JsonNode> agreements,
                                AccountAwareStorageBackend<JsonNode> agreementRequests,
                                AccountAwareStorageBackend<JsonNode> cancellationRequests,
                                AccountAwareStorageBackend<JsonNode> paymentRequests,
                                AccountAwareStorageBackend<JsonNode> billingAdjustments,
                                AccountAwareStorageBackend<JsonNode> catalogEntities,
                                RequestContext requestContext) {
        this.mapper = mapper;
        this.agreements = agreements;
        this.agreementRequests = agreementRequests;
        this.cancellationRequests = cancellationRequests;
        this.paymentRequests = paymentRequests;
        this.billingAdjustments = billingAdjustments;
        this.catalogEntities = catalogEntities;
        this.requestContext = requestContext;
    }

    private static TypeReference<Map<String, JsonNode>> type() {
        return new TypeReference<>() {};
    }

    public JsonNode handle(String action, JsonNode request, String region) {
        validateRegion(region);
        return switch (action) {
            case "AcceptAgreementCancellationRequest" -> acceptCancellation(request);
            case "AcceptAgreementPaymentRequest" -> transitionPayment(request, "APPROVED", null);
            case "AcceptAgreementRequest" -> acceptAgreementRequest(request);
            case "BatchCreateBillingAdjustmentRequest" -> batchCreateBillingAdjustments(request);
            case "CancelAgreement" -> cancelAgreement(request);
            case "CancelAgreementCancellationRequest" -> transitionCancellation(
                    request, "CANCELLED", text(request, "cancellationReason", true));
            case "CancelAgreementPaymentRequest" -> transitionPayment(request, "CANCELLED", null);
            case "CreateAgreementRequest" -> createAgreementRequest(request);
            case "DescribeAgreement" -> describeAgreement(request);
            case "GetAgreementCancellationRequest" -> getCancellation(request);
            case "GetAgreementEntitlements" -> getAgreementEntitlements(request);
            case "GetAgreementPaymentRequest" -> getPayment(request);
            case "GetAgreementTerms" -> getAgreementTerms(request);
            case "GetBillingAdjustmentRequest" -> getBillingAdjustment(request);
            case "ListAgreementCancellationRequests" -> listCancellations(request);
            case "ListAgreementCharges" -> listAgreementCharges(request);
            case "ListAgreementInvoiceLineItems" -> listInvoiceLineItems(request);
            case "ListAgreementPaymentRequests" -> listPayments(request);
            case "ListBillingAdjustmentRequests" -> listBillingAdjustments(request);
            case "RejectAgreementCancellationRequest" -> transitionCancellation(
                    request, "REJECTED", text(request, "rejectionReason", true));
            case "RejectAgreementPaymentRequest" -> transitionPayment(request, "REJECTED", null);
            case "SearchAgreements" -> searchAgreements(request);
            case "SendAgreementCancellationRequest" -> sendCancellation(request);
            case "SendAgreementPaymentRequest" -> sendPayment(request);
            case "UpdatePurchaseOrders" -> updatePurchaseOrders(request);
            default -> null;
        };
    }

    private ObjectNode createAgreementRequest(JsonNode request) {
        String intent = text(request, "intent", true);
        if (!Set.of("NEW", "AMEND", "REPLACE").contains(intent)) {
            throw validation("intent must be NEW, AMEND, or REPLACE.");
        }
        ArrayNode terms = array(request, "requestedTerms", true);
        if (terms.isEmpty() || terms.size() > 30) {
            throw validation("requestedTerms must contain between 1 and 30 terms.");
        }
        String source = text(request, "sourceAgreementIdentifier", false);
        String proposal = text(request, "agreementProposalIdentifier", false);
        if ("NEW".equals(intent) && source != null) {
            throw validation("sourceAgreementIdentifier must not be provided for NEW intent.");
        }
        if (!"NEW".equals(intent) && source == null) {
            throw validation("sourceAgreementIdentifier is required for AMEND and REPLACE intents.");
        }
        if (!"AMEND".equals(intent) && proposal == null) {
            throw validation("agreementProposalIdentifier is required for NEW and REPLACE intents.");
        }
        if (proposal != null && !PROPOSAL_ID.matcher(proposal).matches()) {
            throw validation("agreementProposalIdentifier is invalid.");
        }
        if (source != null) {
            requireAgreement(source);
        }
        String token = text(request, "clientToken", false);
        if (token != null && !CLIENT_TOKEN.matcher(token).matches()) {
            throw validation("clientToken is invalid.");
        }
        if (token != null) {
            for (JsonNode existing : agreementRequests.scan(key -> true)) {
                if (!token.equals(existing.path("clientToken").asText(null))) {
                    continue;
                }
                if (!existing.path("_idempotencyRequest").equals(request)) {
                    throw validation("clientToken was already used with different parameters.");
                }
                return createAgreementRequestResponse(existing);
            }
        }
        String id = "ar-" + compactId();
        ObjectNode record = mapper.createObjectNode();
        record.put("agreementRequestId", id);
        record.put("intent", intent);
        record.set("requestedTerms", terms.deepCopy());
        if (source != null) {
            record.put("sourceAgreementIdentifier", source);
        }
        if (proposal != null) {
            record.put("agreementProposalIdentifier", proposal);
        }
        if (token != null) {
            record.put("clientToken", token);
            record.set("_idempotencyRequest", request.deepCopy());
        }
        record.put("createdAt", epoch());
        record.put("status", "PENDING");
        agreementRequests.put(id, record);
        return createAgreementRequestResponse(record);
    }

    private ObjectNode createAgreementRequestResponse(JsonNode record) {
        ObjectNode out = mapper.createObjectNode()
                .put("agreementRequestId", record.path("agreementRequestId").asText());
        ObjectNode charges = out.putObject("chargeSummary");
        charges.put("currencyCode", "USD");
        charges.put("newAgreementValue", "0");
        charges.putArray("expectedCharges");
        charges.putArray("itemizedCharges");
        return out;
    }

    private ObjectNode acceptAgreementRequest(JsonNode request) {
        String requestId = text(request, "agreementRequestId", true);
        ObjectNode agreementRequest = object(agreementRequests, requestId, "Agreement request");
        if ("ACCEPTED".equals(agreementRequest.path("status").asText())) {
            return mapper.createObjectNode().put("agreementId", agreementRequest.path("agreementId").asText());
        }
        String source = agreementRequest.path("sourceAgreementIdentifier").asText(null);
        String intent = agreementRequest.path("intent").asText();
        String id = "AMEND".equals(intent) && source != null ? source : "agr-" + compactId();
        ObjectNode agreement = source == null
                ? mapper.createObjectNode()
                : agreements.get(source).filter(JsonNode::isObject)
                        .map(node -> (ObjectNode) node.deepCopy()).orElseGet(mapper::createObjectNode);
        double now = epoch();
        agreement.put("agreementId", id);
        agreement.put("initialAgreementId", agreement.path("initialAgreementId").asText(id));
        agreement.put("agreementType", "PurchaseAgreement");
        agreement.put("status", "ACTIVE");
        agreement.put("acceptanceTime", now);
        agreement.put("startTime", now);
        agreement.put("lastUpdateTime", now);
        agreement.put("endTime", now + 31536000d);
        String accountId = accountId();
        agreement.putObject("acceptor").put("accountId", accountId);
        agreement.putObject("proposer").put("accountId", accountId);
        agreement.set("proposalSummary", proposalSummary(agreementRequest));
        agreement.set("acceptedTerms", agreementRequest.path("requestedTerms").deepCopy());
        agreement.set("purchaseOrders", request.path("purchaseOrders").deepCopy());
        agreements.put(id, agreement);
        if ("REPLACE".equals(intent) && source != null) {
            ObjectNode old = requireAgreement(source);
            old.put("status", "REPLACED");
            old.put("lastUpdateTime", now);
            agreements.put(source, old);
        }
        agreementRequest.put("status", "ACCEPTED");
        agreementRequest.put("agreementId", id);
        agreementRequests.put(requestId, agreementRequest);
        return mapper.createObjectNode().put("agreementId", id);
    }

    private ObjectNode proposalSummary(JsonNode agreementRequest) {
        AgreementProposalContext context = resolveProposalContext(
                agreementRequest.path("agreementProposalIdentifier").asText(null));
        ObjectNode proposal = mapper.createObjectNode();
        if (context.offerId() != null) {
            proposal.put("offerId", context.offerId());
        }
        if (context.offerSetId() != null) {
            proposal.put("offerSetId", context.offerSetId());
        }
        ArrayNode resources = proposal.putArray("resources");
        context.resources().forEach(resource -> resources.addObject()
                .put("id", resource.id()).put("type", resource.type()));
        return proposal;
    }

    private AgreementProposalContext resolveProposalContext(String proposalIdentifier) {
        if (proposalIdentifier == null) {
            return new AgreementProposalContext(null, null, List.of());
        }
        for (JsonNode entity : catalogEntities.scan(key -> true)) {
            String entityType = entity.path("EntityType").asText();
            if (!entityType.startsWith("Offer")) {
                continue;
            }
            JsonNode details = entity.path("DetailsDocument");
            if (!proposalIdentifier.equals(details.path("AgreementProposalId").asText(null))
                    && !proposalIdentifier.equals(details.path("AgreementProposalIdentifier").asText(null))) {
                continue;
            }
            String offerId = entity.path("EntityId").asText(null);
            String offerSetId = details.path("OfferSetId").asText(null);
            String productId = details.path("ProductId").asText(null);
            String productType = details.path("ProductType").asText("SaaSProduct");
            List<AgreementProposalContext.ResourceReference> resources = productId == null
                    ? List.of()
                    : List.of(new AgreementProposalContext.ResourceReference(productId, productType));
            return new AgreementProposalContext(offerId, offerSetId, resources);
        }
        return new AgreementProposalContext(null, null, List.of());
    }

    private ObjectNode describeAgreement(JsonNode request) {
        ObjectNode agreement = requireAgreement(text(request, "agreementId", true));
        ObjectNode out = agreement.deepCopy();
        out.remove(List.of("acceptedTerms", "purchaseOrders", "agreementEntitlements"));
        return out;
    }

    private ObjectNode cancelAgreement(JsonNode request) {
        String id = text(request, "agreementId", true);
        ObjectNode agreement = requireAgreement(id);
        agreement.put("status", "CANCELLED");
        agreement.put("endTime", epoch());
        agreement.put("lastUpdateTime", epoch());
        agreements.put(id, agreement);
        return mapper.createObjectNode();
    }

    private ObjectNode getAgreementTerms(JsonNode request) {
        ObjectNode agreement = requireAgreement(text(request, "agreementId", true));
        return page("acceptedTerms", nodes(agreement.path("acceptedTerms")), request, 50);
    }

    private ObjectNode getAgreementEntitlements(JsonNode request) {
        ObjectNode agreement = requireAgreement(text(request, "agreementId", true));
        return page("agreementEntitlements", nodes(agreement.path("agreementEntitlements")), request, 50);
    }

    private ObjectNode searchAgreements(JsonNode request) {
        JsonNode filters = request.path("filters");
        if (!hasFilter(filters, "AgreementType")) {
            throw validation("AgreementType is required for SearchAgreements.");
        }
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode agreement : agreements.scan(key -> true)) {
            if (matchesAgreementFilters(agreement, filters)) {
                found.add(agreementSummary(agreement));
            }
        }
        JsonNode sort = request.path("sort");
        String sortBy = sort.path("sortBy").asText("EndTime");
        String sortOrder = sort.path("sortOrder").asText("DESCENDING");
        if (!SEARCH_SORTS.contains(sortBy)) {
            throw validation("Unsupported SearchAgreements sortBy value.");
        }
        String partyType = filterValue(filters, "PartyType");
        if (("StartTime".equals(sortBy) || "LastUpdateTime".equals(sortBy))
                && !"Proposer".equals(partyType)) {
            throw validation(sortBy + " sorting requires PartyType=Proposer.");
        }
        if (!Set.of("ASCENDING", "DESCENDING").contains(sortOrder)) {
            throw validation("sortOrder must be ASCENDING or DESCENDING.");
        }
        String field = switch (sortBy) {
            case "StartTime" -> "startTime";
            case "LastUpdateTime" -> "lastUpdateTime";
            default -> "endTime";
        };
        Comparator<JsonNode> comparator = Comparator.comparingDouble(node -> node.path(field).asDouble(0));
        found.sort("ASCENDING".equals(sortOrder) ? comparator : comparator.reversed());
        return page("agreementViewSummaries", found, request, 50);
    }

    private boolean matchesAgreementFilters(JsonNode agreement, JsonNode filters) {
        if (!filters.isArray()) {
            return true;
        }
        if (filters.size() > 10) {
            throw validation("filters can contain at most 10 entries.");
        }
        for (JsonNode filter : filters) {
            String name = text(filter, "name", true);
            JsonNode values = filter.get("values");
            if (values == null || !values.isArray() || values.isEmpty()) {
                throw validation("Filter values must be a non-empty array.");
            }
            boolean any = false;
            for (JsonNode value : values) {
                if (matchesAgreementFilter(agreement, name, value.asText())) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAgreementFilter(JsonNode agreement, String name, String wanted) {
        return switch (name) {
            case "AgreementType" -> wanted.equals(agreement.path("agreementType").asText());
            case "Status" -> wanted.equals(agreement.path("status").asText());
            case "AcceptorAccountId" -> wanted.equals(agreement.path("acceptor").path("accountId").asText());
            case "OfferId" -> wanted.equals(agreement.path("proposalSummary").path("offerId").asText());
            case "OfferSetId" -> wanted.equals(agreement.path("proposalSummary").path("offerSetId").asText());
            case "ResourceIdentifier" -> resourceMatches(agreement, wanted, null);
            case "ResourceType" -> resourceMatches(agreement, null, wanted);
            case "PartyType" -> Set.of("Acceptor", "Proposer").contains(wanted);
            case "BeforeEndTime" -> agreement.path("endTime").asDouble() < timestampFilter(wanted);
            case "AfterEndTime" -> agreement.path("endTime").asDouble() > timestampFilter(wanted);
            case "BeforeStartTime" -> agreement.path("startTime").asDouble() < timestampFilter(wanted);
            case "AfterStartTime" -> agreement.path("startTime").asDouble() > timestampFilter(wanted);
            case "BeforeLastUpdateTime" -> agreement.path("lastUpdateTime").asDouble() < timestampFilter(wanted);
            case "AfterLastUpdateTime" -> agreement.path("lastUpdateTime").asDouble() > timestampFilter(wanted);
            case "EndTimeBehaviorType" -> wanted.equals(agreement.path("endTimeBehaviorType").asText());
            case "EndTimeBehaviorReasonCode" -> wanted.equals(agreement.path("endTimeBehaviorReasonCode").asText());
            case "InitialAgreementId" -> wanted.equals(agreement.path("initialAgreementId").asText());
            case "LicenseArn" -> licenseMatches(agreement, wanted);
            default -> false;
        };
    }


    private static boolean hasFilter(JsonNode filters, String name) {
        return filterValue(filters, name) != null;
    }

    private static String filterValue(JsonNode filters, String name) {
        if (filters == null || !filters.isArray()) {
            return null;
        }
        for (JsonNode filter : filters) {
            if (name.equals(filter.path("name").asText())) {
                JsonNode values = filter.path("values");
                return values.isArray() && !values.isEmpty() ? values.get(0).asText(null) : null;
            }
        }
        return null;
    }

    private static boolean licenseMatches(JsonNode agreement, String wanted) {
        for (String field : List.of("entitlements", "agreementEntitlements")) {
            JsonNode values = agreement.path(field);
            if (values.isArray()) {
                for (JsonNode value : values) {
                    if (wanted.equals(value.path("licenseArn").asText())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean resourceMatches(JsonNode agreement, String resourceId, String resourceType) {
        JsonNode resources = agreement.path("proposalSummary").path("resources");
        if (!resources.isArray()) {
            return false;
        }
        for (JsonNode resource : resources) {
            boolean idMatches = resourceId == null || resourceId.equals(resource.path("id").asText());
            boolean typeMatches = resourceType == null || resourceType.equals(resource.path("type").asText());
            if (idMatches && typeMatches) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode agreementSummary(JsonNode agreement) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("agreementId", "agreementType", "acceptanceTime", "startTime", "endTime",
                "lastUpdateTime", "initialAgreementId", "status")) {
            if (agreement.has(field)) {
                out.set(field, agreement.get(field));
            }
        }
        copy(agreement, out, "acceptor");
        copy(agreement, out, "proposer");
        copy(agreement, out, "proposalSummary");
        copy(agreement, out, "entitlements");
        return out;
    }

    private ObjectNode sendCancellation(JsonNode request) {
        String agreementId = text(request, "agreementId", true);
        ObjectNode agreement = requireAgreement(agreementId);
        String id = "acr-" + compactId();
        double now = epoch();
        ObjectNode record = mapper.createObjectNode()
                .put("agreementCancellationRequestId", id)
                .put("agreementId", agreementId)
                .put("agreementType", agreement.path("agreementType").asText("PurchaseAgreement"))
                .put("catalog", CATALOG)
                .put("reasonCode", text(request, "reasonCode", true))
                .put("status", "PENDING_APPROVAL")
                .put("createdAt", now)
                .put("updatedAt", now);
        if (request.hasNonNull("description")) {
            record.put("description", request.path("description").asText());
        }
        cancellationRequests.put(id, record);
        return record.deepCopy();
    }

    private ObjectNode getCancellation(JsonNode request) {
        String agreementId = text(request, "agreementId", true);
        String id = text(request, "agreementCancellationRequestId", true);
        ObjectNode record = object(cancellationRequests, id, "Agreement cancellation request");
        if (!agreementId.equals(record.path("agreementId").asText())) {
            throw notFound("Agreement cancellation request", id);
        }
        return record;
    }

    private ObjectNode acceptCancellation(JsonNode request) {
        ObjectNode record = transitionCancellation(request, "APPROVED", null);
        ObjectNode agreement = requireAgreement(record.path("agreementId").asText());
        agreement.put("status", "CANCELLED");
        agreement.put("endTime", epoch());
        agreement.put("lastUpdateTime", epoch());
        agreements.put(agreement.path("agreementId").asText(), agreement);
        return record;
    }

    private ObjectNode transitionCancellation(JsonNode request, String status, String statusMessage) {
        ObjectNode record = getCancellation(request);
        record.put("status", status);
        record.put("updatedAt", epoch());
        if (statusMessage != null) {
            record.put("statusMessage", statusMessage);
        }
        cancellationRequests.put(record.path("agreementCancellationRequestId").asText(), record);
        return record.deepCopy();
    }

    private ObjectNode listCancellations(JsonNode request) {
        text(request, "partyType", true);
        List<JsonNode> list = new ArrayList<>(cancellationRequests.scan(key -> true));
        list.removeIf(record -> !matchesListRequest(record, request, true));
        list.sort(Comparator.comparingDouble((JsonNode record) -> record.path("createdAt").asDouble()).reversed());
        return page("items", list, request, 50);
    }

    private ObjectNode sendPayment(JsonNode request) {
        String agreementId = text(request, "agreementId", true);
        ObjectNode agreement = requireAgreement(agreementId);
        text(request, "termId", true);
        String id = "pr-" + compactId();
        double now = epoch();
        ObjectNode record = mapper.createObjectNode()
                .put("paymentRequestId", id)
                .put("agreementId", agreementId)
                .put("agreementType", agreement.path("agreementType").asText("PurchaseAgreement"))
                .put("catalog", CATALOG)
                .put("status", "PENDING_APPROVAL")
                .put("name", text(request, "name", true))
                .put("chargeAmount", text(request, "chargeAmount", true))
                .put("currencyCode", request.path("currencyCode").asText("USD"))
                .put("createdAt", now)
                .put("updatedAt", now);
        if (request.hasNonNull("description")) {
            record.put("description", request.path("description").asText());
        }
        paymentRequests.put(id, record);
        return record.deepCopy();
    }

    private ObjectNode getPayment(JsonNode request) {
        String agreementId = text(request, "agreementId", true);
        String id = text(request, "paymentRequestId", true);
        ObjectNode record = object(paymentRequests, id, "Agreement payment request");
        if (!agreementId.equals(record.path("agreementId").asText())) {
            throw notFound("Agreement payment request", id);
        }
        return record;
    }

    private ObjectNode transitionPayment(JsonNode request, String status, String message) {
        ObjectNode record = getPayment(request);
        record.put("status", status);
        record.put("updatedAt", epoch());
        if ("APPROVED".equals(status) && !record.hasNonNull("chargeId")) {
            record.put("chargeId", "ch-" + compactId());
        }
        if (message != null) {
            record.put("statusMessage", message);
        }
        paymentRequests.put(record.path("paymentRequestId").asText(), record);
        return record.deepCopy();
    }

    private ObjectNode listPayments(JsonNode request) {
        text(request, "partyType", true);
        List<JsonNode> list = new ArrayList<>(paymentRequests.scan(key -> true));
        list.removeIf(record -> !matchesListRequest(record, request, true));
        list.sort(Comparator.comparingDouble((JsonNode record) -> record.path("createdAt").asDouble()).reversed());
        return page("items", list, request, 50);
    }

    private ObjectNode batchCreateBillingAdjustments(JsonNode request) {
        ArrayNode entries = array(request, "billingAdjustmentRequestEntries", true);
        if (entries.isEmpty() || entries.size() > 25) {
            throw validation("billingAdjustmentRequestEntries must contain between 1 and 25 entries.");
        }
        ObjectNode out = mapper.createObjectNode();
        ArrayNode items = out.putArray("items");
        out.putArray("errors");
        for (JsonNode entry : entries) {
            String agreementId = text(entry, "agreementId", true);
            ObjectNode agreement = requireAgreement(agreementId);
            String id = "ba-" + compactId();
            double now = epoch();
            ObjectNode record = mapper.createObjectNode()
                    .put("billingAdjustmentRequestId", id)
                    .put("agreementId", agreementId)
                    .put("agreementType", agreement.path("agreementType").asText("PurchaseAgreement"))
                    .put("catalog", CATALOG)
                    .put("status", "PENDING")
                    .put("adjustmentReasonCode", entry.path("adjustmentReasonCode").asText("OTHER"))
                    .put("adjustmentAmount", entry.path("adjustmentAmount").asText("0"))
                    .put("currencyCode", entry.path("currencyCode").asText("USD"))
                    .put("createdAt", now)
                    .put("updatedAt", now);
            if (entry.has("description")) {
                record.set("description", entry.get("description"));
            }
            if (entry.hasNonNull("originalInvoiceId")) {
                record.set("originalInvoiceId", entry.get("originalInvoiceId"));
            }
            billingAdjustments.put(id, record);
            items.add(record.deepCopy());
        }
        return out;
    }

    private ObjectNode getBillingAdjustment(JsonNode request) {
        String agreementId = text(request, "agreementId", true);
        String id = text(request, "billingAdjustmentRequestId", true);
        ObjectNode record = object(billingAdjustments, id, "Billing adjustment request");
        if (!agreementId.equals(record.path("agreementId").asText())) {
            throw notFound("Billing adjustment request", id);
        }
        return record;
    }

    private ObjectNode listBillingAdjustments(JsonNode request) {
        List<JsonNode> list = new ArrayList<>(billingAdjustments.scan(key -> true));
        list.removeIf(record -> !matchesListRequest(record, request, false));
        list.removeIf(record -> !matchesCreatedWindow(record, request));
        list.sort(Comparator.comparingDouble((JsonNode record) -> record.path("createdAt").asDouble()).reversed());
        return page("items", list, request, 50);
    }

    private ObjectNode listAgreementCharges(JsonNode request) {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode payment : paymentRequests.scan(key -> true)) {
            if (!"APPROVED".equals(payment.path("status").asText())) {
                continue;
            }
            if (!matchesListRequest(payment, request, false)) {
                continue;
            }
            ObjectNode charge = mapper.createObjectNode()
                    .put("id", payment.path("chargeId").asText("ch-" + compactId()))
                    .put("revision", 1)
                    .put("agreementId", payment.path("agreementId").asText())
                    .put("agreementType", payment.path("agreementType").asText("PurchaseAgreement"))
                    .put("amount", payment.path("chargeAmount").asText())
                    .put("currencyCode", payment.path("currencyCode").asText("USD"))
                    .put("time", payment.path("updatedAt").asDouble(payment.path("createdAt").asDouble()));
            list.add(charge);
        }
        return page("items", list, request, 50);
    }

    private boolean matchesListRequest(JsonNode record, JsonNode request, boolean hasPartyType) {
        if (hasPartyType) {
            String partyType = text(request, "partyType", true);
            if (!Set.of("Acceptor", "Proposer").contains(partyType)) {
                throw validation("partyType must be Acceptor or Proposer.");
            }
        }
        return optionalEquals(record, request, "agreementId")
                && optionalEquals(record, request, "agreementType")
                && optionalEquals(record, request, "catalog")
                && optionalEquals(record, request, "status");
    }

    private static boolean matchesCreatedWindow(JsonNode record, JsonNode request) {
        double createdAt = record.path("createdAt").asDouble();
        if (request.hasNonNull("createdAfter") && createdAt <= request.path("createdAfter").asDouble()) {
            return false;
        }
        return !request.hasNonNull("createdBefore") || createdAt < request.path("createdBefore").asDouble();
    }

    private static boolean optionalEquals(JsonNode record, JsonNode request, String field) {
        return !request.hasNonNull(field) || request.path(field).asText().equals(record.path(field).asText());
    }

    private ObjectNode listInvoiceLineItems(JsonNode request) {
        String agreementId = text(request, "agreementId", true);
        requireAgreement(agreementId);
        text(request, "groupBy", true);
        return page("agreementInvoiceLineItemGroupSummaries", List.of(), request, 50);
    }

    private ObjectNode updatePurchaseOrders(JsonNode request) {
        ArrayNode purchaseOrders = array(request, "purchaseOrders", true);
        for (JsonNode purchaseOrder : purchaseOrders) {
            String agreementId = text(purchaseOrder, "agreementId", true);
            ObjectNode agreement = requireAgreement(agreementId);
            agreement.withArray("purchaseOrders").add(purchaseOrder.deepCopy());
            agreement.put("lastUpdateTime", epoch());
            agreements.put(agreementId, agreement);
        }
        return mapper.createObjectNode();
    }

    private ObjectNode page(String field, List<JsonNode> values, JsonNode request, int defaultSize) {
        int max = request.path("maxResults").isInt() ? request.path("maxResults").asInt() : defaultSize;
        if (max < 1 || max > 50) {
            throw validation("maxResults must be between 1 and 50.");
        }
        int offset = token(request.path("nextToken").asText(null));
        if (offset > values.size()) {
            throw validation("nextToken is invalid.");
        }
        int end = Math.min(values.size(), offset + max);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode array = out.putArray(field);
        values.subList(offset, end).forEach(value -> array.add(value.deepCopy()));
        if (end < values.size()) {
            out.put("nextToken", Integer.toString(end));
        }
        return out;
    }

    private static int token(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int token = Integer.parseInt(value);
            if (token < 0) {
                throw new NumberFormatException();
            }
            return token;
        } catch (NumberFormatException e) {
            throw validation("nextToken is invalid.");
        }
    }

    private ObjectNode requireAgreement(String id) {
        return object(agreements, id, "Agreement");
    }

    private static ObjectNode object(AccountAwareStorageBackend<JsonNode> store, String id, String kind) {
        if (id == null || id.isBlank()) {
            throw validation(kind + " identifier is required.");
        }
        JsonNode node = store.get(id).orElseThrow(() -> notFound(kind, id));
        if (!node.isObject()) {
            throw new AwsException("InternalServerException", "Stored " + kind + " is invalid.", 500);
        }
        return (ObjectNode) node.deepCopy();
    }

    private static ArrayNode array(JsonNode request, String field, boolean required) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw validation(field + " is required.");
            }
            return new ObjectMapper().createArrayNode();
        }
        if (!node.isArray()) {
            throw validation(field + " must be an array.");
        }
        return (ArrayNode) node;
    }

    private static String text(JsonNode request, String field, boolean required) {
        JsonNode node = request == null ? null : request.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            if (required) {
                throw validation(field + " is required.");
            }
            return null;
        }
        return node.asText();
    }

    private static List<JsonNode> nodes(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(out::add);
        }
        return out;
    }

    private static void copy(JsonNode from, ObjectNode to, String field) {
        if (from.has(field)) {
            to.set(field, from.get(field).deepCopy());
        }
    }

    private static double timestampFilter(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            try {
                return Instant.parse(value).toEpochMilli() / 1000.0d;
            } catch (Exception ignored) {
                throw validation("Time filters must be valid timestamps.");
            }
        }
    }

    private String accountId() {
        return requestContext == null || requestContext.getAccountId() == null
                ? "000000000000" : requestContext.getAccountId();
    }

    private static void validateRegion(String region) {
        if (!REGION.equals(region)) {
            throw validation("AWS Marketplace Agreement API is available only in us-east-1.");
        }
    }

    private static double epoch() {
        return Instant.now().toEpochMilli() / 1000.0d;
    }

    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String kind, String id) {
        return new AwsException("ResourceNotFoundException", kind + " " + id + " does not exist.", 400);
    }

    @Override
    public void clear() {
        agreements.clear();
        agreementRequests.clear();
        cancellationRequests.clear();
        paymentRequests.clear();
        billingAdjustments.clear();
    }
}
