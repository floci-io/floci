package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacedeployment.MarketplaceDeploymentClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketplaceDeploymentTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceDeploymentClient client = TestFixtures.marketplaceDeploymentClient()) {
            var response = client.putDeploymentParameter(r -> r
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
