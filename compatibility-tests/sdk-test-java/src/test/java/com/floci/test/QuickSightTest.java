package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.quicksight.QuickSightClient;
import software.amazon.awssdk.services.quicksight.model.ResourceNotFoundException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class QuickSightTest {
    @Test
    @DisplayName("The local QuickSight subset stores dashboard metadata through the AWS SDK")
    void dashboardMetadataRoundTrip() {
        assumeFalse(TestFixtures.isRealAws(), "Exercises the metadata-only local subset without a QuickSight subscription");
        String account = "000000000000";
        String id = "compat-" + UUID.randomUUID();
        try (QuickSightClient client = TestFixtures.quickSightClient()) {
            String arn = client.createDashboard(request -> request.awsAccountId(account).dashboardId(id)
                    .name("Metadata dashboard").definition(definition -> definition.sheets(List.of()))).arn();
            try {
                assertThat(arn).isEqualTo(TestFixtures.arn("quicksight", account, "dashboard/" + id));
                assertThat(client.describeDashboard(request -> request.awsAccountId(account).dashboardId(id))
                        .dashboard().name()).isEqualTo("Metadata dashboard");
                assertThat(client.describeDashboard(request -> request.awsAccountId(account).dashboardId(id))
                        .dashboard().createdTime()).isNotNull();
            } finally {
                client.deleteDashboard(request -> request.awsAccountId(account).dashboardId(id));
            }
            assertThatThrownBy(() -> client.describeDashboard(request -> request.awsAccountId(account).dashboardId(id)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
