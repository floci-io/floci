package io.github.hectorvent.floci.services.ssoadmin;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * IAM Identity Center (SSO Admin) business logic.
 *
 * <p>Models the single organization Identity Center instance LZA's
 * Custom::GetIdentityCenterInstanceMetadata Lambda expects: its {@code ListInstances} call
 * succeeds only when exactly one instance is returned, carrying both {@code InstanceArn} and
 * {@code IdentityStoreId}. The identifiers are fixed so they stay stable across calls and
 * container restarts — LZA persists them into SSM parameters between stages.
 */
@ApplicationScoped
public class SsoAdminService {

    private static final String INSTANCE_ARN = "arn:aws:sso:::instance/ssoins-7223b02a5d9f7c8e";
    private static final String IDENTITY_STORE_ID = "d-9067f2a3c1";

    public String getInstanceArn() {
        return INSTANCE_ARN;
    }

    public String getIdentityStoreId() {
        return IDENTITY_STORE_ID;
    }
}
