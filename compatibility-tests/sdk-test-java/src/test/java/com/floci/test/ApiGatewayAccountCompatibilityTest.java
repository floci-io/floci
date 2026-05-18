package com.floci.test;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetAccountRequest;
import software.amazon.awssdk.services.apigateway.model.GetAccountResponse;
import software.amazon.awssdk.services.apigateway.model.UpdateAccountRequest;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;

class ApiGatewayAccountCompatibilityTest {

    @Test
    void getAndUpdateAccount_viaSdk() {
        ApiGatewayClient apigw = TestFixtures.apiGatewayClient();

        GetAccountResponse before = apigw.getAccount(GetAccountRequest.builder().build());
        assertThat(before.apiKeyVersion()).isNotBlank();
        assertThat(before.features()).contains("UsagePlans");
        assertThat(before.throttleSettings()).isNotNull();

        String arn = "arn:aws:iam::123456789012:role/apigAwsProxyRole";
        PatchOperation op = PatchOperation.builder()
                .op("replace")
                .path("/cloudwatchRoleArn")
                .value(arn)
                .build();

        apigw.updateAccount(UpdateAccountRequest.builder().patchOperations(op).build());

        GetAccountResponse after = apigw.getAccount(GetAccountRequest.builder().build());
        assertThat(after.cloudwatchRoleArn()).isEqualTo(arn);

        // cleanup
        PatchOperation remove = PatchOperation.builder().op("remove").path("/cloudwatchRoleArn").build();
        apigw.updateAccount(UpdateAccountRequest.builder().patchOperations(remove).build());
    }
}
