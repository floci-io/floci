package io.floci.aws.services.mwaa;

import io.floci.aws.services.mwaa.model.Environment;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * The DAG-sync poller runs outside any request, so each environment has to read its DAG bucket
 * as the account that owns it: an environment of a non-default account syncs the DAGs of that
 * account's bucket.
 */
@QuarkusTest
class MwaaDagSyncAccountScopeIntegrationTest {

    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-east-1";

    @InjectMock
    MwaaEnvironmentManager environmentManager;

    @Inject
    MwaaService mwaaService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void dagSyncOfANonDefaultAccountsEnvironmentReadsThatAccountsBucket() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "mwaa-account-dags-" + suffix;
        String environment = "mwaa-account-env-" + suffix;
        byte[] dag = "from airflow import DAG\n".getBytes(StandardCharsets.UTF_8);

        s3().when().put("/" + bucket).then().statusCode(200);
        cleanup.register(() -> s3().when().delete("/" + bucket).then().statusCode(204));
        s3().body(dag).when().put("/" + bucket + "/dags/hello.py").then().statusCode(200);
        cleanup.register(() -> s3().when().delete("/" + bucket + "/dags/hello.py").then().statusCode(204));

        given().header("Authorization", auth(ACCOUNT, REGION, "airflow")).contentType("application/json")
                .body("{\"ExecutionRoleArn\":\"arn:aws:iam::" + ACCOUNT + ":role/mwaa-execution-role\","
                        + "\"SourceBucketArn\":\"arn:aws:s3:::" + bucket + "\",\"DagS3Path\":\"dags\","
                        + "\"NetworkConfiguration\":{\"SubnetIds\":[\"subnet-1\",\"subnet-2\"],"
                        + "\"SecurityGroupIds\":[\"sg-1\"]}}")
                .when().put("/environments/" + environment)
                .then().statusCode(200);
        cleanup.register(() -> given().header("Authorization", auth(ACCOUNT, REGION, "airflow"))
                .when().delete("/environments/" + environment).then().statusCode(200));

        mwaaService.syncDagsOfAvailableEnvironments();

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(environmentManager).copyDagFile(
                argThat((Environment synced) -> synced != null && synced.getName().equals(environment)),
                eq("hello.py"), content.capture());
        assertArrayEquals(dag, content.getValue());
    }

    private static RequestSpecification s3() {
        return given().header("Authorization", auth(ACCOUNT, REGION, "s3"));
    }
}
