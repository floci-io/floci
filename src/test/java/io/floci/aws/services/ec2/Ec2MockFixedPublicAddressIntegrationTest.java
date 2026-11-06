package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static io.floci.aws.services.ec2.Ec2MockPublicAddressSettingIntegrationTest.INSTANCE;
import static io.floci.aws.services.ec2.Ec2MockPublicAddressSettingIntegrationTest.describe;
import static io.floci.aws.services.ec2.Ec2MockPublicAddressSettingIntegrationTest.launchInPublicSubnet;
import static io.floci.aws.services.ec2.Ec2MockPublicAddressSettingIntegrationTest.publicIp;
import static io.floci.aws.services.ec2.Ec2MockPublicAddressSettingIntegrationTest.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mock instances under {@code mock-public-addresses: fixed} all show the configured address. */
@QuarkusTest
@TestProfile(Ec2MockFixedPublicAddressIntegrationTest.FixedAddress.class)
class Ec2MockFixedPublicAddressIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(724000000000L);
    private static final String CONFIGURED = "192.168.65.2";

    @Test
    void everyMockInstanceDueAnAddressShowsTheConfiguredOne() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String first = launchInPublicSubnet(owner, "10.95");
        String second = launchInPublicSubnet(owner, "10.96");
        ValidatableResponse described = describe(owner, first);
        assertEquals(CONFIGURED, described.extract().xmlPath().getString(INSTANCE + "ipAddress"));
        assertEquals(CONFIGURED, described.extract().xmlPath().getString(INSTANCE + "dnsName"));
        assertEquals(CONFIGURED, publicIp(owner, second));

        // The rule still decides when the address applies: none while stopped, an Elastic IP first.
        request("StopInstances", owner).formParam("InstanceId.1", first).post("/").then().statusCode(200);
        assertEquals("", publicIp(owner, first));
        request("StartInstances", owner).formParam("InstanceId.1", first).post("/").then().statusCode(200);
        assertEquals(CONFIGURED, publicIp(owner, first));
        ValidatableResponse allocated = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String allocation = allocated.extract().path("AllocateAddressResponse.allocationId");
        String association = request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", first).post("/").then().statusCode(200)
                .extract().path("AssociateAddressResponse.associationId");
        String elastic = allocated.extract().path("AllocateAddressResponse.publicIp");
        assertTrue(elastic.matches("203\\.0\\.113\\.[0-9]+"), elastic);
        // Associated, the Elastic IP stands for the configured address too, as a container's does.
        assertEquals(CONFIGURED, publicIp(owner, first));
        assertEquals(CONFIGURED, address(owner, allocation));
        request("DisassociateAddress", owner).formParam("AssociationId", association).post("/").then()
                .statusCode(200);
        assertEquals(CONFIGURED, publicIp(owner, first));
        assertEquals(elastic, address(owner, allocation));

        request("TerminateInstances", owner).formParam("InstanceId.1", first).formParam("InstanceId.2", second)
                .post("/").then().statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", allocation).post("/").then().statusCode(200);
    }

    private static String address(String owner, String allocation) {
        return request("DescribeAddresses", owner).formParam("AllocationId.1", allocation).post("/").then()
                .statusCode(200).extract().path("DescribeAddressesResponse.addressesSet.item.publicIp");
    }

    public static class FixedAddress implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock-public-addresses", "fixed",
                    "floci.services.ec2.mock-public-address", CONFIGURED);
        }
    }
}
