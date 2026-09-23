package io.github.hectorvent.floci.services.ec2.model;

import java.time.Instant;

/**
 * An IAM instance profile association as the association actions report it: the state is the
 * transitional one AWS answers with ({@code associating}, {@code disassociating}); the describe
 * action reports the settled {@code associated}.
 */
public record IamInstanceProfileAssociation(String associationId, String instanceId, String arn,
                                            String id, String state, Instant timestamp) {
}
