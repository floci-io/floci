package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.QueryConstraintCheck;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * The declared constraints IAM reads from its model rather than from a hand-written check, as
 * {@code tools/aws/regen_iam_shapes.py} vendors them into
 * {@code aws/iam-request-shapes.json}. The check runs ahead of the operation, which is where AWS's
 * validation layer runs: a request that breaks a declared constraint is a 400 {@code
 * ValidationError} whether or not the thing it names exists, the ordering #6402 settled for this
 * package.
 *
 * <p>The violations read as IAM writes them, naming the member with no value beside it, which is
 * {@link IamValidationErrors}' reading of the captures rather than the walk's own default.
 *
 * <p>The vendored file carries 162 of IAM's 180 operations, which is every one the model
 * constrains bar the two account alias operations: those answer their single member in AWS's own
 * wording, so there is nothing left for the walk to read on them. It arrived a wave at a time,
 * each wave chosen where no operation checked a declared constraint by hand, so the model added
 * the refusals AWS answers without rewording an answer already given. The managed-policy family
 * came first, with {@code AttachRolePolicy} and {@code DetachRolePolicy} as the exception and the
 * parity case, {@code IamService.validateRoleName} already answering {@code roleName} in the same
 * form. {@code GetAccountAuthorizationDetails} was the first read through the list walk, its
 * {@code Filter}, {@code MaxItems} and {@code Marker} having been read by nothing at all, and the
 * last wave took the operations whose requests nest a map or a list deeper than the walk read:
 * {@code AcquireRole}, {@code PutAccountProperties}, {@code CreateDelegationRequest} and both
 * simulators. {@code MaxItems} and {@code Marker} are held to the model here rather than by the
 * paging itself, for every paged operation.
 *
 * <p>Two hand-written checks answered a map before this read one, and the answers they gave are
 * the answers it gives: an entry's value is named under the key the entry carried, and a key
 * outside its shape's rules carries the whole set the shape declares. {@code docs/services/iam.md}
 * records what moved with them and what did not.
 */
final class IamRequestShapes {

    private static final QueryConstraintCheck CHECK = QueryConstraintCheck.load(
            "aws/iam-request-shapes.json", "IAM request shapes", IamValidationErrors.MESSAGES);

    private IamRequestShapes() {
    }

    /** Throws {@code ValidationError} when the request breaks a constraint the model declares. */
    static void check(String action, MultivaluedMap<String, String> params) {
        CHECK.check(action, params);
    }
}
