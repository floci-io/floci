package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class ServiceCatalogIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "AWS242ServiceCatalogService.";
    private static final String ORGANIZATIONS_TARGET = "AWSOrganizationsV20161128.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=723679240095/20260101/us-east-1/servicecatalog/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createAndListPortfolio_returnsAwsShape() {
        String id = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePortfolio")
                .header("Authorization", AUTH)
                .body("{\"DisplayName\":\"VellumGoldenWorkloads\","
                        + "\"ProviderName\":\"Vellum Cloud Platform\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("PortfolioDetail.DisplayName", equalTo("VellumGoldenWorkloads"))
                .extract().path("PortfolioDetail.Id");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListPortfolios")
                .header("Authorization", AUTH)
                .body("{}")
                .when().post("/")
                .then().statusCode(200)
                .body("PortfolioDetails.Id", hasItem(id))
                .body("PortfolioDetails.DisplayName", hasItem("VellumGoldenWorkloads"));
    }

    @Test
    void listPortfolios_includesControlTowerAccountFactoryPortfolio() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListPortfolios")
                .header("Authorization", AUTH)
                .body("{}")
                .when().post("/")
                .then().statusCode(200)
                .body("PortfolioDetails.DisplayName",
                        hasItem(ServiceCatalogService.CONTROL_TOWER_PORTFOLIO_NAME))
                .body("PortfolioDetails.ProviderName",
                        hasItem(ServiceCatalogService.CONTROL_TOWER_PROVIDER_NAME))
                .body("PortfolioDetails.Id",
                        hasItem(ServiceCatalogService.CONTROL_TOWER_PORTFOLIO_ID));
    }

    @Test
    void updatePortfolioShare_isStoredAndImmediatelyComplete() {
        String portfolioId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePortfolio")
                .header("Authorization", AUTH)
                .body("{\"DisplayName\":\"Shared\",\"ProviderName\":\"Vellum\"}")
                .when().post("/").then().statusCode(200)
                .extract().path("PortfolioDetail.Id");

        String token = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "UpdatePortfolioShare")
                .header("Authorization", AUTH)
                .body("{\"PortfolioId\":\"" + portfolioId + "\","
                        + "\"OrganizationNode\":{\"Type\":\"ORGANIZATIONAL_UNIT\","
                        + "\"Value\":\"ou-vellum-workloads\"},\"ShareTagOptions\":true}")
                .when().post("/").then().statusCode(200)
                .body("Status", equalTo("COMPLETED"))
                .extract().path("PortfolioShareToken");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribePortfolioShareStatus")
                .header("Authorization", AUTH)
                .body("{\"PortfolioShareToken\":\"" + token + "\"}")
                .when().post("/").then().statusCode(200)
                .body("Status", equalTo("COMPLETED"));
    }

    @Test
    void searchProvisionedProducts_returnsEmptyAwsShapeWhenNoneExist() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "SearchProvisionedProducts")
                .header("Authorization", AUTH)
                .body("{\"Filters\":{\"SearchQuery\":[\"physicalId: 723679240095\"]},"
                        + "\"AccessLevelFilter\":{\"Key\":\"Account\",\"Value\":\"self\"}}")
                .when().post("/")
                .then().statusCode(200)
                .body("ProvisionedProducts", hasSize(0))
                .body("TotalResultsCount", equalTo(0));
    }

    @Test
    void searchProductsAndListArtifacts_includeControlTowerAccountFactory() {
        String productId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "SearchProducts")
                .header("Authorization", AUTH)
                .body("{\"Filters\":{\"FullTextSearch\":[\"AWS Control Tower Account Factory\"]}}")
                .when().post("/")
                .then().statusCode(200)
                .body("ProductViewSummaries", hasSize(1))
                .body("ProductViewSummaries[0].Name", equalTo(ServiceCatalogService.CONTROL_TOWER_PRODUCT_NAME))
                .extract().path("ProductViewSummaries[0].ProductId");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ListProvisioningArtifacts")
                .header("Authorization", AUTH)
                .body("{\"ProductId\":\"" + productId + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("ProvisioningArtifactDetails", hasSize(1))
                .body("ProvisioningArtifactDetails[0].Id",
                        equalTo(ServiceCatalogService.CONTROL_TOWER_ARTIFACT_ID))
                .body("ProvisioningArtifactDetails[0].Active", equalTo(true));
    }

    @Test
    void provisionControlTowerProduct_createsAccountAndPersistsAvailableProduct() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", ORGANIZATIONS_TARGET + "CreateOrganization")
                .header("Authorization", AUTH)
                .body("{\"FeatureSet\":\"ALL\"}")
                .when().post("/").then().statusCode(200);

        String rootId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", ORGANIZATIONS_TARGET + "ListRoots")
                .header("Authorization", AUTH)
                .body("{}")
                .when().post("/").then().statusCode(200)
                .extract().path("Roots[0].Id");
        String ouId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", ORGANIZATIONS_TARGET + "CreateOrganizationalUnit")
                .header("Authorization", AUTH)
                .body("{\"ParentId\":\"" + rootId + "\",\"Name\":\"Workloads\"}")
                .when().post("/").then().statusCode(200)
                .extract().path("OrganizationalUnit.Id");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "ProvisionProduct")
                .header("Authorization", AUTH)
                .body("{\"ProductName\":\"AWS Control Tower Account Factory\","
                        + "\"ProvisioningArtifactId\":\"" + ServiceCatalogService.CONTROL_TOWER_ARTIFACT_ID + "\","
                        + "\"ProvisionedProductName\":\"ServiceCatalogWorkload\","
                        + "\"ProvisionToken\":\"token-1\",\"ProvisioningParameters\":["
                        + "{\"Key\":\"AccountName\",\"Value\":\"ServiceCatalogWorkload\"},"
                        + "{\"Key\":\"AccountEmail\",\"Value\":\"servicecatalog-workload@floci.test\"},"
                        + "{\"Key\":\"ManagedOrganizationalUnit\","
                        + "\"Value\":\"Workloads (" + ouId + ")\"}]}")
                .when().post("/")
                .then().statusCode(200)
                .body("RecordDetail.Status", equalTo("SUCCEEDED"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "SearchProvisionedProducts")
                .header("Authorization", AUTH)
                .body("{\"Filters\":{\"SearchQuery\":[\"status: AVAILABLE\"]},"
                        + "\"AccessLevelFilter\":{\"Key\":\"Account\",\"Value\":\"self\"}}")
                .when().post("/")
                .then().statusCode(200)
                .body("ProvisionedProducts", hasSize(1))
                .body("ProvisionedProducts[0].Type", equalTo("CONTROL_TOWER_ACCOUNT"))
                .body("ProvisionedProducts[0].Status", equalTo("AVAILABLE"));
    }

    @Test
    void acceptPortfolioShare_withExistingPortfolio_returnsEmptyBody() {
        String portfolioId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePortfolio")
                .header("Authorization", AUTH)
                .body("{\"DisplayName\":\"ShareAccept\",\"ProviderName\":\"Vellum\"}")
                .when().post("/").then().statusCode(200)
                .extract().path("PortfolioDetail.Id");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "AcceptPortfolioShare")
                .header("Authorization", AUTH)
                .body("{\"PortfolioId\":\"" + portfolioId + "\"}")
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    void acceptPortfolioShare_withoutPortfolioId_returnsAwsError() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "AcceptPortfolioShare")
                .header("Authorization", AUTH)
                .body("{}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParametersException"))
                .body("message", equalTo("PortfolioId is required"));
    }

    @Test
    void rejectPortfolioShare_withExistingPortfolio_returnsEmptyBody() {
        String portfolioId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePortfolio")
                .header("Authorization", AUTH)
                .body("{\"DisplayName\":\"ShareReject\",\"ProviderName\":\"Vellum\"}")
                .when().post("/").then().statusCode(200)
                .extract().path("PortfolioDetail.Id");

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "RejectPortfolioShare")
                .header("Authorization", AUTH)
                .body("{\"PortfolioId\":\"" + portfolioId + "\"}")
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    void rejectPortfolioShare_withoutPortfolioId_returnsAwsError() {
        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "RejectPortfolioShare")
                .header("Authorization", AUTH)
                .body("{}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidParametersException"))
                .body("message", equalTo("PortfolioId is required"));
    }

    @Test
    void createDescribeAndDeletePortfolioShare_removesShare() {
        String portfolioId = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePortfolio")
                .header("Authorization", AUTH)
                .body("{\"DisplayName\":\"ShareRoundTrip\",\"ProviderName\":\"Vellum\"}")
                .when().post("/").then().statusCode(200)
                .extract().path("PortfolioDetail.Id");

        String accountId = "123456789012";

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "CreatePortfolioShare")
                .header("Authorization", AUTH)
                .body("{\"PortfolioId\":\"" + portfolioId + "\",\"AccountId\":\"" + accountId + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Status", equalTo("COMPLETED"));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribePortfolioShares")
                .header("Authorization", AUTH)
                .body("{\"PortfolioId\":\"" + portfolioId + "\",\"Type\":\"ACCOUNT\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("PortfolioShareDetails", hasSize(1))
                .body("PortfolioShareDetails[0].PrincipalId", equalTo(accountId));

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DeletePortfolioShare")
                .header("Authorization", AUTH)
                .body("{\"PortfolioId\":\"" + portfolioId + "\",\"AccountId\":\"" + accountId + "\"}")
                .when().post("/")
                .then().statusCode(200);

        given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + "DescribePortfolioShares")
                .header("Authorization", AUTH)
                .body("{\"PortfolioId\":\"" + portfolioId + "\",\"Type\":\"ACCOUNT\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("PortfolioShareDetails", hasSize(0));
    }
}
