package io.github.hectorvent.floci.services.iam;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes IAM policy variables, the {@code ${...}} placeholders a policy writes where it
 * cannot know a value until the request arrives.
 *
 * <p>A variable names a condition context key, so {@code ${aws:username}} in a Resource pattern
 * resolves to the name of the user making the request. Key names are case-insensitive, matching
 * the user guide, and only a single-valued key resolves: the guide says a multivalued key cannot
 * be used as a variable, and there is no one value to put in its place.
 *
 * <p>An empty {@link Optional} means the text held a variable with nothing to resolve to. The
 * caller decides what that means, because AWS gives the two elements different answers: a
 * Resource pattern "will not match any resource", while a Condition value is effectively null,
 * which a positive operator does not match and an inverted one does.
 *
 * <p>Three variables name a character rather than a key: {@code ${*}}, {@code ${?}} and
 * {@code ${$}} stand for a literal asterisk, question mark and dollar sign. The first two have to
 * survive glob matching as themselves, so they resolve to the character behind {@link #ESCAPE},
 * which {@code IamPolicyEvaluator}'s glob reads as "the next character matches literally".
 * Operators that compare whole strings instead of globbing take {@link #literal} first.
 *
 * <p>A substituted value is not escaped, so a value carrying a {@code *} widens the pattern it
 * lands in. That is AWS's own behaviour: its policy checks warn that "policy variables can be
 * evaluated as effective wildcards".
 *
 * <p>Reporting the key names a document references, which is what
 * {@code GetContextKeysForCustomPolicy} answers, is a different question and keeps its own pattern
 * in {@code IamPolicyEvaluator}: it has to name the key even where the variable around it is
 * written in a form that resolves to nothing here, such as a default value missing its quotes.
 */
final class PolicyVariableResolver {

    /**
     * Marks the character after it as a literal, so a substituted asterisk or question mark is
     * not read as a wildcard. Only text this class has substituted carries a marker: it strips
     * any that arrived in the policy, and the matcher honours markers only on a pattern that came
     * back from substitution. A document can hold one as the JSON escape {@code \u0000}, and left
     * to act as a marker it would turn a wildcard of the document's own into a literal character
     * and narrow the statement it sits in. AWS refuses such a document outright, its
     * {@code policyDocumentType} admitting tab, newline, carriage return and the printable range
     * from the space character upward and nothing below it, but that is AWS's pattern to enforce
     * and not a property of what reaches this class.
     */
    static final char ESCAPE = '\u0000';

    /**
     * A variable: {@code ${key}}, or {@code ${key, 'default'}} where the default runs to its
     * closing quote rather than to the first brace, so one carrying a brace of its own survives.
     * The key is one or more characters and holds no whitespace, the guide saying "you cannot
     * include a space in the variable name" while "you can include a space when you specify a
     * default variable". Anything else written between {@code ${} and {@code }} matches nothing
     * here and stays the text it is, which is what an empty {@code ${}}, a spaced name, an
     * unquoted default and a missing brace all come to.
     */
    private static final Pattern VARIABLE =
            Pattern.compile("\\$\\{([^\\s,}]+)(?:,\\s*'([^']*)'\\s*)?}");

    /** The policy language version that introduced variables. */
    private static final String VARIABLES_VERSION = "2012-10-17";

    private static final int ARN_RESOURCE_COLON = 5;

    private PolicyVariableResolver() {
    }

    /**
     * Whether a document declaring this {@code Version} has policy variables at all. They arrived
     * with 2012-10-17; under the older 2008-10-17, which is also the default when the element is
     * absent, the user guide says they "are instead treated as literal strings in the policy".
     * The policy-check reference reads "2012-10-17 or later", and equality covers that: the guide
     * lists exactly two values IAM supports for the element, and no later one exists to admit.
     */
    static boolean enabledFor(String policyVersion) {
        return VARIABLES_VERSION.equals(policyVersion);
    }

    /**
     * Substitutes every variable in a Condition value. Empty when one of them has no value, which
     * leaves the whole value effectively null rather than partly resolved.
     */
    static Optional<String> resolveValue(String text, Map<String, List<String>> context) {
        String plain = withoutMarker(text);
        if (plain == null || !plain.contains("${")) {
            return Optional.ofNullable(plain);
        }
        Matcher matcher = VARIABLE.matcher(plain);
        StringBuilder resolved = new StringBuilder(plain.length());
        int copiedTo = 0;
        while (matcher.find()) {
            String substitution = substitutionFor(matcher.group(1), matcher.group(2), context);
            if (substitution == null) {
                return Optional.empty();
            }
            resolved.append(plain, copiedTo, matcher.start()).append(substitution);
            copiedTo = matcher.end();
        }
        resolved.append(plain, copiedTo, plain.length());
        return Optional.of(resolved.toString());
    }

    /**
     * Substitutes variables in the resource portion of an ARN pattern, which is what follows the
     * fifth colon. The user guide is explicit that a variable cannot replace any part before it,
     * such as the service or the account, so anything written there stays the literal text it is
     * and matches nothing. A pattern that is not an ARN, {@code *} among them, has no resource
     * portion and is returned unchanged.
     */
    static Optional<String> resolveResourcePattern(String pattern, Map<String, List<String>> context) {
        if (pattern == null) {
            return Optional.empty();
        }
        String plain = withoutMarker(pattern);
        int resourceStart = resourcePortionStart(plain);
        if (resourceStart < 0) {
            return Optional.of(plain);
        }
        return resolveValue(plain.substring(resourceStart), context)
                .map(resource -> plain.substring(0, resourceStart) + resource);
    }

    /**
     * The text with every {@link #ESCAPE} character removed, applied to whatever arrives from a
     * policy. One written into a document is not a marker but a forgery of one, and dropping it
     * leaves the document's own wildcards to glob as they are written.
     */
    private static String withoutMarker(String text) {
        return text == null || text.indexOf(ESCAPE) < 0
                ? text
                : text.replace(String.valueOf(ESCAPE), "");
    }

    /** The text with every {@link #ESCAPE} marker dropped, leaving the character it protected. */
    static String literal(String text) {
        if (text == null || text.indexOf(ESCAPE) < 0) {
            return text;
        }
        StringBuilder plain = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current != ESCAPE) {
                plain.append(current);
            } else if (i + 1 < text.length()) {
                plain.append(text.charAt(++i));
            }
        }
        return plain.toString();
    }

    /** The value to put in a variable's place, or null when it has none. */
    private static String substitutionFor(String key, String defaultValue,
                                           Map<String, List<String>> context) {
        if ("*".equals(key)) {
            return ESCAPE + "*";
        }
        if ("?".equals(key)) {
            return ESCAPE + "?";
        }
        if ("$".equals(key)) {
            return "$";
        }
        List<String> values = context.get(key.toLowerCase(Locale.ROOT));
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        if (values.size() > 1) {
            // "You can't use a multivalued condition key as a variable", and the guide gives a
            // default no part in that rule: it stands in for a key with no value, not for one
            // holding several. So the variable resolves to nothing and the value it sits in
            // matches nothing, rather than quietly widening to the default.
            return null;
        }
        return values.getFirst();
    }

    /** The index just past the fifth colon, or -1 when the pattern has fewer than five. */
    private static int resourcePortionStart(String pattern) {
        int colons = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == ':') {
                colons++;
                if (colons == ARN_RESOURCE_COLON) {
                    return i + 1;
                }
            }
        }
        return -1;
    }
}
