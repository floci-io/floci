package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every ARN minted for a request signed in another partition carries that partition, in every
 * non-commercial partition. One probe per sweep group: the CloudFormation pseudo-parameters
 * (template engine), a KMS default key policy principal (an IAM root ARN built through the
 * resolver), an SSM parameter (a regional {@code Arn.of} site) and a Service Catalog portfolio (a
 * service that never imported the helper).
 */
@QuarkusTest
class PartitionArnMintingIntegrationTest {

    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void cloudFormationPseudoParametersFollowTheStackRegion(PartitionCase partitionCase) {
        String region = partitionCase.region();
        String partition = partitionCase.partition();
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
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "cloudformation"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "cloudformation"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/"));

        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE",
                PartitionMatrix.sigV4Auth(region, "cloudformation"));
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "cloudformation"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<OutputValue>" + partition + "</OutputValue>"))
            .body(containsString("<OutputValue>" + partitionCase.dnsSuffix() + "</OutputValue>"))
            .body(containsString("<OutputValue>arn:" + partition
                    + ":iam::aws:policy/AdministratorAccess</OutputValue>"));
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void kmsDefaultKeyPolicyNamesTheRootPrincipalInTheRequestPartition(PartitionCase partitionCase) {
        String region = partitionCase.region();
        String partition = partitionCase.partition();
        Response created = given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "kms"))
            .header("X-Amz-Target", "TrentService.CreateKey")
            .contentType(JSON_1_1)
            .body("{}")
        .when().post("/");
        created.then().statusCode(200);
        String keyId = created.jsonPath().getString("KeyMetadata.KeyId");
        String keyArn = created.jsonPath().getString("KeyMetadata.Arn");
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "kms"))
            .header("X-Amz-Target", "TrentService.ScheduleKeyDeletion")
            .contentType(JSON_1_1)
            .body("{\"KeyId\":\"" + keyId + "\",\"PendingWindowInDays\":7}")
        .when().post("/"));
        assertTrue(keyArn.startsWith("arn:" + partition + ":kms:" + region + ":"), keyArn);

        String policy = given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "kms"))
            .header("X-Amz-Target", "TrentService.GetKeyPolicy")
            .contentType(JSON_1_1)
            .body("{\"KeyId\":\"" + keyId + "\",\"PolicyName\":\"default\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Policy");
        assertTrue(policy.contains("arn:" + partition + ":iam::" + PartitionMatrix.ACCOUNT + ":root"), policy);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void ssmParameterArnIsMintedInTheRequestPartition(PartitionCase partitionCase) {
        String region = partitionCase.region();
        String name = "/partition/" + Long.toString(System.nanoTime(), 36);
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "ssm"))
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\",\"Type\":\"String\",\"Value\":\"v\"}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "ssm"))
            .header("X-Amz-Target", "AmazonSSM.DeleteParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/"));

        String arn = given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "ssm"))
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Parameter.ARN");
        assertEquals("arn:" + partitionCase.partition() + ":ssm:" + region + ":" + PartitionMatrix.ACCOUNT
                + ":parameter" + name, arn);
    }

    /**
     * A hand-rolled client that signs with the {@code aws-cn-global} pseudo-region is served in
     * China's implicit global region: the parameter lands in {@code cn-northwest-1}, is readable
     * there, and no ARN carries the pseudo-region.
     */
    @Test
    void aPseudoSigningRegionIsServedAsThePartitionsGlobalRegion() {
        String name = "/partition-pseudo/" + Long.toString(System.nanoTime(), 36);
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("aws-cn-global", "ssm"))
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\",\"Type\":\"String\",\"Value\":\"v\"}")
        .when().post("/").then().statusCode(200);
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-northwest-1", "ssm"))
            .header("X-Amz-Target", "AmazonSSM.DeleteParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/"));

        String arn = given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-northwest-1", "ssm"))
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Parameter.ARN");
        assertEquals("arn:aws-cn:ssm:cn-northwest-1:000000000000:parameter" + name, arn);
    }

    @ParameterizedTest
    @MethodSource(PartitionMatrix.NON_COMMERCIAL_CASES)
    void serviceCatalogPortfolioArnIsMintedInTheRequestPartition(PartitionCase partitionCase) {
        String region = partitionCase.region();
        String token = Long.toString(System.nanoTime(), 36);
        Response created = given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "servicecatalog"))
            .header("X-Amz-Target", "AWS242ServiceCatalogService.CreatePortfolio")
            .contentType(JSON_1_1)
            .body("{\"DisplayName\":\"partition-" + token + "\",\"ProviderName\":\"floci\",\"IdempotencyToken\":\"" + token + "\"}")
        .when().post("/");
        created.then().statusCode(200);
        String id = created.jsonPath().getString("PortfolioDetail.Id");
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "servicecatalog"))
            .header("X-Amz-Target", "AWS242ServiceCatalogService.DeletePortfolio")
            .contentType(JSON_1_1)
            .body("{\"Id\":\"" + id + "\"}")
        .when().post("/"));

        String arn = created.jsonPath().getString("PortfolioDetail.ARN");
        assertEquals("arn:" + partitionCase.partition() + ":catalog:" + region + ":" + PartitionMatrix.ACCOUNT
                + ":portfolio/" + id, arn);
    }
}
