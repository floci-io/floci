package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateGrantResponse;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;
import software.amazon.awssdk.services.kms.model.GrantOperation;
import software.amazon.awssdk.services.kms.model.ListGrantsResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KMS grant lifecycle compatibility")
class KmsGrantLifecycleTest {

    private static final String GRANTEE_PRINCIPAL = "arn:aws:iam::000000000000:role/grantee";

    private static KmsClient kms;

    @BeforeAll
    static void setup() {
        kms = TestFixtures.kmsClient();
    }

    @AfterAll
    static void cleanup() {
        if (kms != null) {
            kms.close();
        }
    }

    @Test
    void createListGrantRoundTrip() {
        final String[] keyId = new String[1];
        try {
            CreateKeyResponse keyResponse = kms.createKey(b -> b.description("grant-create-list-key"));
            keyId[0] = keyResponse.keyMetadata().keyId();

            CreateGrantResponse grantResponse = kms.createGrant(b -> b
                    .keyId(keyId[0])
                    .granteePrincipal(GRANTEE_PRINCIPAL)
                    .operations(GrantOperation.DECRYPT));

            String grantId = grantResponse.grantId();
            String grantToken = grantResponse.grantToken();

            assertThat(grantId).isNotBlank();
            assertThat(grantToken).isNotBlank();

            ListGrantsResponse grantsResponse = kms.listGrants(b -> b.keyId(keyId[0]));
            assertThat(grantsResponse.truncated()).isFalse();
            assertThat(grantsResponse.grants())
                    .anySatisfy(grant -> {
                        assertThat(grant.grantId()).isEqualTo(grantId);
                        assertThat(grant.keyId()).contains(keyId[0]);
                        assertThat(grant.granteePrincipal()).isEqualTo(GRANTEE_PRINCIPAL);
                        assertThat(grant.operations()).contains(GrantOperation.DECRYPT);
                    });
        } finally {
            if (keyId[0] != null) {
                kms.scheduleKeyDeletion(b -> b
                        .keyId(keyId[0])
                        .pendingWindowInDays(7));
            }
        }
    }

    @Test
    void createListRevokeGrantRoundTrip() {
        final String[] keyId = new String[1];
        try {
            CreateKeyResponse keyResponse = kms.createKey(b -> b.description("grant-revoke-key"));
            keyId[0] = keyResponse.keyMetadata().keyId();

            CreateGrantResponse grantResponse = kms.createGrant(b -> b
                    .keyId(keyId[0])
                    .granteePrincipal(GRANTEE_PRINCIPAL)
                    .operations(GrantOperation.DECRYPT));

            String grantId = grantResponse.grantId();
            String grantToken = grantResponse.grantToken();

            assertThat(grantId).isNotBlank();
            assertThat(grantToken).isNotBlank();

            ListGrantsResponse beforeRevoke = kms.listGrants(b -> b.keyId(keyId[0]));
            assertThat(beforeRevoke.grants())
                    .anyMatch(grant -> grantId.equals(grant.grantId()));

            kms.revokeGrant(b -> b
                    .keyId(keyId[0])
                    .grantId(grantId));

            ListGrantsResponse afterRevoke = kms.listGrants(b -> b.keyId(keyId[0]));
            assertThat(afterRevoke.grants())
                    .noneMatch(grant -> grantId.equals(grant.grantId()));
        } finally {
            if (keyId[0] != null) {
                kms.scheduleKeyDeletion(b -> b
                        .keyId(keyId[0])
                        .pendingWindowInDays(7));
            }
        }
    }

    @Test
    void createListRetireGrantRoundTrip() {
        final String[] keyId = new String[1];
        try {
            CreateKeyResponse keyResponse = kms.createKey(b -> b.description("grant-retire-key"));
            keyId[0] = keyResponse.keyMetadata().keyId();

            CreateGrantResponse grantResponse = kms.createGrant(b -> b
                    .keyId(keyId[0])
                    .granteePrincipal(GRANTEE_PRINCIPAL)
                    .operations(GrantOperation.DECRYPT));

            String grantId = grantResponse.grantId();
            String grantToken = grantResponse.grantToken();

            assertThat(grantId).isNotBlank();
            assertThat(grantToken).isNotBlank();

            ListGrantsResponse beforeRetire = kms.listGrants(b -> b.keyId(keyId[0]));
            assertThat(beforeRetire.grants())
                    .anyMatch(grant -> grantId.equals(grant.grantId()));

            kms.retireGrant(b -> b
                    .grantId(grantId)
                    .grantToken(grantToken));

            ListGrantsResponse afterRetire = kms.listGrants(b -> b.keyId(keyId[0]));
            assertThat(afterRetire.grants())
                    .noneMatch(grant -> grantId.equals(grant.grantId()));
        } finally {
            if (keyId[0] != null) {
                kms.scheduleKeyDeletion(b -> b
                        .keyId(keyId[0])
                        .pendingWindowInDays(7));
            }
        }
    }
}
