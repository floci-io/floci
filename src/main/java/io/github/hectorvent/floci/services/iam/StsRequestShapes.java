package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.QueryConstraintCheck;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.List;
import java.util.Map;

/**
 * The declared constraints STS reads from its model rather than from a hand-written check, as
 * {@code tools/aws/regen_sts_shapes.py} vendors them into {@code aws/sts-request-shapes.json}. It
 * is IAM's walk over STS's own file: the two services share the Query protocol, the message wording
 * and the generator, and differ in which operations are read and in one word of the message. STS
 * quotes the offending value where IAM leaves it out, which is what its own capture carries and
 * what DRIFT-13 settled for both, so the form is named here rather than taken as a default.
 *
 * <p>The violations are returned rather than thrown, because each STS operation answers its own
 * refusals in its own order: {@code GetSessionToken} refuses session credentials before it reads
 * anything, and a value that is no integer at all is {@code InvalidParameterValue} rather than a
 * constraint violation. So the operation asks for the violations where its hand-written check ran
 * and answers them in the namespace it answers everything else in.
 *
 * <p>The vendored file carries a wave of operations rather than all 11, and grows a wave at a time,
 * each one chosen where no operation answers a declared constraint in a wording of its own.
 * {@code GetAccessKeyInfo} is the parity case, its own check having answered {@code accessKeyId}
 * from the same bounds and the same pattern; {@code GetSessionToken} brings {@code SerialNumber},
 * {@code TokenCode} and {@code MinimumSessionTokenSize}, which nothing read at all; and
 * {@code GetWebIdentityToken} is where the audience and tag checks come off hand-written code.
 */
final class StsRequestShapes {

    private static final QueryConstraintCheck CHECK = QueryConstraintCheck.load(
            "aws/sts-request-shapes.json", "STS request shapes",
            SmithyValidationErrors.Violations.QUOTED);

    private StsRequestShapes() {
    }

    /** Every constraint the model declares for {@code action} that {@code params} breaks. */
    static List<String> violations(String action, MultivaluedMap<String, String> params) {
        return CHECK.violations(action, params);
    }

    /**
     * The same, with a constraint the reference documents and the model declares nowhere reported
     * in its own member's place, keyed by that member's name.
     */
    static List<String> violations(String action, MultivaluedMap<String, String> params,
                                   Map<String, String> documented) {
        return CHECK.violations(action, params, documented);
    }
}
