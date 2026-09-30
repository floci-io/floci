package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour that AWS gates on the partition rather than on a literal region: a WAF CLOUDFRONT
 * scope exists only where CloudFront does and lives in the partition's implicit global region,
 * and Lightsail exists only in the commercial partition.
 */
@QuarkusTest
class PartitionRegionGatesIntegrationTest {

    private static final String JSON_1_1 = "application/x-amz-json-1.1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response createIpSet(String region, String name) {
        return given()
            .header("Authorization", PartitionMatrix.sigV4Auth(region, "wafv2"))
            .header("X-Amz-Target", "AWSWAF_20190729.CreateIPSet")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\",\"Scope\":\"CLOUDFRONT\",\"IPAddressVersion\":\"IPV4\",\"Addresses\":[\"10.0.0.0/8\"]}")
        .when().post("/");
    }

    @Test
    void cloudFrontScopeIsRejectedWhereThePartitionHasNoCloudFront() {
        createIpSet("us-gov-west-1", "gov-" + Long.toString(System.nanoTime(), 36)).then().statusCode(400)
                .body("__type", containsString("WAFInvalidParameterException"))
                .body("Reason", containsString("aws-us-gov"));
    }

    /** The scope is unavailable for every operation in such a partition, not only for creates. */
    @Test
    void cloudFrontScopeListsAreRejectedWhereThePartitionHasNoCloudFront() {
        for (String action : new String[] {"ListIPSets", "ListWebACLs"}) {
            given()
                .header("Authorization", PartitionMatrix.sigV4Auth("us-gov-west-1", "wafv2"))
                .header("X-Amz-Target", "AWSWAF_20190729." + action)
                .contentType(JSON_1_1)
                .body("{\"Scope\":\"CLOUDFRONT\"}")
            .when().post("/")
            .then().statusCode(400)
                .body("__type", containsString("WAFInvalidParameterException"))
                .body("Reason", containsString("aws-us-gov"));
        }
    }

    /**
     * Edge-optimized domains exist only in the commercial partition: GovCloud has no CloudFront, and
     * China has CloudFront but no edge-optimized API Gateway. A regional domain needs neither.
     */
    @Test
    void edgeCustomDomainsAreRejectedOutsideTheCommercialPartition() {
        String suffix = Long.toString(System.nanoTime(), 36);
        for (String region : List.of("us-gov-west-1", "cn-north-1")) {
            String partition = AwsRegions.partitionFor(region);
            given()
                .header("Authorization", PartitionMatrix.sigV4Auth(region, "apigateway"))
                .contentType("application/json")
                .body("{\"domainName\":\"edge-" + suffix + "." + region + ".example.com\","
                        + "\"certificateArn\":\"arn:" + partition + ":acm:" + region + ":000000000000:certificate/edge\","
                        + "\"endpointConfiguration\":{\"types\":[\"EDGE\"]}}")
            .when().post("/domainnames")
            .then().statusCode(400)
                .body(containsString("not available in partition " + partition));
        }

        String regional = "regional-" + suffix + ".example.com";
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-gov-west-1", "apigateway"))
            .contentType("application/json")
            .body("{\"domainName\":\"" + regional + "\","
                    + "\"regionalCertificateArn\":\"arn:aws-us-gov:acm:us-gov-west-1:000000000000:certificate/regional\","
                    + "\"endpointConfiguration\":{\"types\":[\"REGIONAL\"]}}")
        .when().post("/domainnames")
        .then().statusCode(201);
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-gov-west-1", "apigateway"))
        .when().delete("/domainnames/" + regional));
    }

    @Test
    void cloudFrontScopeLivesInThePartitionsImplicitGlobalRegion() {
        String name = "cn-" + Long.toString(System.nanoTime(), 36);
        Response created = createIpSet("cn-north-1", name);
        created.then().statusCode(200);
        String arn = created.jsonPath().getString("Summary.ARN");
        String id = created.jsonPath().getString("Summary.Id");
        String lockToken = created.jsonPath().getString("Summary.LockToken");
        cleanup.register(() -> given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-north-1", "wafv2"))
            .header("X-Amz-Target", "AWSWAF_20190729.DeleteIPSet")
            .contentType(JSON_1_1)
            .body("{\"Name\":\"" + name + "\",\"Scope\":\"CLOUDFRONT\",\"Id\":\"" + id + "\",\"LockToken\":\"" + lockToken + "\"}")
        .when().post("/"));
        assertTrue(arn.startsWith("arn:aws-cn:wafv2:cn-northwest-1:000000000000:global/ipset/" + name + "/"), arn);
    }

    /**
     * The AWS-managed prefix lists belong to the S3 and DynamoDB gateway endpoints, which keep
     * {@code com.amazonaws} in every partition; only interface endpoint names take the reversed
     * suffix China lists.
     */
    @Test
    void gatewayPrefixListsKeepComAmazonawsWhileChinaInterfaceNamesReverseTheSuffix() {
        List<String> names = given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-north-1", "ec2"))
            .formParam("Action", "DescribeManagedPrefixLists")
            .formParam("Version", "2016-11-15")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getList("DescribeManagedPrefixListsResponse.prefixListSet.item.prefixListName");
        assertTrue(names.contains("com.amazonaws.cn-north-1.s3"), names.toString());
        assertTrue(names.contains("com.amazonaws.cn-north-1.dynamodb"), names.toString());

        XmlPath endpointServices = given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-north-1", "ec2"))
            .formParam("Action", "DescribeVpcEndpointServices")
            .formParam("Version", "2016-11-15")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath();
        List<String> services = endpointServices.getList("DescribeVpcEndpointServicesResponse.serviceNameSet.item");
        assertTrue(services.contains("cn.com.amazonaws.cn-north-1.lambda"), services.toString());
        assertTrue(services.contains("com.amazonaws.cn-north-1.s3"), services.toString());
        assertTrue(services.contains("cn.com.amazonaws.cn-north-1.s3"), services.toString());
        assertEquals(List.of("Gateway"), serviceTypes(endpointServices, "com.amazonaws.cn-north-1.s3"));
        assertEquals(List.of("Interface"), serviceTypes(endpointServices, "cn.com.amazonaws.cn-north-1.s3"));
    }

    /** Where the two S3 offerings share a name, the one service detail carries both types. */
    @Test
    void commercialS3IsOneServiceWithBothEndpointTypes() {
        XmlPath endpointServices = given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "ec2"))
            .formParam("Action", "DescribeVpcEndpointServices")
            .formParam("Version", "2016-11-15")
        .when().post("/").then().statusCode(200)
            .extract().xmlPath();
        List<String> s3Names = endpointServices.getList("DescribeVpcEndpointServicesResponse.serviceNameSet.item",
                String.class).stream().filter(name -> name.endsWith(".s3")).toList();
        assertEquals(List.of("com.amazonaws.us-east-1.s3"), s3Names);
        assertEquals(List.of("Gateway", "Interface"), serviceTypes(endpointServices, "com.amazonaws.us-east-1.s3"));
    }

    private static List<String> serviceTypes(XmlPath endpointServices, String serviceName) {
        return endpointServices.getList("DescribeVpcEndpointServicesResponse.serviceDetailSet.item"
                + ".find { it.serviceName == '" + serviceName + "' }.serviceType.item.serviceType", String.class);
    }

    @Test
    void lightsailRegionsAreEmptyOutsideTheCommercialPartition() {
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("cn-north-1", "lightsail"))
            .header("X-Amz-Target", "Lightsail_20161128.GetRegions")
            .contentType(JSON_1_1)
            .body("{}")
        .when().post("/").then().statusCode(200)
            .body("regions", hasSize(0));
        given()
            .header("Authorization", PartitionMatrix.sigV4Auth("us-east-1", "lightsail"))
            .header("X-Amz-Target", "Lightsail_20161128.GetRegions")
            .contentType(JSON_1_1)
            .body("{}")
        .when().post("/").then().statusCode(200)
            .body("regions", hasSize(4));
    }
}
