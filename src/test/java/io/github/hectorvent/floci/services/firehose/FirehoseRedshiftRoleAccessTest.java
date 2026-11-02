package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The role checks of a Redshift destination against the real {@link ServiceRoleAuthorizer}, with IAM enforcement on. */
class FirehoseRedshiftRoleAccessTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String STREAM_ARN = "arn:aws:firehose:us-east-1:" + ACCOUNT_ID + ":deliverystream/rs";
    private static final String LOAD_ROLE = "arn:aws:iam::" + ACCOUNT_ID + ":role/firehose-load";
    private static final String STAGING_ROLE = "arn:aws:iam::" + ACCOUNT_ID + ":role/firehose-staging";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"firehose.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String TRUST_OTHER_STREAM = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"firehose.amazonaws.com"},"Action":"sts:AssumeRole",
            "Condition":{"StringEquals":{"aws:SourceArn":"arn:aws:firehose:us-east-1:000000000000:deliverystream/other"}}}]}""";
    private static final String TRUST_LAMBDA = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
            "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    private static final String ALLOW_PUT = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:PutObject","Resource":"arn:aws:s3:::stage/*"}]}""";
    private static final String ALLOW_GET = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"arn:aws:s3:::stage/*"}]}""";
    private static final String DENY_GET = """
            {"Version":"2012-10-17","Statement":[
            {"Effect":"Allow","Action":"s3:*","Resource":"*"},
            {"Effect":"Deny","Action":"s3:GetObject","Resource":"arn:aws:s3:::stage/*"}]}""";

    private IamService iamService;
    private FirehoseRedshiftDelivery delivery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        iamService = mock(IamService.class);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        Instance<ScpProvider> scpProvider = mock(Instance.class);
        when(scpProvider.isResolvable()).thenReturn(false);
        ServiceRoleAuthorizer authorizer = new ServiceRoleAuthorizer(config, iamService, evaluator,
                new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), scpProvider);
        delivery = new FirehoseRedshiftDelivery(mock(RedshiftService.class), mock(RedshiftServerlessService.class),
                mock(SecretsManagerService.class), authorizer, new ObjectMapper(),
                (url, user, password, sql) -> { });
    }

    private void role(String name, String arn, String trust, String policy) {
        when(iamService.findRole(ACCOUNT_ID, name))
                .thenReturn(Optional.of(new IamRole("id-" + name, name, "/", arn, trust)));
        when(iamService.resolvePrincipalContext(arn)).thenReturn(CallerContext.of(List.of(policy)));
    }

    private static RedshiftDestination destination() {
        RedshiftDestination destination = new RedshiftDestination();
        destination.setRoleArn(LOAD_ROLE);
        S3Destination s3 = new S3Destination();
        s3.setRoleArn(STAGING_ROLE);
        destination.setS3(s3);
        return destination;
    }

    @Test
    void trustedRolesWithThePermissionsStageAndLoad() {
        role("firehose-staging", STAGING_ROLE, TRUST, ALLOW_PUT);
        role("firehose-load", LOAD_ROLE, TRUST, ALLOW_GET);

        assertDoesNotThrow(() -> delivery.requireStagingAccess(ACCOUNT_ID, STREAM_ARN, destination(), "stage", "k"));
        assertDoesNotThrow(() -> delivery.requireLoadAccess(ACCOUNT_ID, STREAM_ARN, destination(), "stage", "k"));
    }

    @Test
    void aStagingRoleThatDoesNotTrustFirehoseIsRefused() {
        role("firehose-staging", STAGING_ROLE, TRUST_LAMBDA, ALLOW_PUT);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.requireStagingAccess(ACCOUNT_ID, STREAM_ARN, destination(), "stage", "k"));

        assertTrue(e.getMessage().contains("does not trust firehose.amazonaws.com"), e.getMessage());
    }

    @Test
    void aTrustConditionOnAnotherStreamRefusesThisOne() {
        role("firehose-load", LOAD_ROLE, TRUST_OTHER_STREAM, ALLOW_GET);

        assertThrows(IllegalStateException.class,
                () -> delivery.requireLoadAccess(ACCOUNT_ID, STREAM_ARN, destination(), "stage", "k"));
    }

    @Test
    void aStagingRoleWithoutPutObjectIsRefused() {
        role("firehose-staging", STAGING_ROLE, TRUST, ALLOW_GET);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.requireStagingAccess(ACCOUNT_ID, STREAM_ARN, destination(), "stage", "k"));

        assertTrue(e.getMessage().contains("s3:PutObject"), e.getMessage());
    }

    @Test
    void anExplicitDenyOnGetObjectRefusesTheLoad() {
        role("firehose-load", LOAD_ROLE, TRUST, DENY_GET);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.requireLoadAccess(ACCOUNT_ID, STREAM_ARN, destination(), "stage", "k"));

        assertTrue(e.getMessage().contains("s3:GetObject"), e.getMessage());
    }

    @Test
    void aRoleOfAnotherAccountIsRefused() {
        RedshiftDestination destination = destination();
        destination.setRoleArn("arn:aws:iam::999999999999:role/firehose-load");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.requireLoadAccess(ACCOUNT_ID, STREAM_ARN, destination, "stage", "k"));

        assertTrue(e.getMessage().contains("belongs to another account"), e.getMessage());
    }

    @Test
    void aMissingStagingRoleIsRefused() {
        RedshiftDestination destination = destination();
        destination.getS3().setRoleArn(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.requireStagingAccess(ACCOUNT_ID, STREAM_ARN, destination, "stage", "k"));

        assertTrue(e.getMessage().contains("not a valid ARN"), e.getMessage());
    }

    @Test
    void aBlankLoadRoleIsRefused() {
        RedshiftDestination destination = destination();
        destination.setRoleArn("   ");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> delivery.requireLoadAccess(ACCOUNT_ID, STREAM_ARN, destination, "stage", "k"));

        assertTrue(e.getMessage().contains("not a valid ARN"), e.getMessage());
    }
}
