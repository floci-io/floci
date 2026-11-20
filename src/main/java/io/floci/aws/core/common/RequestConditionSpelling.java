package io.floci.aws.core.common;

/**
 * Spells a value a request member carries the way its service spells the member's botocore shape, for the condition
 * keys IAM reads from request members (Route 53 spells a {@code DNSName} as its record names are spelled, so
 * {@code route53:ChangeResourceRecordSetsNormalizedRecordNames} carries the name as AWS normalizes it).
 *
 * <p>Implemented by the service whose shapes have a spelling of their own; looked up lazily by
 * {@code IamConditionContextResolver} through {@code Instance<RequestConditionSpelling>} for the namespace a request
 * is of, so IAM never depends on that service directly. Which keys an action takes, which members carry them and the
 * members' shapes all come from the generated {@code request-condition-keys.json}: a spelling only answers for the
 * shape the table gives, never for a key or an action.</p>
 */
public interface RequestConditionSpelling {

    /** The namespace of the requests whose members it spells, as the table names it ({@code route53}). */
    String namespace();

    /** {@code value} as the service spells a member of {@code shape}; {@code value} itself for a shape it does not. */
    String spell(String shape, String value);
}
