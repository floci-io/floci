package io.floci.aws.services.iam;

import java.util.List;

/**
 * Which delegation requests an identity owns, asked by the policy set rather than by a handler.
 *
 * <p>The customer-side guide says of these requests that "By default, IAM principals who initiate a
 * delegation request are automatically granted permissions to manage that specific request", naming
 * what that covers: viewing the request, rejecting it, forwarding it to an administrator for
 * approval, releasing the exchange token after approval, and listing the requests they own. So the
 * grant is per request rather than per account, and the policy set has to know which requests the
 * caller holds before it can write one.
 *
 * <p>Declared as a port so {@link IamService} does not depend on the delegation store it would
 * otherwise have to read, the way {@code ScpProvider} and {@code ResourcePolicyProvider} are
 * declared for the same reason. {@link DelegationRequestService} implements it.
 */
public interface DelegationRequestOwnership {

    /**
     * The ids of the requests {@code ownerArn} owns in {@code accountId}, newest first is not
     * promised and order does not matter: the grant names them all. Empty when the identity owns
     * none, which is every caller who has claimed no request.
     */
    List<String> requestIdsOwnedBy(String accountId, String ownerArn);
}
