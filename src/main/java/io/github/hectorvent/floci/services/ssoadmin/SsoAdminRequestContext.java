package io.github.hectorvent.floci.services.ssoadmin;

import jakarta.enterprise.context.RequestScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What this request found, kept for the request.
 *
 * <p>A {@code ProvisionPermissionSet} for {@code ALL_PROVISIONED_ACCOUNTS} provisions a permission set to every
 * account it is provisioned to, and the permission check finds those accounts and checks each one. Another request
 * may provision the permission set to one more account while this one is running, with an assignment of its own,
 * and the request must provision to the accounts it was checked on: an account found again afterwards could be one
 * no policy was checked against.
 */
@RequestScoped
public class SsoAdminRequestContext {

    /** The accounts a permission set was provisioned to, by the permission set's ARN. */
    private final Map<String, List<String>> provisionedAccounts = new HashMap<>();

    /**
     * The accounts the permission set was provisioned to when this request found them, and null when this request
     * has not looked for them yet.
     */
    List<String> provisionedAccounts(String permissionSetArn) {
        return provisionedAccounts.get(permissionSetArn);
    }

    /** Remembers the accounts the permission set is provisioned to, so that the request provisions to those. */
    void pinProvisionedAccounts(String permissionSetArn, List<String> accountIds) {
        provisionedAccounts.put(permissionSetArn, List.copyOf(accountIds));
    }
}
