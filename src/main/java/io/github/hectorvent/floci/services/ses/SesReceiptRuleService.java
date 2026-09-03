package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ses.model.ReceiptAction;
import io.github.hectorvent.floci.services.ses.model.ReceiptRule;
import io.github.hectorvent.floci.services.ses.model.ReceiptRuleSet;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Owns the SES receipt-rule domain (the {@code receiptRuleSetStore}: rule sets and the rules they
 * hold). Extracted from {@link SesService} in the store-based domain split and reached only through
 * that facade. Action-target validation couples this domain to {@link S3Service},
 * {@link SnsService}, and {@link LambdaService}, reproducing the checks real SES runs against the
 * account; the bounce-sender check arrives as a predicate the facade binds to
 * {@code SesIdentityService}, keeping identity resolution out of this class's dependencies.
 *
 * <p>Floci has no inbound-mail endpoint, so rule sets and rules are stored inertly: stored rules
 * route no mail and their actions never execute. The management API round-trips (enough to unblock
 * tools such as Terraform that declare inbound configuration during bootstrap).
 */
@ApplicationScoped
public class SesReceiptRuleService {

    private static final Logger LOG = Logger.getLogger(SesReceiptRuleService.class);

    // These RuleSetName constraints are not in the botocore model: service-2.json (SES 2010-12-01)
    // declares ReceiptRuleSetName as a bare {"type": "string"} with no pattern or length. They were
    // established by probing real SES in us-west-2 via boto3 (2026-08): a character outside
    // ^[a-zA-Z0-9_.-]+$ is a Smithy ValidationError, and a name that is >64 chars or does not
    // start/end with an alphanumeric is a service-level "Not a valid ruleSetName" InvalidParameterValue.
    // Re-verify against live SES (not the model, which can't confirm it) if these ever need to change.
    private static final Pattern RULE_SET_NAME_CHARS = Pattern.compile("^[a-zA-Z0-9_.-]+$");
    private static final int MAX_RULES_PER_SET = 200;
    private static final int MAX_ACTIONS_PER_RULE = 10;
    // Probed: a value that is not a well-formed topic/function ARN (a bare name, or an ARN with
    // missing segments) gets the "Invalid ..." message before any existence lookup. There is no
    // recipient-count limit (101 recipients are accepted).
    private static final Pattern SNS_TOPIC_ARN = Pattern.compile("^arn:aws[a-zA-Z-]*:sns:[a-z0-9-]+:\\d{12}:.+$");
    private static final Pattern LAMBDA_FUNCTION_ARN =
            Pattern.compile("^arn:aws[a-zA-Z-]*:lambda:[a-z0-9-]+:\\d{12}:function:.+$");
    // RFC 5322 ftext: printable US-ASCII excluding the colon. Real SES rejects anything else with
    // "Invalid header name: <name>" (probed 2026-09).
    private static final Pattern HEADER_NAME_CHARS = Pattern.compile("^[\\x21-\\x39\\x3B-\\x7E]+$");

    private final StorageBackend<String, ReceiptRuleSet> receiptRuleSetStore;
    // Serializes receipt-rule-set create (check-then-put) and set-active (clear-then-set) so the
    // one-active-per-region invariant and duplicate-name rejection hold under concurrency. Rule
    // mutations (create/update/delete/set-position, all read-modify-write on the set record) take
    // the same lock.
    private final Object receiptRuleSetLock = new Object();
    private final S3Service s3Service;
    private final SnsService snsService;
    private final LambdaService lambdaService;
    private final Clock clock;

    @Inject
    public SesReceiptRuleService(StorageFactory storageFactory, S3Service s3Service,
                                 SnsService snsService, LambdaService lambdaService, Clock clock) {
        this.receiptRuleSetStore = storageFactory.create("ses", "ses-receipt-rule-sets.json",
                new TypeReference<Map<String, ReceiptRuleSet>>() {});
        this.s3Service = s3Service;
        this.snsService = snsService;
        this.lambdaService = lambdaService;
        this.clock = clock;
    }

    SesReceiptRuleService(StorageBackend<String, ReceiptRuleSet> receiptRuleSetStore,
                          S3Service s3Service, SnsService snsService, LambdaService lambdaService,
                          Clock clock) {
        this.receiptRuleSetStore = receiptRuleSetStore;
        this.s3Service = s3Service;
        this.snsService = snsService;
        this.lambdaService = lambdaService;
        this.clock = clock;
    }

    public ReceiptRuleSet createReceiptRuleSet(String name, String region) {
        requireRuleSetName(name);
        String key = receiptRuleSetKey(region, name);
        ReceiptRuleSet ruleSet = new ReceiptRuleSet(name, Instant.now(clock));
        synchronized (receiptRuleSetLock) {
            if (receiptRuleSetStore.get(key).isPresent()) {
                throw new AwsException("AlreadyExists", "Rule set already exists: " + name, 400);
            }
            receiptRuleSetStore.put(key, ruleSet);
        }
        LOG.infov("Created SES receipt rule set: {0} in region {1}", name, region);
        return ruleSet;
    }

    public ReceiptRuleSet describeReceiptRuleSet(String name, String region) {
        requireRuleSetName(name);
        return receiptRuleSetStore.get(receiptRuleSetKey(region, name))
                .orElseThrow(() -> ruleSetDoesNotExist(name));
    }

    public List<ReceiptRuleSet> listReceiptRuleSets(String region) {
        String prefix = "receiptRuleSet::" + region + "::";
        List<ReceiptRuleSet> all = new ArrayList<>(receiptRuleSetStore.scan(k -> k.startsWith(prefix)));
        all.sort(Comparator.comparing(ReceiptRuleSet::getCreatedTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReceiptRuleSet::getName, Comparator.nullsLast(Comparator.naturalOrder())));
        return all;
    }

    public void deleteReceiptRuleSet(String name, String region) {
        requireRuleSetName(name);
        // Hold the lock so the active-check-then-delete is atomic and a concurrent set-active/clear
        // (which scans and re-puts active sets) can't resurrect the rule set we just deleted.
        synchronized (receiptRuleSetLock) {
            ReceiptRuleSet existing = receiptRuleSetStore.get(receiptRuleSetKey(region, name)).orElse(null);
            if (existing != null && existing.isActive()) {
                // AWS rejects deleting the active rule set (verified: CannotDelete / 400).
                throw new AwsException("CannotDelete", "Cannot delete active rule set: " + name, 400);
            }
            // AWS is idempotent otherwise: deleting a non-existent rule set succeeds without error.
            receiptRuleSetStore.delete(receiptRuleSetKey(region, name));
        }
        LOG.infov("Deleted SES receipt rule set: {0} in region {1}", name, region);
    }

    public void setActiveReceiptRuleSet(String name, String region) {
        // No RuleSetName clears the account's active rule set (matches AWS).
        boolean clearOnly = name == null || name.isBlank();
        if (!clearOnly) {
            requireRuleSetName(name);
        }
        synchronized (receiptRuleSetLock) {
            if (!clearOnly) {
                ReceiptRuleSet target = receiptRuleSetStore.get(receiptRuleSetKey(region, name))
                        .orElseThrow(() -> ruleSetDoesNotExist(name));
                clearActiveReceiptRuleSet(region);
                target.setActive(true);
                receiptRuleSetStore.put(receiptRuleSetKey(region, name), target);
            } else {
                clearActiveReceiptRuleSet(region);
            }
        }
        if (clearOnly) {
            LOG.infov("Cleared active SES receipt rule set in region {0}", region);
        } else {
            LOG.infov("Set active SES receipt rule set: {0} in region {1}", name, region);
        }
    }

    public ReceiptRuleSet describeActiveReceiptRuleSet(String region) {
        String prefix = "receiptRuleSet::" + region + "::";
        // Read under the lock so a concurrent set-active replacement (clear-then-set) can't expose its
        // intermediate no-active state — the reader sees either the old or the new active set.
        synchronized (receiptRuleSetLock) {
            return receiptRuleSetStore.scan(k -> k.startsWith(prefix)).stream()
                    .filter(ReceiptRuleSet::isActive)
                    .findFirst()
                    .orElse(null);
        }
    }

    public void createReceiptRule(String ruleSetName, ReceiptRule rule, String after, String region,
                                  Predicate<String> verifiedSender) {
        requireRuleSetName(ruleSetName);
        List<String> violations = new ArrayList<>();
        collectRuleNameParamViolations(after, "after", violations);
        collectRuleShapeViolations(rule, violations);
        throwViolations(violations);
        requireValidRuleName(rule.getName());
        synchronized (receiptRuleSetLock) {
            ReceiptRuleSet ruleSet = requireRuleSet(ruleSetName, region);
            List<ReceiptRule> rules = new ArrayList<>(ruleSet.getRules());
            if (rules.stream().anyMatch(r -> rule.getName().equals(r.getName()))) {
                throw new AwsException("AlreadyExists", "Rule already exists: " + rule.getName(), 400);
            }
            if (rules.size() >= MAX_RULES_PER_SET) {
                // Probed: the 201st rule in a set is rejected.
                throw new AwsException("LimitExceeded", "Too many rules", 400);
            }
            // AWS inserts at the FRONT when After is absent (probed); After places the rule
            // immediately behind the named one.
            int index = after == null ? 0 : indexOfRule(rules, after) + 1;
            validateActionTargets(rule, region, verifiedSender);
            applyRuleDefaults(rule);
            rules.add(index, rule);
            replaceRules(ruleSet, rules, ruleSetName, region);
        }
        LOG.infov("Created SES receipt rule {0} in rule set {1} ({2})", rule.getName(), ruleSetName, region);
    }

    public ReceiptRule describeReceiptRule(String ruleSetName, String ruleName, String region) {
        requireRuleSetName(ruleSetName);
        requireRuleNameParam(ruleName, "ruleName");
        ReceiptRuleSet ruleSet = requireRuleSet(ruleSetName, region);
        List<ReceiptRule> rules = ruleSet.getRules();
        return rules.get(indexOfRule(rules, ruleName));
    }

    public void updateReceiptRule(String ruleSetName, ReceiptRule rule, String region,
                                  Predicate<String> verifiedSender) {
        requireRuleSetName(ruleSetName);
        List<String> violations = new ArrayList<>();
        collectRuleShapeViolations(rule, violations);
        throwViolations(violations);
        requireValidRuleName(rule.getName());
        synchronized (receiptRuleSetLock) {
            ReceiptRuleSet ruleSet = requireRuleSet(ruleSetName, region);
            List<ReceiptRule> rules = new ArrayList<>(ruleSet.getRules());
            // AWS replaces the whole rule in place (omitted members reset to their defaults) and
            // keeps its position; an unknown name is an error, so update cannot rename.
            int index = indexOfRule(rules, rule.getName());
            validateActionTargets(rule, region, verifiedSender);
            applyRuleDefaults(rule);
            rules.set(index, rule);
            replaceRules(ruleSet, rules, ruleSetName, region);
        }
        LOG.infov("Updated SES receipt rule {0} in rule set {1} ({2})", rule.getName(), ruleSetName, region);
    }

    public void deleteReceiptRule(String ruleSetName, String ruleName, String region) {
        requireRuleSetName(ruleSetName);
        requireRuleNameParam(ruleName, "ruleName");
        synchronized (receiptRuleSetLock) {
            // The rule set must exist, but deleting an absent rule is idempotent (probed).
            ReceiptRuleSet ruleSet = requireRuleSet(ruleSetName, region);
            List<ReceiptRule> rules = new ArrayList<>(ruleSet.getRules());
            if (rules.removeIf(r -> ruleName.equals(r.getName()))) {
                replaceRules(ruleSet, rules, ruleSetName, region);
            }
        }
        LOG.infov("Deleted SES receipt rule {0} from rule set {1} ({2})", ruleName, ruleSetName, region);
    }

    public void setReceiptRulePosition(String ruleSetName, String ruleName, String after, String region) {
        requireRuleSetName(ruleSetName);
        requireRuleNamePresent(ruleName);
        List<String> violations = new ArrayList<>();
        collectRuleNameParamViolations(ruleName, "ruleName", violations);
        collectRuleNameParamViolations(after, "after", violations);
        throwViolations(violations);
        requireValidRuleName(ruleName);
        synchronized (receiptRuleSetLock) {
            ReceiptRuleSet ruleSet = requireRuleSet(ruleSetName, region);
            List<ReceiptRule> rules = new ArrayList<>(ruleSet.getRules());
            int index = indexOfRule(rules, ruleName);
            if (ruleName.equals(after)) {
                // AWS answers 200 for a rule positioned after itself (probed); nothing moves.
                return;
            }
            if (after != null) {
                indexOfRule(rules, after);
            }
            ReceiptRule moved = rules.remove(index);
            int target = after == null ? 0 : indexOfRule(rules, after) + 1;
            rules.add(target, moved);
            replaceRules(ruleSet, rules, ruleSetName, region);
        }
        LOG.infov("Positioned SES receipt rule {0} in rule set {1} ({2})", ruleName, ruleSetName, region);
    }

    /**
     * Swaps in a freshly built rules list and persists the set. Mutations never touch the list a
     * previously returned rule set holds: the storage backends hand out live object references, so
     * a describe caller may still be iterating the old list after the lock is released.
     */
    private void replaceRules(ReceiptRuleSet ruleSet, List<ReceiptRule> rules,
                              String ruleSetName, String region) {
        ruleSet.setRules(rules);
        receiptRuleSetStore.put(receiptRuleSetKey(region, ruleSetName), ruleSet);
    }

    private ReceiptRuleSet requireRuleSet(String name, String region) {
        return receiptRuleSetStore.get(receiptRuleSetKey(region, name))
                .orElseThrow(() -> ruleSetDoesNotExist(name));
    }

    private static int indexOfRule(List<ReceiptRule> rules, String name) {
        for (int i = 0; i < rules.size(); i++) {
            if (name.equals(rules.get(i).getName())) {
                return i;
            }
        }
        throw new AwsException("RuleDoesNotExist", "Rule does not exist: " + name, 400);
    }

    /**
     * The Smithy-model layer of rule validation, reproduced from probing real SES: violations
     * across the rule name, TlsPolicy, and every action member collect into one
     * {@code ValidationError} ("N validation errors detected: ...; ..."). Only the members real
     * SES enforces are enforced; the documented-required S3 BucketName, SNS TopicArn, and Stop
     * Scope are accepted absent on the wire (probed), so they are not checked here.
     */
    private static void collectRuleShapeViolations(ReceiptRule rule, List<String> violations) {
        String name = rule.getName();
        if (name == null || name.isEmpty()) {
            violations.add(lengthViolation("rule.name"));
            violations.add(patternViolation("rule.name"));
        } else if (!RULE_SET_NAME_CHARS.matcher(name).matches()) {
            violations.add(patternViolation("rule.name"));
        }
        if (rule.getTlsPolicy() != null && !"Optional".equals(rule.getTlsPolicy())
                && !"Require".equals(rule.getTlsPolicy())) {
            violations.add(enumViolation("rule.tlsPolicy", "[Optional, Require]"));
        }
        int i = 1;
        for (ReceiptAction action : rule.getActions()) {
            String prefix = "rule.actions." + i + ".member.";
            if (action.is("AddHeaderAction")) {
                requireMember(violations, action, "HeaderName", prefix + "addHeaderAction.headerName");
                requireMember(violations, action, "HeaderValue", prefix + "addHeaderAction.headerValue");
            } else if (action.is("BounceAction")) {
                // Probed order: sender is reported before message.
                requireMember(violations, action, "Sender", prefix + "bounceAction.sender");
                requireMember(violations, action, "Message", prefix + "bounceAction.message");
                requireMember(violations, action, "SmtpReplyCode", prefix + "bounceAction.smtpReplyCode");
            } else if (action.is("ConnectAction")) {
                requireMember(violations, action, "InstanceARN", prefix + "connectAction.instanceARN");
                requireMember(violations, action, "IAMRoleARN", prefix + "connectAction.iAMRoleARN");
            } else if (action.is("WorkmailAction")) {
                requireMember(violations, action, "OrganizationArn", prefix + "workmailAction.organizationArn");
            } else if (action.is("LambdaAction")) {
                requireMember(violations, action, "FunctionArn", prefix + "lambdaAction.functionArn");
                if (invalidEnum(action, "InvocationType", "RequestResponse", "Event")) {
                    violations.add(enumViolation(prefix + "lambdaAction.invocationType",
                            "[RequestResponse, Event]"));
                }
            } else if (action.is("SNSAction")) {
                if (invalidEnum(action, "Encoding", "Base64", "UTF-8")) {
                    violations.add(enumViolation(prefix + "sNSAction.encoding", "[Base64, UTF-8]"));
                }
            } else if (action.is("StopAction")) {
                if (invalidEnum(action, "Scope", "RuleSet")) {
                    violations.add(enumViolation(prefix + "stopAction.scope", "[RuleSet]"));
                }
            }
            i++;
        }
    }

    private static void throwViolations(List<String> violations) {
        if (violations.isEmpty()) {
            return;
        }
        String label = violations.size() == 1 ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        throw new AwsException("ValidationError", label + String.join("; ", violations), 400);
    }

    /**
     * Smithy-layer checks for the top-level RuleName and After parameters, which real SES
     * validates with the same length and pattern constraints as Rule.Name but under their own
     * wire member paths ('ruleName' / 'after', probed on describe, delete, set-position, and
     * the create After parameter). An absent parameter is left to the caller.
     */
    private static void collectRuleNameParamViolations(String value, String path, List<String> violations) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            violations.add(lengthViolation(path));
            violations.add(patternViolation(path));
        } else if (!RULE_SET_NAME_CHARS.matcher(value).matches()) {
            violations.add(patternViolation(path));
        }
    }

    /**
     * Full validation of a required top-level RuleName parameter: presence, then the Smithy
     * layers, then the service-level "Not a valid ruleName" boundary check (probed on
     * describe and delete).
     */
    private static void requireRuleNameParam(String ruleName, String path) {
        requireRuleNamePresent(ruleName);
        List<String> violations = new ArrayList<>();
        collectRuleNameParamViolations(ruleName, path, violations);
        throwViolations(violations);
        requireValidRuleName(ruleName);
    }

    private static void requireMember(List<String> violations, ReceiptAction action,
                                      String member, String path) {
        if (action.property(member) == null) {
            violations.add("Value at '" + path + "' failed to satisfy constraint: "
                    + "Member must not be null");
        }
    }

    private static boolean invalidEnum(ReceiptAction action, String member, String... allowed) {
        String value = action.property(member);
        if (value == null) {
            return false;
        }
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return false;
            }
        }
        return true;
    }

    private static String patternViolation(String path) {
        return "Value at '" + path + "' failed to satisfy constraint: "
                + "Member must satisfy regular expression pattern: ^[a-zA-Z0-9_.-]+$";
    }

    private static String lengthViolation(String path) {
        return "Value at '" + path + "' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1";
    }

    private static String enumViolation(String path, String valueSet) {
        return "Value at '" + path + "' failed to satisfy constraint: "
                + "Member must satisfy enum value set: " + valueSet;
    }

    /**
     * Validates every resource an action points at against the local emulator, reproducing the
     * checks real SES runs on create/update: any TopicArn must be a publishable SNS topic, an
     * S3 bucket must exist, a Lambda function must be invokable, and a bounce sender must be a
     * verified identity. Each check fires only when its member is present, matching AWS.
     */
    private void validateActionTargets(ReceiptRule rule, String region, Predicate<String> verifiedSender) {
        if (rule.getActions().size() > MAX_ACTIONS_PER_RULE) {
            // Probed: the 11th action is rejected at the service layer, like the rules-per-set cap.
            throw new AwsException("LimitExceeded", "Too many actions", 400);
        }
        for (ReceiptAction action : rule.getActions()) {
            String headerName = action.property("HeaderName");
            if (action.is("AddHeaderAction") && headerName != null
                    && !HEADER_NAME_CHARS.matcher(headerName).matches()) {
                throw new AwsException("InvalidParameterValue", "Invalid header name: " + headerName, 400);
            }
            // Empty members get their own service-level messages before the existence checks
            // (probed: they never reach the "no such resource" family).
            String topicArn = action.property("TopicArn");
            if (topicArn != null) {
                // The shape check covers the probed empty-string case too (same message).
                if (!SNS_TOPIC_ARN.matcher(topicArn).matches()) {
                    throw new AwsException("InvalidSnsTopic", "Invalid SNS topic: " + topicArn, 400);
                }
                if (!snsService.topicExists(topicArn, region)) {
                    throw new AwsException("InvalidSnsTopic",
                            "Could not publish to SNS topic: " + topicArn, 400);
                }
            }
            String bucketName = action.property("BucketName");
            if (action.is("S3Action") && bucketName != null) {
                if (bucketName.isEmpty()) {
                    throw new AwsException("InvalidParameterValue", "Bucket name must not be empty", 400);
                }
                if (!s3Service.bucketExists(bucketName)) {
                    throw new AwsException("InvalidS3Configuration", "No such bucket: " + bucketName, 400);
                }
            }
            String functionArn = action.property("FunctionArn");
            if (action.is("LambdaAction") && functionArn != null) {
                if (functionArn.isEmpty()) {
                    throw new AwsException("InvalidParameterValue",
                            "Lambda function ARN must not be empty", 400);
                }
                // A bare function name or malformed ARN gets its own message before the
                // existence lookup (probed), so a same-named local function cannot make a
                // non-ARN value pass.
                if (!LAMBDA_FUNCTION_ARN.matcher(functionArn).matches()) {
                    throw new AwsException("InvalidLambdaFunction",
                            "Invalid Lambda function: " + functionArn, 400);
                }
                if (!lambdaService.functionExists(region, functionArn)) {
                    throw new AwsException("InvalidLambdaFunction",
                            "Could not invoke Lambda function: " + functionArn, 400);
                }
            }
            if (action.is("BounceAction")) {
                String smtpReplyCode = action.property("SmtpReplyCode");
                if (smtpReplyCode != null && smtpReplyCode.isEmpty()) {
                    throw new AwsException("InvalidParameterValue",
                            "Invalid SMTP reply code: " + smtpReplyCode, 400);
                }
                String message = action.property("Message");
                if (message != null && message.isEmpty()) {
                    throw new AwsException("InvalidParameterValue",
                            "Invalid SMTP response message: " + message, 400);
                }
                String sender = action.property("Sender");
                if (sender != null && !verifiedSender.test(sender)) {
                    throw new AwsException("InvalidParameterValue",
                            "Identity is not verified: " + sender, 400);
                }
            }
        }
    }

    private static void applyRuleDefaults(ReceiptRule rule) {
        if (rule.getTlsPolicy() == null) {
            rule.setTlsPolicy("Optional");
        }
        for (ReceiptAction action : rule.getActions()) {
            // AWS fills in the SNS encoding default on the stored rule (probed: UTF-8 comes back
            // from Describe without ever being sent).
            if (action.is("SNSAction") && action.property("TopicArn") != null
                    && action.property("Encoding") == null) {
                action.getProperties().put("Encoding", "UTF-8");
            }
        }
    }

    private static void requireRuleNamePresent(String ruleName) {
        // Only an ABSENT parameter takes this error; a supplied empty or whitespace-only value
        // falls through to the Smithy length/pattern violations probed under the 'ruleName' path.
        if (ruleName == null) {
            throw new AwsException("InvalidParameterValue", "RuleName is required.", 400);
        }
    }

    /**
     * The service-level rule name check that runs after the Smithy pattern passes, mirroring
     * {@link #requireRuleSetName}: too long or not alphanumeric at both ends.
     */
    private static void requireValidRuleName(String name) {
        if (name.length() > 64
                || !Character.isLetterOrDigit(name.charAt(0))
                || !Character.isLetterOrDigit(name.charAt(name.length() - 1))) {
            throw new AwsException("InvalidParameterValue", "Not a valid ruleName: " + name, 400);
        }
    }

    private void clearActiveReceiptRuleSet(String region) {
        String prefix = "receiptRuleSet::" + region + "::";
        for (ReceiptRuleSet rs : receiptRuleSetStore.scan(k -> k.startsWith(prefix))) {
            if (rs.isActive()) {
                rs.setActive(false);
                receiptRuleSetStore.put(receiptRuleSetKey(region, rs.getName()), rs);
            }
        }
    }

    private static void requireRuleSetName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "RuleSetName is required.", 400);
        }
        if (!RULE_SET_NAME_CHARS.matcher(name).matches()) {
            throw new AwsException("ValidationError",
                    "1 validation error detected: Value at 'ruleSetName' failed to satisfy constraint: "
                            + "Member must satisfy regular expression pattern: ^[a-zA-Z0-9_.-]+$", 400);
        }
        if (name.length() > 64
                || !Character.isLetterOrDigit(name.charAt(0))
                || !Character.isLetterOrDigit(name.charAt(name.length() - 1))) {
            throw new AwsException("InvalidParameterValue", "Not a valid ruleSetName: " + name, 400);
        }
    }

    private static AwsException ruleSetDoesNotExist(String name) {
        return new AwsException("RuleSetDoesNotExist", "Rule set does not exist: " + name, 400);
    }

    private static String receiptRuleSetKey(String region, String name) {
        return "receiptRuleSet::" + region + "::" + name;
    }
}
