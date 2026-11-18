package io.floci.aws.services.iam;

import java.util.List;
import java.util.Optional;

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

    /**
     * The ARN the request {@code requestId} records as its owner, where {@code accountId} is the account that
     * owns it; empty when no request carries that id, when the account owning it is another, and when it
     * records no owner at all, which is every request until it is claimed.
     *
     * <p>Asked for by the condition key the Service Reference publishes on the delegation request type, which
     * AWS describes as filtering "based on the AWS identity or principal that owns the delegation request". The
     * lookup is of what the store holds under that id and nothing more: which requests an operation acts on is
     * the generated table's to say.
     */
    Optional<String> ownerArnOf(String accountId, String requestId);
}
