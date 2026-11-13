package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a policy document a write was handed is a policy document, and the sentence IAM refuses
 * one that is not with.
 *
 * <p>Every operation here models {@code MalformedPolicyDocument}, "the request was rejected because
 * the policy document was malformed", and nothing in this package answered it: a document that was
 * no policy at all was stored and only told apart later, when something tried to decide with it. A
 * trust policy that is not a policy is the sharpest case, since the evaluator reads one it cannot
 * parse as matching nothing, so a role written that way can no longer be assumed.
 *
 * <p>Two readings are applied, and which of them an operation asks for follows what the document
 * is rather than which operation writes it:
 *
 * <ul>
 *   <li>the <em>grammar</em>, which every policy document is held to: it has to parse as a JSON
 *       object and carry {@code Statement}, "the main element for a policy. This element is
 *       required.";</li>
 *   <li>the <em>values</em> the elements declare, which an identity-based document is held to: the
 *       version it declares has to be one the language defines, a statement's {@code Effect} has to
 *       be {@code Allow} or {@code Deny} compared literally, and a resource it names has to be
 *       named by ARN or by the wildcard.</li>
 * </ul>
 *
 * <p>A managed policy's document is held to its statements' own elements as well, each statement
 * naming the actions it is about and the resources those apply to. A trust policy is held to the
 * grammar alone, and to its own wording of it: IAM validates a role's trust policy with a validator
 * that tells a document which does not parse from one that parses and describes no statement, where
 * the identity validator answers one sentence for both.
 *
 * <p>The wordings are AWS's own, taken from its emulator's implementation rather than from a
 * capture or a published sentence, which {@code docs/services/iam.md} records. What is already
 * stored is not re-read: this answers what a request supplies.
 *
 * <p>A null document is nothing to judge and passes: a required member the request leaves out is
 * what the constraints ahead of the operation answer, and they answer it as absent. Every write
 * here reaches this through {@link IamService}, so a caller that never touches the Query front
 * door, a CloudFormation provisioner among them, is held to the same reading, and each write asks
 * for it where AWS answers it: before the name clash on a create, before the entity is resolved on
 * {@code UpdateAssumeRolePolicy} and {@code CreatePolicyVersion}, and after the holder is resolved
 * on the three inline writes, which answer {@code NoSuchEntity} for a principal that is not there
 * whatever document the request carried.
 */
final class IamPolicyDocuments {

    /** The versions of the policy language that exist, so a document declaring another is refused. */
    private static final List<String> VERSIONS = List.of("2012-10-17", "2008-10-17");

    /** The two effects a statement may declare, compared as written. */
    private static final List<String> EFFECTS = List.of("Allow", "Deny");

    /** What every supplied document that is no policy document is refused with. */
    private static final String SYNTAX_ERRORS_IN_POLICY = "Syntax errors in policy.";

    /** The same for a role's trust policy, whose validator words the two cases apart. */
    private static final String TRUST_POLICY_INVALID_JSON = "This policy contains invalid Json";

    /** A trust policy that parses and describes no statement. */
    private static final String TRUST_POLICY_NO_STATEMENTS = "Policy has no statements";

    private static final String STATEMENT_WITHOUT_ACTIONS = "Policy statement must contain actions.";

    private static final String STATEMENT_WITHOUT_RESOURCES = "Policy statement must contain resources.";

    /** What a statement declaring an effect the language does not define is refused with. */
    private static final String POLICY_PARSE_FAILED = "The policy failed legacy parsing";

    private static final String ANY_RESOURCE = "*";

    private static final String ARN_PREFIX = "arn:";

    /** Parsing is a pure function of the text, and this reader holds no state of its own. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Why a document is no policy document, which is what lets each caller word it as IAM does: a
     * text that is no JSON object failed to parse, where an object without {@code Statement}
     * parsed and described nothing.
     */
    private enum Grammar { SYNTAX, NO_STATEMENTS, NO_ACTIONS, NO_RESOURCES }

    private IamPolicyDocuments() {
    }

    /**
     * Holds {@code document} to what a role's trust policy must be. The grammar alone: a trust
     * policy names a principal and the actions it may take rather than resources, so the elements
     * an identity-based statement must carry are not asked of it, and neither are the values an
     * identity-based document declares.
     */
    static void checkTrustPolicy(String document) {
        if (document == null) {
            return;
        }
        Grammar defect = grammarDefect(document);
        if (defect == Grammar.SYNTAX) {
            throw malformed(TRUST_POLICY_INVALID_JSON);
        }
        if (defect == Grammar.NO_STATEMENTS) {
            throw malformed(TRUST_POLICY_NO_STATEMENTS);
        }
    }

    /**
     * Holds {@code document} to what an inline identity policy must be: the grammar, worded as the
     * identity validator words it, and the values the language defines.
     */
    static void checkInlinePolicy(String document) {
        if (document == null) {
            return;
        }
        checkIdentityGrammar(document, false);
        checkIdentityValues(document);
    }

    /**
     * Holds {@code document} to what a managed policy must be, which is an inline policy's reading
     * and its statements' own elements besides: every statement of an identity-based policy names
     * the actions it is about and the resources they apply to.
     */
    static void checkManagedPolicy(String document) {
        if (document == null) {
            return;
        }
        checkIdentityGrammar(document, true);
        checkIdentityValues(document);
    }

    /**
     * Whether {@code document} reads as a policy document at all, which is the grammar above
     * answered as a boolean for a caller that needs no sentence of its own.
     * {@link IamPolicyEvaluator} asks this of a document a request supplied, the four operations
     * taking one answering the input rather than deciding from a text they cannot read, so the
     * writes here and that gate hold a document to the same top-level shape.
     */
    static boolean readsAsPolicyDocument(String document) {
        return document != null && grammarDefect(document) == null;
    }

    private static void checkIdentityGrammar(String document, boolean statementElements) {
        Grammar defect = grammarDefect(document);
        if (defect == null && statementElements) {
            defect = statementElementDefect(document);
        }
        if (defect == null) {
            return;
        }
        throw malformed(switch (defect) {
            case SYNTAX, NO_STATEMENTS -> SYNTAX_ERRORS_IN_POLICY;
            case NO_ACTIONS -> STATEMENT_WITHOUT_ACTIONS;
            case NO_RESOURCES -> STATEMENT_WITHOUT_RESOURCES;
        });
    }

    /**
     * The values an identity-based document declares, read after the grammar and only for a
     * document the grammar accepted, so a text that is no policy is answered as one rather than
     * through whichever value it happens to carry.
     */
    private static void checkIdentityValues(String document) {
        JsonNode root = parse(document);
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode version = root.get("Version");
        if (version != null && !version.isNull() && !VERSIONS.contains(version.asText())) {
            throw malformed(SYNTAX_ERRORS_IN_POLICY);
        }
        for (JsonNode statement : statements(root)) {
            JsonNode effect = statement.get("Effect");
            if (effect != null && !EFFECTS.contains(effect.asText())) {
                throw malformed(POLICY_PARSE_FAILED);
            }
            for (String resource : resourceValues(statement)) {
                if (!resource.startsWith(ARN_PREFIX) && !ANY_RESOURCE.equals(resource)) {
                    throw malformed("Resource " + resource + " must be in ARN format or \""
                            + ANY_RESOURCE + "\".");
                }
            }
        }
    }

    /**
     * Why {@code document} is no policy document at all, or null when it is one. The one place the
     * top-level grammar is read, so every operation taking a document refuses the same texts.
     */
    private static Grammar grammarDefect(String document) {
        JsonNode root = parse(document);
        if (root == null || !root.isObject()) {
            return Grammar.SYNTAX;
        }
        return root.has("Statement") ? null : Grammar.NO_STATEMENTS;
    }

    /**
     * The element a statement of an identity-based policy names nothing for, or null when every
     * statement names both. A statement that is not an object is no statement, which is the syntax
     * defect rather than a missing element.
     */
    private static Grammar statementElementDefect(String document) {
        JsonNode root = parse(document);
        JsonNode declared = root == null ? null : root.get("Statement");
        if (declared == null) {
            return null;
        }
        for (JsonNode statement : declared.isArray() ? declared : List.of(declared)) {
            if (!statement.isObject()) {
                return Grammar.SYNTAX;
            }
            if (!namesSomething(statement, "Action", "NotAction")) {
                return Grammar.NO_ACTIONS;
            }
            if (!namesSomething(statement, "Resource", "NotResource")) {
                return Grammar.NO_RESOURCES;
            }
        }
        return null;
    }

    /** The statements of a document the grammar accepted, a lone statement object included. */
    private static List<JsonNode> statements(JsonNode root) {
        JsonNode declared = root.get("Statement");
        if (declared == null) {
            return List.of();
        }
        List<JsonNode> statements = new ArrayList<>();
        for (JsonNode statement : declared.isArray() ? declared : List.of(declared)) {
            if (statement.isObject()) {
                statements.add(statement);
            }
        }
        return statements;
    }

    /**
     * Whether a statement names anything under any of {@code spellings}. An element that is absent,
     * null or an empty list names nothing, so it satisfies no requirement of the grammar.
     */
    private static boolean namesSomething(JsonNode statement, String... spellings) {
        for (String spelling : spellings) {
            JsonNode element = statement.get(spelling);
            if (element == null || element.isNull()) {
                continue;
            }
            if (element.isArray() && element.isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Every value a statement's resource elements name, in the order the document carries them. */
    private static List<String> resourceValues(JsonNode statement) {
        List<String> values = new ArrayList<>();
        for (String spelling : new String[] {"Resource", "NotResource"}) {
            JsonNode element = statement.get(spelling);
            if (element == null) {
                continue;
            }
            for (JsonNode value : element.isArray() ? element : List.of(element)) {
                if (value.isTextual()) {
                    values.add(value.asText());
                }
            }
        }
        return values;
    }

    private static JsonNode parse(String document) {
        if (document == null) {
            return null;
        }
        try {
            // The WHOLE text has to be the document: a member taking one takes "the complete,
            // valid JSON text of an IAM policy", so an object with anything after it is no policy
            // document, which is what FAIL_ON_TRAILING_TOKENS answers here.
            return MAPPER.readTree(document);
        } catch (JsonProcessingException unreadable) {
            // Not JSON, so no policy document either, and the caller's sentence says so.
            return null;
        }
    }

    private static AwsException malformed(String message) {
        return new AwsException("MalformedPolicyDocument", message, 400);
    }
}
