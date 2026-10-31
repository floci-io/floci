package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateCodeSigningConfigResponse;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.CreateFunctionResponse;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetFunctionCodeSigningConfigResponse;
import software.amazon.awssdk.services.lambda.model.ListFunctionsByCodeSigningConfigResponse;
import software.amazon.awssdk.services.lambda.model.PutFunctionCodeSigningConfigResponse;
import software.amazon.awssdk.services.lambda.model.ResourceConflictException;
import software.amazon.awssdk.services.lambda.model.Runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Lambda - code signing attachment lifecycle through AWS SDK v2")
class LambdaCodeSigningAttachmentSdkTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = TestFixtures.globalArn("iam", ACCOUNT_ID, "role/lambda-role");
    private static final String PROFILE = TestFixtures.arn("signer", ACCOUNT_ID,
            "/signing-profiles/prof/AbCdEfGhIj");

    @Test
    void createAttachReadListDetachAndDelete() {
        String functionName = TestFixtures.uniqueName("sdk-code-signing");
        try (LambdaClient lambda = TestFixtures.lambdaClient()) {
            String configArn = null;
            boolean functionCreated = false;
            try {
                CreateCodeSigningConfigResponse createdConfig = lambda.createCodeSigningConfig(request -> request
                        .description("SDK lifecycle")
                        .allowedPublishers(publishers -> publishers.signingProfileVersionArns(PROFILE)));
                configArn = createdConfig.codeSigningConfig().codeSigningConfigArn();
                String createdConfigArn = configArn;
                assertThat(configArn).startsWith(TestFixtures.arn("lambda", ACCOUNT_ID,
                        "code-signing-config:csc-"));
                assertThat(lambda.getCodeSigningConfig(request -> request.codeSigningConfigArn(createdConfigArn))
                        .codeSigningConfig().allowedPublishers().signingProfileVersionArns())
                        .containsExactly(PROFILE);

                CreateFunctionResponse createdFunction = lambda.createFunction(CreateFunctionRequest.builder()
                        .functionName(functionName)
                        .runtime(Runtime.NODEJS20_X)
                        .role(ROLE)
                        .handler("index.handler")
                        .code(FunctionCode.builder()
                                .zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip()))
                                .build())
                        .build());
                functionCreated = true;

                PutFunctionCodeSigningConfigResponse attached = lambda.putFunctionCodeSigningConfig(request -> request
                        .functionName(functionName).codeSigningConfigArn(createdConfigArn));
                assertThat(attached.functionName()).isEqualTo(functionName);
                assertThat(attached.codeSigningConfigArn()).isEqualTo(configArn);

                GetFunctionCodeSigningConfigResponse read = lambda.getFunctionCodeSigningConfig(
                        request -> request.functionName(functionName));
                assertThat(read.functionName()).isEqualTo(functionName);
                assertThat(read.codeSigningConfigArn()).isEqualTo(configArn);

                ListFunctionsByCodeSigningConfigResponse listed = lambda.listFunctionsByCodeSigningConfig(
                        request -> request.codeSigningConfigArn(createdConfigArn).maxItems(1));
                assertThat(listed.functionArns()).containsExactly(createdFunction.functionArn());
                assertThat(listed.nextMarker()).isNull();

                assertThatThrownBy(() -> lambda.deleteCodeSigningConfig(
                        request -> request.codeSigningConfigArn(createdConfigArn)))
                        .isInstanceOf(ResourceConflictException.class);

                lambda.deleteFunctionCodeSigningConfig(request -> request.functionName(functionName));
                assertThat(lambda.getFunctionCodeSigningConfig(request -> request.functionName(functionName))
                        .codeSigningConfigArn()).isNullOrEmpty();
                assertThat(lambda.listFunctionsByCodeSigningConfig(request -> request.codeSigningConfigArn(createdConfigArn))
                        .functionArns()).isEmpty();
                lambda.deleteCodeSigningConfig(request -> request.codeSigningConfigArn(createdConfigArn));
                configArn = null;
            } finally {
                try {
                    if (functionCreated) {
                        lambda.deleteFunction(request -> request.functionName(functionName));
                    }
                } finally {
                    if (configArn != null) {
                        String remainingConfigArn = configArn;
                        lambda.deleteCodeSigningConfig(request -> request.codeSigningConfigArn(remainingConfigArn));
                    }
                }
            }
        }
    }
}
