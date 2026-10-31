package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.FlowLogsResourceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TrafficType;

import java.net.URI;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CreateFlowLogs dropped its TagSpecification, so Terraform's aws_flow_log tags never read back and
 * every plan after apply wanted to add them again.
 */
@QuarkusTest
class Ec2FlowLogTagsIntegrationTest {

    @Test
    void aFlowLogKeepsTheTagsItWasCreatedWith() {
        String vpcId = given().formParam("Action", "CreateVpc").formParam("CidrBlock", "10.21.0.0/16")
                .when().post("/").then().statusCode(200).extract().xmlPath().getString("CreateVpcResponse.vpc.vpcId");
        String flowLogId = given()
                .formParam("Action", "CreateFlowLogs")
                .formParam("ResourceType", "VPC")
                .formParam("ResourceId.1", vpcId)
                .formParam("TrafficType", "ALL")
                .formParam("LogDestinationType", "s3")
                .formParam("LogDestination", "arn:aws:s3:::flow-logs-test-bucket")
                .formParam("TagSpecification.1.ResourceType", "vpc-flow-log")
                .formParam("TagSpecification.1.Tag.1.Key", "Name")
                .formParam("TagSpecification.1.Tag.1.Value", "kthw")
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getString("CreateFlowLogsResponse.flowLogIdSet.item");

        given().formParam("Action", "DescribeFlowLogs").formParam("FlowLogId.1", flowLogId)
                .when().post("/").then().statusCode(200)
                .body(hasXPath("//*[local-name()='flowLogSet']/*[local-name()='item']/*[local-name()='tagSet']"
                        + "/*[local-name()='item']/*[local-name()='value']", equalTo("kthw")));
    }

    @Test
    void awsSdkReadsBackTheTagsAFlowLogWasCreatedWith() {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String vpcId = client.createVpc(r -> r.cidrBlock("10.22.0.0/16")).vpc().vpcId();
            String flowLogId = client.createFlowLogs(r -> r
                    .resourceIds(vpcId)
                    .resourceType(FlowLogsResourceType.VPC)
                    .trafficType(TrafficType.ALL)
                    .logDestinationType("s3")
                    .logDestination("arn:aws:s3:::flow-logs-test-bucket")
                    .tagSpecifications(t -> t.resourceType(ResourceType.VPC_FLOW_LOG)
                            .tags(Tag.builder().key("Name").value("kthw").build(),
                                    Tag.builder().key("env").value("test").build())))
                    .flowLogIds().getFirst();
            try {
                List<Tag> tags = client.describeFlowLogs(r -> r.flowLogIds(flowLogId))
                        .flowLogs().getFirst().tags();
                assertEquals(List.of(Tag.builder().key("Name").value("kthw").build(),
                        Tag.builder().key("env").value("test").build()), tags);
            } finally {
                client.deleteFlowLogs(r -> r.flowLogIds(flowLogId));
                client.deleteVpc(r -> r.vpcId(vpcId));
            }
        }
    }
}
