package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetAliasResponse;
import software.amazon.awssdk.services.lambda.model.GetProvisionedConcurrencyConfigResponse;
import software.amazon.awssdk.services.lambda.model.InvalidParameterValueException;
import software.amazon.awssdk.services.lambda.model.ProvisionedConcurrencyConfigNotFoundException;
import software.amazon.awssdk.services.lambda.model.PutProvisionedConcurrencyConfigResponse;
import software.amazon.awssdk.services.lambda.model.ResourceConflictException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Lambda provisioned concurrency control plane")
class LambdaProvisionedConcurrencyTest {
    private LambdaClient lambda;
    private String name;
    private String arn;
    private String version;

    @BeforeEach
    void createFunctionAndAlias() {
        lambda = TestFixtures.lambdaClient();
        name = "sdk-pc-" + UUID.randomUUID();
        arn = lambda.createFunction(request -> request.functionName(name).runtime(Runtime.NODEJS20_X)
                .role(TestFixtures.globalArn("iam", "000000000000", "role/lambda-role")).handler("index.handler")
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip())).build()))
                .functionArn();
        version = lambda.publishVersion(request -> request.functionName(name)).version();
        lambda.createAlias(request -> request.functionName(name).name("live").functionVersion(version));
    }

    @AfterEach
    void deleteFunction() {
        if (lambda != null) {
            try {
                lambda.deleteFunction(request -> request.functionName(name));
            } catch (ResourceNotFoundException ignored) {
                // The deletion test already removed this test-owned function.
            } finally {
                lambda.close();
            }
        }
    }

    @Test
    @DisplayName("Put/Get/Delete deserialize through the Java SDK")
    void configurationRoundTrip() {
        PutProvisionedConcurrencyConfigResponse put = lambda.putProvisionedConcurrencyConfig(request -> request
                .functionName(arn).qualifier("live").provisionedConcurrentExecutions(1));
        assertThat(put.sdkHttpResponse().statusCode()).isEqualTo(202);
        assertThat(put.requestedProvisionedConcurrentExecutions()).isEqualTo(1);
        assertThat(put.lastModified()).isNotBlank();
        GetProvisionedConcurrencyConfigResponse get = lambda.getProvisionedConcurrencyConfig(request -> request
                .functionName(name).qualifier("live"));
        assertThat(get.requestedProvisionedConcurrentExecutions()).isEqualTo(1);
        assertThat(get.lastModified()).isEqualTo(put.lastModified());
        assertThatThrownBy(() -> lambda.putProvisionedConcurrencyConfig(request -> request
                .functionName(name).qualifier(version).provisionedConcurrentExecutions(1)))
                .isInstanceOf(ResourceConflictException.class);
        assertThat(lambda.deleteProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier("live"))
                .sdkHttpResponse().statusCode()).isEqualTo(204);
        assertThatThrownBy(() -> lambda.getProvisionedConcurrencyConfig(request -> request
                .functionName(name).qualifier("live"))).isInstanceOf(ProvisionedConcurrencyConfigNotFoundException.class);
        lambda.deleteProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier("live"));
    }

    @Test
    @DisplayName("Deleting and recreating an alias does not restore its configuration")
    void aliasDeletionRemovesConfiguration() {
        lambda.putProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier("live")
                .provisionedConcurrentExecutions(1));
        lambda.deleteAlias(request -> request.functionName(name).name("live"));
        lambda.createAlias(request -> request.functionName(name).name("live").functionVersion(version));
        assertThatThrownBy(() -> lambda.getProvisionedConcurrencyConfig(request -> request
                .functionName(name).qualifier("live"))).isInstanceOf(ProvisionedConcurrencyConfigNotFoundException.class);
        lambda.putProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier(version)
                .provisionedConcurrentExecutions(1));
    }

    @Test
    @DisplayName("Version deletion releases its requested capacity")
    void versionDeletionReleasesCapacity() {
        lambda.deleteAlias(request -> request.functionName(name).name("live"));
        lambda.putProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier(version)
                .provisionedConcurrentExecutions(1));
        lambda.deleteFunction(request -> request.functionName(name).qualifier(version));
        assertThatThrownBy(() -> lambda.getProvisionedConcurrencyConfig(request -> request
                .functionName(name).qualifier(version))).isInstanceOf(ResourceNotFoundException.class);
        lambda.putFunctionConcurrency(request -> request.functionName(name).reservedConcurrentExecutions(0));
    }

    @Test
    @DisplayName("Reserved-concurrency rejection preserves both configurations")
    void rejectedReservationPreservesState() {
        lambda.putFunctionConcurrency(request -> request.functionName(name).reservedConcurrentExecutions(2));
        lambda.putProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier("live")
                .provisionedConcurrentExecutions(2));
        assertThatThrownBy(() -> lambda.putFunctionConcurrency(request -> request.functionName(name)
                .reservedConcurrentExecutions(1))).isInstanceOf(InvalidParameterValueException.class);
        assertThat(lambda.getFunctionConcurrency(request -> request.functionName(name)).reservedConcurrentExecutions())
                .isEqualTo(2);
        assertThat(lambda.getProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier("live"))
                .requestedProvisionedConcurrentExecutions()).isEqualTo(2);
    }

    @Test
    @DisplayName("Function deletion cleans configuration before name reuse")
    void functionDeletionRemovesConfiguration() {
        lambda.putProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier("live")
                .provisionedConcurrentExecutions(1));
        lambda.deleteFunction(request -> request.functionName(name));
        lambda.createFunction(request -> request.functionName(name).runtime(Runtime.NODEJS20_X)
                .role(TestFixtures.globalArn("iam", "000000000000", "role/lambda-role")).handler("index.handler")
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(LambdaUtils.minimalZip())).build()));
        String replacement = lambda.publishVersion(request -> request.functionName(name)).version();
        lambda.createAlias(request -> request.functionName(name).name("live").functionVersion(replacement));
        assertThatThrownBy(() -> lambda.getProvisionedConcurrencyConfig(request -> request
                .functionName(name).qualifier("live"))).isInstanceOf(ProvisionedConcurrencyConfigNotFoundException.class);
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Alias retarget validates destination capacity before mutating alias fields")
    void aliasRetargetRejectsConfiguredDestination(boolean otherAlias) {
        lambda.updateFunctionConfiguration(request -> request.functionName(name).description("second version"));
        String second = lambda.publishVersion(request -> request.functionName(name)).version();
        lambda.createAlias(request -> request.functionName(name).name("peer").functionVersion(second));
        String destination = otherAlias ? "peer" : second;
        lambda.putProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier("live")
                .provisionedConcurrentExecutions(1));
        lambda.putProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier(destination)
                .provisionedConcurrentExecutions(1));
        GetAliasResponse before = lambda.getAlias(request -> request.functionName(name).name("live"));
        assertThatThrownBy(() -> lambda.updateAlias(request -> request.functionName(name).name("live")
                .functionVersion(second).description("must not persist")))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("Alias can't be used for Provisioned Concurrency configuration on an already Provisioned version");
        GetAliasResponse after = lambda.getAlias(request -> request.functionName(name).name("live"));
        assertThat(after.functionVersion()).isEqualTo(before.functionVersion());
        assertThat(after.description()).isEqualTo(before.description());
        assertThat(after.revisionId()).isEqualTo(before.revisionId());
        lambda.updateAlias(request -> request.functionName(name).name("live").description("description only"));
        lambda.createAlias(request -> request.functionName(name).name("unconfigured").functionVersion(version));
        lambda.updateAlias(request -> request.functionName(name).name("unconfigured").functionVersion(second));
        lambda.deleteProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier(destination));
        lambda.updateAlias(request -> request.functionName(name).name("live").functionVersion(second));
        assertThat(lambda.getProvisionedConcurrencyConfig(request -> request.functionName(name).qualifier("live"))
                .requestedProvisionedConcurrentExecutions()).isEqualTo(1);
    }

}
