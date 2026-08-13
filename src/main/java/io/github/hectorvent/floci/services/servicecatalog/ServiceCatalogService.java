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
import io.github.hectorvent.floci.services.organizations.model.OrgAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public void deletePortfolio(String id) {
        require(portfolioStore, id, "portfolio");
        portfolioStore.delete(id);
        associationStore.keys().stream()
                .filter(key -> associationStore.get(key)
                        .map(value -> id.equals(text(value, "PortfolioId"))).orElse(false))
                .toList().forEach(associationStore::delete);
        shareStore.keys().stream().filter(key -> key.startsWith(id + "|"))
                .toList().forEach(shareStore::delete);
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
        ObjectNode existing = provisionedProductStore.scan(key -> true).stream()
                .filter(product -> name.equals(text(product, "Name")))
                .findFirst().orElse(null);
        if (existing != null) {
            return existing.deepCopy();
        }
        Map<String, String> parameters = new java.util.HashMap<>();
        request.path("ProvisioningParameters").forEach(parameter ->
                parameters.put(text(parameter, "Key"), text(parameter, "Value")));
        String email = parameters.get("AccountEmail");
        String accountName = parameters.getOrDefault("AccountName", name);
        OrgAccount account = organizationsService.listAccounts(accountId).stream()
                .filter(candidate -> email != null && email.equalsIgnoreCase(candidate.getEmail()))
                .findFirst().orElse(null);
        if (account == null) {
            CreateAccountStatus status = organizationsService.createAccount(
                    accountId, email, accountName, null, Map.of(), false);
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
        String provisionedId = id("pp");
        ObjectNode product = objectMapper.createObjectNode();
        product.put("Id", provisionedId);
        product.put("Arn", "arn:aws:servicecatalog:" + region + ":" + accountId
                + ":provisionedproduct/" + provisionedId);
        product.put("Name", name);
        product.put("Type", "CONTROL_TOWER_ACCOUNT");
        product.put("Status", "AVAILABLE");
        product.put("PhysicalId", account.getId());
        product.put("ProductId", CONTROL_TOWER_PRODUCT_ID);
        product.put("ProvisioningArtifactId", CONTROL_TOWER_ARTIFACT_ID);
        product.put("CreatedTime", Instant.now().toEpochMilli() / 1000.0);
        provisionedProductStore.put(provisionedId, product);
        return product.deepCopy();
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

    public void deleteProduct(String id) {
        ObjectNode product = requireProduct(id);
        String productId = text(product, "Id");
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
        require(portfolioStore, portfolioId, "portfolio");
        requireProduct(productId);
        String id = associationId("product", portfolioId, productId);
        associationStore.put(id, objectMapper.createObjectNode()
                .put("Type", "PRODUCT").put("PortfolioId", portfolioId).put("ProductId", productId));
        return id;
    }

    public void disassociateProduct(String portfolioId, String productId) {
        associationStore.delete(associationId("product", portfolioId, productId));
    }

    public String associateTagOption(String resourceId, String tagOptionId) {
        require(tagOptionStore, tagOptionId, "TagOption");
        if (portfolioStore.get(resourceId).isEmpty()) {
            requireProduct(resourceId);
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
        JsonNode orgNode = request.path("OrganizationNode");
        String target = request.hasNonNull("AccountId")
                ? "ACCOUNT:" + request.get("AccountId").asText()
                : orgNode.path("Type").asText() + ":" + orgNode.path("Value").asText();
        if (target.equals(":")) {
            throw new AwsException("InvalidParametersException",
                    "AccountId or OrganizationNode is required", 400);
        }
        String token = id("share");
        ObjectNode share = copy(request);
        share.put("PortfolioShareToken", token);
        share.put("Status", "COMPLETED");
        shareStore.put(portfolioId + "|" + target, share);
        return share.deepCopy();
    }

    public ObjectNode describePortfolioShareStatus(String token) {
        return shareStore.scan(key -> true).stream()
                .filter(share -> token.equals(text(share, "PortfolioShareToken")))
                .findFirst().map(ObjectNode::deepCopy)
                .orElseThrow(() -> notFound("portfolio share", token));
    }

    private ObjectNode requireProduct(String identifier) {
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
        return store.get(id).orElseThrow(() -> notFound(type, id));
    }

    private AwsException notFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", "Unknown " + type + ": " + id, 400);
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
}
