package io.floci.aws.services.redshiftserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.RequestCallerResolver;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessSnapshotKeyAccessTest {

    private static final String OWNER = "111111111111";
    private static final String CALLER = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String KEY_ID = "acc0a82a-f9ff-42a9-b9ee-8d53e5d69084";
    private static final String KEY_ARN = "arn:aws:kms:" + REGION + ":" + OWNER + ":key/" + KEY_ID;
    private static final String ALLOW_CALLER = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":"
            + "\"arn:aws:iam::" + CALLER + ":root\"},\"Action\":\"kms:Decrypt\",\"Resource\":\"*\"}]}";

    private EmulatorConfig config;
    private KmsService kms;
    private RedshiftServerlessSnapshotKeyAccess access;
    private RedshiftServerlessSnapshot snapshot;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        kms = mock(KmsService.class);
        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerArns(any())).thenReturn(Optional.empty());
        Instance<RequestContext> requestContext = mock(Instance.class);
        RegionResolver regionResolver = new RegionResolver(REGION, CALLER);
        access = new RedshiftServerlessSnapshotKeyAccess(config, kms, new IamPolicyEvaluator(new ObjectMapper()),
                regionResolver, new RequestCallerResolver(iamService, regionResolver, requestContext));
        snapshot = new RedshiftServerlessSnapshot();
        snapshot.setSnapshotName("shared");
    }

    private void keyPolicy(String arn, String policy) {
        when(kms.findKeyPolicyOfAccount(arn)).thenReturn(Optional.ofNullable(policy));
    }

    private void restore() {
        access.requireRestoreAccess(snapshot, OWNER, REGION);
    }

    @Test
    void aKeyPolicyThatAllowsTheCallersAccountLetsTheRestoreThrough() {
        snapshot.setKmsKeyId(KEY_ARN);
        keyPolicy(KEY_ARN, ALLOW_CALLER);

        assertDoesNotThrow(this::restore);
    }

    @Test
    void aBareKeyIdIsTheOwnersKeyInTheSnapshotsRegion() {
        snapshot.setKmsKeyId(KEY_ID);
        keyPolicy(KEY_ARN, ALLOW_CALLER);

        assertDoesNotThrow(this::restore);
    }

    @Test
    void anAliasNameIsTheOwnersAliasInTheSnapshotsRegion() {
        String aliasArn = "arn:aws:kms:" + REGION + ":" + OWNER + ":alias/shared";
        snapshot.setKmsKeyId("alias/shared");
        keyPolicy(aliasArn, ALLOW_CALLER);

        assertDoesNotThrow(this::restore);
    }

    @Test
    void aKeyThatNoPolicyAllowsRefusesTheRestoreNamingTheActionAndTheKey() {
        snapshot.setKmsKeyId(KEY_ARN);
        keyPolicy(KEY_ARN, "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + OWNER + ":root\"},\"Action\":\"kms:*\",\"Resource\":\"*\"}]}");

        AwsException refused = assertThrows(AwsException.class, this::restore);

        assertEquals("AccessDeniedException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("kms:Decrypt"));
        assertTrue(refused.getMessage().contains(KEY_ARN));
    }

    @Test
    void anExplicitDenyOverridesAnAllow() {
        snapshot.setKmsKeyId(KEY_ARN);
        keyPolicy(KEY_ARN, ALLOW_CALLER.replace("]}", ",{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"arn:aws:iam::"
                + CALLER + ":root\"},\"Action\":\"kms:Decrypt\",\"Resource\":\"*\"}]}"));

        assertThrows(AwsException.class, this::restore);
    }

    @Test
    void aMissingKeyOrKeyWithoutAPolicyRefusesTheRestore() {
        snapshot.setKmsKeyId(KEY_ARN);
        keyPolicy(KEY_ARN, null);

        assertThrows(AwsException.class, this::restore);
    }

    @Test
    void theAwsOwnedKeyAndNoKeyAtAllNeedNoKeyAccess() {
        snapshot.setKmsKeyId(RedshiftServerlessService.AWS_OWNED_KMS_KEY);
        assertDoesNotThrow(this::restore);

        snapshot.setKmsKeyId(null);
        assertDoesNotThrow(this::restore);

        snapshot.setKmsKeyId(" ");
        assertDoesNotThrow(this::restore);

        verify(kms, never()).findKeyPolicyOfAccount(anyString());
    }

    @Test
    void nothingIsEvaluatedWithoutIamEnforcement() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        snapshot.setKmsKeyId(KEY_ARN);

        assertDoesNotThrow(this::restore);

        verify(kms, never()).findKeyPolicyOfAccount(anyString());
    }
}
