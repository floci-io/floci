package io.github.hectorvent.floci.services.servicecatalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.OrganizationAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

@ApplicationScoped
public class ServiceCatalogService {

    static final String CONTROL_TOWER_PORTFOLIO_ID = "port-flocicontrol1";
    static final String CONTROL_TOWER_PORTFOLIO_NAME = "AWS Control Tower Account Factory Portfolio";
    static final String CONTROL_TOWER_PROVIDER_NAME = "AWS Control Tower";
    static final String CONTROL_TOWER_PRODUCT_ID = "prod-flocicontrol1";
    static final String CONTROL_TOWER_PRODUCT_NAME = "AWS Control Tower Account Factory";
    static final String CONTROL_TOWER_ARTIFACT_ID = "pa-flocicontrol1";

    private final StorageBackend<String, ObjectNode> portfolioStore;
    private final StorageBackend<String, ObjectNode> productStore;
    private final StorageBackend<String, ObjectNode> tagOptionStore;
    private final StorageBackend<String, ObjectNode> associationStore;
    private final StorageBackend<String, ObjectNode> shareStore;
    private final StorageBackend<String, ObjectNode> provisionedProductStore;
    private final ObjectMapper objectMapper;
    private final OrganizationsService organizationsService;

    @Inject
    public ServiceCatalogService(StorageFactory storageFactory, ObjectMapper objectMapper,
                                 OrganizationsService organizationsService) {
        this.portfolioStore = storageFactory.create("servicecatalog", "servicecatalog-portfolios.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.productStore = storageFactory.create("servicecatalog", "servicecatalog-products.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.tagOptionStore = storageFactory.create("servicecatalog", "servicecatalog-tag-options.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.associationStore = storageFactory.create("servicecatalog", "servicecatalog-associations.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.shareStore = storageFactory.create("servicecatalog", "servicecatalog-shares.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.provisionedProductStore = storageFactory.create("servicecatalog", "servicecatalog-provisioned-products.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.objectMapper = objectMapper;
        this.organizationsService = organizationsService;
    }

    public ObjectNode createPortfolio(JsonNode request, String region, String accountId) {
        requireText(request, "DisplayName");
        requireText(request, "ProviderName");
        String id = id("port");
        ObjectNode portfolio = copy(request);
        portfolio.put("Id", id);
        portfolio.put("ARN", "arn:aws:catalog:" + region + ":" + accountId + ":portfolio/" + id);
        portfolio.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        portfolioStore.put(id, portfolio);
        return portfolio.deepCopy();
    }

    public ObjectNode updatePortfolio(String id, JsonNode request) {
        ObjectNode portfolio = require(portfolioStore, id, "portfolio");
        copyIfPresent(request, portfolio, "DisplayName", "ProviderName", "Description", "Tags");
        portfolioStore.put(id, portfolio);
        return portfolio.deepCopy();
    }

    public ObjectNode describePortfolio(String id) {
        return require(portfolioStore, id, "portfolio").deepCopy();
    }

    public synchronized List<ObjectNode> listPortfolios(String region, String accountId) {
        ensureControlTowerCatalog(region, accountId);
        return portfolioStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    private void ensureControlTowerCatalog(String region, String accountId) {
        boolean exists = portfolioStore.scan(key -> true).stream()
                .anyMatch(portfolio -> CONTROL_TOWER_PORTFOLIO_NAME.equals(text(portfolio, "DisplayName"))
                        && CONTROL_TOWER_PROVIDER_NAME.equals(text(portfolio, "ProviderName")));
        if (!exists) {
            ObjectNode portfolio = objectMapper.createObjectNode();
            portfolio.put("Id", CONTROL_TOWER_PORTFOLIO_ID);
            portfolio.put("ARN", "arn:aws:catalog:" + region + ":" + accountId
                    + ":portfolio/" + CONTROL_TOWER_PORTFOLIO_ID);
            portfolio.put("DisplayName", CONTROL_TOWER_PORTFOLIO_NAME);
            portfolio.put("ProviderName", CONTROL_TOWER_PROVIDER_NAME);
            portfolio.put("Description", "AWS Control Tower Account Factory Portfolio");
            portfolio.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
            portfolioStore.put(CONTROL_TOWER_PORTFOLIO_ID, portfolio);
        }
        if (productStore.get(CONTROL_TOWER_PRODUCT_ID).isEmpty()) {
            ObjectNode product = objectMapper.createObjectNode();
            product.put("Id", CONTROL_TOWER_PRODUCT_ID);
            product.put("ARN", "arn:aws:catalog:" + region + ":" + accountId
                    + ":product/" + CONTROL_TOWER_PRODUCT_ID);
            product.put("Name", CONTROL_TOWER_PRODUCT_NAME);
            product.put("Owner", CONTROL_TOWER_PROVIDER_NAME);
            product.put("Type", "CLOUD_FORMATION_TEMPLATE");
            product.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
            product.putArray("ProvisioningArtifactIds").add(CONTROL_TOWER_ARTIFACT_ID);
            product.putArray("ProvisioningArtifactNames").add("AWS Control Tower Account Factory");
            productStore.put(CONTROL_TOWER_PRODUCT_ID, product);
        }
        String associationId = associationId("product", CONTROL_TOWER_PORTFOLIO_ID,
                CONTROL_TOWER_PRODUCT_ID);
        if (associationStore.get(associationId).isEmpty()) {
            associationStore.put(associationId, objectMapper.createObjectNode()
                    .put("Type", "PRODUCT").put("PortfolioId", CONTROL_TOWER_PORTFOLIO_ID)
                    .put("ProductId", CONTROL_TOWER_PRODUCT_ID));
        }
    }

    /**
     * AWS refuses to delete a portfolio that "has associated products, users, constraints, or
     * shared accounts" rather than cascading. Every one of those rows carries the portfolio's
     * id in {@code PortfolioId} — products, principals and constraints alike — so a single scan
     * covers the documented list. Tag-option associations key off {@code ResourceId} instead and
     * are deliberately not counted: they are absent from AWS's list, and the previous cascade
     * never removed them either, so nothing is orphaned that was not orphaned before.
     */
    public void deletePortfolio(String id) {
        require(portfolioStore, id, "portfolio");
        boolean hasAssociations = associationStore.scan(key -> true).stream()
                .anyMatch(value -> id.equals(text(value, "PortfolioId")));
        boolean hasShares = shareStore.keys().stream().anyMatch(key -> key.startsWith(id + "|"));
        if (hasAssociations || hasShares) {
            throw inUse("portfolio", id,
                    "it has associated products, users, constraints, or shared accounts");
        }
        portfolioStore.delete(id);
    }

    public ObjectNode createProduct(JsonNode request, String region, String accountId) {
        requireText(request, "Name");
        requireText(request, "Owner");
        String id = id("prod");
        ObjectNode product = copy(request);
        product.put("Id", id);
        product.put("ARN", "arn:aws:catalog:" + region + ":" + accountId + ":product/" + id);
        product.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        ArrayNode artifactIds = product.putArray("ProvisioningArtifactIds");
        ArrayNode artifactNames = product.putArray("ProvisioningArtifactNames");
        JsonNode artifacts = request.path("ProvisioningArtifactParameters");
        if (artifacts.isArray()) {
            for (JsonNode artifact : artifacts) {
                artifactIds.add(id("pa"));
                artifactNames.add(artifact.path("Name").asText(""));
            }
        }
        if (artifactIds.isEmpty()) {
            artifactIds.add(id("pa"));
            artifactNames.add("v1");
        }
        productStore.put(id, product);
        return product.deepCopy();
    }

    public ObjectNode updateProduct(String id, JsonNode request) {
        ObjectNode product = requireProduct(id);
        copyIfPresent(request, product, "Name", "Owner", "Description", "Distributor", "SupportDescription",
                "SupportEmail", "SupportUrl", "Tags", "ProvisioningArtifactParameters");
        productStore.put(text(product, "Id"), product);
        return product.deepCopy();
    }

    public ObjectNode describeProduct(String id) {
        return requireProduct(id).deepCopy();
    }

    public List<ObjectNode> listProducts() {
        return productStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    public synchronized List<ObjectNode> searchProducts(JsonNode request, String region, String accountId) {
        ensureControlTowerCatalog(region, accountId);
        JsonNode terms = request.path("Filters").path("FullTextSearch");
        return productStore.scan(key -> true).stream()
                .filter(product -> !terms.isArray() || terms.isEmpty()
                        || java.util.stream.StreamSupport.stream(terms.spliterator(), false)
                        .allMatch(term -> (text(product, "Name") + " " + text(product, "Owner"))
                                .toLowerCase().contains(term.asText().toLowerCase())))
                .map(ObjectNode::deepCopy).toList();
    }

    public ObjectNode provisionProduct(JsonNode request, String region, String accountId) {
        ensureControlTowerCatalog(region, accountId);
        String name = requireText(request, "ProvisionedProductName");
        ObjectNode product = resolveProduct(request);
        String productId = text(product, "Id");
        String artifactId = resolveArtifactId(product, request);
        ObjectNode existing = provisionedProductStore.scan(key -> true).stream()
                .filter(candidate -> name.equals(text(candidate, "Name")))
                .findFirst().orElse(null);
        if (existing != null) {
            return existing.deepCopy();
        }
        String provisionedId = id("pp");
        ObjectNode provisioned = objectMapper.createObjectNode();
        provisioned.put("Id", provisionedId);
        provisioned.put("Arn", "arn:aws:servicecatalog:" + region + ":" + accountId
                + ":provisionedproduct/" + provisionedId);
        provisioned.put("Name", name);
        if (CONTROL_TOWER_PRODUCT_ID.equals(productId)) {
            provisioned.put("Type", "CONTROL_TOWER_ACCOUNT");
            provisioned.put("PhysicalId", accountFactoryAccountId(request, name, accountId));
        } else {
            provisioned.put("Type", "CFN_STACK");
        }
        provisioned.put("Status", "AVAILABLE");
        provisioned.put("ProductId", productId);
        provisioned.put("ProvisioningArtifactId", artifactId);
        provisioned.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        provisionedProductStore.put(provisionedId, provisioned);
        return provisioned.deepCopy();
    }

    /**
     * Runs the Control Tower Account Factory side of a provision: finds or creates the
     * Organizations member account the provisioning parameters describe, moves it to the
     * requested OU, and returns its account id.
     */
    private String accountFactoryAccountId(JsonNode request, String name, String accountId) {
        Map<String, String> parameters = new java.util.HashMap<>();
        request.path("ProvisioningParameters").forEach(parameter ->
                parameters.put(text(parameter, "Key"), text(parameter, "Value")));
        String email = parameters.get("AccountEmail");
        if (email == null || email.isBlank()) {
            // Without this, Organizations' validateEmail raises InvalidInputException — an
            // Organizations shape a servicecatalog client cannot deserialize. Keep every error
            // a Service Catalog caller sees inside the Service Catalog model, the same way the
            // createAccount failure below is translated.
            throw new AwsException("InvalidParametersException",
                    "AccountEmail is required in ProvisioningParameters for " + CONTROL_TOWER_PRODUCT_NAME, 400);
        }
        String accountName = parameters.getOrDefault("AccountName", name);
        OrganizationAccount account = organizationsService.listAccounts(accountId).stream()
                .filter(candidate -> email != null && email.equalsIgnoreCase(candidate.getEmail()))
                .findFirst().orElse(null);
        if (account == null) {
            CreateAccountStatus status = organizationsService.createAccount(
                    accountId, email, accountName, Map.of(), false);
            if (!"SUCCEEDED".equals(status.getState())) {
                throw new AwsException("InvalidParametersException",
                        "Control Tower account creation failed: " + status.getFailureReason(), 400);
            }
            account = organizationsService.describeAccount(accountId, status.getAccountId());
        }
        String destinationParent = organizationalUnitId(parameters.get("ManagedOrganizationalUnit"));
        if (destinationParent != null && !destinationParent.equals(account.getParentId())) {
            organizationsService.moveAccount(accountId, account.getId(), account.getParentId(), destinationParent);
        }
        return account.getId();
    }

    private ObjectNode resolveProduct(JsonNode request) {
        String productId = text(request, "ProductId");
        if (productId != null && !productId.isBlank()) {
            // Strict id-only lookup: requireProduct also matches provisioning-artifact ids,
            // which would let an artifact id in ProductId resolve its owning product.
            return productStore.get(productId)
                    .orElseThrow(() -> notFound("product", productId));
        }
        String productName = text(request, "ProductName");
        if (productName != null && !productName.isBlank()) {
            // Names are not unique keys: an ambiguous name must fail rather than
            // first-match into an arbitrary product (which could be, or displace,
            // the Account Factory product).
            List<ObjectNode> matches = productStore.scan(key -> true).stream()
                    .filter(product -> productName.equals(text(product, "Name")))
                    .toList();
            if (matches.size() > 1) {
                throw new AwsException("DuplicateResourceException",
                        "More than one product matches name: " + productName, 400);
            }
            if (matches.isEmpty()) {
                throw notFound("product", productName);
            }
            return matches.get(0);
        }
        throw new AwsException("InvalidParametersException", "ProductId or ProductName is required", 400);
    }

    /**
     * Resolves the provisioning artifact a request targets, rejecting one the product does
     * not own. Neither identifier supplied means the product's first artifact.
     */
    private String resolveArtifactId(ObjectNode product, JsonNode request) {
        JsonNode ids = product.path("ProvisioningArtifactIds");
        JsonNode names = product.path("ProvisioningArtifactNames");
        String artifactId = text(request, "ProvisioningArtifactId");
        String artifactName = text(request, "ProvisioningArtifactName");
        if (artifactId != null && !artifactId.isBlank()) {
            for (JsonNode candidate : ids) {
                if (artifactId.equals(candidate.asText())) {
                    return artifactId;
                }
            }
            throw new AwsException("ResourceNotFoundException",
                    "Unknown provisioning artifact: " + artifactId, 400);
        }
        if (artifactName != null && !artifactName.isBlank()) {
            for (int i = 0; i < names.size(); i++) {
                if (artifactName.equals(names.get(i).asText())) {
                    return i < ids.size() ? ids.get(i).asText() : null;
                }
            }
            throw new AwsException("ResourceNotFoundException",
                    "Unknown provisioning artifact: " + artifactName, 400);
        }
        return ids.isEmpty() ? null : ids.get(0).asText();
    }

    private String organizationalUnitId(String value) {
        if (value == null) {
            return null;
        }
        int open = value.lastIndexOf('(');
        if (open >= 0 && value.endsWith(")")) {
            String candidate = value.substring(open + 1, value.length() - 1).trim();
            if (candidate.startsWith("ou-")) {
                return candidate;
            }
        }
        return value;
    }

    public List<ObjectNode> searchProvisionedProducts(JsonNode request) {
        JsonNode terms = request.path("Filters").path("SearchQuery");
        return provisionedProductStore.scan(key -> true).stream()
                .filter(product -> matchesProvisionedProductTerms(product, terms))
                .map(ObjectNode::deepCopy).toList();
    }

    private boolean matchesProvisionedProductTerms(ObjectNode product, JsonNode terms) {
        if (!terms.isArray() || terms.isEmpty()) {
            return true;
        }
        for (JsonNode termNode : terms) {
            String[] parts = termNode.asText().split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            String expected = parts[1].trim();
            String actual = switch (parts[0].trim().toLowerCase()) {
                case "status" -> text(product, "Status");
                case "physicalid" -> text(product, "PhysicalId");
                default -> null;
            };
            if (actual != null && !actual.equalsIgnoreCase(expected)) {
                return false;
            }
        }
        return true;
    }

    /**
     * AWS refuses to delete a product that "is associated with a portfolio". Only the portfolio
     * association blocks — a product carrying just service-action associations or constraints is
     * still deletable, and those rows are cleaned up below as before.
     */
    public void deleteProduct(String id) {
        ObjectNode product = requireProduct(id);
        String productId = text(product, "Id");
        boolean inPortfolio = associationStore.scan(key -> true).stream()
                .anyMatch(value -> "PRODUCT".equals(text(value, "Type"))
                        && productMatches(value.path("ProductId").asText(), product));
        if (inPortfolio) {
            throw inUse("product", productId, "it is associated with a portfolio");
        }
        productStore.delete(productId);
        associationStore.keys().stream()
                .filter(key -> associationStore.get(key)
                        .map(value -> productMatches(value.path("ProductId").asText(), product)).orElse(false))
                .toList().forEach(associationStore::delete);
    }

    public ObjectNode createTagOption(JsonNode request) {
        requireText(request, "Key");
        requireText(request, "Value");
        String id = id("tag");
        ObjectNode option = copy(request);
        option.put("Id", id);
        if (!option.has("Active")) {
            option.put("Active", true);
        }
        tagOptionStore.put(id, option);
        return option.deepCopy();
    }

    public ObjectNode updateTagOption(String id, JsonNode request) {
        ObjectNode option = require(tagOptionStore, id, "TagOption");
        copyIfPresent(request, option, "Active", "Value");
        tagOptionStore.put(id, option);
        return option.deepCopy();
    }

    public ObjectNode describeTagOption(String id) {
        return require(tagOptionStore, id, "TagOption").deepCopy();
    }

    public List<ObjectNode> listTagOptions() {
        return tagOptionStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    public void deleteTagOption(String id) {
        require(tagOptionStore, id, "TagOption");
        tagOptionStore.delete(id);
        associationStore.keys().stream()
                .filter(key -> associationStore.get(key)
                        .map(value -> id.equals(text(value, "TagOptionId"))).orElse(false))
                .toList().forEach(associationStore::delete);
    }

    public String associateProduct(String portfolioId, String productId) {
        require(portfolioStore, portfolioId, "portfolio", "PortfolioId");
        requireProduct(productId, "ProductId");
        String id = associationId("product", portfolioId, productId);
        associationStore.put(id, objectMapper.createObjectNode()
                .put("Type", "PRODUCT").put("PortfolioId", portfolioId).put("ProductId", productId));
        return id;
    }

    public void disassociateProduct(String portfolioId, String productId) {
        associationStore.delete(associationId("product", portfolioId, productId));
    }

    public String associateTagOption(String resourceId, String tagOptionId) {
        require(tagOptionStore, tagOptionId, "TagOption", "TagOptionId");
        requireIdentifier(resourceId, "ResourceId");
        if (portfolioStore.get(resourceId).isEmpty()) {
            requireProduct(resourceId, "ResourceId");
        }
        String id = associationId("tag", resourceId, tagOptionId);
        associationStore.put(id, objectMapper.createObjectNode()
                .put("Type", "TAG_OPTION").put("ResourceId", resourceId).put("TagOptionId", tagOptionId));
        return id;
    }

    public void disassociateTagOption(String resourceId, String tagOptionId) {
        associationStore.delete(associationId("tag", resourceId, tagOptionId));
    }

    public ObjectNode updatePortfolioShare(JsonNode request) {
        String portfolioId = requireText(request, "PortfolioId");
        require(portfolioStore, portfolioId, "portfolio");
        String target = shareTarget(request);
        String token = id("share");
        ObjectNode share = copy(request);
        share.put("PortfolioShareToken", token);
        share.put("Status", "COMPLETED");
        shareStore.put(portfolioId + "|" + target, share);
        return share.deepCopy();
    }

    public ObjectNode describePortfolioShareStatus(String token) {
        requireIdentifier(token, "PortfolioShareToken");
        return shareStore.scan(key -> true).stream()
                .filter(share -> token.equals(text(share, "PortfolioShareToken")))
                .findFirst().map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("portfolio share", token));
    }

    private ObjectNode requireProduct(String identifier) {
        return requireProduct(identifier, "Id");
    }

    private ObjectNode requireProduct(String identifier, String parameter) {
        requireIdentifier(identifier, parameter);
        ObjectNode direct = productStore.get(identifier).orElse(null);
        if (direct != null) {
            return direct;
        }
        return productStore.scan(key -> true).stream()
                .filter(product -> productMatches(identifier, product))
                .findFirst().orElseThrow(() -> notFound("product", identifier));
    }

    private boolean productMatches(String identifier, ObjectNode product) {
        if (identifier.equals(text(product, "Id"))) {
            return true;
        }
        JsonNode ids = product.path("ProvisioningArtifactIds");
        return ids.isArray() && java.util.stream.StreamSupport.stream(ids.spliterator(), false)
                .anyMatch(id -> identifier.equals(id.asText()));
    }

    private ObjectNode require(StorageBackend<String, ObjectNode> store, String id, String type) {
        return require(store, id, type, "Id");
    }

    private ObjectNode require(StorageBackend<String, ObjectNode> store, String id, String type,
                               String parameter) {
        requireIdentifier(id, parameter);
        return store.get(id).orElseThrow(() -> notFound(type, id));
    }

    private void requireIdentifier(String id, String parameter) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParametersException", parameter + " is required", 400);
        }
    }

    /** botocore {@code OrganizationNodeType}. */
    static final List<String> ORGANIZATION_NODE_TYPES =
            List.of("ORGANIZATION", "ORGANIZATIONAL_UNIT", "ACCOUNT");

    /** botocore {@code DescribePortfolioShareType} — a superset of the node types above. */
    static final List<String> DESCRIBE_PORTFOLIO_SHARE_TYPES =
            List.of("ACCOUNT", "ORGANIZATION", "ORGANIZATIONAL_UNIT", "ORGANIZATION_MEMBER_ACCOUNT");

    private static final java.util.regex.Pattern ACCOUNT_ID_PATTERN =
            java.util.regex.Pattern.compile("[0-9]{12}");

    /**
     * Rejects a value botocore does not list in the member's enum. Accepting one leaves the
     * emulator holding state AWS would never have created — a portfolio share keyed by an
     * unmodelled node type is reported back by DescribePortfolioShares as though real.
     */
    static void requireEnum(String value, String field, List<String> allowed) {
        if (!allowed.contains(value)) {
            throw new AwsException("InvalidParametersException",
                    field + " must be one of " + String.join(", ", allowed) + ": " + value, 400);
        }
    }

    /**
     * Resolves the {@code AccountId} / {@code OrganizationNode} pair the portfolio-share
     * operations key their store on, enforcing the constraints botocore pins on each:
     * {@code AccountId} matches {@code ^[0-9]{12}$} and {@code OrganizationNode.Type} is an
     * {@code OrganizationNodeType}. Both used to be concatenated into the key unchecked.
     */
    private String shareTarget(JsonNode request) {
        if (request.hasNonNull("AccountId")) {
            String accountId = request.get("AccountId").asText();
            if (!ACCOUNT_ID_PATTERN.matcher(accountId).matches()) {
                throw new AwsException("InvalidParametersException",
                        "AccountId must be a 12-digit AWS account id: " + accountId, 400);
            }
            return "ACCOUNT:" + accountId;
        }
        JsonNode orgNode = request.path("OrganizationNode");
        String type = orgNode.path("Type").asText();
        String value = orgNode.path("Value").asText();
        if (type.isEmpty() && value.isEmpty()) {
            throw new AwsException("InvalidParametersException",
                    "AccountId or OrganizationNode is required", 400);
        }
        requireEnum(type, "OrganizationNode.Type", ORGANIZATION_NODE_TYPES);
        return type + ":" + value;
    }

    private AwsException notFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", "Unknown " + type + ": " + id, 400);
    }

    /** 400 matches every other error this service raises; the shape models no status of its own. */
    private AwsException inUse(String type, String id, String reason) {
        return new AwsException("ResourceInUseException",
                "Cannot delete " + type + " " + id + " because " + reason + ".", 400);
    }

    private String requireText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidParametersException", field + " is required", 400);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private ObjectNode copy(JsonNode node) {
        return node != null && node.isObject()
                ? ((ObjectNode) node).deepCopy() : objectMapper.createObjectNode();
    }

    private void copyIfPresent(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            if (source.has(field)) {
                target.set(field, source.get(field).deepCopy());
            }
        }
    }

    private String associationId(String type, String left, String right) {
        return type + "|" + left + "|" + right;
    }

    private String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
    }

    public List<ObjectNode> describePortfolioShares(String portfolioId, String type) {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PortfolioId is required", 400);
        }
        require(portfolioStore, portfolioId, "portfolio");
        String prefix = portfolioId + "|";
        List<ObjectNode> results = new java.util.ArrayList<>();
        for (String key : shareStore.keys()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            shareStore.get(key).ifPresent(share -> {
                String target = key.substring(prefix.length());
                int colon = target.indexOf(':');
                String shareType = colon >= 0 ? target.substring(0, colon) : target;
                String principalId = colon >= 0 ? target.substring(colon + 1) : "";
                if (type != null && !type.equals(shareType)) {
                    return;
                }
                ObjectNode result = objectMapper.createObjectNode();
                result.put("PrincipalId", principalId);
                result.put("Type", shareType);
                result.put("Accepted", true);
                result.put("ShareTagOptions", false);
                result.put("SharePrincipals", false);
                results.add(result);
            });
        }
        return results;
    }

    public List<ObjectNode> listPortfoliosForProduct(String productId) {
        requireProduct(productId, "ProductId");
        return associationStore.scan(key -> true).stream()
                .filter(assoc -> "PRODUCT".equals(text(assoc, "Type"))
                        && productId.equals(text(assoc, "ProductId")))
                .map(assoc -> text(assoc, "PortfolioId"))
                .distinct()
                .map(portfolioStore::get)
                .flatMap(Optional::stream)
                .map(ObjectNode::deepCopy)
                .toList();
    }

    public String copyProduct(JsonNode request, String region, String accountId) {
        String sourceArn = requireText(request, "SourceProductArn");
        String sourceId = sourceArn.substring(sourceArn.lastIndexOf('/') + 1);
        ObjectNode source = requireProduct(sourceId);
        String newId = id("prod");
        ObjectNode product = source.deepCopy();
        product.put("Id", newId);
        product.put("ARN", "arn:aws:catalog:" + region + ":" + accountId + ":product/" + newId);
        product.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        String targetName = text(request, "TargetProductName");
        if (targetName != null && !targetName.isBlank()) {
            product.put("Name", targetName);
        }
        productStore.put(newId, product);
        String token = id("copy");
        ObjectNode status = objectMapper.createObjectNode();
        status.put("Status", "SUCCEEDED");
        status.put("TargetProductId", newId);
        associationStore.put(token, status);
        return token;
    }

    // no new service method required

    public void acceptPortfolioShare(String portfolioId) {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PortfolioId is required", 400);
        }
        require(portfolioStore, portfolioId, "portfolio");
    }

    public String deletePortfolioShare(JsonNode request) {
        String portfolioId = requireText(request, "PortfolioId");
        require(portfolioStore, portfolioId, "portfolio");
        String key = portfolioId + "|" + shareTarget(request);
        String token = shareStore.get(key).map(share -> text(share, "PortfolioShareToken")).orElse(null);
        shareStore.delete(key);
        return token;
    }

    public void rejectPortfolioShare(String portfolioId) {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PortfolioId is required", 400);
        }
        require(portfolioStore, portfolioId, "portfolio");
    }

    public void associateBudgetWithResource(String budgetName, String resourceId) {
        if (budgetName == null || budgetName.isBlank()) {
            throw new AwsException("InvalidParametersException", "BudgetName is required", 400);
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ResourceId is required", 400);
        }
        if (portfolioStore.get(resourceId).isEmpty()) {
            requireProduct(resourceId);
        }
        String id = associationId("budget", resourceId, budgetName);
        if (associationStore.get(id).isPresent()) {
            throw new AwsException("DuplicateResourceException",
                    "Budget " + budgetName + " is already associated with resource " + resourceId, 400);
        }
        associationStore.put(id, objectMapper.createObjectNode()
                .put("Type", "BUDGET").put("ResourceId", resourceId).put("BudgetName", budgetName));
    }

    public void associatePrincipal(String portfolioId, String principalArn, String principalType) {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PortfolioId is required", 400);
        }
        if (principalArn == null || principalArn.isBlank()) {
            throw new AwsException("InvalidParametersException", "PrincipalARN is required", 400);
        }
        if (principalType == null || principalType.isBlank()) {
            throw new AwsException("InvalidParametersException", "PrincipalType is required", 400);
        }
        requireEnum(principalType, "PrincipalType", List.of("IAM", "IAM_PATTERN"));
        require(portfolioStore, portfolioId, "portfolio");
        String id = associationId("principal", portfolioId, principalArn);
        associationStore.put(id, objectMapper.createObjectNode()
                .put("Type", "PRINCIPAL").put("PortfolioId", portfolioId)
                .put("PrincipalARN", principalArn).put("PrincipalType", principalType));
    }

    public void associateServiceActionWithProvisioningArtifact(String productId, String provisioningArtifactId, String serviceActionId) {
        if (productId == null || productId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProductId is required", 400);
        }
        if (provisioningArtifactId == null || provisioningArtifactId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProvisioningArtifactId is required", 400);
        }
        if (serviceActionId == null || serviceActionId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ServiceActionId is required", 400);
        }
        ObjectNode product = requireProduct(productId);
        JsonNode artifactIds = product.path("ProvisioningArtifactIds");
        boolean found = artifactIds.isArray() && java.util.stream.StreamSupport.stream(artifactIds.spliterator(), false)
                .anyMatch(id -> provisioningArtifactId.equals(id.asText()));
        if (!found) {
            throw new AwsException("ResourceNotFoundException", "Unknown provisioning artifact: " + provisioningArtifactId, 400);
        }
        String id = associationId("service_action", productId, provisioningArtifactId + "|" + serviceActionId);
        if (associationStore.get(id).isPresent()) {
            throw new AwsException("DuplicateResourceException",
                    "Service action " + serviceActionId + " is already associated with provisioning artifact " + provisioningArtifactId, 400);
        }
        associationStore.put(id, objectMapper.createObjectNode()
                .put("Type", "SERVICE_ACTION")
                .put("ProductId", productId)
                .put("ProvisioningArtifactId", provisioningArtifactId)
                .put("ServiceActionId", serviceActionId));
    }

    public List<ObjectNode> batchAssociateServiceActionWithProvisioningArtifact(JsonNode request) {
        JsonNode associations = request.path("ServiceActionAssociations");
        if (!associations.isArray() || associations.isEmpty()) {
            throw new AwsException("InvalidParametersException", "ServiceActionAssociations is required", 400);
        }
        List<ObjectNode> failures = new java.util.ArrayList<>();
        for (JsonNode assoc : associations) {
            String productId = text(assoc, "ProductId");
            String artifactId = text(assoc, "ProvisioningArtifactId");
            String serviceActionId = text(assoc, "ServiceActionId");
            if (productId == null || productId.isBlank() || artifactId == null || artifactId.isBlank()
                    || serviceActionId == null || serviceActionId.isBlank()) {
                failures.add(serviceActionFailure(assoc, "INVALID_PARAMETER",
                        "ProductId, ProvisioningArtifactId, and ServiceActionId are required"));
                continue;
            }
            ObjectNode product;
            try {
                product = requireProduct(productId);
            } catch (AwsException e) {
                failures.add(serviceActionFailure(assoc, "RESOURCE_NOT_FOUND", "Unknown product: " + productId));
                continue;
            }
            JsonNode artifactIds = product.path("ProvisioningArtifactIds");
            boolean artifactFound = artifactIds.isArray() && java.util.stream.StreamSupport
                    .stream(artifactIds.spliterator(), false)
                    .anyMatch(id -> artifactId.equals(id.asText()));
            if (!artifactFound) {
                failures.add(serviceActionFailure(assoc, "RESOURCE_NOT_FOUND",
                        "Unknown provisioning artifact: " + artifactId));
                continue;
            }
            String id = associationId("service_action", productId, artifactId + "|" + serviceActionId);
            associationStore.put(id, objectMapper.createObjectNode()
                    .put("Type", "SERVICE_ACTION").put("ProductId", productId)
                    .put("ProvisioningArtifactId", artifactId).put("ServiceActionId", serviceActionId));
        }
        return failures;
    }

    private ObjectNode serviceActionFailure(JsonNode assoc, String code, String message) {
        ObjectNode failure = objectMapper.createObjectNode();
        failure.put("ErrorCode", code);
        failure.put("ErrorMessage", message);
        if (text(assoc, "ProductId") != null) {
            failure.put("ProductId", text(assoc, "ProductId"));
        }
        if (text(assoc, "ProvisioningArtifactId") != null) {
            failure.put("ProvisioningArtifactId", text(assoc, "ProvisioningArtifactId"));
        }
        if (text(assoc, "ServiceActionId") != null) {
            failure.put("ServiceActionId", text(assoc, "ServiceActionId"));
        }
        return failure;
    }

    public List<ObjectNode> batchDisassociateServiceActionFromProvisioningArtifact(JsonNode request) {
        JsonNode associations = request.path("ServiceActionAssociations");
        if (!associations.isArray() || associations.isEmpty()) {
            throw new AwsException("InvalidParametersException", "ServiceActionAssociations is required", 400);
        }
        List<ObjectNode> failures = new java.util.ArrayList<>();
        for (JsonNode assoc : associations) {
            String productId = text(assoc, "ProductId");
            String artifactId = text(assoc, "ProvisioningArtifactId");
            String serviceActionId = text(assoc, "ServiceActionId");
            if (productId == null || productId.isBlank() || artifactId == null || artifactId.isBlank()
                    || serviceActionId == null || serviceActionId.isBlank()) {
                ObjectNode failure = objectMapper.createObjectNode();
                failure.put("ErrorCode", "INVALID_PARAMETER");
                failure.put("ErrorMessage", "ProductId, ProvisioningArtifactId, and ServiceActionId are required");
                if (productId != null) failure.put("ProductId", productId);
                if (artifactId != null) failure.put("ProvisioningArtifactId", artifactId);
                if (serviceActionId != null) failure.put("ServiceActionId", serviceActionId);
                failures.add(failure);
                continue;
            }
            String key = associationId("service_action", productId, artifactId + "|" + serviceActionId);
            if (associationStore.get(key).isEmpty()) {
                ObjectNode failure = objectMapper.createObjectNode();
                failure.put("ErrorCode", "RESOURCE_NOT_FOUND");
                failure.put("ErrorMessage", "Service action association not found for product " + productId
                        + ", artifact " + artifactId + ", service action " + serviceActionId);
                failure.put("ProductId", productId);
                failure.put("ProvisioningArtifactId", artifactId);
                failure.put("ServiceActionId", serviceActionId);
                failures.add(failure);
            } else {
                associationStore.delete(key);
            }
        }
        return failures;
    }

    public ObjectNode createConstraint(JsonNode request, String region, String accountId) {
        String portfolioId = requireText(request, "PortfolioId");
        String productId = requireText(request, "ProductId");
        requireText(request, "Parameters");
        requireText(request, "Type");
        requireText(request, "IdempotencyToken");
        require(portfolioStore, portfolioId, "portfolio");
        requireProduct(productId);
        String constraintId = id("con");
        ObjectNode constraint = objectMapper.createObjectNode();
        constraint.put("ConstraintId", constraintId);
        constraint.put("PortfolioId", portfolioId);
        constraint.put("ProductId", productId);
        constraint.put("Type", text(request, "Type"));
        constraint.put("Owner", accountId);
        if (request.has("Description")) {
            constraint.put("Description", text(request, "Description"));
        }
        associationStore.put(constraintId, constraint);
        return constraint.deepCopy();
    }

    public ObjectNode createProvisionedProductPlan(JsonNode request, String region, String accountId) {
        requireText(request, "PlanName");
        // ProvisionedProductPlanType has exactly one member; the plan is always described
        // as CLOUDFORMATION, so accepting another value would report a type never asked for.
        requireEnum(requireText(request, "PlanType"), "PlanType", List.of("CLOUDFORMATION"));
        String productId = requireText(request, "ProductId");
        requireText(request, "ProvisionedProductName");
        String artifactId = requireText(request, "ProvisioningArtifactId");
        requireText(request, "IdempotencyToken");
        ObjectNode product = requireProduct(productId);
        JsonNode ids = product.path("ProvisioningArtifactIds");
        boolean found = false;
        for (JsonNode id : ids) {
            if (artifactId.equals(id.asText())) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AwsException("ResourceNotFoundException",
                    "Unknown provisioning artifact: " + artifactId, 400);
        }
        String planId = id("plan");
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("Type", "PROVISIONED_PRODUCT_PLAN");
        plan.put("Id", planId);
        plan.put("PlanId", planId);
        plan.put("Name", text(request, "ProvisionedProductName"));
        plan.put("PlanName", text(request, "PlanName"));
        plan.put("ProductId", productId);
        plan.put("ProvisionProductId", productId);
        plan.put("ProvisionedProductName", text(request, "ProvisionedProductName"));
        plan.put("ProvisioningArtifactId", artifactId);
        plan.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        associationStore.put(planId, plan);
        return plan.deepCopy();
    }

    public ObjectNode createServiceAction(JsonNode request) {
        requireText(request, "Name");
        // ServiceActionDefinitionType has exactly one member.
        requireEnum(requireText(request, "DefinitionType"), "DefinitionType", List.of("SSM_AUTOMATION"));
        requireText(request, "IdempotencyToken");
        JsonNode definition = request.get("Definition");
        if (definition == null || definition.isNull() || !definition.isObject()) {
            throw new AwsException("InvalidParametersException", "Definition is required", 400);
        }
        String id = id("serv");
        ObjectNode action = objectMapper.createObjectNode();
        action.put("Id", id);
        action.put("Name", text(request, "Name"));
        action.put("DefinitionType", text(request, "DefinitionType"));
        action.put("Description", text(request, "Description") != null ? text(request, "Description") : "");
        action.set("Definition", definition.deepCopy());
        action.put("Type", "SERVICE_ACTION");
        associationStore.put(id, action);
        return action.deepCopy();
    }

    public void deleteConstraint(String id) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParametersException", "Id is required", 400);
        }
        require(associationStore, id, "constraint");
        associationStore.delete(id);
    }

    public void deleteProvisionedProductPlan(String planId) {
        if (planId == null || planId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PlanId is required", 400);
        }
        require(associationStore, planId, "provisioned product plan");
        associationStore.delete(planId);
    }

    public void deleteProvisioningArtifact(String productId, String artifactId) {
        if (productId == null || productId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProductId is required", 400);
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProvisioningArtifactId is required", 400);
        }
        ObjectNode product = requireProduct(productId);
        JsonNode ids = product.path("ProvisioningArtifactIds");
        JsonNode names = product.path("ProvisioningArtifactNames");
        int index = -1;
        for (int i = 0; i < ids.size(); i++) {
            if (artifactId.equals(ids.get(i).asText())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new AwsException("ResourceNotFoundException",
                    "Unknown provisioning artifact: " + artifactId, 400);
        }
        ((ArrayNode) ids).remove(index);
        if (index < names.size()) {
            ((ArrayNode) names).remove(index);
        }
        productStore.put(text(product, "Id"), product);
    }

    public void deleteServiceAction(String id) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParametersException", "Id is required", 400);
        }
        require(associationStore, id, "service action");
        associationStore.delete(id);
    }

    public ObjectNode describeConstraint(String id) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParametersException", "Id is required", 400);
        }
        return require(associationStore, id, "constraint").deepCopy();
    }

    public ObjectNode describeProvisionedProduct(String id, String name) {
        if (id != null && !id.isBlank()) {
            return require(provisionedProductStore, id, "provisioned product").deepCopy();
        }
        if (name != null && !name.isBlank()) {
            return provisionedProductStore.scan(key -> true).stream()
                    .filter(product -> name.equals(text(product, "Name")))
                    .findFirst().map(ObjectNode::deepCopy)
                    .orElseThrow(() -> notFound("provisioned product", name));
        }
        throw new AwsException("InvalidParametersException", "Id or Name is required", 400);
    }

    public ObjectNode describeProvisionedProductPlan(String planId) {
        if (planId == null || planId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PlanId is required", 400);
        }
        return require(associationStore, planId, "provisioned product plan").deepCopy();
    }

    public ObjectNode describeProvisioningParameters(JsonNode request) {
        ObjectNode product = resolveProduct(request);
        resolveArtifactId(product, request);
        return product.deepCopy();
    }

    public ObjectNode describeRecord(String id) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParametersException", "Id is required", 400);
        }
        return associationStore.scan(key -> true).stream()
                .filter(record -> "RECORD".equals(text(record, "Type")) && id.equals(text(record, "RecordId")))
                .findFirst().map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("record", id));
    }

    public void describeServiceActionExecutionParameters(String provisionedProductId, String serviceActionId) {
        if (provisionedProductId == null || provisionedProductId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProvisionedProductId is required", 400);
        }
        if (serviceActionId == null || serviceActionId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ServiceActionId is required", 400);
        }
        require(provisionedProductStore, provisionedProductId, "provisioned product");
    }

    public void disassociateBudgetFromResource(String budgetName, String resourceId) {
        if (budgetName == null || budgetName.isBlank()) {
            throw new AwsException("InvalidParametersException", "BudgetName is required", 400);
        }
        if (resourceId == null || resourceId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ResourceId is required", 400);
        }
        if (portfolioStore.get(resourceId).isEmpty()) {
            requireProduct(resourceId);
        }
        associationStore.delete(associationId("budget", resourceId, budgetName));
    }

    public void disassociatePrincipal(String portfolioId, String principalArn) {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PortfolioId is required", 400);
        }
        if (principalArn == null || principalArn.isBlank()) {
            throw new AwsException("InvalidParametersException", "PrincipalARN is required", 400);
        }
        require(portfolioStore, portfolioId, "portfolio");
        associationStore.delete(associationId("principal", portfolioId, principalArn));
    }

    public void disassociateServiceActionFromProvisioningArtifact(String productId, String provisioningArtifactId, String serviceActionId) {
        if (productId == null || productId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProductId is required", 400);
        }
        if (provisioningArtifactId == null || provisioningArtifactId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProvisioningArtifactId is required", 400);
        }
        if (serviceActionId == null || serviceActionId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ServiceActionId is required", 400);
        }
        ObjectNode product = requireProduct(productId);
        JsonNode artifactIds = product.path("ProvisioningArtifactIds");
        boolean found = artifactIds.isArray() && java.util.stream.StreamSupport.stream(artifactIds.spliterator(), false)
                .anyMatch(id -> provisioningArtifactId.equals(id.asText()));
        if (!found) {
            throw new AwsException("ResourceNotFoundException", "Unknown provisioning artifact: " + provisioningArtifactId, 400);
        }
        String id = associationId("service_action", productId, provisioningArtifactId + "|" + serviceActionId);
        require(associationStore, id, "service action association");
        associationStore.delete(id);
    }

    public ObjectNode executeProvisionedProductPlan(JsonNode request) {
        String planId = requireText(request, "PlanId");
        requireText(request, "IdempotencyToken");
        require(associationStore, planId, "provisioned product plan");
        ObjectNode record = objectMapper.createObjectNode();
        record.put("RecordId", "rec-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", System.currentTimeMillis() / 1000.0);
        return record;
    }

    public ObjectNode executeProvisionedProductServiceAction(JsonNode request, String region, String accountId) {
        requireText(request, "ServiceActionId");
        requireText(request, "ExecuteToken");
        String provisionedProductId = requireText(request, "ProvisionedProductId");
        ObjectNode product = provisionedProductStore.get(provisionedProductId)
                .orElseThrow(() -> notFound("provisioned product", provisionedProductId));

        String recordId = id("rec");
        ObjectNode record = objectMapper.createObjectNode();
        record.put("Type", "RECORD");
        record.put("RecordId", recordId);
        record.put("ProvisionedProductId", provisionedProductId);
        record.put("ProvisionedProductName", text(product, "Name"));
        record.put("ProvisionedProductType", text(product, "Type"));
        record.put("ProductId", text(product, "ProductId"));
        record.put("ProvisioningArtifactId", text(product, "ProvisioningArtifactId"));
        record.put("RecordType", "UPDATE_PROVISIONED_PRODUCT");
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        associationStore.put(recordId, record);

        ObjectNode result = product.deepCopy();
        result.put("RecordId", recordId);
        return result;
    }

    public void getProvisionedProductOutputs(JsonNode request) {
        String id = text(request, "ProvisionedProductId");
        if (id != null && !id.isBlank()) {
            require(provisionedProductStore, id, "provisioned product");
            return;
        }
        String name = text(request, "ProvisionedProductName");
        if (name != null && !name.isBlank()) {
            provisionedProductStore.scan(key -> true).stream()
                    .filter(product -> name.equals(text(product, "Name")))
                    .findFirst().orElseThrow(() -> notFound("provisioned product", name));
            return;
        }
        throw new AwsException("InvalidParametersException",
                "ProvisionedProductId or ProvisionedProductName is required", 400);
    }

    public ObjectNode importAsProvisionedProduct(JsonNode request, String region, String accountId) {
        String productId = requireText(request, "ProductId");
        String provisioningArtifactId = requireText(request, "ProvisioningArtifactId");
        String provisionedProductName = requireText(request, "ProvisionedProductName");
        String physicalId = requireText(request, "PhysicalId");
        requireText(request, "IdempotencyToken");
        ObjectNode product = requireProduct(productId);
        String provisionedId = id("pp");
        ObjectNode provisioned = objectMapper.createObjectNode();
        provisioned.put("Id", provisionedId);
        provisioned.put("Arn", "arn:aws:servicecatalog:" + region + ":" + accountId
                + ":provisionedproduct/" + provisionedId);
        provisioned.put("Name", provisionedProductName);
        provisioned.put("Type", "IMPORTED");
        provisioned.put("Status", "AVAILABLE");
        provisioned.put("PhysicalId", physicalId);
        provisioned.put("ProductId", productId);
        provisioned.put("ProvisioningArtifactId", provisioningArtifactId);
        provisioned.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        provisionedProductStore.put(provisionedId, provisioned);

        String recordId = id("rec");
        ObjectNode record = objectMapper.createObjectNode();
        record.put("Type", "RECORD");
        record.put("RecordId", recordId);
        record.put("ProvisionedProductId", provisionedId);
        record.put("ProvisionedProductName", provisionedProductName);
        record.put("ProductId", productId);
        record.put("ProvisioningArtifactId", provisioningArtifactId);
        record.put("RecordType", "IMPORT");
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", provisioned.get("CreatedTime").asDouble());
        associationStore.put(recordId, record);

        ObjectNode result = provisioned.deepCopy();
        result.put("RecordId", recordId);
        return result;
    }

    public List<ObjectNode> listBudgetsForResource(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ResourceId is required", 400);
        }
        if (portfolioStore.get(resourceId).isEmpty()) {
            requireProduct(resourceId);
        }
        return associationStore.scan(key -> true).stream()
                .filter(assoc -> "BUDGET".equals(text(assoc, "Type"))
                        && resourceId.equals(text(assoc, "ResourceId")))
                .map(ObjectNode::deepCopy)
                .toList();
    }

    public List<ObjectNode> listConstraintsForPortfolio(String portfolioId, String productId) {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PortfolioId is required", 400);
        }
        require(portfolioStore, portfolioId, "portfolio");
        return associationStore.scan(key -> true).stream()
                .filter(node -> portfolioId.equals(text(node, "PortfolioId")) && node.has("ConstraintId"))
                .filter(node -> productId == null || productId.isBlank() || productId.equals(text(node, "ProductId")))
                .map(ObjectNode::deepCopy)
                .toList();
    }

    public List<String> listPortfolioAccess(String portfolioId, String organizationParentId) {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PortfolioId is required", 400);
        }
        require(portfolioStore, portfolioId, "portfolio");
        String prefix = portfolioId + "|";
        List<String> accountIds = new java.util.ArrayList<>();
        for (String key : shareStore.keys()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String target = key.substring(prefix.length());
            int colon = target.indexOf(':');
            String shareType = colon >= 0 ? target.substring(0, colon) : target;
            String principalId = colon >= 0 ? target.substring(colon + 1) : "";
            if ("ACCOUNT".equals(shareType)) {
                accountIds.add(principalId);
            }
        }
        return accountIds;
    }

    public List<ObjectNode> listPrincipalsForPortfolio(String portfolioId) {
        if (portfolioId == null || portfolioId.isBlank()) {
            throw new AwsException("InvalidParametersException", "PortfolioId is required", 400);
        }
        require(portfolioStore, portfolioId, "portfolio");
        return associationStore.scan(key -> true).stream()
                .filter(assoc -> "PRINCIPAL".equals(text(assoc, "Type"))
                        && portfolioId.equals(text(assoc, "PortfolioId")))
                .map(assoc -> {
                    ObjectNode principal = objectMapper.createObjectNode();
                    principal.put("PrincipalARN", text(assoc, "PrincipalARN"));
                    principal.put("PrincipalType", text(assoc, "PrincipalType"));
                    return principal;
                })
                .toList();
    }

    public List<ObjectNode> listProvisionedProductPlans(String provisionProductId) {
        return associationStore.scan(key -> true).stream()
                .filter(node -> "PROVISIONED_PRODUCT_PLAN".equals(text(node, "Type")))
                .filter(plan -> provisionProductId == null || provisionProductId.isBlank()
                        || provisionProductId.equals(text(plan, "ProvisionProductId")))
                .map(ObjectNode::deepCopy)
                .toList();
    }

    public List<ObjectNode> listRecordHistory() {
        return provisionedProductStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    public List<ObjectNode> listResourcesForTagOption(String tagOptionId, String resourceType) {
        if (tagOptionId == null || tagOptionId.isBlank()) {
            throw new AwsException("InvalidParametersException", "TagOptionId is required", 400);
        }
        require(tagOptionStore, tagOptionId, "TagOption");
        List<ObjectNode> results = new java.util.ArrayList<>();
        for (String key : associationStore.keys()) {
            associationStore.get(key).ifPresent(assoc -> {
                if (!"TAG_OPTION".equals(text(assoc, "Type"))) {
                    return;
                }
                if (!tagOptionId.equals(text(assoc, "TagOptionId"))) {
                    return;
                }
                String resourceId = text(assoc, "ResourceId");
                ObjectNode resource = portfolioStore.get(resourceId).orElse(null);
                String type = "PORTFOLIO";
                if (resource == null) {
                    resource = productStore.get(resourceId).orElse(null);
                    type = "PRODUCT";
                }
                if (resource == null) {
                    return;
                }
                if (resourceType != null && !resourceType.isBlank() && !resourceType.equalsIgnoreCase(type)) {
                    return;
                }
                ObjectNode detail = objectMapper.createObjectNode();
                detail.put("Id", resourceId);
                detail.put("ARN", text(resource, "ARN"));
                String name = text(resource, "DisplayName");
                if (name == null) {
                    name = text(resource, "Name");
                }
                detail.put("Name", name);
                detail.put("CreatedTime", resource.path("CreatedTime").asDouble(0.0));
                String description = text(resource, "Description");
                if (description != null) {
                    detail.put("Description", description);
                }
                results.add(detail);
            });
        }
        return results;
    }

    public List<ObjectNode> listStackInstancesForProvisionedProduct(String provisionedProductId) {
        if (provisionedProductId == null || provisionedProductId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProvisionedProductId is required", 400);
        }
        ObjectNode product = require(provisionedProductStore, provisionedProductId, "provisioned product");
        ObjectNode instance = objectMapper.createObjectNode();
        instance.put("Account", text(product, "PhysicalId"));
        String arn = text(product, "Arn");
        if (arn != null) {
            String[] parts = arn.split(":");
            if (parts.length >= 4) {
                instance.put("Region", parts[3]);
            }
        }
        instance.put("StackInstanceStatus", "CURRENT");
        return List.of(instance);
    }

    public void notifyProvisionProductEngineWorkflowResult(JsonNode request) {
        requireText(request, "WorkflowToken");
        requireText(request, "RecordId");
        String status = requireText(request, "Status");
        if (!"SUCCEEDED".equals(status) && !"FAILED".equals(status)) {
            throw new AwsException("InvalidParametersException",
                    "Status must be SUCCEEDED or FAILED", 400);
        }
        requireText(request, "IdempotencyToken");
    }

    public void notifyTerminateProvisionedProductEngineWorkflowResult(JsonNode request) {
        requireText(request, "WorkflowToken");
        requireText(request, "RecordId");
        String status = requireText(request, "Status");
        if (!"SUCCEEDED".equals(status) && !"FAILED".equals(status)) {
            throw new AwsException("InvalidParametersException",
                    "Status must be SUCCEEDED or FAILED", 400);
        }
        requireText(request, "IdempotencyToken");
    }

    public void notifyUpdateProvisionedProductEngineWorkflowResult(JsonNode request) {
        requireText(request, "WorkflowToken");
        requireText(request, "RecordId");
        String status = requireText(request, "Status");
        if (!"SUCCEEDED".equals(status) && !"FAILED".equals(status)) {
            throw new AwsException("InvalidParametersException",
                    "Status must be SUCCEEDED or FAILED", 400);
        }
        requireText(request, "IdempotencyToken");
    }

    public List<ObjectNode> scanProvisionedProducts() {
        return provisionedProductStore.scan(key -> true).stream().map(ObjectNode::deepCopy).toList();
    }

    public ObjectNode updateConstraint(String id, JsonNode request) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParametersException", "Id is required", 400);
        }
        ObjectNode constraint = require(associationStore, id, "constraint");
        if (request.has("Description")) {
            constraint.put("Description", request.get("Description").asText());
        }
        if (request.has("Parameters")) {
            constraint.set("Parameters", request.get("Parameters").deepCopy());
        }
        associationStore.put(id, constraint);
        return constraint.deepCopy();
    }

    public ObjectNode updateProvisionedProduct(JsonNode request, String region, String accountId) {
        requireText(request, "UpdateToken");
        String productId = text(request, "ProvisionedProductId");
        String productName = text(request, "ProvisionedProductName");
        ObjectNode product;
        if (productId == null || productId.isBlank()) {
            if (productName == null || productName.isBlank()) {
                throw new AwsException("InvalidParametersException",
                        "ProvisionedProductId or ProvisionedProductName is required", 400);
            }
            product = provisionedProductStore.scan(key -> true).stream()
                    .filter(p -> productName.equals(text(p, "Name")))
                    .findFirst().orElseThrow(() -> notFound("provisioned product", productName));
        } else {
            product = require(provisionedProductStore, productId, "provisioned product");
        }

        String recordId = id("rec");
        ObjectNode record = objectMapper.createObjectNode();
        record.put("Type", "RECORD");
        record.put("RecordId", recordId);
        record.put("ProvisionedProductId", text(product, "Id"));
        record.put("ProvisionedProductName", text(product, "Name"));
        record.put("ProductId", text(product, "ProductId"));
        record.put("ProvisioningArtifactId", text(product, "ProvisioningArtifactId"));
        record.put("RecordType", "UPDATE_PROVISIONED_PRODUCT");
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        associationStore.put(recordId, record);

        ObjectNode result = product.deepCopy();
        result.put("RecordId", recordId);
        return result;
    }

    public ObjectNode updateProvisionedProductProperties(String provisionedProductId, JsonNode request) {
        if (provisionedProductId == null || provisionedProductId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProvisionedProductId is required", 400);
        }
        requireText(request, "IdempotencyToken");
        JsonNode properties = request.path("ProvisionedProductProperties");
        if (!properties.isObject() || properties.isEmpty()) {
            throw new AwsException("InvalidParametersException", "ProvisionedProductProperties is required", 400);
        }
        ObjectNode product = require(provisionedProductStore, provisionedProductId, "provisioned product");
        product.set("ProvisionedProductProperties", properties.deepCopy());
        provisionedProductStore.put(provisionedProductId, product);

        String recordId = id("rec");
        ObjectNode record = objectMapper.createObjectNode();
        record.put("Type", "RECORD");
        record.put("RecordId", recordId);
        record.put("ProvisionedProductId", provisionedProductId);
        record.put("ProvisionedProductName", text(product, "Name"));
        record.put("ProductId", text(product, "ProductId"));
        record.put("ProvisioningArtifactId", text(product, "ProvisioningArtifactId"));
        record.put("RecordType", "UPDATE_PROVISIONED_PRODUCT");
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        associationStore.put(recordId, record);

        ObjectNode result = product.deepCopy();
        result.put("RecordId", recordId);
        return result;
    }

    public List<ObjectNode> listServiceActions() {
        return associationStore.scan(key -> true).stream()
                .filter(node -> "SERVICE_ACTION".equals(text(node, "Type")) && node.has("Id"))
                .map(ObjectNode::deepCopy)
                .toList();
    }

    public ObjectNode updateServiceAction(String id, JsonNode request) {
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParametersException", "Id is required", 400);
        }
        ObjectNode action = associationStore.scan(key -> true).stream()
                .filter(node -> "SERVICE_ACTION".equals(text(node, "Type")) && id.equals(text(node, "Id")))
                .findFirst().orElseThrow(() -> notFound("service action", id));
        copyIfPresent(request, action, "Name", "Description", "Definition");
        associationStore.put(id, action);
        return action.deepCopy();
    }

    public ObjectNode createProvisioningArtifact(JsonNode request, String region, String accountId) {
        String productId = requireText(request, "ProductId");
        requireText(request, "IdempotencyToken");
        JsonNode parameters = request.path("Parameters");
        if (!parameters.isObject() || parameters.isEmpty()) {
            throw new AwsException("InvalidParametersException", "Parameters is required", 400);
        }
        ObjectNode product = requireProduct(productId);
        String name = text(parameters, "Name");
        if (name == null || name.isBlank()) {
            name = "v" + (product.path("ProvisioningArtifactIds").size() + 1);
        }
        String type = text(parameters, "Type");
        if (type == null || type.isBlank()) {
            type = "CLOUD_FORMATION_TEMPLATE";
        }
        String artifactId = id("pa");
        ((ArrayNode) product.path("ProvisioningArtifactIds")).add(artifactId);
        ((ArrayNode) product.path("ProvisioningArtifactNames")).add(name);
        productStore.put(text(product, "Id"), product);
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("Id", artifactId);
        detail.put("Name", name);
        detail.put("Type", type);
        detail.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        detail.put("Active", true);
        String description = text(parameters, "Description");
        if (description != null && !description.isBlank()) {
            detail.put("Description", description);
        }
        return detail;
    }

    public ObjectNode describeCopyProductStatus(String token) {
        if (token == null || token.isBlank()) {
            throw new AwsException("InvalidParametersException", "CopyProductToken is required", 400);
        }
        return associationStore.get(token).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "Unknown copy product token: " + token, 400));
    }

    public ObjectNode terminateProvisionedProduct(JsonNode request, String region, String accountId) {
        requireText(request, "TerminateToken");
        String provisionedProductId = text(request, "ProvisionedProductId");
        String provisionedProductName = text(request, "ProvisionedProductName");
        ObjectNode product = null;
        if (provisionedProductId != null && !provisionedProductId.isBlank()) {
            product = provisionedProductStore.get(provisionedProductId).orElse(null);
        }
        if (product == null && provisionedProductName != null && !provisionedProductName.isBlank()) {
            String identifier = provisionedProductName;
            if (identifier.contains(":provisionedproduct/")) {
                identifier = identifier.substring(identifier.lastIndexOf('/') + 1);
            }
            final String finalIdentifier = identifier;
            product = provisionedProductStore.scan(key -> true).stream()
                    .filter(p -> finalIdentifier.equals(text(p, "Name")) || finalIdentifier.equals(text(p, "Id")))
                    .findFirst().orElse(null);
        }
        if (product == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Unknown provisioned product: " + (provisionedProductId != null ? provisionedProductId : provisionedProductName), 400);
        }
        String id = text(product, "Id");
        product.put("Status", "TERMINATED");
        product.put("UpdatedTime", Instant.now().toEpochMilli() / 1000.0);
        provisionedProductStore.put(id, product);

        String recordId = id("rec");
        ObjectNode record = objectMapper.createObjectNode();
        record.put("Type", "RECORD");
        record.put("RecordId", recordId);
        record.put("ProvisionedProductId", id);
        record.put("ProvisionedProductName", text(product, "Name"));
        record.put("ProductId", text(product, "ProductId"));
        record.put("ProvisioningArtifactId", text(product, "ProvisioningArtifactId"));
        record.put("RecordType", "TERMINATE_PROVISIONED_PRODUCT");
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", product.get("CreatedTime").asDouble());
        record.put("UpdatedTime", product.get("UpdatedTime").asDouble());
        associationStore.put(recordId, record);

        ObjectNode result = product.deepCopy();
        result.put("RecordId", recordId);
        return result;
    }

    public ObjectNode updateProvisioningArtifact(String productId, String artifactId, JsonNode request) {
        if (productId == null || productId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProductId is required", 400);
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProvisioningArtifactId is required", 400);
        }
        ObjectNode product = requireProduct(productId);
        JsonNode ids = product.path("ProvisioningArtifactIds");
        int index = -1;
        for (int i = 0; i < ids.size(); i++) {
            if (artifactId.equals(ids.get(i).asText())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new AwsException("ResourceNotFoundException",
                    "Unknown provisioning artifact: " + artifactId, 400);
        }
        JsonNode names = product.path("ProvisioningArtifactNames");
        String newName = text(request, "Name");
        if (newName != null && !newName.isBlank()) {
            ((ArrayNode) names).set(index, objectMapper.getNodeFactory().textNode(newName));
            productStore.put(text(product, "Id"), product);
        }
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("Id", artifactId);
        detail.put("Name", index < names.size() ? names.get(index).asText() : "");
        detail.put("Active", true);
        detail.put("Type", "CLOUD_FORMATION_TEMPLATE");
        detail.put("CreatedTime", product.path("CreatedTime").asDouble());
        return detail;
    }
}
