package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Set;

/**
 * The refusals IAM answers for an ARN member whose value parses as no ARN. The model declares a
 * length for those members and no pattern, so what AWS answers beyond the length is recorded
 * rather than published, and this is where the recordings are read.
 *
 * <p>{@code PolicyArn} is {@code arnType} on every operation that takes one, and
 * {@link IamRequestShapes} holds it to that length ahead of this check, which is the order #6402
 * settled. A value that clears 20 characters and is still no ARN is refused here rather than
 * reaching the lookup: a 400 {@code ValidationError} on both sides of an attachment, in two
 * sentences. Attaching names the value, {@code ARN <value> is not valid.}, recorded for a bare
 * policy name on 2026-11-03 and for a value that is no ARN at all on all three attach operations
 * on 2026-11-06. Everything else answers {@code Invalid ARN:  Could not be parsed!}, wholly fixed,
 * the value slot rendering empty so two spaces follow the colon: recorded on the three detach
 * operations on 2026-11-06, on {@code ListEntitiesForPolicy} on 2026-11-03 and on
 * {@code GetPolicy} on 2026-10-29.
 *
 * <p>The other operations that take the member carry no recording of their own and answer the
 * parse sentence with the eight that do, which is this emulator's reading under the standing
 * decision rather than a measurement: one member is read by one parser, and the attach side is the
 * exception the recordings name. An older recording disagrees about that side and is no longer
 * followed. LocalStack's AWS-validated snapshots have {@code AttachUserPolicy} and
 * {@code AttachRolePolicy} answering the parse sentence for the same two values, validated
 * 2025-03-06 and 2025-06-19, where all three 2026 recordings have the attach side naming the
 * value, so AWS looks to have reworded it between. {@code docs/services/iam.md} carries both and
 * says which is followed.
 *
 * <p>{@code PolicySourceArn} is the same type on the two operations that resolve a principal from
 * one, and a value of {@code *} is refused ahead of the declared length rather than by it: AWS
 * answers that value with an entity sentence of its own where the length it publishes for the
 * member is 20, so its entity parse runs first. Recorded on {@code SimulatePrincipalPolicy} on
 * 2026-11-03; {@code GetContextKeysForPrincipalPolicy} takes the same member through the same
 * resolver and answers it the same way, which is a reading rather than a recording.
 */
final class IamArnShapes {

    /** The attach side, which AWS words with the value in it. */
    private static final Set<String> ATTACH_POLICY = Set.of(
            "AttachUserPolicy", "AttachGroupPolicy", "AttachRolePolicy");

    /** What IAM answers wherever it reports nothing it could parse, the value slot left empty. */
    private static final String UNPARSEABLE_ARN = "Invalid ARN:  Could not be parsed!";

    /** The member the two principal simulators resolve an entity from. */
    private static final String POLICY_SOURCE_ARN = "PolicySourceArn";

    private static final String POLICY_ARN = "PolicyArn";

    private IamArnShapes() {
    }

    /**
     * Refuses a {@code PolicyArn} that parses as no ARN, on every operation whose generated input
     * shape declares the member. Runs after the declared constraints, so a value too short to be
     * any ARN keeps answering the length it breaks.
     */
    static void checkPolicyArn(String action, MultivaluedMap<String, String> params) {
        if (!IamRequestShapes.hasMember(action, POLICY_ARN)) {
            return;
        }
        String value = params.getFirst(POLICY_ARN);
        if (value == null || AwsArnUtils.isArn(value)) {
            return;
        }
        throw ATTACH_POLICY.contains(action)
                ? new AwsException("ValidationError", "ARN " + value + " is not valid.", 400)
                : new AwsException("ValidationError", UNPARSEABLE_ARN, 400);
    }

    /**
     * Refuses a {@code PolicySourceArn} of {@code *}, which names no entity and is answered ahead
     * of the member's declared length. Every other value is left to the length and then to the
     * resolver, which refuses what parses as no ARN in the operation's own sentence.
     */
    static void checkPolicySourceArn(String action, MultivaluedMap<String, String> params) {
        if (!"SimulatePrincipalPolicy".equals(action)
                && !"GetContextKeysForPrincipalPolicy".equals(action)) {
            return;
        }
        if ("*".equals(params.getFirst(POLICY_SOURCE_ARN))) {
            throw new AwsException("InvalidInput", "Invalid Entity Arn: *"
                    + " does not clearly define entity type and name.", 400);
        }
    }
}
