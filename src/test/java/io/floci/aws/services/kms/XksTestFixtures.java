package io.floci.aws.services.kms;

import io.floci.aws.services.kms.model.KmsCustomKeyStore;

/** Shared, non-production XKS credentials and metadata fixtures for KMS tests. */
final class XksTestFixtures {
    static final String CREDENTIAL_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    static final String ACCESS_KEY_ID = "ABCDEFGHIJKLMNOPQRST";
    static final String SECRET_ACCESS_KEY = "A".repeat(43);

    private XksTestFixtures() {
    }

    static XksCredentialCipher credentialCipher() {
        return new XksCredentialCipher(CREDENTIAL_ENCRYPTION_KEY);
    }

    static KmsCustomKeyStore candidate(String name) {
        KmsCustomKeyStore store = new KmsCustomKeyStore();
        store.setCustomKeyStoreName(name);
        store.setAccessKeyId(ACCESS_KEY_ID);
        store.setProxyUriEndpoint("https://proxy.example.com");
        store.setProxyUriPath("/kms/xks/v1");
        return store;
    }
}
