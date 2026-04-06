package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.AddTagsToResourceRequest;
import software.amazon.awssdk.services.ssm.model.DeleteParameterRequest;
import software.amazon.awssdk.services.ssm.model.DeleteParametersRequest;
import software.amazon.awssdk.services.ssm.model.DeleteParametersResponse;
import software.amazon.awssdk.services.ssm.model.DescribeParametersRequest;
import software.amazon.awssdk.services.ssm.model.DescribeParametersResponse;
import software.amazon.awssdk.services.ssm.model.GetParameterHistoryRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterHistoryResponse;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.LabelParameterVersionRequest;
import software.amazon.awssdk.services.ssm.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.ssm.model.ListTagsForResourceResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;
import software.amazon.awssdk.services.ssm.model.PutParameterResponse;
import software.amazon.awssdk.services.ssm.model.RemoveTagsFromResourceRequest;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SSM Parameter Store")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SsmTest {

    private static SsmClient ssm;
    private static final String PARAM_NAME = "/sdk-test/param";
    private static final String PARAM_VALUE = "param-value-123";

    @BeforeAll
    static void setup() {
        ssm = TestFixtures.ssmClient();
    }

    @AfterAll
    static void cleanup() {
        if (ssm != null) {
            try {
                ssm.deleteParameter(DeleteParameterRequest.builder().name(PARAM_NAME).build());
            } catch (Exception ignored) {}
            try {
                ssm.deleteParameter(DeleteParameterRequest.builder().name("/sdk-test/param1").build());
            } catch (Exception ignored) {}
            try {
                ssm.deleteParameter(DeleteParameterRequest.builder().name("/sdk-test/param2").build());
            } catch (Exception ignored) {}
            ssm.close();
        }
    }

    @Test
    @Order(1)
    void putParameter() {
        PutParameterResponse response = ssm.putParameter(PutParameterRequest.builder()
                .name(PARAM_NAME)
                .value(PARAM_VALUE)
                .type(ParameterType.STRING)
                .overwrite(true)
                .build());

        assertThat(response.version()).isNotNull().isGreaterThan(0L);
    }

    @Test
    @Order(2)
    void getParameter() {
        GetParameterResponse response = ssm.getParameter(GetParameterRequest.builder()
                .name(PARAM_NAME)
                .withDecryption(false)
                .build());

        assertThat(response.parameter()).isNotNull();
        assertThat(response.parameter().value()).isEqualTo(PARAM_VALUE);
    }

    @Test
    @Order(3)
    void labelParameterVersion() {
        ssm.labelParameterVersion(LabelParameterVersionRequest.builder()
                .name(PARAM_NAME)
                .labels("test-label")
                .parameterVersion(1L)
                .build());
        // No exception means success
    }

    @Test
    @Order(4)
    void getParameterHistory() {
        GetParameterHistoryResponse response = ssm.getParameterHistory(
                GetParameterHistoryRequest.builder()
                        .name(PARAM_NAME)
                        .withDecryption(false)
                        .build());

        assertThat(response.parameters())
                .anyMatch(p -> PARAM_VALUE.equals(p.value()));
    }

    @Test
    @Order(5)
    void getParameters() {
        GetParametersResponse response = ssm.getParameters(
                GetParametersRequest.builder()
                        .names(PARAM_NAME)
                        .build());

        assertThat(response.parameters())
                .anyMatch(p -> PARAM_NAME.equals(p.name()) && PARAM_VALUE.equals(p.value()));
    }

    @Test
    @Order(6)
    void describeParameters() {
        DescribeParametersResponse response = ssm.describeParameters(
                DescribeParametersRequest.builder().build());

        assertThat(response.parameters())
                .anyMatch(p -> PARAM_NAME.equals(p.name()));
    }

    @Test
    @Order(7)
    void getParametersByPath() {
        GetParametersByPathResponse response = ssm.getParametersByPath(
                GetParametersByPathRequest.builder()
                        .path("/sdk-test")
                        .recursive(false)
                        .build());

        assertThat(response.parameters())
                .anyMatch(p -> PARAM_NAME.equals(p.name()));
    }

    @Test
    @Order(8)
    void addTagsToResource() {
        ssm.addTagsToResource(AddTagsToResourceRequest.builder()
                .resourceType("Parameter")
                .resourceId(PARAM_NAME)
                .tags(
                        software.amazon.awssdk.services.ssm.model.Tag.builder().key("env").value("test").build(),
                        software.amazon.awssdk.services.ssm.model.Tag.builder().key("team").value("backend").build()
                )
                .build());
        // No exception means success
    }

    @Test
    @Order(9)
    void listTagsForResource() {
        ListTagsForResourceResponse response = ssm.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .resourceType("Parameter")
                        .resourceId(PARAM_NAME)
                        .build());

        assertThat(response.tagList())
                .anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()))
                .anyMatch(t -> "team".equals(t.key()) && "backend".equals(t.value()));
    }

    @Test
    @Order(10)
    void removeTagsFromResource() {
        ssm.removeTagsFromResource(RemoveTagsFromResourceRequest.builder()
                .resourceType("Parameter")
                .resourceId(PARAM_NAME)
                .tagKeys("team")
                .build());

        ListTagsForResourceResponse response = ssm.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .resourceType("Parameter")
                        .resourceId(PARAM_NAME)
                        .build());

        assertThat(response.tagList())
                .anyMatch(t -> "env".equals(t.key()) && "test".equals(t.value()))
                .noneMatch(t -> "team".equals(t.key()));
    }

    @Test
    @Order(11)
    void deleteParameter() {
        ssm.deleteParameter(DeleteParameterRequest.builder()
                .name(PARAM_NAME)
                .build());

        assertThatThrownBy(() -> ssm.getParameter(GetParameterRequest.builder()
                .name(PARAM_NAME)
                .withDecryption(false)
                .build()))
                .isInstanceOf(ParameterNotFoundException.class);
    }

    @Test
    @Order(12)
    void deleteParameters() {
        ssm.putParameter(PutParameterRequest.builder()
                .name("/sdk-test/param1")
                .value("v1")
                .type(ParameterType.STRING)
                .overwrite(true)
                .build());
        ssm.putParameter(PutParameterRequest.builder()
                .name("/sdk-test/param2")
                .value("v2")
                .type(ParameterType.STRING)
                .overwrite(true)
                .build());

        DeleteParametersResponse response = ssm.deleteParameters(
                DeleteParametersRequest.builder()
                        .names("/sdk-test/param1", "/sdk-test/param2")
                        .build());

        assertThat(response.deletedParameters()).hasSize(2);
    }
}
