package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.floci.aws.testing.Ec2Requests.ec2;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2AddressMetadataIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-west-2";
    private static final String ALLOCATED = "AllocateAddressResponse.";
    private static final String DESCRIBED = "DescribeAddressesResponse.addressesSet.item.";

    private String allocationId;
    private String associationId;
    private String instanceId;

    @AfterEach
    void deleteCreatedResources() {
        try {
            if (associationId != null) {
                ec2("DisassociateAddress", ACCOUNT_ID, REGION).formParam("AssociationId", associationId)
                        .post("/").then().statusCode(200);
            }
        } finally {
            try {
                if (allocationId != null) {
                    ec2("ReleaseAddress", ACCOUNT_ID, REGION).formParam("AllocationId", allocationId)
                            .post("/").then().statusCode(200);
                }
            } finally {
                if (instanceId != null) {
                    ec2("TerminateInstances", ACCOUNT_ID, REGION).formParam("InstanceId.1", instanceId)
                            .post("/").then().statusCode(200);
                }
            }
        }
    }

    @Test
    void omittedMetadataUsesAmazonPoolAndTheRequestRegion() {
        Response allocated = allocate(ec2("AllocateAddress", ACCOUNT_ID, REGION));

        allocated.then()
                .body(ALLOCATED + "publicIpv4Pool", equalTo("amazon"))
                .body(ALLOCATED + "networkBorderGroup", equalTo(REGION));
        describe().then()
                .body(DESCRIBED + "publicIpv4Pool", equalTo("amazon"))
                .body(DESCRIBED + "networkBorderGroup", equalTo(REGION));
    }

    @ParameterizedTest
    @CsvSource({"amazon,us-west-2", "ipv4pool-ec2-0123456789abcdef0,us-west-2-lax-1"})
    void explicitMetadataIsAllocatedDescribedAndFiltered(String pool, String borderGroup) {
        Response allocated = allocate(ec2("AllocateAddress", ACCOUNT_ID, REGION)
                .formParam("PublicIpv4Pool", pool)
                .formParam("NetworkBorderGroup", borderGroup));

        allocated.then()
                .body(ALLOCATED + "publicIpv4Pool", equalTo(pool))
                .body(ALLOCATED + "networkBorderGroup", equalTo(borderGroup));
        describe().then()
                .body(DESCRIBED + "publicIpv4Pool", equalTo(pool))
                .body(DESCRIBED + "networkBorderGroup", equalTo(borderGroup));

        ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", allocationId)
                .formParam("Filter.1.Name", "network-border-group")
                .formParam("Filter.1.Value.1", borderGroup)
                .post("/").then().statusCode(200)
                .body("DescribeAddressesResponse.addressesSet.item.size()", equalTo(1))
                .body(DESCRIBED + "allocationId", equalTo(allocationId));
        ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", allocationId)
                .formParam("Filter.1.Name", "network-border-group")
                .formParam("Filter.1.Value.1", "us-east-1")
                .post("/").then().statusCode(200)
                .body("DescribeAddressesResponse.addressesSet.item.size()", equalTo(0));
    }

    @Test
    void metadataSurvivesInstanceAssociationAndDisassociation() {
        String pool = "ipv4pool-ec2-0123456789abcdef0";
        allocate(ec2("AllocateAddress", ACCOUNT_ID, REGION)
                .formParam("PublicIpv4Pool", pool)
                .formParam("NetworkBorderGroup", REGION));
        instanceId = ec2("RunInstances", ACCOUNT_ID, REGION)
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1")
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        associationId = ec2("AssociateAddress", ACCOUNT_ID, REGION)
                .formParam("AllocationId", allocationId)
                .formParam("InstanceId", instanceId)
                .post("/").then().statusCode(200)
                .extract().path("AssociateAddressResponse.associationId");

        describe().then()
                .body(DESCRIBED + "associationId", equalTo(associationId))
                .body(DESCRIBED + "instanceId", equalTo(instanceId))
                .body(DESCRIBED + "publicIpv4Pool", equalTo(pool))
                .body(DESCRIBED + "networkBorderGroup", equalTo(REGION));

        ec2("DisassociateAddress", ACCOUNT_ID, REGION).formParam("AssociationId", associationId)
                .post("/").then().statusCode(200);
        associationId = null;

        describe().then()
                .body(DESCRIBED + "associationId.size()", equalTo(0))
                .body(DESCRIBED + "instanceId.size()", equalTo(0))
                .body(DESCRIBED + "publicIpv4Pool", equalTo(pool))
                .body(DESCRIBED + "networkBorderGroup", equalTo(REGION));
    }

    private Response allocate(RequestSpecification request) {
        Response allocated = request.formParam("Domain", "vpc")
                .post("/").then().statusCode(200).extract().response();
        allocationId = allocated.path(ALLOCATED + "allocationId");
        return allocated;
    }

    private Response describe() {
        return ec2("DescribeAddresses", ACCOUNT_ID, REGION).formParam("AllocationId.1", allocationId)
                .post("/").then().statusCode(200).extract().response();
    }
}
