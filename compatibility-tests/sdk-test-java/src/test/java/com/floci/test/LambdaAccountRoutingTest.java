package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.assertThat;

class LambdaAccountRoutingTest {

    private static final String CALLER_ACCOUNT = "234567890123";
    private static final String TARGET_ACCOUNT = "345678901234";
    private static final Region REGION = Region.US_EAST_1;

    @Test
    void invokeFullArnUsesTargetAccountWithDifferentCallerCredentials() {
        String functionName = TestFixtures.uniqueName("sdk-arn-account-routing");
        try (LambdaClient caller = client(CALLER_ACCOUNT);
             LambdaClient target = client(TARGET_ACCOUNT)) {
            target.createFunction(CreateFunctionRequest.builder()
                    .functionName(functionName)
                    .runtime(Runtime.NODEJS20_X)
                    .role("arn:aws:iam::" + TARGET_ACCOUNT + ":role/lambda-role")
                    .handler("index.handler")
                    .code(FunctionCode.builder()
                            .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                            .build())
                    .build());

            try {
                String functionArn = "arn:aws:lambda:" + REGION.id() + ":" + TARGET_ACCOUNT
                        + ":function:" + functionName;
                assertThat(caller.invoke(InvokeRequest.builder()
                                .functionName(functionArn)
                                .invocationType(InvocationType.DRY_RUN)
                                .payload(SdkBytes.fromUtf8String("{}"))
                                .build())
                        .statusCode()).isEqualTo(204);
            } finally {
                target.deleteFunction(DeleteFunctionRequest.builder()
                        .functionName(functionName)
                        .build());
            }
        }
    }

    private LambdaClient client(String accountId) {
        return LambdaClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accountId, "test")))
                .build();
    }
}
