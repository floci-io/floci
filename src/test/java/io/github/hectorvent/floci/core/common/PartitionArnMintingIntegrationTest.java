package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every ARN minted for a request signed in another partition carries that partition. One probe
 * per sweep group: the CloudFormation pseudo-parameters (template engine), a KMS default key
 * policy principal (an IAM root ARN built through the resolver), an SSM parameter (a regional
 * {@code Arn.of} site) and a Service Catalog portfolio (a service that never imported the helper).
 */
@QuarkusTest
class PartitionArnMintingIntegrationTest {

    private static final String REGION = "cn-north-1";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void cloudFormationPseudoParametersFollowTheStackRegion() {
        String stackName = "partition-pseudo-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {
                  "Resources": {"Wait": {"Type": "AWS::CloudFormation::WaitConditionHandle"}},
                  "Outputs": {
                    "Partition": {"Value": {"Ref": "AWS::Partition"}},
                    "Suffix": {"Value": {"Ref": "AWS::URLSuffix"}},
                    "Sub": {"Value": {"Fn::Sub": "arn:${AWS::Partition}:iam::aws:policy/AdministratorAccess"}}
                  }
                }
                """;
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "cloudformation"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "cloudformation"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/"));

        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "cloudformation"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<OutputValue>aws-cn</OutputValue>"))
            .body(containsString("<OutputValue>amazonaws.com.cn</OutputValue>"))
            .body(containsString("<OutputValue>arn:aws-cn:iam::aws:policy/AdministratorAccess</OutputValue>"));
    }

    @Test
    void kmsDefaultKeyPolicyNamesTheRootPrincipalInTheRequestPartition() {
        Response created = given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "kms"))
            .header("X-Amz-Target", "TrentService.CreateKey")
            .contentType(JSON_1_1)
            .body("{}")
        .when().post("/");
        created.then().statusCode(200);
        String keyId = created.jsonPath().getString("KeyMetadata.KeyId");
        String keyArn = created.jsonPath().getString("KeyMetadata.Arn");
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "kms"))
            .header("X-Amz-Target", "TrentService.ScheduleKeyDeletion")
            .contentType(JSON_1_1)
            .body("{\"KeyId\":\"" + keyId + "\",\"PendingWindowInDays\":7}")
        .when().post("/"));
        assertTrue(keyArn.startsWith("arn:aws-cn:kms:" + REGION + ":"), keyArn);

        String policy = given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "kms"))
            .header("X-Amz-Target", "TrentService.GetKeyPolicy")
            .contentType(JSON_1_1)
            .body("{\"KeyId\":\"" + keyId + "\",\"PolicyName\":\"default\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Policy");
        assertTrue(policy.contains("arn:aws-cn:iam::000000000000:root"), policy);
    }

    @Test
    void ssmParameterArnIsMintedInTheRequestPartition() {
        String name = "/partition/" + Long.toString(System.nanoTime(), 36);
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "ssm"))
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\",\"Type\":\"String\",\"Value\":\"v\"}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "ssm"))
            .header("X-Amz-Target", "AmazonSSM.DeleteParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/"));

        String arn = given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "ssm"))
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Parameter.ARN");
        assertEquals("arn:aws-cn:ssm:" + REGION + ":000000000000:parameter" + name, arn);
    }

    @Test
    void serviceCatalogPortfolioArnIsMintedInTheRequestPartition() {
        String token = Long.toString(System.nanoTime(), 36);
        Response created = given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "servicecatalog"))
            .header("X-Amz-Target", "AWS242ServiceCatalogService.CreatePortfolio")
            .contentType(JSON_1_1)
            .body("{\"DisplayName\":\"partition-" + token + "\",\"ProviderName\":\"floci\",\"IdempotencyToken\":\"" + token + "\"}")
        .when().post("/");
        created.then().statusCode(200);
        String id = created.jsonPath().getString("PortfolioDetail.Id");
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth(REGION, "servicecatalog"))
            .header("X-Amz-Target", "AWS242ServiceCatalogService.DeletePortfolio")
            .contentType(JSON_1_1)
            .body("{\"Id\":\"" + id + "\"}")
        .when().post("/"));

        String arn = created.jsonPath().getString("PortfolioDetail.ARN");
        assertEquals("arn:aws-cn:catalog:" + REGION + ":000000000000:portfolio/" + id, arn);
    }
}
