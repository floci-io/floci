package io.floci.aws.services.ssoadmin;

/** How an account may use an identity store ({@link SsoAdminService#identityStoreAccess}). */
public enum IdentityStoreAccess {
    /** Every Identity Store action: the store's own account, or its organization's delegated administrator. */
    FULL,
    /** Only the read actions: another account of an organization instance's organization. */
    READ,
    /** None: the store is not one the account can see. */
    NONE
}
