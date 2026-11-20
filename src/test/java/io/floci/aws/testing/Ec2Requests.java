package io.floci.aws.testing;

import io.restassured.specification.RequestSpecification;
import software.amazon.awssdk.services.ec2.Ec2Client;

import static io.restassured.RestAssured.given;

/** Requests to EC2 for tests: a signed Query request through RestAssured, and lookups through the SDK. */
public final class Ec2Requests {
    private Ec2Requests() {}

    /** The id of the {@code default} security group EC2 made with VPC {@code vpcId}. */
    public static String defaultSecurityGroupId(Ec2Client client, String vpcId) {
        return client.describeSecurityGroups(request -> request.filters(
                        filter -> filter.name("vpc-id").values(vpcId),
                        filter -> filter.name("group-name").values("default")))
                .securityGroups().getFirst().groupId();
    }

    public static RequestSpecification ec2(String action, String accountId, String region) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + accountId + "/20261016/" + region + "/ec2/aws4_request")
                .formParam("Action", action);
    }
}
