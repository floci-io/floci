package io.floci.aws.testing;

/**
 * Policy documents for tests whose subject is not the document.
 *
 * <p>Every IAM write that takes one holds it to being a policy document, so a test that needs a
 * role, a managed policy or an inline policy to exist cannot hand in a placeholder like
 * {@code "{}"}: that is a document IAM refuses with {@code MalformedPolicyDocument}, as
 * {@code docs/services/iam.md} records. These are the smallest documents that are policies, so a
 * test naming one says that the document is beside its point.
 */
public final class IamTestPolicies {

    /**
     * The smallest document that is a policy: the element the grammar requires, carrying no
     * statement, so it describes no permission and is refused by nothing. A trust policy, an
     * inline policy and a managed policy are all held to the grammar, so one constant serves a
     * test that needs any of them to exist without being about the document.
     */
    public static final String EMPTY_POLICY_DOCUMENT = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    /** A trust policy a service principal can assume the role under. */
    public static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    /** An identity policy that allows one action on every resource. */
    public static final String IDENTITY_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    /**
     * A policy document distinct for each {@code marker} and refused by nothing, for a test that
     * needs several documents and is about none of them. The marker rides in the statement's
     * {@code Sid}, so what differs between two of them is a value the language defines, and the
     * document stays short because an inline policy set is bounded by the characters it carries,
     * 2048 for a user. {@code Version} is left out, which the language allows.
     */
    public static String policyDocument(Object marker) {
        return "{\"Statement\":[{\"Sid\":\"s" + marker + "\",\"Effect\":\"Allow\","
                + "\"Action\":\"s3:Get\",\"Resource\":\"*\"}]}";
    }

    private IamTestPolicies() {
    }
}
