package io.github.hectorvent.floci.services.servicecatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
public class ServiceCatalogJsonHandler {

    private static final Logger LOG = Logger.getLogger(ServiceCatalogJsonHandler.class);
    private final ServiceCatalogService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ServiceCatalogJsonHandler(ServiceCatalogService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region, String accountId) {
        try {
            return switch (action) {
                case "CreatePortfolio" -> portfolioResponse(service.createPortfolio(request, region, accountId));
                case "UpdatePortfolio" -> portfolioResponse(service.updatePortfolio(text(request, "Id"), request));
                case "DescribePortfolio" -> portfolioResponse(service.describePortfolio(text(request, "Id")));
                case "ListPortfolios" -> listPortfolios(region, accountId);
                case "DeletePortfolio" -> empty(() -> service.deletePortfolio(text(request, "Id")));
                case "CreateProduct" -> productResponse(service.createProduct(request, region, accountId));
                case "UpdateProduct" -> productResponse(service.updateProduct(text(request, "Id"), request));
                case "DescribeProductAsAdmin" -> productResponse(service.describeProduct(text(request, "Id")));
                case "SearchProductsAsAdmin" -> listProducts();
                case "SearchProducts" -> searchProducts(request, region, accountId);
                case "ListProvisioningArtifacts" -> provisioningArtifacts(text(request, "ProductId"));
                case "ProvisionProduct" -> provisionProduct(request, region, accountId);
                case "SearchProvisionedProducts" -> searchProvisionedProducts(request);
                case "DeleteProduct" -> empty(() -> service.deleteProduct(text(request, "Id")));
                case "CreateTagOption" -> tagOptionResponse(service.createTagOption(request));
                case "UpdateTagOption" -> tagOptionResponse(service.updateTagOption(text(request, "Id"), request));
                case "DescribeTagOption" -> tagOptionResponse(service.describeTagOption(text(request, "Id")));
                case "ListTagOptions" -> listTagOptions();
                case "DeleteTagOption" -> empty(() -> service.deleteTagOption(text(request, "Id")));
                case "AssociateProductWithPortfolio" -> empty(() -> service.associateProduct(
                        text(request, "PortfolioId"), text(request, "ProductId")));
                case "DisassociateProductFromPortfolio" -> empty(() -> service.disassociateProduct(
                        text(request, "PortfolioId"), text(request, "ProductId")));
                case "AssociateTagOptionWithResource" -> empty(() -> service.associateTagOption(
                        text(request, "ResourceId"), text(request, "TagOptionId")));
                case "DisassociateTagOptionFromResource" -> empty(() -> service.disassociateTagOption(
                        text(request, "ResourceId"), text(request, "TagOptionId")));
                case "UpdatePortfolioShare", "CreatePortfolioShare" -> shareResponse(
                        service.updatePortfolioShare(request));
                case "DescribePortfolioShareStatus" -> shareStatusResponse(
                        service.describePortfolioShareStatus(text(request, "PortfolioShareToken")));
                default -> Response.status(400).entity(new AwsErrorResponse(
                        "InvalidParametersException", "Operation " + action + " is not supported.")).build();
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Service Catalog error processing %s", action);
            return Response.status(500).entity(new AwsErrorResponse("InternalFailure", e.getMessage())).build();
        }
    }

    private Response listPortfolios(String region, String accountId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("PortfolioDetails");
        service.listPortfolios(region, accountId).forEach(details::add);
        return Response.ok(response).build();
    }

    private Response portfolioResponse(ObjectNode portfolio) {
        return Response.ok(objectMapper.createObjectNode().set("PortfolioDetail", portfolio)).build();
    }

    private Response listProducts() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("ProductViewDetails");
        service.listProducts().forEach(product -> details.add(productView(product)));
        return Response.ok(response).build();
    }

    private Response searchProducts(JsonNode request, String region, String accountId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("ProductViewSummaries");
        service.searchProducts(request, region, accountId).forEach(product ->
                summaries.add(productView(product).path("ProductViewSummary")));
        return Response.ok(response).build();
    }

    private Response provisioningArtifacts(String productId) {
        ObjectNode product = service.describeProduct(productId);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode artifacts = response.putArray("ProvisioningArtifactDetails");
        JsonNode ids = product.path("ProvisioningArtifactIds");
        JsonNode names = product.path("ProvisioningArtifactNames");
        for (int i = 0; i < ids.size(); i++) {
            artifacts.add(objectMapper.createObjectNode().put("Id", ids.get(i).asText())
                    .put("Name", i < names.size() ? names.get(i).asText() : "")
                    .put("Active", true).put("Type", "CLOUD_FORMATION_TEMPLATE"));
        }
        return Response.ok(response).build();
    }

    private Response provisionProduct(JsonNode request, String region, String accountId) {
        ObjectNode detail = service.provisionProduct(request, region, accountId);
        ObjectNode record = objectMapper.createObjectNode();
        record.put("RecordId", "rec-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        record.put("ProvisionedProductId", detail.path("Id").asText());
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", System.currentTimeMillis() / 1000.0);
        return Response.ok(objectMapper.createObjectNode().set("RecordDetail", record)).build();
    }

    private Response searchProvisionedProducts(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode products = response.putArray("ProvisionedProducts");
        service.searchProvisionedProducts(request).forEach(products::add);
        response.put("TotalResultsCount", products.size());
        return Response.ok(response).build();
    }

    private Response productResponse(ObjectNode product) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProductViewDetail", productView(product));
        ArrayNode artifacts = response.putArray("ProvisioningArtifactDetails");
        JsonNode ids = product.path("ProvisioningArtifactIds");
        JsonNode names = product.path("ProvisioningArtifactNames");
        for (int i = 0; i < ids.size(); i++) {
            artifacts.add(objectMapper.createObjectNode().put("Id", ids.get(i).asText())
                    .put("Name", i < names.size() ? names.get(i).asText() : "")
                    .put("Active", true).put("Type", "CLOUD_FORMATION_TEMPLATE"));
        }
        return Response.ok(response).build();
    }

    private ObjectNode productView(ObjectNode product) {
        ObjectNode summary = product.deepCopy();
        summary.put("ProductId", product.path("Id").asText());
        ObjectNode view = objectMapper.createObjectNode();
        view.set("ProductViewSummary", summary);
        view.put("Status", "AVAILABLE");
        return view;
    }

    private Response listTagOptions() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("TagOptionDetails");
        service.listTagOptions().forEach(details::add);
        return Response.ok(response).build();
    }

    private Response tagOptionResponse(ObjectNode option) {
        return Response.ok(objectMapper.createObjectNode().set("TagOptionDetail", option)).build();
    }

    private Response shareResponse(ObjectNode share) {
        return Response.ok(objectMapper.createObjectNode()
                .put("PortfolioShareToken", share.path("PortfolioShareToken").asText())
                .put("Status", share.path("Status").asText())).build();
    }

    private Response shareStatusResponse(ObjectNode share) {
        return Response.ok(objectMapper.createObjectNode()
                .put("PortfolioShareToken", share.path("PortfolioShareToken").asText())
                .put("Status", share.path("Status").asText())).build();
    }

    private Response empty(Runnable operation) {
        operation.run();
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
