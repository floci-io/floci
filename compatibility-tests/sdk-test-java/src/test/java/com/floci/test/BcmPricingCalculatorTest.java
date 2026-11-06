package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bcmpricingcalculator.BcmPricingCalculatorClient;
import software.amazon.awssdk.services.bcmpricingcalculator.model.BatchCreateWorkloadEstimateUsageEntry;
import software.amazon.awssdk.services.bcmpricingcalculator.model.BatchCreateWorkloadEstimateUsageRequest;
import software.amazon.awssdk.services.bcmpricingcalculator.model.BatchCreateWorkloadEstimateUsageResponse;
import software.amazon.awssdk.services.bcmpricingcalculator.model.CreateWorkloadEstimateRequest;
import software.amazon.awssdk.services.bcmpricingcalculator.model.CreateWorkloadEstimateResponse;
import software.amazon.awssdk.services.bcmpricingcalculator.model.DeleteWorkloadEstimateRequest;
import software.amazon.awssdk.services.bcmpricingcalculator.model.GetWorkloadEstimateRequest;
import software.amazon.awssdk.services.bcmpricingcalculator.model.GetWorkloadEstimateResponse;
import software.amazon.awssdk.services.bcmpricingcalculator.model.WorkloadEstimateRateType;
import software.amazon.awssdk.services.bcmpricingcalculator.model.WorkloadEstimateStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BcmPricingCalculatorTest {
    @Test
    void workloadEstimateLifecycle() {
        try (BcmPricingCalculatorClient client = TestFixtures.bcmPricingCalculatorClient()) {
            CreateWorkloadEstimateResponse created = client.createWorkloadEstimate(CreateWorkloadEstimateRequest.builder()
                    .name("sdk-estimate")
                    .clientToken("sdk-estimate-token")
                    .rateType(WorkloadEstimateRateType.BEFORE_DISCOUNTS)
                    .build());
            assertNotNull(created.id());

            BatchCreateWorkloadEstimateUsageResponse batch = client.batchCreateWorkloadEstimateUsage(BatchCreateWorkloadEstimateUsageRequest.builder()
                    .workloadEstimateId(created.id())
                    .clientToken("sdk-usage-token")
                    .usage(List.of(BatchCreateWorkloadEstimateUsageEntry.builder()
                            .serviceCode("AmazonEC2")
                            .usageType("BoxUsage:t3.micro")
                            .operation("")
                            .key("sdk1")
                            .usageAccountId("000000000000")
                            .group("compute")
                            .amount(730d)
                            .build()))
                    .build());
            assertTrue(batch.errors().isEmpty());
            assertEquals(1, batch.items().size());

            GetWorkloadEstimateResponse estimate = client.getWorkloadEstimate(GetWorkloadEstimateRequest.builder()
                    .identifier(created.id()).build());
            assertEquals(WorkloadEstimateStatus.VALID, estimate.status());
            assertEquals(7.592d, estimate.totalCost(), 0.000001d);

            client.deleteWorkloadEstimate(DeleteWorkloadEstimateRequest.builder()
                    .identifier(created.id()).build());
        }
    }
}
