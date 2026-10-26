package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambdaweb.LambdaWebClient;
import software.amazon.awssdk.services.lambdaweb.model.AccessDeniedException;
import software.amazon.awssdk.services.lambdaweb.model.AuthType;
import software.amazon.awssdk.services.lambdaweb.model.AutoDeploymentMode;
import software.amazon.awssdk.services.lambdaweb.model.BuildConfig;
import software.amazon.awssdk.services.lambdaweb.model.CodeConfig;
import software.amazon.awssdk.services.lambdaweb.model.ConflictException;
import software.amazon.awssdk.services.lambdaweb.model.CreateWebFunctionResponse;
import software.amazon.awssdk.services.lambdaweb.model.CreateWebFunctionRevisionResponse;
import software.amazon.awssdk.services.lambdaweb.model.EndpointConfig;
import software.amazon.awssdk.services.lambdaweb.model.EndpointType;
import software.amazon.awssdk.services.lambdaweb.model.GetWebFunctionEndpointResponse;
import software.amazon.awssdk.services.lambdaweb.model.ListWebFunctionRevisionsResponse;
import software.amazon.awssdk.services.lambdaweb.model.PutResourcePolicyResponse;
import software.amazon.awssdk.services.lambdaweb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambdaweb.model.RevisionConfig;
import software.amazon.awssdk.services.lambdaweb.model.RevisionWeight;
import software.amazon.awssdk.services.lambdaweb.model.RuntimeConfig;
import software.amazon.awssdk.services.lambdaweb.model.S3Object;
import software.amazon.awssdk.services.lambdaweb.model.ServiceConfig;
import software.amazon.awssdk.services.lambdaweb.model.UpdateWebFunctionEndpointResponse;
import software.amazon.awssdk.services.lambdaweb.model.ValidationException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Lambda Web Functions")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaWebTest {

    private static final String FUNCTION_NAME = "sdk-test-web-fn";
    private static final String LAMBDA_TRUST = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
    private static final String LAMBDA_READ = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},"
            + "\"Action\":[\"s3:GetObject\",\"s3:GetObjectVersion\"],\"Resource\":\"arn:aws:s3:::%s/*\"}]}";
    private static LambdaWebClient lambdaWeb;
    private static IamClient iam;
    private static String roleName;
    private static String role;
    private static S3Client s3;
    private static String bucket;
    private static String codeVersion;
    private static String functionArn;
    private static String firstRevisionId;
    private static String secondRevisionId;

    @BeforeAll
    static void setup() {
        lambdaWeb = TestFixtures.lambdaWebClient();
        s3 = TestFixtures.s3Client();
        iam = TestFixtures.iamClient();
        roleName = TestFixtures.uniqueName("sdk-test-web-role");
        role = iam.createRole(r -> r.roleName(roleName).assumeRolePolicyDocument(LAMBDA_TRUST)).role().arn();
        bucket = TestFixtures.uniqueName("sdk-test-web-code");
        s3.createBucket(r -> r.bucket(bucket));
        // AWS takes code only from a versioned bucket that lets Lambda read it.
        s3.putBucketVersioning(r -> r.bucket(bucket)
                .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        s3.putBucketPolicy(r -> r.bucket(bucket).policy(LAMBDA_READ.formatted(bucket)));
        codeVersion = s3.putObject(r -> r.bucket(bucket).key("app.zip"),
                RequestBody.fromBytes(LambdaUtils.minimalZip())).versionId();
    }

    /** Only "already gone" is tolerated; any other failure to clean up fails the run. */
    @AfterAll
    static void cleanup() {
        if (lambdaWeb != null) {
            try {
                lambdaWeb.deleteWebFunction(r -> r.functionName(FUNCTION_NAME));
            } catch (ResourceNotFoundException expected) {
                // The last test deletes the function itself.
            }
            lambdaWeb.close();
        }
        if (s3 != null) {
            try {
                s3.deleteObject(r -> r.bucket(bucket).key("app.zip").versionId(codeVersion));
                s3.deleteBucket(r -> r.bucket(bucket));
            } catch (NoSuchBucketException expected) {
                // Setup did not get as far as creating the bucket.
            }
            s3.close();
        }
        if (iam != null) {
            if (role != null) {
                iam.deleteRole(r -> r.roleName(roleName));
            }
            iam.close();
        }
    }

    private static BuildConfig buildConfig() {
        return BuildConfig.builder()
                .codeConfig(CodeConfig.builder().s3Object(S3Object.builder().bucket(bucket).key("app.zip").build()).build())
                .runtimeConfig(RuntimeConfig.builder().runtime("nodejs24.x").build())
                .build();
    }

    @Test
    @Order(1)
    void createWebFunctionReportsPendingChildren() {
        CreateWebFunctionResponse created = lambdaWeb.createWebFunction(r -> r
                .functionName(FUNCTION_NAME)
                .revisionConfig(RevisionConfig.builder()
                        .buildConfig(buildConfig())
                        .serviceConfig(ServiceConfig.builder().executionRoleArn(role).build())
                        .build())
                .endpointConfig(EndpointConfig.builder()
                        .endpointName("dev")
                        .endpointType(EndpointType.HOME_REGION)
                        .authType(AuthType.APPLICATION_MANAGED)
                        .autoDeploymentMode(AutoDeploymentMode.LATEST_REVISION)
                        .build())
                .tags(Map.of("env", "sdk")));

        functionArn = created.functionArn();
        firstRevisionId = created.revision().revisionId();
        assertThat(functionArn).endsWith(":web-function/" + FUNCTION_NAME);
        assertThat(created.stateAsString()).isEqualTo("Active");
        assertThat(created.revision().stateAsString()).isEqualTo("Pending");
        assertThat(created.endpoint().stateAsString()).isEqualTo("Pending");
        assertThat(created.endpoint().domainName()).contains(".lambda-web.");
        assertThat(created.createdAt()).isNotNull();

        lambdaWeb.waiter().waitUntilWebFunctionEndpointActive(r -> r.functionName(FUNCTION_NAME).endpointName("dev"));
        GetWebFunctionEndpointResponse endpoint = lambdaWeb.getWebFunctionEndpoint(r -> r
                .functionName(FUNCTION_NAME).endpointName("dev"));
        assertThat(endpoint.stateAsString()).isEqualTo("Active");
        assertThat(endpoint.revisionWeights()).extracting(RevisionWeight::revisionId).containsExactly(firstRevisionId);
        assertThat(endpoint.regionalEndpoints()).containsKey(TestFixtures.region().id());
        assertThat(endpoint.updateStatusAsString()).isNull();
    }

    @Test
    @Order(2)
    void functionsAreAddressableByNameAndArn() {
        assertThat(lambdaWeb.getWebFunction(r -> r.functionName(functionArn)).functionName()).isEqualTo(FUNCTION_NAME);
        assertThat(lambdaWeb.listWebFunctions(r -> {}).functions())
                .extracting(f -> f.functionName()).contains(FUNCTION_NAME);
        assertThat(lambdaWeb.getWebAccountSettings(r -> {}).accountQuotas().maxEndpointsPerFunction()).isEqualTo(10);

        String foreignRegion = functionArn.replace(":" + TestFixtures.region().id() + ":", ":eu-west-1:");
        assertThatThrownBy(() -> lambdaWeb.getWebFunction(r -> r.functionName(foreignRegion)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("The operation is only allowed in the function's home region eu-west-1.");
        assertThatThrownBy(() -> lambdaWeb.getWebFunction(r -> r.functionName("sdk-test-no-such-web-fn")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("does not exist.");
    }

    @Test
    @Order(3)
    void aNewRevisionMovesALatestRevisionEndpoint() {
        CreateWebFunctionRevisionResponse revision = lambdaWeb.createWebFunctionRevision(r -> r
                .functionName(FUNCTION_NAME)
                .description("second")
                .buildConfig(buildConfig())
                .serviceConfig(ServiceConfig.builder().executionRoleArn(role).timeoutSeconds(60)
                        .environmentVariables(Map.of("APP_VERSION", "2")).build()));
        secondRevisionId = revision.revisionId();
        assertThat(revision.stateAsString()).isEqualTo("Pending");
        assertThat(revision.serviceConfig().timeoutSeconds()).isEqualTo(60);
        assertThat(revision.serviceConfig().maxConcurrencyPerEnvironment()).isEqualTo(64);
        assertThat(revision.serviceConfig().telemetryConfig().loggingConfig().logGroup())
                .isEqualTo("/aws/lambda/web/" + FUNCTION_NAME);

        lambdaWeb.waiter().waitUntilWebFunctionRevisionActive(r -> r.functionName(FUNCTION_NAME).revisionId(secondRevisionId));
        ListWebFunctionRevisionsResponse revisions = lambdaWeb.listWebFunctionRevisions(r -> r.functionName(FUNCTION_NAME));
        assertThat(revisions.revisions()).extracting(s -> s.revisionId()).containsExactly(secondRevisionId, firstRevisionId);
        assertThat(lambdaWeb.getWebFunctionEndpoint(r -> r.functionName(FUNCTION_NAME).endpointName("dev"))
                .revisionWeights()).extracting(RevisionWeight::revisionId).containsExactly(secondRevisionId);
    }

    @Test
    @Order(4)
    void weightedRoutingAndTheInUseGuard() {
        UpdateWebFunctionEndpointResponse update = lambdaWeb.updateWebFunctionEndpoint(r -> r
                .functionName(FUNCTION_NAME).endpointName("dev")
                .autoDeploymentMode(AutoDeploymentMode.DISABLED)
                .revisionWeights(RevisionWeight.builder().revisionId(firstRevisionId).weight(90).build(),
                        RevisionWeight.builder().revisionId(secondRevisionId).weight(10).build()));
        assertThat(update.updateStatusAsString()).isEqualTo("InProgress");
        assertThat(update.revisionWeights()).hasSize(2);

        lambdaWeb.waiter().waitUntilWebFunctionEndpointUpdated(r -> r.functionName(FUNCTION_NAME).endpointName("dev"));
        assertThat(lambdaWeb.getWebFunctionEndpoint(r -> r.functionName(FUNCTION_NAME).endpointName("dev"))
                .updateStatusAsString()).isEqualTo("Successful");

        assertThatThrownBy(() -> lambdaWeb.deleteWebFunctionRevision(r -> r
                .functionName(FUNCTION_NAME).revisionId(firstRevisionId)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("the endpoints [dev] are using it");
        assertThatThrownBy(() -> lambdaWeb.updateWebFunctionEndpoint(r -> r
                .functionName(FUNCTION_NAME).endpointName("dev")
                .revisionWeights(RevisionWeight.builder().revisionId("rev-zzzzz").weight(100).build())))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("The revision rev-zzzzz does not exist.");
    }

    @Test
    @Order(5)
    void validationErrorsUseTheAwsWording() {
        assertThatThrownBy(() -> lambdaWeb.createWebFunctionRevision(r -> r
                .functionName(FUNCTION_NAME)
                .buildConfig(buildConfig())
                .serviceConfig(ServiceConfig.builder().executionRoleArn(role).timeoutSeconds(2).build())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("1 validation error detected: Value '2' at 'serviceConfig.timeoutSeconds' failed"
                        + " to satisfy constraint: Member must have value greater than or equal to 3");
        assertThatThrownBy(() -> lambdaWeb.getWebFunctionRevision(r -> r.functionName(FUNCTION_NAME).revisionId("nope")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid revision id or ARN 'nope'");
        assertThatThrownBy(() -> lambdaWeb.createWebFunctionEndpoint(r -> r
                .functionName(FUNCTION_NAME).endpointName("prod")
                .endpointType(EndpointType.HOME_REGION).authType(AuthType.APPLICATION_MANAGED)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("The revisionWeights must be specified when the autoDeploymentMode is Disabled.");
        String missingRole = role.replace(roleName, roleName + "-missing");
        assertThatThrownBy(() -> lambdaWeb.createWebFunctionRevision(r -> r
                .functionName(FUNCTION_NAME)
                .buildConfig(buildConfig())
                .serviceConfig(ServiceConfig.builder().executionRoleArn(missingRole).build())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("The execution role " + missingRole + " cannot be assumed by lambda.amazonaws.com."
                        + " Verify its trust policy. If the role was just created, retry in a few seconds.");
        assertThatThrownBy(() -> lambdaWeb.createWebFunctionRevision(r -> r
                .functionName(FUNCTION_NAME)
                .buildConfig(buildConfig())
                .serviceConfig(ServiceConfig.builder()
                        .executionRoleArn("arn:aws:iam::111122223333:role/" + roleName).build())))
                .isInstanceOfSatisfying(AccessDeniedException.class, e -> assertThat(e.statusCode()).isEqualTo(403))
                .hasMessageContaining("Cross-account pass role is not allowed.");
    }

    @Test
    @Order(6)
    void tagsAndResourcePolicy() {
        lambdaWeb.tagResource(r -> r.resource(functionArn).tags(Map.of("team", "web")));
        assertThat(lambdaWeb.listTags(r -> r.resource(functionArn)).tags())
                .containsEntry("env", "sdk").containsEntry("team", "web");
        lambdaWeb.untagResource(r -> r.resource(functionArn).tagKeys("env"));
        assertThat(lambdaWeb.listTags(r -> r.resource(functionArn)).tags()).containsOnlyKeys("team");

        String publicPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"" + functionArn + "/endpoint/*\"}]}";
        assertThatThrownBy(() -> lambdaWeb.putResourcePolicy(r -> r.resourceArn(functionArn).policy(publicPolicy)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Lambda blocked the creation of this policy");
        assertThatThrownBy(() -> lambdaWeb.putResourcePolicy(r -> r.resourceArn(functionArn).policy("{}")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Policy has syntax errors.");

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"AWS\":\"arn:aws:iam::000000000000:root\"},\"Action\":\"lambda:InvokeWebFunctionEndpoint\","
                + "\"Resource\":\"" + functionArn + "/endpoint/*\"}]}";
        PutResourcePolicyResponse put = lambdaWeb.putResourcePolicy(r -> r.resourceArn(functionArn).policy(policy));
        assertThat(put.policy()).isEqualTo(policy);
        assertThat(put.revisionId()).matches("[0-9a-f-]{36}");
        assertThat(lambdaWeb.getResourcePolicy(r -> r.resourceArn(functionArn)).revisionId()).isEqualTo(put.revisionId());
        lambdaWeb.deleteResourcePolicy(r -> r.resourceArn(functionArn).revisionId(put.revisionId()));
        assertThatThrownBy(() -> lambdaWeb.getResourcePolicy(r -> r.resourceArn(functionArn)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("The function does not have a policy.");
    }

    @Test
    @Order(7)
    void deleteRemovesTheFunction() {
        lambdaWeb.deleteWebFunction(r -> r.functionName(FUNCTION_NAME));
        lambdaWeb.waiter().waitUntilWebFunctionDeleted(r -> r.functionName(FUNCTION_NAME));
        assertThatThrownBy(() -> lambdaWeb.getWebFunction(r -> r.functionName(FUNCTION_NAME)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
