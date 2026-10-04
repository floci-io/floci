package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacedeployment.MarketplaceDeploymentClient;
import software.amazon.awssdk.services.marketplacedeployment.model.PutDeploymentParameterResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MarketplaceDeploymentTest {

    private static final List<String> REGIONS = List.of("us-east-1");

    @Test
    void usesAwsSdkWireContract() {
        assumeTrue(REGIONS.contains(TestFixtures.region().id()),
                "AWS Marketplace Deployment has no endpoint in this region");
        try (MarketplaceDeploymentClient client = TestFixtures.marketplaceDeploymentClient()) {
            PutDeploymentParameterResponse response = client.putDeploymentParameter(r -> r
                    .catalog("AWSMarketplace")
                    .productId("prod-sdk-compat")
                    .agreementId("agr-sdk-compat")
                    .deploymentParameter(p -> p.name("ApiKey").secretString("local-secret"))
                    .tags(java.util.Map.of("suite", "sdk-compat")));
            assertNotNull(response.deploymentParameterId());
            assertNotNull(response.resourceArn());
            assertEquals("sdk-compat", response.tags().get("suite"));
        }
    }
}
