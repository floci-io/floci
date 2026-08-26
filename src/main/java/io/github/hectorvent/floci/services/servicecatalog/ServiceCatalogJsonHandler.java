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
import java.util.List;

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
                case "DescribeProduct" -> describeProductForUser(request);
                case "DescribePortfolioShares" -> describePortfolioShares(request);
                case "ListPortfoliosForProduct" -> listPortfoliosForProduct(text(request, "ProductId"));
                case "CopyProduct" -> copyProductResponse(service.copyProduct(request, region, accountId));
                case "DescribeProvisioningArtifact" -> describeProvisioningArtifact(request);
                case "AcceptPortfolioShare" -> empty(() -> service.acceptPortfolioShare(text(request, "PortfolioId")));
                case "DeletePortfolioShare" -> deletePortfolioShare(request);
                case "RejectPortfolioShare" -> empty(() -> service.rejectPortfolioShare(text(request, "PortfolioId")));
                case "AssociateBudgetWithResource" -> empty(() -> service.associateBudgetWithResource(
                text(request, "BudgetName"), text(request, "ResourceId")));
                case "AssociatePrincipalWithPortfolio" -> empty(() -> service.associatePrincipal(
                text(request, "PortfolioId"), text(request, "PrincipalARN"), text(request, "PrincipalType")));
                case "AssociateServiceActionWithProvisioningArtifact" -> empty(() -> service.associateServiceActionWithProvisioningArtifact(
                text(request, "ProductId"), text(request, "ProvisioningArtifactId"), text(request, "ServiceActionId")));
                case "BatchAssociateServiceActionWithProvisioningArtifact" -> batchAssociateServiceActionWithProvisioningArtifact(request);
                case "BatchDisassociateServiceActionFromProvisioningArtifact" -> batchDisassociateServiceActionFromProvisioningArtifact(request);
                case "CreateConstraint" -> createConstraintResponse(request, region, accountId);
                case "CreateProvisionedProductPlan" -> createProvisionedProductPlan(request, region, accountId);
                case "CreateServiceAction" -> createServiceActionResponse(service.createServiceAction(request));
                case "DeleteConstraint" -> empty(() -> service.deleteConstraint(text(request, "Id")));
                case "DeleteProvisionedProductPlan" -> empty(() -> service.deleteProvisionedProductPlan(text(request, "PlanId")));
                case "DeleteProvisioningArtifact" -> empty(() -> service.deleteProvisioningArtifact(
                text(request, "ProductId"), text(request, "ProvisioningArtifactId")));
                case "DeleteServiceAction" -> empty(() -> service.deleteServiceAction(text(request, "Id")));
                case "DescribeConstraint" -> describeConstraint(request);
                case "DescribeProductView" -> describeProductView(request);
                case "DescribeProvisionedProduct" -> describeProvisionedProduct(request);
                case "DescribeProvisionedProductPlan" -> describeProvisionedProductPlan(request);
                case "DescribeProvisioningParameters" -> describeProvisioningParameters(request);
                case "DescribeRecord" -> describeRecord(request);
                case "DescribeServiceActionExecutionParameters" -> describeServiceActionExecutionParameters(request);
                case "DisableAWSOrganizationsAccess" -> empty(() -> {});
                case "DisassociateBudgetFromResource" -> empty(() -> service.disassociateBudgetFromResource(
                text(request, "BudgetName"), text(request, "ResourceId")));
                case "DisassociatePrincipalFromPortfolio" -> empty(() -> service.disassociatePrincipal(
                text(request, "PortfolioId"), text(request, "PrincipalARN")));
                case "DisassociateServiceActionFromProvisioningArtifact" -> empty(() -> service.disassociateServiceActionFromProvisioningArtifact(
                text(request, "ProductId"), text(request, "ProvisioningArtifactId"), text(request, "ServiceActionId")));
                case "EnableAWSOrganizationsAccess" -> Response.ok(objectMapper.createObjectNode()).build();
                case "ExecuteProvisionedProductPlan" -> executeProvisionedProductPlan(request);
                case "ExecuteProvisionedProductServiceAction" -> executeProvisionedProductServiceAction(request, region, accountId);
                case "GetAWSOrganizationsAccessStatus" -> getAWSOrganizationsAccessStatus();
                case "GetProvisionedProductOutputs" -> getProvisionedProductOutputs(request);
                case "ImportAsProvisionedProduct" -> importAsProvisionedProduct(request, region, accountId);
                case "ListAcceptedPortfolioShares" -> listPortfolios(region, accountId);
                case "ListBudgetsForResource" -> listBudgetsForResource(text(request, "ResourceId"));
                case "ListConstraintsForPortfolio" -> listConstraintsForPortfolio(text(request, "PortfolioId"), text(request, "ProductId"));
                case "ListLaunchPaths" -> listLaunchPaths(request);
                case "ListOrganizationPortfolioAccess" -> listOrganizationPortfolioAccess(request);
                case "ListPortfolioAccess" -> listPortfolioAccess(request);
                case "ListPrincipalsForPortfolio" -> listPrincipalsForPortfolio(text(request, "PortfolioId"));
                case "ListProvisionedProductPlans" -> listProvisionedProductPlans(request);
                case "ListProvisioningArtifactsForServiceAction" -> listProvisioningArtifactsForServiceAction(request);
                case "ListRecordHistory" -> listRecordHistory();
                case "ListResourcesForTagOption" -> listResourcesForTagOption(request);
                case "ListServiceActions" -> listServiceActions();
                case "ListServiceActionsForProvisioningArtifact" -> listServiceActionsForProvisioningArtifact(request);
                case "ListStackInstancesForProvisionedProduct" -> listStackInstancesForProvisionedProduct(request);
                case "NotifyProvisionProductEngineWorkflowResult" -> empty(() -> service.notifyProvisionProductEngineWorkflowResult(request));
                case "NotifyTerminateProvisionedProductEngineWorkflowResult" -> empty(() ->
                service.notifyTerminateProvisionedProductEngineWorkflowResult(request));
                case "NotifyUpdateProvisionedProductEngineWorkflowResult" -> empty(() -> service.notifyUpdateProvisionedProductEngineWorkflowResult(request));
                case "ScanProvisionedProducts" -> scanProvisionedProducts(request);
                case "UpdateConstraint" -> updateConstraint(request, region, accountId);
                case "UpdateProvisionedProduct" -> updateProvisionedProduct(request, region, accountId);
                case "UpdateProvisionedProductProperties" -> updateProvisionedProductProperties(request);
                case "UpdateServiceAction" -> updateServiceAction(request);
                case "CreateProvisioningArtifact" -> createProvisioningArtifact(request, region, accountId);
                case "DescribeCopyProductStatus" -> describeCopyProductStatus(request);
                case "TerminateProvisionedProduct" -> terminateProvisionedProduct(request, region, accountId);
                case "UpdateProvisioningArtifact" -> updateProvisioningArtifact(request);
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

    private Response describeProductForUser(JsonNode request) {
        String id = text(request, "Id");
        if (id == null || id.isBlank()) {
            id = text(request, "Name");
        }
        ObjectNode product = service.describeProduct(id);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProductViewSummary", productView(product).path("ProductViewSummary"));
        ArrayNode artifacts = response.putArray("ProvisioningArtifacts");
        JsonNode ids = product.path("ProvisioningArtifactIds");
        JsonNode names = product.path("ProvisioningArtifactNames");
        for (int i = 0; i < ids.size(); i++) {
            artifacts.add(objectMapper.createObjectNode()
                    .put("Id", ids.get(i).asText())
                    .put("Name", i < names.size() ? names.get(i).asText() : "")
                    .put("CreatedTime", product.path("CreatedTime").asDouble(0.0)));
        }
        return Response.ok(response).build();
    }

    private Response describePortfolioShares(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("PortfolioShareDetails");
        String type = text(request, "Type");
        // Validated only when supplied: botocore marks Type required, but this emulator has
        // always treated it as an optional filter and tests pin that leniency (lesson 3's
        // territory). What matters here is that a supplied value is a real share type
        // rather than silently filtering everything out.
        if (type != null) {
            ServiceCatalogService.requireEnum(type, "Type",
                    ServiceCatalogService.DESCRIBE_PORTFOLIO_SHARE_TYPES);
        }
        service.describePortfolioShares(text(request, "PortfolioId"), type).forEach(details::add);
        return Response.ok(response).build();
    }

    private Response listPortfoliosForProduct(String productId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("PortfolioDetails");
        service.listPortfoliosForProduct(productId).forEach(details::add);
        return Response.ok(response).build();
    }

    private Response copyProductResponse(String token) {
        return Response.ok(objectMapper.createObjectNode().put("CopyProductToken", token)).build();
    }

    private Response describeProvisioningArtifact(JsonNode request) {
        String artifactId = text(request, "ProvisioningArtifactId");
        String productId = text(request, "ProductId");
        String artifactName = text(request, "ProvisioningArtifactName");
        ObjectNode product = service.describeProduct(productId != null ? productId : artifactId);
        JsonNode ids = product.path("ProvisioningArtifactIds");
        JsonNode names = product.path("ProvisioningArtifactNames");
        int index = -1;
        if (artifactId != null) {
            for (int i = 0; i < ids.size(); i++) {
                if (artifactId.equals(ids.get(i).asText())) {
                    index = i;
                    break;
                }
            }
        } else if (artifactName != null) {
            for (int i = 0; i < names.size(); i++) {
                if (artifactName.equals(names.get(i).asText())) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            throw new AwsException("ResourceNotFoundException",
                    "Unknown provisioning artifact: " + (artifactId != null ? artifactId : artifactName), 400);
        }
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("Id", ids.get(index).asText());
        detail.put("Name", index < names.size() ? names.get(index).asText() : "");
        detail.put("Type", "CLOUD_FORMATION_TEMPLATE");
        detail.put("CreatedTime", product.path("CreatedTime").asDouble());
        detail.put("Active", true);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProvisioningArtifactDetail", detail);
        response.put("Status", "AVAILABLE");
        return Response.ok(response).build();
    }

    private Response deletePortfolioShare(JsonNode request) {
        String token = service.deletePortfolioShare(request);
        ObjectNode response = objectMapper.createObjectNode();
        if (token != null) {
            response.put("PortfolioShareToken", token);
        }
        return Response.ok(response).build();
    }

    private Response batchAssociateServiceActionWithProvisioningArtifact(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode failed = response.putArray("FailedServiceActionAssociations");
        service.batchAssociateServiceActionWithProvisioningArtifact(request).forEach(failed::add);
        return Response.ok(response).build();
    }

    private Response batchDisassociateServiceActionFromProvisioningArtifact(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        var failures = service.batchDisassociateServiceActionFromProvisioningArtifact(request);
        if (!failures.isEmpty()) {
            ArrayNode failed = response.putArray("FailedServiceActionAssociations");
            failures.forEach(failed::add);
        }
        return Response.ok(response).build();
    }

    private Response createConstraintResponse(JsonNode request, String region, String accountId) {
        ObjectNode constraint = service.createConstraint(request, region, accountId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ConstraintDetail", constraint);
        response.set("ConstraintParameters", request.path("Parameters"));
        response.put("Status", "AVAILABLE");
        return Response.ok(response).build();
    }

    private Response createProvisionedProductPlan(JsonNode request, String region, String accountId) {
        ObjectNode plan = service.createProvisionedProductPlan(request, region, accountId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PlanId", plan.path("PlanId").asText());
        response.put("PlanName", plan.path("PlanName").asText());
        response.put("ProvisionProductId", plan.path("ProvisionProductId").asText());
        response.put("ProvisionedProductName", plan.path("ProvisionedProductName").asText());
        response.put("ProvisioningArtifactId", plan.path("ProvisioningArtifactId").asText());
        return Response.ok(response).build();
    }

    private Response createServiceActionResponse(ObjectNode action) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("Id", action.path("Id").asText());
        summary.put("Name", action.path("Name").asText());
        summary.put("DefinitionType", action.path("DefinitionType").asText());
        summary.put("Description", action.path("Description").asText());
        ObjectNode detail = objectMapper.createObjectNode();
        detail.set("Definition", action.path("Definition"));
        detail.set("ServiceActionSummary", summary);
        return Response.ok(objectMapper.createObjectNode().set("ServiceActionDetail", detail)).build();
    }

    private Response describeConstraint(JsonNode request) {
        ObjectNode constraint = service.describeConstraint(text(request, "Id"));
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("ConstraintId", constraint.path("ConstraintId").asText());
        detail.put("Description", constraint.path("Description").asText());
        detail.put("Owner", constraint.path("Owner").asText());
        detail.put("PortfolioId", constraint.path("PortfolioId").asText());
        detail.put("ProductId", constraint.path("ProductId").asText());
        detail.put("Type", constraint.path("Type").asText());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ConstraintDetail", detail);
        response.put("Status", "AVAILABLE");
        return Response.ok(response).build();
    }

    private Response describeProductView(JsonNode request) {
        String id = text(request, "Id");
        if (id == null || id.isBlank()) {
            throw new AwsException("InvalidParametersException", "Id is required", 400);
        }
        ObjectNode product = service.describeProduct(id);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProductViewSummary", productView(product).path("ProductViewSummary"));
        ArrayNode artifacts = response.putArray("ProvisioningArtifacts");
        JsonNode ids = product.path("ProvisioningArtifactIds");
        JsonNode names = product.path("ProvisioningArtifactNames");
        for (int i = 0; i < ids.size(); i++) {
            artifacts.add(objectMapper.createObjectNode()
                    .put("Id", ids.get(i).asText())
                    .put("Name", i < names.size() ? names.get(i).asText() : "")
                    .put("CreatedTime", product.path("CreatedTime").asDouble(0.0)));
        }
        return Response.ok(response).build();
    }

    private Response describeProvisionedProduct(JsonNode request) {
        ObjectNode detail = service.describeProvisionedProduct(text(request, "Id"), text(request, "Name"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProvisionedProductDetail", detail);
        return Response.ok(response).build();
    }

    private Response describeProvisionedProductPlan(JsonNode request) {
        ObjectNode plan = service.describeProvisionedProductPlan(text(request, "PlanId"));
        ObjectNode details = objectMapper.createObjectNode();
        details.put("PlanId", plan.path("Id").asText());
        if (plan.has("PlanName")) {
            details.put("PlanName", plan.get("PlanName").asText());
        }
        if (plan.has("ProductId")) {
            details.put("ProductId", plan.get("ProductId").asText());
        }
        if (plan.has("ProvisionProductId")) {
            details.put("ProvisionProductId", plan.get("ProvisionProductId").asText());
        }
        if (plan.has("ProvisionedProductName")) {
            details.put("ProvisionProductName", plan.get("ProvisionedProductName").asText());
        }
        if (plan.has("ProvisioningArtifactId")) {
            details.put("ProvisioningArtifactId", plan.get("ProvisioningArtifactId").asText());
        }
        if (plan.has("CreatedTime")) {
            details.put("CreatedTime", plan.get("CreatedTime").asDouble());
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProvisionedProductPlanDetails", details);
        return Response.ok(response).build();
    }

    private Response describeProvisioningParameters(JsonNode request) {
        ObjectNode product = service.describeProvisioningParameters(request);
        ObjectNode response = objectMapper.createObjectNode();
        JsonNode storedParams = product.path("ProvisioningArtifactParameters");
        if (storedParams.isArray() && !storedParams.isEmpty()) {
            ArrayNode params = response.putArray("ProvisioningArtifactParameters");
            for (JsonNode param : storedParams) {
                ObjectNode p = objectMapper.createObjectNode();
                if (param.has("Name")) {
                    p.put("ParameterKey", param.get("Name").asText());
                }
                if (param.has("Description")) {
                    p.put("Description", param.get("Description").asText());
                }
                if (param.has("Type")) {
                    p.put("ParameterType", param.get("Type").asText());
                }
                params.add(p);
            }
        }
        return Response.ok(response).build();
    }

    private Response describeRecord(JsonNode request) {
        ObjectNode record = service.describeRecord(text(request, "Id"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RecordDetail", record);
        return Response.ok(response).build();
    }

    private Response describeServiceActionExecutionParameters(JsonNode request) {
        service.describeServiceActionExecutionParameters(
                text(request, "ProvisionedProductId"), text(request, "ServiceActionId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ServiceActionParameters");
        return Response.ok(response).build();
    }

    private Response executeProvisionedProductPlan(JsonNode request) {
        ObjectNode record = service.executeProvisionedProductPlan(request);
        return Response.ok(objectMapper.createObjectNode().set("RecordDetail", record)).build();
    }

    private Response executeProvisionedProductServiceAction(JsonNode request, String region, String accountId) {
        ObjectNode product = service.executeProvisionedProductServiceAction(request, region, accountId);
        ObjectNode record = objectMapper.createObjectNode();
        record.put("RecordId", product.path("RecordId").asText());
        record.put("ProvisionedProductId", product.path("Id").asText());
        record.put("ProvisionedProductName", product.path("Name").asText());
        record.put("ProvisionedProductType", product.path("Type").asText());
        record.put("ProductId", product.path("ProductId").asText());
        record.put("ProvisioningArtifactId", product.path("ProvisioningArtifactId").asText());
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", System.currentTimeMillis() / 1000.0);
        record.put("UpdatedTime", System.currentTimeMillis() / 1000.0);
        return Response.ok(objectMapper.createObjectNode().set("RecordDetail", record)).build();
    }

    private Response getAWSOrganizationsAccessStatus() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AccessStatus", "ENABLED");
        return Response.ok(response).build();
    }

    private Response getProvisionedProductOutputs(JsonNode request) {
        service.getProvisionedProductOutputs(request);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Outputs");
        return Response.ok(response).build();
    }

    private Response importAsProvisionedProduct(JsonNode request, String region, String accountId) {
        ObjectNode detail = service.importAsProvisionedProduct(request, region, accountId);
        ObjectNode record = objectMapper.createObjectNode();
        record.put("RecordId", detail.path("RecordId").asText());
        record.put("ProvisionedProductId", detail.path("Id").asText());
        record.put("ProvisionedProductName", detail.path("Name").asText());
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", System.currentTimeMillis() / 1000.0);
        record.put("ProductId", detail.path("ProductId").asText());
        record.put("ProvisioningArtifactId", detail.path("ProvisioningArtifactId").asText());
        record.put("RecordType", "IMPORT");
        return Response.ok(objectMapper.createObjectNode().set("RecordDetail", record)).build();
    }

    private Response listBudgetsForResource(String resourceId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode budgets = response.putArray("Budgets");
        service.listBudgetsForResource(resourceId).forEach(assoc ->
                budgets.add(objectMapper.createObjectNode().put("BudgetName", text(assoc, "BudgetName"))));
        return Response.ok(response).build();
    }

    private Response listConstraintsForPortfolio(String portfolioId, String productId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("ConstraintDetails");
        service.listConstraintsForPortfolio(portfolioId, productId).forEach(details::add);
        return Response.ok(response).build();
    }

    private Response listLaunchPaths(JsonNode request) {
        String productId = text(request, "ProductId");
        if (productId == null || productId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProductId is required", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("LaunchPathSummaries");
        service.listPortfoliosForProduct(productId).forEach(portfolio -> {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("Id", portfolio.path("Id").asText());
            summary.put("Name", portfolio.path("DisplayName").asText());
            if (portfolio.has("Tags")) {
                summary.set("Tags", portfolio.get("Tags").deepCopy());
            }
            summaries.add(summary);
        });
        return Response.ok(response).build();
    }

    private Response listOrganizationPortfolioAccess(JsonNode request) {
        String nodeType = text(request, "OrganizationNodeType");
        if (nodeType == null || nodeType.isBlank()) {
            throw new AwsException("InvalidParametersException", "OrganizationNodeType is required", 400);
        }
        ServiceCatalogService.requireEnum(nodeType, "OrganizationNodeType",
                ServiceCatalogService.ORGANIZATION_NODE_TYPES);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode nodes = response.putArray("OrganizationNodes");
        service.describePortfolioShares(text(request, "PortfolioId"), nodeType).forEach(share -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Type", share.path("Type").asText());
            node.put("Value", share.path("PrincipalId").asText());
            nodes.add(node);
        });
        return Response.ok(response).build();
    }

    private Response listPortfolioAccess(JsonNode request) {
        String portfolioId = text(request, "PortfolioId");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ids = response.putArray("AccountIds");
        service.listPortfolioAccess(portfolioId, text(request, "OrganizationParentId")).forEach(ids::add);
        return Response.ok(response).build();
    }

    private Response listPrincipalsForPortfolio(String portfolioId) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode principals = response.putArray("Principals");
        service.listPrincipalsForPortfolio(portfolioId).forEach(principals::add);
        return Response.ok(response).build();
    }

    private Response listProvisionedProductPlans(JsonNode request) {
        String provisionProductId = text(request, "ProvisionProductId");
        int pageSize = 20;
        JsonNode pageSizeNode = request.get("PageSize");
        if (pageSizeNode != null && pageSizeNode.isInt()) {
            pageSize = Math.max(0, Math.min(20, pageSizeNode.asInt()));
        }
        String pageToken = text(request, "PageToken");
        int offset = 0;
        if (pageToken != null && !pageToken.isBlank()) {
            try {
                offset = Integer.parseInt(pageToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidParametersException", "Invalid PageToken: " + pageToken, 400);
            }
        }

        var all = service.listProvisionedProductPlans(provisionProductId);
        var page = all.stream().skip(offset).limit(pageSize).toList();

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode plans = response.putArray("ProvisionedProductPlans");
        for (ObjectNode product : page) {
            ObjectNode plan = objectMapper.createObjectNode();
            plan.put("PlanId", text(product, "Id"));
            plan.put("PlanName", text(product, "PlanName"));
            plan.put("PlanType", "CLOUDFORMATION");
            plan.put("ProvisionProductId", text(product, "ProvisionProductId"));
            plan.put("ProvisionProductName", text(product, "ProvisionedProductName"));
            plan.put("ProvisioningArtifactId", text(product, "ProvisioningArtifactId"));
            plans.add(plan);
        }
        int nextOffset = offset + page.size();
        if (nextOffset < all.size()) {
            response.put("NextPageToken", String.valueOf(nextOffset));
        }
        return Response.ok(response).build();
    }

    private Response listProvisioningArtifactsForServiceAction(JsonNode request) {
        String serviceActionId = text(request, "ServiceActionId");
        if (serviceActionId == null || serviceActionId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ServiceActionId is required", 400);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ProvisioningArtifactViews");
        return Response.ok(response).build();
    }

    private Response listRecordHistory() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("RecordDetails");
        service.listRecordHistory().forEach(product -> {
            ObjectNode record = objectMapper.createObjectNode();
            record.put("ProvisionedProductId", product.path("Id").asText());
            record.put("ProvisionedProductName", product.path("Name").asText());
            record.put("ProvisionedProductType", product.path("Type").asText());
            record.put("ProductId", product.path("ProductId").asText());
            record.put("ProvisioningArtifactId", product.path("ProvisioningArtifactId").asText());
            record.put("CreatedTime", product.path("CreatedTime").asDouble());
            record.put("Status", "SUCCEEDED");
            details.add(record);
        });
        return Response.ok(response).build();
    }

    private Response listResourcesForTagOption(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("ResourceDetails");
        service.listResourcesForTagOption(text(request, "TagOptionId"), text(request, "ResourceType")).forEach(details::add);
        return Response.ok(response).build();
    }

    private Response listServiceActions() {
        ObjectNode response = objectMapper.createObjectNode();
        var summaries = response.putArray("ServiceActionSummaries");
        service.listServiceActions().forEach(action -> {
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("Id", action.path("Id").asText());
            if (action.has("Name")) {
                summary.put("Name", action.get("Name").asText());
            }
            if (action.has("Description")) {
                summary.put("Description", action.get("Description").asText());
            }
            summary.put("DefinitionType", "SSM_AUTOMATION");
            summaries.add(summary);
        });
        return Response.ok(response).build();
    }

    private Response listServiceActionsForProvisioningArtifact(JsonNode request) {
        String productId = text(request, "ProductId");
        if (productId == null || productId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProductId is required", 400);
        }
        String artifactId = text(request, "ProvisioningArtifactId");
        if (artifactId == null || artifactId.isBlank()) {
            throw new AwsException("InvalidParametersException", "ProvisioningArtifactId is required", 400);
        }
        ObjectNode product = service.describeProduct(productId);
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
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("ServiceActionSummaries");
        return Response.ok(response).build();
    }

    private Response listStackInstancesForProvisionedProduct(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode instances = response.putArray("StackInstances");
        service.listStackInstancesForProvisionedProduct(text(request, "ProvisionedProductId")).forEach(instances::add);
        return Response.ok(response).build();
    }

    private Response scanProvisionedProducts(JsonNode request) {
        List<ObjectNode> all = service.scanProvisionedProducts();
        int pageSize = request.path("PageSize").asInt(0);
        int offset = 0;
        String pageToken = text(request, "PageToken");
        if (pageToken != null && !pageToken.isBlank()) {
            try {
                offset = Integer.parseInt(pageToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidParametersException", "Invalid PageToken: " + pageToken, 400);
            }
        }
        int start = Math.min(offset, all.size());
        int end = pageSize > 0 ? Math.min(start + pageSize, all.size()) : all.size();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode products = response.putArray("ProvisionedProducts");
        for (int i = start; i < end; i++) {
            products.add(all.get(i));
        }
        if (end < all.size()) {
            response.put("NextPageToken", String.valueOf(end));
        }
        return Response.ok(response).build();
    }

    private Response updateConstraint(JsonNode request, String region, String accountId) {
        String id = text(request, "Id");
        ObjectNode constraint = service.updateConstraint(id, request);
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("ConstraintId", id);
        if (constraint.has("Description")) {
            detail.put("Description", constraint.get("Description").asText());
        }
        detail.put("Owner", accountId);
        detail.put("PortfolioId", text(constraint, "PortfolioId"));
        detail.put("ProductId", text(constraint, "ProductId"));
        detail.put("Type", text(constraint, "Type"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ConstraintDetail", detail);
        if (constraint.has("Parameters")) {
            response.set("ConstraintParameters", constraint.get("Parameters"));
        }
        response.put("Status", "AVAILABLE");
        return Response.ok(response).build();
    }

    private Response updateProvisionedProduct(JsonNode request, String region, String accountId) {
        ObjectNode product = service.updateProvisionedProduct(request, region, accountId);
        ObjectNode record = objectMapper.createObjectNode();
        record.put("RecordId", product.path("RecordId").asText());
        record.put("ProvisionedProductId", product.path("Id").asText());
        record.put("ProvisionedProductName", product.path("Name").asText());
        record.put("ProductId", product.path("ProductId").asText());
        record.put("ProvisioningArtifactId", product.path("ProvisioningArtifactId").asText());
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", product.path("CreatedTime").asDouble());
        record.put("UpdatedTime", System.currentTimeMillis() / 1000.0);
        return Response.ok(objectMapper.createObjectNode().set("RecordDetail", record)).build();
    }

    private Response updateProvisionedProductProperties(JsonNode request) {
        ObjectNode result = service.updateProvisionedProductProperties(text(request, "ProvisionedProductId"), request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ProvisionedProductId", result.path("Id").asText());
        response.set("ProvisionedProductProperties", result.path("ProvisionedProductProperties"));
        response.put("RecordId", result.path("RecordId").asText());
        response.put("Status", "SUCCEEDED");
        return Response.ok(response).build();
    }

    private Response updateServiceAction(JsonNode request) {
        ObjectNode action = service.updateServiceAction(text(request, "Id"), request);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("Id", action.path("Id").asText());
        if (action.has("Name")) {
            summary.put("Name", action.get("Name").asText());
        }
        if (action.has("Description")) {
            summary.put("Description", action.get("Description").asText());
        }
        summary.put("DefinitionType", "SSM_AUTOMATION");
        ObjectNode detail = objectMapper.createObjectNode();
        detail.set("ServiceActionSummary", summary);
        if (action.has("Definition")) {
            detail.set("Definition", action.get("Definition"));
        }
        return Response.ok(objectMapper.createObjectNode().set("ServiceActionDetail", detail)).build();
    }

    private Response createProvisioningArtifact(JsonNode request, String region, String accountId) {
        ObjectNode detail = service.createProvisioningArtifact(request, region, accountId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProvisioningArtifactDetail", detail);
        response.put("Status", "AVAILABLE");
        return Response.ok(response).build();
    }

    private Response describeCopyProductStatus(JsonNode request) {
        String token = text(request, "CopyProductToken");
        ObjectNode record = service.describeCopyProductStatus(token);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("CopyProductStatus", record.path("Status").asText("SUCCEEDED"));
        String targetProductId = record.path("TargetProductId").asText("");
        if (!targetProductId.isEmpty()) {
            response.put("TargetProductId", targetProductId);
        }
        return Response.ok(response).build();
    }

    private Response terminateProvisionedProduct(JsonNode request, String region, String accountId) {
        ObjectNode product = service.terminateProvisionedProduct(request, region, accountId);
        ObjectNode record = objectMapper.createObjectNode();
        record.put("RecordId", product.path("RecordId").asText());
        record.put("ProvisionedProductId", product.path("Id").asText());
        record.put("ProvisionedProductName", product.path("Name").asText());
        record.put("ProductId", product.path("ProductId").asText());
        record.put("ProvisioningArtifactId", product.path("ProvisioningArtifactId").asText());
        record.put("Status", "SUCCEEDED");
        record.put("CreatedTime", product.path("CreatedTime").asDouble(0.0));
        record.put("UpdatedTime", product.path("UpdatedTime").asDouble(0.0));
        record.put("RecordType", "TERMINATE_PROVISIONED_PRODUCT");
        return Response.ok(objectMapper.createObjectNode().set("RecordDetail", record)).build();
    }

    private Response updateProvisioningArtifact(JsonNode request) {
        String productId = text(request, "ProductId");
        String artifactId = text(request, "ProvisioningArtifactId");
        ObjectNode detail = service.updateProvisioningArtifact(productId, artifactId, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ProvisioningArtifactDetail", detail);
        response.put("Status", "AVAILABLE");
        return Response.ok(response).build();
    }
}
