package io.floci.aws.services.servicecatalog;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Service Catalog's resources are Regional, as on AWS: a portfolio, product, TagOption, service action,
 * constraint or provisioned product is listed and reached only in the Region it was created in, and each Region seeds
 * a Control Tower Account Factory catalog of its own. Each test works in an account of its own.
 */
@QuarkusTest
class ServiceCatalogRegionScopingIntegrationTest {

    private static final AtomicLong ACCOUNTS = new AtomicLong(732100000000L);
    private static final String HOME = "eu-west-1";
    private static final String OTHER = "eu-central-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String account;

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void accountOfItsOwn() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
    }

    @Test
    void eachResourceIsListedAndReachedOnlyInTheRegionItWasCreatedIn() {
        String portfolio = call(HOME, "CreatePortfolio", "{\"DisplayName\":\"apps\",\"ProviderName\":\"floci\"}")
                .statusCode(200).extract().path("PortfolioDetail.Id");
        cleanup.register(() -> call(HOME, "DeletePortfolio", byId(portfolio)));
        String product = createProduct(HOME);
        String tagOption = call(HOME, "CreateTagOption", "{\"Key\":\"team\",\"Value\":\"data\"}")
                .statusCode(200).extract().path("TagOptionDetail.Id");
        cleanup.register(() -> call(HOME, "DeleteTagOption", byId(tagOption)));
        String action = call(HOME, "CreateServiceAction", "{\"Name\":\"restart\",\"DefinitionType\":"
                + "\"SSM_AUTOMATION\",\"Definition\":{\"Name\":\"AWS-RestartEC2Instance\"},\"IdempotencyToken\":\"t1\"}")
                .statusCode(200).extract().path("ServiceActionDetail.ServiceActionSummary.Id");
        cleanup.register(() -> call(HOME, "DeleteServiceAction", byId(action)));
        String constraint = call(HOME, "CreateConstraint", "{\"PortfolioId\":\"" + portfolio + "\",\"ProductId\":\""
                + product + "\",\"Parameters\":\"{}\",\"Type\":\"LAUNCH\",\"IdempotencyToken\":\"t2\"}")
                .statusCode(200).extract().path("ConstraintDetail.ConstraintId");
        cleanup.register(() -> call(HOME, "DeleteConstraint", byId(constraint)));
        String provisioned = call(HOME, "ProvisionProduct", "{\"ProductId\":\"" + product
                + "\",\"ProvisionedProductName\":\"app-1\"}")
                .statusCode(200).extract().path("RecordDetail.ProvisionedProductId");
        cleanup.register(() -> call(HOME, "TerminateProvisionedProduct", "{\"ProvisionedProductId\":\""
                + provisioned + "\",\"TerminateToken\":\"t4\"}"));

        call(OTHER, "ListPortfolios", "{}").statusCode(200).body("PortfolioDetails.Id", not(hasItem(portfolio)));
        notFound(call(OTHER, "DescribePortfolio", byId(portfolio)));
        notFound(call(OTHER, "DeletePortfolio", byId(portfolio)));
        call(OTHER, "SearchProductsAsAdmin", "{}").statusCode(200)
                .body("ProductViewDetails.ProductViewSummary.ProductId", not(hasItem(product)));
        notFound(call(OTHER, "DescribeProductAsAdmin", byId(product)));
        call(OTHER, "ListTagOptions", "{}").statusCode(200).body("TagOptionDetails.Id", not(hasItem(tagOption)));
        notFound(call(OTHER, "DescribeTagOption", byId(tagOption)));
        call(OTHER, "ListServiceActions", "{}").statusCode(200).body("ServiceActionSummaries.Id", not(hasItem(action)));
        notFound(call(OTHER, "UpdateServiceAction", "{\"Id\":\"" + action + "\",\"Name\":\"renamed\"}"));
        notFound(call(OTHER, "DescribeConstraint", byId(constraint)));
        call(OTHER, "SearchProvisionedProducts", "{}").statusCode(200)
                .body("ProvisionedProducts.Id", not(hasItem(provisioned)));
        notFound(call(OTHER, "DescribeProvisionedProduct", byId(provisioned)));

        call(HOME, "ListPortfolios", "{}").statusCode(200).body("PortfolioDetails.Id", hasItem(portfolio));
        call(HOME, "DescribePortfolio", byId(portfolio)).statusCode(200);
        call(HOME, "SearchProductsAsAdmin", "{}").statusCode(200)
                .body("ProductViewDetails.ProductViewSummary.ProductId", hasItem(product));
        call(HOME, "DescribeProductAsAdmin", byId(product)).statusCode(200);
        call(HOME, "ListTagOptions", "{}").statusCode(200).body("TagOptionDetails.Id", hasItem(tagOption));
        call(HOME, "ListServiceActions", "{}").statusCode(200).body("ServiceActionSummaries.Id", hasItem(action));
        call(HOME, "DescribeConstraint", byId(constraint)).statusCode(200);
        call(HOME, "DescribeProvisionedProduct", byId(provisioned)).statusCode(200)
                .body("ProvisionedProductDetail.Arn", startsWith("arn:aws:servicecatalog:" + HOME + ":"));
    }

    /** CopyProduct copies a product of another Region, the one its ARN names, into the request's Region. */
    @Test
    void aProductOfAnotherRegionIsCopiedIntoTheRequestsRegion() {
        String source = createProduct(HOME);
        String sourceArn = call(HOME, "DescribeProductAsAdmin", byId(source)).statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.ARN");

        String token = call(OTHER, "CopyProduct", "{\"SourceProductArn\":\"" + sourceArn
                + "\",\"TargetProductName\":\"copied\",\"IdempotencyToken\":\"t3\"}")
                .statusCode(200).extract().path("CopyProductToken");
        String copy = call(OTHER, "DescribeCopyProductStatus", "{\"CopyProductToken\":\"" + token + "\"}")
                .statusCode(200).body("CopyProductStatus", equalTo("SUCCEEDED")).extract().path("TargetProductId");
        cleanup.register(() -> call(OTHER, "DeleteProduct", byId(copy)));

        call(OTHER, "DescribeProductAsAdmin", byId(copy)).statusCode(200)
                .body("ProductViewDetail.ProductViewSummary.Name", equalTo("copied"))
                .body("ProductViewDetail.ProductViewSummary.ARN", startsWith("arn:aws:catalog:" + OTHER + ":"));
        notFound(call(HOME, "DescribeProductAsAdmin", byId(copy)));
    }

    /**
     * A product of another partition isn't copied, though that partition holds one under the ARN's id: neither by an
     * ARN of that partition, nor by one of the request's partition naming that partition's Region.
     */
    @Test
    void aProductOfAnotherPartitionIsNotFound() {
        String source = createProduct("cn-north-1");

        notFound(call(OTHER, "CopyProduct", "{\"SourceProductArn\":\"arn:aws-cn:catalog:cn-north-1:" + account
                + ":product/" + source + "\",\"IdempotencyToken\":\"t5\"}"));
        notFound(call(OTHER, "CopyProduct", "{\"SourceProductArn\":\"arn:aws:catalog:cn-north-1:" + account
                + ":product/" + source + "\",\"IdempotencyToken\":\"t6\"}"));
    }

    /**
     * The Control Tower Account Factory catalog Floci seeds is each Region's own, as Floci's Control Tower seeds a
     * landing zone in each: the portfolio a Region lists is of that Region, and deleting one leaves the other's.
     */
    @Test
    void eachRegionSeedsAnAccountFactoryCatalogOfItsOwn() {
        for (String region : List.of(HOME, OTHER)) {
            call(region, "ListPortfolios", "{}").statusCode(200)
                    .body("PortfolioDetails.Id", hasItem(ServiceCatalogService.CONTROL_TOWER_PORTFOLIO_ID));
            call(region, "DescribePortfolio", byId(ServiceCatalogService.CONTROL_TOWER_PORTFOLIO_ID)).statusCode(200)
                    .body("PortfolioDetail.ARN", startsWith("arn:aws:catalog:" + region + ":"));
        }

        call(HOME, "DisassociateProductFromPortfolio", "{\"PortfolioId\":\""
                + ServiceCatalogService.CONTROL_TOWER_PORTFOLIO_ID + "\",\"ProductId\":\""
                + ServiceCatalogService.CONTROL_TOWER_PRODUCT_ID + "\"}").statusCode(200);
        call(HOME, "DeletePortfolio", byId(ServiceCatalogService.CONTROL_TOWER_PORTFOLIO_ID)).statusCode(200);

        call(OTHER, "DescribePortfolio", byId(ServiceCatalogService.CONTROL_TOWER_PORTFOLIO_ID)).statusCode(200)
                .body("PortfolioDetail.ARN", startsWith("arn:aws:catalog:" + OTHER + ":"));
    }

    /** A product created in {@code region}, deleted after the test. */
    private String createProduct(String region) {
        String product = call(region, "CreateProduct", "{\"Name\":\"app\",\"Owner\":\"floci\","
                + "\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .statusCode(200).extract().path("ProductViewDetail.ProductViewSummary.ProductId");
        cleanup.register(() -> call(region, "DeleteProduct", byId(product)));
        return product;
    }

    private static String byId(String id) {
        return "{\"Id\":\"" + id + "\"}";
    }

    private static void notFound(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    private ValidatableResponse call(String region, String action, String body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWS242ServiceCatalogService." + action)
                .header("Authorization", EnforcementFixtures.auth(account, "20260918", region, "servicecatalog"))
                .body(body)
        .when().post("/")
        .then();
    }
}
