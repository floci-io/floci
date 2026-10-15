package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Policy variables: the {@code ${...}} placeholders a policy writes where it cannot know a value
 * until the request arrives. The evaluator resolved none of them before, so a statement scoped to
 * {@code user/${aws:username}} matched no user at all, which is how AWS writes its own
 * {@code IAMUserChangePassword} policy and how a great many of the managed policies Floci vendors
 * scope themselves to the caller's account.
 *
 * <p>Both directions are checked for every rule, because the half that matters is often the Deny:
 * a pattern that matches nothing silently drops a refusal AWS honours.
 */
class PolicyVariableSubstitutionTest {

    private static final String OWN_USER = "arn:aws:iam::111122223333:user/Alice";
    private static final String OTHER_USER = "arn:aws:iam::111122223333:user/Bob";

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    private static String allowingUserResource(String version, String resource) {
        return """
                {"Version":"%s","Statement":[
                  {"Effect":"Allow","Action":"iam:ChangePassword","Resource":"%s"}]}"""
                .formatted(version, resource);
    }

    @Test
    void aResourceVariableResolvesToTheCallersOwnUser() {
        String policy = allowingUserResource("2012-10-17", "arn:aws:iam::*:user/${aws:username}");

        assertEquals(Decision.ALLOW, decide(policy, OWN_USER, Map.of("aws:username", List.of("Alice"))));
        assertEquals(Decision.DENY, decide(policy, OTHER_USER, Map.of("aws:username", List.of("Alice"))));
    }

    /**
     * The version gate, which is easy to miss and changes everything: "If you don't include the
     * Version element and set it to an appropriate version date, variables like ${aws:username}
     * are treated as literal strings in the policy." 2008-10-17 is the only other version IAM
     * accepts, and is the default when the element is absent.
     */
    @Test
    void aVariableStaysLiteralTextWithoutTheVersionThatIntroducedThem() {
        Map<String, List<String>> context = Map.of("aws:username", List.of("Alice"));
        String resource = "arn:aws:iam::*:user/${aws:username}";

        assertEquals(Decision.DENY, decide(allowingUserResource("2008-10-17", resource), OWN_USER, context));
        assertEquals(Decision.DENY, decide("""
                {"Statement":[
                  {"Effect":"Allow","Action":"iam:ChangePassword","Resource":"%s"}]}"""
                .formatted(resource), OWN_USER, context));

        // The same document with the name spelled out is allowed either way, so it is the
        // variable that is inert under the older version and not the statement.
        assertEquals(Decision.ALLOW, decide(
                allowingUserResource("2008-10-17", OWN_USER), OWN_USER, context));
    }

    /** A Deny scoped by a variable has to bind, or the policy refuses nothing. */
    @Test
    void aDenyScopedByAResourceVariableRefusesTheCallersOwnUser() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:ChangePassword",
                   "Resource":"arn:aws:iam::*:user/${aws:username}"}]}""";
        Map<String, List<String>> context = Map.of("aws:username", List.of("Alice"));

        assertEquals(Decision.DENY, decide(policy, OWN_USER, context));
        assertEquals(Decision.ALLOW, decide(policy, OTHER_USER, context));
    }

    /**
     * "If a variable that has no value in the authorization context is used as part of the
     * Resource or NotResource element of a policy, the resource that includes a policy variable
     * with no value will not match any resource."
     */
    @Test
    void aResourceVariableWithNoValueMatchesNoResource() {
        String policy = allowingUserResource("2012-10-17", "arn:aws:iam::*:user/${aws:username}");

        assertEquals(Decision.DENY, decide(policy, OWN_USER, Map.of()));
        assertEquals(Decision.DENY, decide(policy, OWN_USER, Map.of("aws:username", List.of())));
    }

    /**
     * The discriminating half of that rule: a variable with no value is not an empty string. Beside
     * a wildcard an empty one would collapse the pattern to {@code reports/*} and grant the whole
     * prefix, where AWS matches no resource at all.
     */
    @Test
    void aResourceVariableWithNoValueMatchesNothingEvenBesideAWildcard() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/${aws:PrincipalTag/team}*"}]}""";

        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports/blue/q3.csv", Map.of()));
        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports/blue/q3.csv",
                Map.of("aws:principaltag/team", List.of("blue"))));
    }

    /**
     * The same sentence covers NotResource, where it reads the other way round: the pattern
     * excludes no resource, so the statement covers every one of them.
     */
    @Test
    void aNotResourceVariableWithNoValueExcludesNothing() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:ChangePassword",
                   "NotResource":"arn:aws:iam::*:user/${aws:username}"}]}""";

        assertEquals(Decision.ALLOW, decide(policy, OWN_USER, Map.of()));
        // With a value it excludes the one user it names, and nobody else.
        assertEquals(Decision.DENY, decide(policy, OWN_USER, Map.of("aws:username", List.of("Alice"))));
        assertEquals(Decision.ALLOW, decide(policy, OTHER_USER, Map.of("aws:username", List.of("Alice"))));
    }

    /** The same, in a NotResource, where an empty value would exclude the whole prefix instead. */
    @Test
    void aNotResourceVariableWithNoValueExcludesNothingEvenBesideAWildcard() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "NotResource":"arn:aws:s3:::reports/${aws:PrincipalTag/team}*"}]}""";

        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports/blue/q3.csv", Map.of()));
        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports/blue/q3.csv",
                Map.of("aws:principaltag/team", List.of("blue"))));
    }

    /**
     * "You can use a policy variable in the Resource element, but only in the resource portion of
     * the ARN. This portion of the ARN appears after the fifth colon." An account written as a
     * variable is therefore the literal text, and matches no account.
     */
    @Test
    void aVariableBeforeTheFifthColonIsNotSubstituted() {
        Map<String, List<String>> context = Map.of("aws:principalaccount", List.of("111122223333"),
                "aws:username", List.of("Alice"));

        assertEquals(Decision.DENY, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::${aws:PrincipalAccount}:user/Alice"), OWN_USER, context));
        // The resource portion of the same ARN still resolves, so the pattern is not refused whole.
        assertEquals(Decision.ALLOW, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::111122223333:user/${aws:username}"), OWN_USER, context));
    }

    /** "Key names are case-insensitive", and the request context is keyed in lower case. */
    @Test
    void variableKeyNamesAreCaseInsensitive() {
        assertEquals(Decision.ALLOW, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${AWS:UserName}"), OWN_USER,
                Map.of("aws:username", List.of("Alice"))));
    }

    /** "You can't use a multivalued condition key as a variable": there is no one value to use. */
    @Test
    void aMultiValuedKeyDoesNotResolve() {
        assertEquals(Decision.DENY, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${aws:username}"), OWN_USER,
                Map.of("aws:username", List.of("Alice", "Bob"))));

        // Nor does a default rescue it: the guide gives a default the key with no value, not the
        // one holding several, so the pattern resolves to nothing rather than widening.
        assertEquals(Decision.DENY, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${aws:username, 'Alice'}"), OWN_USER,
                Map.of("aws:username", List.of("Alice", "Bob"))));
    }

    /** A tag variable, which is the form AWS's own examples use most. */
    @Test
    void aPrincipalTagVariableResolvesInBothElements() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/${aws:PrincipalTag/team}/*"}]}""";

        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports/yellow/q3.csv",
                Map.of("aws:principaltag/team", List.of("yellow"))));
        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports/blue/q3.csv",
                Map.of("aws:principaltag/team", List.of("yellow"))));
    }

    /**
     * "To add a default value to a variable, surround the default value with single quotes (' '),
     * and separate the variable text and the default value with a comma and space (, )."
     */
    @Test
    void aDefaultValueStandsInWhenTheKeyHasNone() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports-${aws:PrincipalTag/team, 'company-wide'}/*"}]}""";

        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports-company-wide/joining.txt",
                Map.of()));
        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports-company-wide/joining.txt",
                Map.of("aws:principaltag/team", List.of("yellow"))));
        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports-yellow/q3.csv",
                Map.of("aws:principaltag/team", List.of("yellow"))));
    }

    /**
     * A default value runs to its closing quote, so one carrying a brace stays whole. Without the
     * quotes the guide requires there is no default to read, and the variable keeps the text it
     * is written as.
     */
    @Test
    void aDefaultValueIsReadToItsClosingQuote() {
        String braced = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/${aws:PrincipalTag/team, 'a}b'}"}]}""";
        assertEquals(Decision.ALLOW, decideS3(braced, "arn:aws:s3:::reports/a}b", Map.of()));

        String unquoted = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/${aws:PrincipalTag/team, fallback}"}]}""";
        assertEquals(Decision.DENY, decideS3(unquoted, "arn:aws:s3:::reports/fallback", Map.of()));
        assertEquals(Decision.ALLOW, decideS3(unquoted,
                "arn:aws:s3:::reports/${aws:PrincipalTag/team, fallback}", Map.of()));
    }

    /**
     * The three predefined variables stand for a character, so they have to survive glob matching
     * as themselves: "inserting an * asterisk in the string would be interpreted as a wildcard,
     * matching any characters, instead of as a literal *".
     */
    @Test
    void theSingleCharacterEscapesMatchThemselvesRatherThanWildcards() {
        String star = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/${*}"}]}""";
        assertEquals(Decision.ALLOW, decideS3(star, "arn:aws:s3:::reports/*", Map.of()));
        assertEquals(Decision.DENY, decideS3(star, "arn:aws:s3:::reports/q3.csv", Map.of()));

        String question = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/${?}"}]}""";
        assertEquals(Decision.ALLOW, decideS3(question, "arn:aws:s3:::reports/?", Map.of()));
        assertEquals(Decision.DENY, decideS3(question, "arn:aws:s3:::reports/a", Map.of()));

        String dollar = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/${$}cost"}]}""";
        assertEquals(Decision.ALLOW, decideS3(dollar, "arn:aws:s3:::reports/$cost", Map.of()));
        assertEquals(Decision.DENY, decideS3(dollar, "arn:aws:s3:::reports/cost", Map.of()));
    }

    /** An escape keeps its literal meaning around the wildcards the pattern really does carry. */
    @Test
    void anEscapeAndAWildcardCoexistInOnePattern() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/*${*}.csv"}]}""";

        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports/draft*.csv", Map.of()));
        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports/draft.csv", Map.of()));
    }

    /** A condition value resolves the same way a Resource pattern does. */
    @Test
    void aConditionVariableComparesAgainstTheResolvedValue() {
        String policy = conditionPolicy("StringEquals", "${aws:PrincipalTag/team}");

        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports/q3.csv",
                Map.of("aws:principaltag/team", List.of("yellow"),
                        "s3:existingobjecttag/team", List.of("yellow"))));
        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports/q3.csv",
                Map.of("aws:principaltag/team", List.of("yellow"),
                        "s3:existingobjecttag/team", List.of("blue"))));
    }

    /**
     * The asymmetry a null value creates: "Condition operators like StringEquals or StringLike do
     * not match, and the policy statement does not take effect. Inverted condition operators like
     * StringNotEquals or StringNotLike do match against a null value."
     */
    @Test
    void aConditionVariableWithNoValueFailsPositiveOperatorsAndPassesInvertedOnes() {
        Map<String, List<String>> context = Map.of("s3:existingobjecttag/team", List.of("yellow"));

        assertEquals(Decision.DENY, decideS3(conditionPolicy("StringEquals",
                "${aws:PrincipalTag/team}"), "arn:aws:s3:::reports/q3.csv", context));
        assertEquals(Decision.DENY, decideS3(conditionPolicy("StringLike",
                "${aws:PrincipalTag/team}"), "arn:aws:s3:::reports/q3.csv", context));
        assertEquals(Decision.ALLOW, decideS3(conditionPolicy("StringNotEquals",
                "${aws:PrincipalTag/team}"), "arn:aws:s3:::reports/q3.csv", context));
        assertEquals(Decision.ALLOW, decideS3(conditionPolicy("StringNotLike",
                "${aws:PrincipalTag/team}"), "arn:aws:s3:::reports/q3.csv", context));
    }

    /** And in a condition value, where an empty one would leave a bare wildcard to match on. */
    @Test
    void aConditionVariableWithNoValueIsNotAnEmptyString() {
        String policy = conditionPolicy("StringLike", "${aws:PrincipalTag/team}*");
        Map<String, List<String>> tagged = Map.of("s3:existingobjecttag/team", List.of("yellow"));

        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports/q3.csv", tagged));
        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports/q3.csv",
                Map.of("s3:existingobjecttag/team", List.of("yellow"),
                        "aws:principaltag/team", List.of("yell"))));
    }

    /**
     * Which makes the Deny AWS documents for exactly this case fire: access "is explicitly denied
     * when aws:principaltag/Team is not set".
     */
    @Test
    void theDocumentedDenyOnAnUnsetPrincipalTagFires() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"},
                  {"Effect":"Deny","Action":"s3:GetObject","Resource":"arn:aws:s3:::reports/*",
                   "Condition":{"StringNotEquals":
                     {"s3:ExistingObjectTag/Team":"${aws:PrincipalTag/Team}"}}}]}""";

        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports/q3.csv",
                Map.of("s3:existingobjecttag/team", List.of("yellow"))));
        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports/q3.csv",
                Map.of("s3:existingobjecttag/team", List.of("yellow"),
                        "aws:principaltag/team", List.of("yellow"))));
    }

    /**
     * "You can't use a policy variable with other operators, such as Numeric, Date, Boolean,
     * Binary, IP Address, or Null operators", so with one of those the text stays as written and
     * matches nothing, rather than resolving behind the policy author's back.
     */
    @Test
    void anOperatorThatTakesNoVariableComparesTheTextAsWritten() {
        // The request time against itself: resolved it would be greater than or equal to itself
        // and allow, so a Deny here is the text being compared as written and failing to parse.
        assertEquals(Decision.DENY, decideS3(conditionPolicy("DateGreaterThanEquals",
                        "${aws:CurrentTime}", "aws:CurrentTime"),
                "arn:aws:s3:::reports/q3.csv", Map.of("aws:currenttime", List.of("2026-10-13T00:00:00Z"))));
        // Null reads its own values before any substitution could reach them, so a variable there
        // is neither "true" nor "false" and the key it guards is simply absent.
        assertEquals(Decision.DENY, decideS3(conditionPolicy("Null",
                        "${aws:PrincipalTag/team}", "aws:PrincipalTag/team"),
                "arn:aws:s3:::reports/q3.csv", Map.of()));
    }

    /** An escape in a condition value is compared as the character, not as the marker. */
    @Test
    void anEscapeInAConditionValueComparesAsTheCharacter() {
        assertEquals(Decision.ALLOW, decideS3(conditionPolicy("StringEquals", "${*}"),
                "arn:aws:s3:::reports/q3.csv", Map.of("s3:existingobjecttag/team", List.of("*"))));
        assertEquals(Decision.DENY, decideS3(conditionPolicy("StringEquals", "${*}"),
                "arn:aws:s3:::reports/q3.csv", Map.of("s3:existingobjecttag/team", List.of("yellow"))));
        assertEquals(Decision.ALLOW, decideS3(conditionPolicy("StringLike", "${*}"),
                "arn:aws:s3:::reports/q3.csv", Map.of("s3:existingobjecttag/team", List.of("*"))));
        assertEquals(Decision.DENY, decideS3(conditionPolicy("StringLike", "${*}"),
                "arn:aws:s3:::reports/q3.csv", Map.of("s3:existingobjecttag/team", List.of("yellow"))));
    }

    /** An ARN operator is the other family that takes a variable. */
    @Test
    void anArnConditionResolvesAVariable() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*",
                   "Condition":{"ArnEquals":{"aws:SourceArn":"${aws:PrincipalArn}"}}}]}""";
        String principal = "arn:aws:iam::111122223333:user/Alice";

        assertEquals(Decision.ALLOW, decideS3(policy, "arn:aws:s3:::reports/q3.csv",
                Map.of("aws:principalarn", List.of(principal), "aws:sourcearn", List.of(principal))));
        assertEquals(Decision.DENY, decideS3(policy, "arn:aws:s3:::reports/q3.csv",
                Map.of("aws:principalarn", List.of(principal),
                        "aws:sourcearn", List.of("arn:aws:iam::111122223333:user/Bob"))));
    }

    /**
     * A name is not a variable once it holds a space. The policy-check reference calls it an
     * error, "A space is not supported within the policy variable text", and resolving it anyway
     * would grant the caller their own user through text AWS does not read as a variable.
     */
    @Test
    void aVariableNameHoldingASpaceIsNotAVariable() {
        Map<String, List<String>> context = Map.of("aws:username", List.of("Alice"));

        assertEquals(Decision.DENY, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${ aws:username }"), OWN_USER, context));
        assertEquals(Decision.DENY, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${aws:username }"), OWN_USER, context));

        // The space is the only difference, so the same pattern without it still resolves.
        assertEquals(Decision.ALLOW, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${aws:username}"), OWN_USER, context));
    }

    /**
     * The three forms the guide's policy checks call errors, each left as the text it is: an empty
     * variable, a missing closing brace, and a default without its quotes. A space is allowed
     * inside a quoted default, which is the one place the guide permits one.
     */
    @Test
    void aMalformedVariableStaysTheTextItIs() {
        Map<String, List<String>> context = Map.of("aws:username", List.of("Alice"));

        assertEquals(Decision.DENY, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${}Alice"), OWN_USER, context));
        assertEquals(Decision.DENY, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${aws:username"), OWN_USER, context));
        assertEquals(Decision.DENY, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${aws:username, Alice}"), OWN_USER, context));
        assertEquals(Decision.ALLOW, decide(allowingUserResource("2012-10-17",
                "arn:aws:iam::*:user/${aws:username, 'Alice Smith'}"),
                "arn:aws:iam::111122223333:user/Alice Smith", Map.of()));

        // Staying text and resolving to nothing both refuse a Resource, so the two are told
        // apart on a Condition instead, where a value that stayed text still compares and one
        // that resolved to nothing is null: equal fails and not-equal holds.
        Map<String, List<String>> tagged = Map.of("s3:existingobjecttag/team", List.of("${}x"));
        assertEquals(Decision.ALLOW, decideS3(conditionPolicy("StringEquals", "${}x"),
                "arn:aws:s3:::reports/q3.csv", tagged));
        assertEquals(Decision.DENY, decideS3(conditionPolicy("StringNotEquals", "${}x"),
                "arn:aws:s3:::reports/q3.csv", tagged));
    }

    /**
     * A resolved value is not escaped, so one carrying a wildcard widens the pattern it lands in.
     * That is AWS's behaviour, whose policy checks warn that variables "can be evaluated as
     * effective wildcards", and it is worth pinning because the widening also reaches a Deny.
     */
    @Test
    void aResolvedValueCarryingAWildcardWidensThePattern() {
        String policy = allowingUserResource("2012-10-17", "arn:aws:iam::*:user/${aws:username}");
        Map<String, List<String>> wildcardName = Map.of("aws:username", List.of("*"));

        assertEquals(Decision.ALLOW, decide(policy, OWN_USER, wildcardName));
        assertEquals(Decision.ALLOW, decide(policy, OTHER_USER, wildcardName));
    }

    /**
     * The escape marker belongs to the matcher, and a document can write one as the JSON escape
     * {@code \\u0000}. Honouring it there would turn a wildcard of the document's own into a
     * literal character and shrink the statement it sits in, so a marker is dropped on the way in
     * and the matcher reads one only on a pattern that came back from substitution.
     */
    @Test
    void aDocumentCannotForgeTheEscapeMarker() {
        String deny = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"},
                  {"Effect":"Deny","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/\\u0000*"}]}""";

        assertEquals(Decision.DENY, decideS3(deny, "arn:aws:s3:::reports/q3.csv", Map.of()));
        assertEquals(Decision.DENY, decideS3(conditionPolicy("StringNotLike", "\\u0000*"),
                "arn:aws:s3:::reports/q3.csv", Map.of("s3:existingobjecttag/team", List.of("yellow"))));

        // And under the version that substitutes nothing, where the marker cannot be the
        // matcher's own: it is an ordinary character the value has to carry, so the Deny matches
        // no object at all, not even the one named with the asterisk it was shielding. Reading it
        // as a marker instead would make this Deny refuse that object.
        assertEquals(Decision.ALLOW, decideS3("""
                {"Version":"2008-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*"},
                  {"Effect":"Deny","Action":"s3:GetObject",
                   "Resource":"arn:aws:s3:::reports/\\u0000*"}]}""",
                "arn:aws:s3:::reports/*", Map.of()));
    }

    private static String conditionPolicy(String operator, String value) {
        return conditionPolicy(operator, value, "s3:ExistingObjectTag/Team");
    }

    private static String conditionPolicy(String operator, String value, String conditionKey) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetObject","Resource":"*",
                   "Condition":{"%s":{"%s":"%s"}}}]}"""
                .formatted(operator, conditionKey, value);
    }

    private Decision decide(String policy, String resource, Map<String, List<String>> context) {
        return evaluator.simulateCustomPolicy(List.of(policy), "iam:ChangePassword", resource, context);
    }

    private Decision decideS3(String policy, String resource, Map<String, List<String>> context) {
        return evaluator.simulateCustomPolicy(List.of(policy), "s3:GetObject", resource, context);
    }
}
