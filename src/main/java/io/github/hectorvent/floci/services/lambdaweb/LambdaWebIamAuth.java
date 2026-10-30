package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.StarWildcard;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SessionTokenCheck;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.core.common.auth.SigV4SignedRequest;
import io.github.hectorvent.floci.core.common.auth.SigV4SignedRequest.Freshness;
import io.github.hectorvent.floci.core.common.auth.SigV4aSignature;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.IamService.OwnedSecretKey;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService.WebFunctionTarget;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The checks an {@code IamAuth} endpoint makes before a request reaches the function: a SigV4
 * signature for the service {@code lambda} in the endpoint's region, or a SigV4a one whose region
 * set covers it, then {@code lambda:InvokeWebFunctionEndpoint} on the endpoint ARN. The signature
 * is always checked.
 * The policies are evaluated when IAM enforcement is on, as everywhere else in Floci.
 *
 * <p>CloudFront signs for a distribution under an {@code always-amz-auth} origin access control.
 * Floci's CloudFront stands in for that signature with the {@code lambda-web-cloudfront-*} headers,
 * which carry the distribution and a key only this process knows. The function never sees them.
 */
@ApplicationScoped
public class LambdaWebIamAuth {

    private static final String ACTION = "lambda:InvokeWebFunctionEndpoint";
    private static final Logger LOG = Logger.getLogger(LambdaWebIamAuth.class);
    private static final String SERVICE = "lambda";
    private static final String SECURITY_TOKEN = "X-Amz-Security-Token";
    private static final String REGION_SET = "X-Amz-Region-Set";
    private static final String TEST_ACCESS_KEY = "test";
    private static final String CLOUDFRONT_SOURCE_ARN = "lambda-web-cloudfront-source-arn";
    private static final String CLOUDFRONT_KEY = "lambda-web-cloudfront-key";

    private final EmulatorConfig config;
    private final IamService iamService;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ScpProvider> scpProvider;
    private final String cloudFrontKey;

    @Inject
    public LambdaWebIamAuth(EmulatorConfig config, IamService iamService, IamPolicyEvaluator evaluator,
                            Instance<ScpProvider> scpProvider) {
        this.config = config;
        this.iamService = iamService;
        this.evaluator = evaluator;
        this.scpProvider = scpProvider;
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        this.cloudFrontKey = HexFormat.of().formatHex(key);
    }

    /** The headers CloudFront signs an origin request with for {@code distributionArn}. */
    public Map<String, String> cloudFrontSignature(String distributionArn) {
        return Map.of(CLOUDFRONT_SOURCE_ARN, distributionArn, CLOUDFRONT_KEY, cloudFrontKey);
    }

    /** What the checks read from a request, copied on the event loop so a worker can check it. */
    public record Inbound(String method, String path, String query, MultiMap headers, MultiMap params) {

        static Inbound of(HttpServerRequest request) {
            MultiMap params = MultiMap.caseInsensitiveMultiMap();
            try {
                params.addAll(request.params());
            } catch (IllegalArgumentException e) {
                LOG.debugv("A request to a Lambda Web endpoint has a query that does not decode: {0}", e.getMessage());
            }
            return new Inbound(request.method().name(), request.path(), request.query(),
                    MultiMap.caseInsensitiveMultiMap().addAll(request.headers()), params);
        }
    }

    /** A signature and the session token and, for SigV4a, the region set that came with it. */
    private record Signed(SigV4SignedRequest request, String sessionToken, boolean asymmetric, String regionSet) {
    }

    /**
     * Whether the request may invoke the target endpoint. A failure inside Floci, such as in IAM
     * storage, is thrown rather than taken for a refusal. Blocks on IAM storage.
     */
    public boolean allows(Inbound request, WebFunctionTarget target) {
        String distributionArn = cloudFrontSource(request);
        if (distributionArn != null) {
            return authorizeCloudFront(distributionArn, target);
        }
        String accessKeyId = authenticate(request, target.servingRegion());
        return accessKeyId != null && authorize(accessKeyId, target);
    }

    /** The distribution CloudFront signed the request for, or null when it did not sign it. */
    private String cloudFrontSource(Inbound request) {
        String key = request.headers().get(CLOUDFRONT_KEY);
        String distributionArn = request.headers().get(CLOUDFRONT_SOURCE_ARN);
        if (key == null || !AwsArnUtils.isArn(distributionArn) || !MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), cloudFrontKey.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return distributionArn;
    }

    /** CloudFront calls as its service principal, so the function's resource policy decides alone. */
    private boolean authorizeCloudFront(String distributionArn, WebFunctionTarget target) {
        if (!config.services().iam().enforcementEnabled()) {
            return true;
        }
        String principal = ServicePrincipals.of("cloudfront");
        String endpointArn = target.endpoint().endpointArn;
        Map<String, List<String>> context = resourceContext(target);
        context.putAll(IamConditionContextResolver.serviceCallContext(principal, endpointArn, target.accountId(),
                distributionArn, AwsArnUtils.parse(distributionArn).accountId()));
        // The endpoint ARN names the home region, not the region that serves.
        context.put("aws:RequestedRegion", List.of(target.servingRegion()));
        String policy = target.function().policy;
        boolean allowed = evaluator.evaluateServicePrincipal(isBlank(policy) ? null : List.of(policy), principal,
                ACTION, endpointArn, context) == Decision.ALLOW;
        if (!allowed) {
            LOG.infov("IAM enforcement DENY: service={0} action={1} resource={2} source={3}",
                    principal, ACTION, endpointArn, distributionArn);
        }
        return allowed;
    }

    /** The access key that signed the request, or null when the signature does not hold. */
    private String authenticate(Inbound request, String region) {
        Signed found = signed(request);
        // Lambda takes this header as the payload hash and never reads the body.
        String payloadHash = request.headers().get("X-Amz-Content-SHA256");
        if (found == null || isBlank(payloadHash)) {
            return null;
        }
        SigV4SignedRequest signed = found.request();
        CredentialScope scope = found.asymmetric()
                ? CredentialScope.parseSigV4a(signed.credential()) : CredentialScope.parse(signed.credential());
        if (scope == null || !SERVICE.equals(scope.service())
                || !(found.asymmetric() ? regionSetCovers(found.regionSet(), region) : region.equals(scope.region()))
                || !signed.amzDate().startsWith(scope.date())
                || !SigV4RequestValidator.containsHeader(signed.signedHeaders(), "host")
                || signed.freshness(Instant.now()) != Freshness.FRESH) {
            return null;
        }
        // AWS refuses SigV4a from temporary credentials, which it takes in SigV4.
        if (found.asymmetric() && IamService.isTemporaryAccessKey(scope.accessKeyId())) {
            return null;
        }
        String secretKey = secretKey(scope.accessKeyId(), found.sessionToken());
        if (secretKey == null) {
            LOG.debugv("A request to a Lambda Web endpoint names an unknown access key {0}",
                    SigV4RequestValidator.sanitizeForLog(scope.accessKeyId()));
            return null;
        }
        String canonicalHeaders = SigV4Canonicalization.canonicalHeaders(signed.signedHeaders(),
                name -> String.join(",", request.headers().getAll(name)));
        String canonicalQuery;
        try {
            canonicalQuery = SigV4Canonicalization.canonicalQueryString(request.query(), signed.presigned());
        } catch (IllegalArgumentException e) {
            LOG.debugv("A request to a Lambda Web endpoint has a query that does not decode: {0}", e.getMessage());
            return null;
        }
        boolean matches;
        try {
            matches = SigV4Canonicalization.signatureMatches(request.method(), signedPath(request.path()),
                    canonicalQuery, canonicalHeaders, signed.signedHeaders(), payloadHash, signed.amzDate(), scope,
                    secretKey, signed.signature());
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute a SigV4 signature", e);
        }
        return matches ? scope.accessKeyId() : null;
    }

    /**
     * The path as signers sign it, with dot segments resolved and empty segments dropped. Lambda
     * refuses a signature over the path as sent when the two differ.
     */
    static String signedPath(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
            } else if (!segment.isEmpty() && !segment.equals(".")) {
                segments.add(segment);
            }
        }
        String first = path.startsWith("/") ? "/" : "";
        String last = path.endsWith("/") && !segments.isEmpty() ? "/" : "";
        return first + String.join("/", segments) + last;
    }

    /**
     * The header signature, or the presigned query when there is no {@code Authorization} header.
     * The header wins over the query, as on AWS. The session token and the region set are read
     * from where the signature is.
     */
    private static Signed signed(Inbound request) {
        String authorization = request.headers().get("Authorization");
        if (isBlank(authorization)) {
            MultiMap params = request.params();
            String algorithm = params.get("X-Amz-Algorithm");
            boolean asymmetric = SigV4aSignature.ALGORITHM.equals(algorithm);
            if (!asymmetric && !SigV4SignedRequest.ALGORITHM.equals(algorithm)) {
                return null;
            }
            SigV4SignedRequest presigned = SigV4SignedRequest.fromQuery(params::get);
            return presigned == null ? null
                    : new Signed(presigned, params.get(SECURITY_TOKEN), asymmetric, params.get(REGION_SET));
        }
        String amzDate = request.headers().get("X-Amz-Date");
        SigV4SignedRequest header = SigV4SignedRequest.fromHeader(authorization, amzDate);
        if (header != null) {
            return new Signed(header, request.headers().get(SECURITY_TOKEN), false, null);
        }
        SigV4SignedRequest asymmetric = SigV4SignedRequest.fromHeader(authorization, amzDate,
                SigV4aSignature.ALGORITHM);
        return asymmetric == null ? null : new Signed(asymmetric, request.headers().get(SECURITY_TOKEN), true,
                request.headers().get(REGION_SET));
    }

    /**
     * Whether a SigV4a region set covers {@code region}. AWS splits it on commas without trimming,
     * refuses a set with an empty entry, and matches each entry ignoring case with {@code *} as the
     * only wildcard.
     */
    static boolean regionSetCovers(String regionSet, String region) {
        if (regionSet == null) {
            return false;
        }
        List<String> entries = Arrays.asList(regionSet.split(",", -1));
        if (entries.contains("")) {
            return false;
        }
        String lowerRegion = region.toLowerCase(Locale.ROOT);
        return entries.stream().anyMatch(entry -> StarWildcard.matches(entry.toLowerCase(Locale.ROOT), lowerRegion));
    }

    /**
     * The secret of a key Floci issued, when the session token fits it. The local {@code test} key
     * signs with {@code test}.
     */
    private String secretKey(String accessKeyId, String sessionToken) {
        if (TEST_ACCESS_KEY.equals(accessKeyId)) {
            return TEST_ACCESS_KEY;
        }
        if (SessionTokenCheck.of(iamService, accessKeyId, sessionToken) != SessionTokenCheck.VALID) {
            return null;
        }
        return iamService.findSecretKeyInAnyAccount(accessKeyId, sessionToken)
                .map(OwnedSecretKey::secretAccessKey)
                .orElse(null);
    }

    private boolean authorize(String accessKeyId, WebFunctionTarget target) {
        if (!config.services().iam().enforcementEnabled() || TEST_ACCESS_KEY.equals(accessKeyId)) {
            return true;
        }
        Optional<String> callerAccount = iamService.resolveAccountId(accessKeyId);
        if (callerAccount.isEmpty()) {
            return false;
        }
        boolean allowed = RequestScopes.callAs(callerAccount.get(), target.servingRegion(),
                () -> evaluate(accessKeyId, callerAccount.get(), target));
        if (!allowed) {
            LOG.infov("IAM enforcement DENY: akid={0} action={1} resource={2}",
                    SigV4RequestValidator.sanitizeForLog(accessKeyId), ACTION, target.endpoint().endpointArn);
        }
        return allowed;
    }

    /**
     * Within one account the caller's policies or the function's resource policy can allow the
     * call, across accounts both have to, and an explicit deny in either refuses it.
     */
    private boolean evaluate(String accessKeyId, String callerAccount, WebFunctionTarget target) {
        CallerContext caller = iamService.resolveCallerContext(accessKeyId, Instant.now());
        if (caller == null) {
            // A known credential with no policies to evaluate, as the enforcement filter treats it.
            return true;
        }
        List<List<String>> scpLevels = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(callerAccount) : null;
        if (scpLevels != null) {
            caller = caller.withScpLevels(scpLevels);
        }
        Map<String, List<String>> context = resourceContext(target);
        Optional<CallerArns> callerArns = iamService.resolveCallerArns(accessKeyId);
        if (callerArns.isPresent()) {
            caller = caller.withPrincipalArn(callerArns.get().callerArn());
            callerArns.get().putPrincipalKeys(context);
        }
        String endpointArn = target.endpoint().endpointArn;
        Map<String, List<String>> effective = IamConditionContextResolver.withGlobalContext(
                context, endpointArn, target.servingRegion(), callerAccount, target.accountId());
        String policy = target.function().policy;
        ResourcePolicyDecision grant = evaluator.evaluateResourcePolicyFor(
                isBlank(policy) ? null : List.of(policy), RequestPrincipal.caller(callerArns.orElse(null)),
                ACTION, endpointArn, effective);
        ResourceAccountRelationship relationship = callerAccount.equals(target.accountId())
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
        return evaluator.evaluateResolvedResourcePolicy(caller, grant, relationship, ACTION, endpointArn, effective)
                != Decision.DENY;
    }

    private static Map<String, List<String>> resourceContext(WebFunctionTarget target) {
        Map<String, List<String>> context = new HashMap<>();
        context.put("lambda:Resource/WebFunctionAuthType", List.of(target.endpoint().authType));
        target.function().tags.forEach((key, value) -> context.put("aws:ResourceTag/" + key, List.of(value)));
        return context;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
