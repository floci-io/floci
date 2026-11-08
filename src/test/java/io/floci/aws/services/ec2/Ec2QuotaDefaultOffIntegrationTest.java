package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(Ec2QuotaDefaultOffIntegrationTest.Disabled.class)
class Ec2QuotaDefaultOffIntegrationTest {
    public static class Disabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.servicequotas.standard-on-demand-vcpus", "5");
        }
    }

    @Test
    void defaultOffKeepsSixMicroInstancesAcceptable() {
        try {
            given().header("Authorization", auth("687432101241")).formParams("Action", "RunInstances",
                    "ImageId", "ami-0abcdef1234567890", "InstanceType", "t2.micro", "MinCount", "6", "MaxCount", "6")
                    .post("/").then().statusCode(200);
        } finally {
            given().post("/_floci/state/reset").then().statusCode(200);
        }
    }

    @Test
    void defaultOffDoesNotRequireAVcpuSnapshotEntry() {
        try {
            given().header("Authorization", auth("687432101242")).formParams("Action", "RunInstances",
                    "ImageId", "ami-0abcdef1234567890", "InstanceType", "m999.unmeasured",
                    "MinCount", "1", "MaxCount", "1").post("/").then().statusCode(200);
        } finally {
            given().post("/_floci/state/reset").then().statusCode(200);
        }
    }

    private static String auth(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261020/us-east-1/ec2/aws4_request";
    }
}
