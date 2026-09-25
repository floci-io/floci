package io.github.hectorvent.floci.services.redshift.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Emulates {@code COPY <table> FROM 's3://bucket/keyOrPrefix'} and
 * {@code UNLOAD ('<select>') TO 's3://bucket/prefix'} by streaming data between {@link S3Service}
 * and the backing PostgreSQL container over a fabricated {@code COPY ... FROM STDIN} or
 * {@code COPY (...) TO STDOUT} exchange.
 *
 * <p>The caller must own the backend socket exclusively for the whole call: this class writes a
 * query and reads its response frames directly. Every exit path leaves the client with exactly one
 * response for its original {@code 'Q'} (a {@code CommandComplete}/{@code ReadyForQuery} on success,
 * a single {@code ErrorResponse}/{@code ReadyForQuery} on failure) and never closes the connection.
 */
public final class S3CopySimulator {

    private static final Logger LOG = Logger.getLogger(S3CopySimulator.class);

    private static final int LIST_PAGE_SIZE = 1000;
    private static final int CHUNK = 8192;

    // The two limits below are package-private and non-final only so unit tests can shrink them to
    // force multi-slice and over-limit paths on small inputs. Surefire runs a test class serially, and
    // each test restores the previous value in a finally block, so there is no cross-test interference.
    /** Default per-object size when the statement gives no MAXFILESIZE. Small because each slice is buffered in heap. */
    static long UNLOAD_TARGET_FILE_BYTES = 6L * 1024 * 1024;
    /** Whole-result ceiling; a larger UNLOAD is aborted rather than filling the in-memory S3 store. */
    static long UNLOAD_MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final long UNLOAD_WARN_BYTES = 32L * 1024 * 1024;
    /** Shared across every connection so concurrent UNLOADs cannot multiply the per-slice heap cost without bound. */
    static final Semaphore UNLOAD_HEAP_MIB = new Semaphore(192);
    private static final int UNLOAD_INITIAL_MIB = 12;

    private static final String SQLSTATE_PROGRAM_LIMIT_EXCEEDED = "54000";
    private static final String SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED = "53400";
    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";
    private static final String SQLSTATE_INTERNAL = "XX000";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String UPPER_ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String SECRET_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    /** COPY/UNLOAD is synchronous end-to-end; this only needs to outlive one statement. */
    private static final Duration ROLE_SESSION_TTL = Duration.ofMinutes(5);

    // S3Service's signed-request authorization only checks the bucket's resource policy: for a
    // genuine HTTP request the identity-policy gate already happened upstream in
    // IamEnforcementFilter before the request ever reached S3Service. This in-process call never
    // goes through that filter, so the role's identity policy is evaluated here explicitly.
    // Stateless (only needs an ObjectMapper), so a local instance avoids threading a new
    // dependency through the whole proxy chain for this one check.
    private static final IamPolicyEvaluator ROLE_POLICY_EVALUATOR = new IamPolicyEvaluator(new ObjectMapper());
    private static final AssumeRolePolicyEvaluator ROLE_TRUST_POLICY_EVALUATOR =
            new AssumeRolePolicyEvaluator(new ObjectMapper());

    private S3CopySimulator() {
    }

    record CopyInput(CopyStatementParser.S3CopyFrom spec, List<String> keys, S3Service s3,
                     IamService iamService, RoleSession roleSession) {
    }

    private record RoleSession(String accessKeyId, String sessionToken) {
    }

    /**
     * Validates the role ARN, its account and Redshift trust policy, then mints a short-lived session.
     */
    private static RoleSession resolveRoleSession(String iamRoleArn, IamService iamService,
                                                  String clusterAccountId, List<String> associatedRoleArns) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(iamRoleArn);
        } catch (IllegalArgumentException e) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: malformed ARN", e);
        }
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: not an IAM role ARN", null);
        }
        if (clusterAccountId != null && !clusterAccountId.equals(parsed.accountId())) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: cross-account role ARNs are not supported", null);
        }
        if (clusterAccountId != null && associatedRoleArns != null && !associatedRoleArns.contains(iamRoleArn)) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn
                            + "' could not be assumed: role is not associated with the Redshift cluster", null);
        }
        String roleName = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        Optional<IamRole> role = iamService.findRole(parsed.accountId(), roleName);
        if (role.isEmpty()) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: role does not exist", null);
        }
        if (clusterAccountId != null
                && !ROLE_TRUST_POLICY_EVALUATOR.allowsService(
                        role.get().getAssumeRolePolicyDocument(), "redshift.amazonaws.com")) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + iamRoleArn + "' could not be assumed: trust policy does not allow redshift.amazonaws.com",
                    null);
        }

        String accessKeyId = "ASIA" + randomString(UPPER_ALPHANUMERIC, 16);
        // A session token is required: IamService.findSecretKey(accessKeyId, sessionToken) treats a
        // null token on either side as a non-match, so a tokenless session can never be recognised
        // as a known access key and every signed authorization call would fail closed.
        String sessionToken = randomString(SECRET_CHARACTERS, 200);
        iamService.registerSessionForAccount(parsed.accountId(), accessKeyId,
                randomString(SECRET_CHARACTERS, 40), sessionToken, iamRoleArn,
                Instant.now().plus(ROLE_SESSION_TTL), null);
        return new RoleSession(accessKeyId, sessionToken);
    }

    private static void releaseRoleSession(RoleSession roleSession, String iamRoleArn, IamService iamService) {
        if (roleSession == null) {
            return;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(iamRoleArn);
        iamService.unregisterSession(parsed.accountId(), roleSession.accessKeyId());
    }

    /**
     * Evaluates the role's identity-based policy for one S3 action, skipped entirely when
     * {@code FLOCI_SERVICES_S3_ENFORCE_AUTH} is off (matching the anonymous path's behavior).
     */
    private static void authorizeRoleAction(S3Service s3, IamService iamService, String roleArn,
                                            String action, String resourceArn) {
        if (!s3.isAuthEnforced()) {
            return;
        }
        CallerContext caller;
        try {
            caller = iamService.resolvePrincipalContext(roleArn);
        } catch (AwsException e) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "IAM Role '" + roleArn + "' could not be assumed: " + e.getMessage(), e);
        }
        IamPolicyEvaluator.SimulationDecision decision =
                ROLE_POLICY_EVALUATOR.simulatePrincipalPolicy(caller, action, resourceArn, Map.of());
        if (decision != IamPolicyEvaluator.SimulationDecision.ALLOWED) {
            throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                    "S3 access denied for IAM Role '" + roleArn + "': " + action + " on " + resourceArn, null);
        }
    }

    /** The bucket and object ARNs live in the role's partition: a China role authorizes China buckets. */
    private static String bucketArn(String roleArn, String bucket) {
        return AwsArnUtils.Arn.global(AwsArnUtils.parse(roleArn).partition(), "s3", "", bucket).toString();
    }

    private static String objectArn(String roleArn, String bucket, String key) {
        return bucketArn(roleArn, bucket) + "/" + key;
    }

    private static String randomString(String characters, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(characters.charAt(SECURE_RANDOM.nextInt(characters.length())));
        }
        return value.toString();
    }

    interface UnloadCollector extends AutoCloseable {
        void accept(byte[] body) throws IOException;

        void complete() throws IOException;

        void abort();

        @Override
        void close();
    }

    static final class S3TransferException extends RuntimeException {
        private final String sqlState;

        S3TransferException(String sqlState, String message, Throwable cause) {
            super(message, cause);
            this.sqlState = sqlState;
        }

        String sqlState() {
            return sqlState;
        }
    }

    static CopyInput prepareCopy(CopyStatementParser.S3CopyFrom spec, S3Service s3, IamService iamService) {
        return prepareCopy(spec, s3, iamService, null);
    }

    static CopyInput prepareCopy(CopyStatementParser.S3CopyFrom spec, S3Service s3, IamService iamService,
                                 String clusterAccountId) {
        return prepareCopy(spec, s3, iamService, clusterAccountId, null);
    }

    static CopyInput prepareCopy(CopyStatementParser.S3CopyFrom spec, S3Service s3, IamService iamService,
                                 String clusterAccountId, List<String> associatedRoleArns) {
        RoleSession roleSession = spec.iamRoleArn() != null
                ? resolveRoleSession(spec.iamRoleArn(), iamService, clusterAccountId, associatedRoleArns)
                : null;
        try {
            try {
                if (spec.manifest()) {
                    if (roleSession != null) {
                        authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:GetObject", objectArn(spec.iamRoleArn(), spec.bucket(), spec.keyOrPrefix()));
                        s3.authorizeSignedGetObject(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket(), spec.keyOrPrefix());
                    } else {
                        s3.authorizeAnonymousGetObject(spec.bucket(), spec.keyOrPrefix());
                    }
                } else {
                    if (roleSession != null) {
                        authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:ListBucket", bucketArn(spec.iamRoleArn(), spec.bucket()));
                        s3.authorizeSignedListBucket(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket());
                    } else {
                        s3.authorizeAnonymousListBucket(spec.bucket());
                    }
                }
            } catch (AwsException e) {
                throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                        "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), e);
            }

            List<String> keys;
            try {
                keys = resolveKeys(spec, s3);
            } catch (AwsException e) {
                throw new S3TransferException(SQLSTATE_INTERNAL,
                        "S3 COPY could not list s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), e);
            }
            if (keys.isEmpty()) {
                throw new S3TransferException(SQLSTATE_INTERNAL,
                        "S3 object s3://" + spec.bucket() + "/" + spec.keyOrPrefix() + " not found", null);
            }

            try {
                for (String key : keys) {
                    if (roleSession != null) {
                        authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:GetObject", objectArn(spec.iamRoleArn(), spec.bucket(), key));
                        s3.authorizeSignedGetObject(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket(), key);
                    } else {
                        s3.authorizeAnonymousGetObject(spec.bucket(), key);
                    }
                }
            } catch (AwsException e) {
                throw new S3TransferException(SQLSTATE_INSUFFICIENT_PRIVILEGE,
                        "S3 access denied for s3://" + spec.bucket() + "/" + spec.keyOrPrefix(), e);
            }
            return new CopyInput(spec, List.copyOf(keys), s3, iamService, roleSession);
        } catch (RuntimeException e) {
            releaseRoleSession(roleSession, spec.iamRoleArn(), iamService);
            throw e;
        }
    }

    static String copyBackendSql(CopyStatementParser.S3CopyFrom spec) {
        return fabricateCopy(spec, null);
    }

    static String copyBackendSql(CopyStatementParser.S3CopyFrom spec, List<String> discoveredColumns) {
        return fabricateCopy(spec, discoveredColumns);
    }

    static void streamCopyInput(CopyInput input, OutputStream backendOut) throws IOException {
        streamCopyInput(input, null, backendOut);
    }

    static void streamCopyInput(CopyInput input, List<String> discoveredColumns, OutputStream backendOut) throws IOException {
        streamObjects(input.spec(), discoveredColumns, input.s3(), input.iamService(), input.roleSession(), input.keys(), backendOut);
    }

    static void releaseCopySession(CopyInput input) {
        releaseRoleSession(input.roleSession(), input.spec().iamRoleArn(), input.iamService());
    }

    static UnloadCollector prepareUnload(CopyStatementParser.S3Unload spec, S3Service s3, IamService iamService) {
        return prepareUnload(spec, s3, iamService, null);
    }

    static UnloadCollector prepareUnload(CopyStatementParser.S3Unload spec, S3Service s3, IamService iamService,
                                         String clusterAccountId) {
        return prepareUnload(spec, s3, iamService, clusterAccountId, null);
    }

    static UnloadCollector prepareUnload(CopyStatementParser.S3Unload spec, S3Service s3, IamService iamService,
                                         String clusterAccountId, List<String> associatedRoleArns) {
        RoleSession roleSession = spec.iamRoleArn() != null
                ? resolveRoleSession(spec.iamRoleArn(), iamService, clusterAccountId, associatedRoleArns)
                : null;
        String probeKey = unloadDataKey(spec, 0);
        try {
            if (roleSession != null) {
                authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:PutObject", objectArn(spec.iamRoleArn(), spec.bucket(), probeKey));
                s3.authorizeSignedPutObject(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket(), probeKey);
                if (spec.manifest()) {
                    String manifestKey = spec.prefix() + "manifest";
                    authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:PutObject", objectArn(spec.iamRoleArn(), spec.bucket(), manifestKey));
                    s3.authorizeSignedPutObject(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket(), manifestKey);
                }
                if (!spec.allowOverwrite()) {
                    authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:ListBucket", bucketArn(spec.iamRoleArn(), spec.bucket()));
                    s3.authorizeSignedListBucket(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket());
                    if (targetPrefixHasObjects(spec, s3)) {
                        throw new S3TransferException(SQLSTATE_INTERNAL,
                                "S3 prefix s3://" + spec.bucket() + "/" + spec.prefix()
                                        + " is not empty; specify ALLOWOVERWRITE to overwrite", null);
                    }
                }
            } else {
                s3.authorizeAnonymousPutObject(spec.bucket(), probeKey);
                if (spec.manifest()) {
                    s3.authorizeAnonymousPutObject(spec.bucket(), spec.prefix() + "manifest");
                }
                if (!spec.allowOverwrite()) {
                    s3.authorizeAnonymousListBucket(spec.bucket());
                    if (targetPrefixHasObjects(spec, s3)) {
                        throw new S3TransferException(SQLSTATE_INTERNAL,
                                "S3 prefix s3://" + spec.bucket() + "/" + spec.prefix()
                                        + " is not empty; specify ALLOWOVERWRITE to overwrite", null);
                    }
                }
            }
        } catch (AwsException e) {
            releaseRoleSession(roleSession, spec.iamRoleArn(), iamService);
            throw new S3TransferException(unloadWriteSqlState(e), unloadWriteMessage(e, spec), e);
        } catch (RuntimeException e) {
            releaseRoleSession(roleSession, spec.iamRoleArn(), iamService);
            throw e;
        }

        if (!UNLOAD_HEAP_MIB.tryAcquire(UNLOAD_INITIAL_MIB)) {
            releaseRoleSession(roleSession, spec.iamRoleArn(), iamService);
            throw new S3TransferException(SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED,
                    "UNLOAD memory budget exhausted; retry shortly", null);
        }
        try {
            return new S3UnloadCollector(spec, s3, iamService, roleSession);
        } catch (IOException e) {
            UNLOAD_HEAP_MIB.release(UNLOAD_INITIAL_MIB);
            releaseRoleSession(roleSession, spec.iamRoleArn(), iamService);
            throw new S3TransferException(SQLSTATE_INTERNAL, "UNLOAD failed: " + e.getMessage(), e);
        }
    }

    static String unloadBackendSql(CopyStatementParser.S3Unload spec) {
        return fabricateUnloadCopy(spec);
    }

    /**
     * @param txStatus the transaction-status byte from the client's last {@code ReadyForQuery}
     *                 ({@code 'I'} idle, {@code 'T'} in a block, {@code 'E'} failed block); a
     *                 synthesized error reports {@code 'I'} outside a block and {@code 'E'} inside.
     * @return {@code true} when the exchange was handled (success or a clean error sent to the
     *         client). Part 4 always handles; the value exists so a later interceptor can decline.
     */
    public static boolean runCopyFrom(Socket client, Socket backend,
                                      CopyStatementParser.S3CopyFrom spec, S3Service s3, IamService iamService,
                                      char txStatus) throws IOException {
        return runCopyFrom(client, backend, spec, s3, iamService, txStatus, null);
    }

    public static boolean runCopyFrom(Socket client, Socket backend,
                                      CopyStatementParser.S3CopyFrom spec, S3Service s3, IamService iamService,
                                      char txStatus, IntConsumer onStatusChange) throws IOException {
        return runCopyFrom(client, backend, spec, s3, iamService, null, txStatus, onStatusChange);
    }

    public static boolean runCopyFrom(Socket client, Socket backend,
                                      CopyStatementParser.S3CopyFrom spec, S3Service s3, IamService iamService,
                                      String clusterAccountId, char txStatus, IntConsumer onStatusChange) throws IOException {
        return runCopyFrom(client, backend, spec, s3, iamService, clusterAccountId, null, txStatus, onStatusChange);
    }

    public static boolean runCopyFrom(Socket client, Socket backend,
                                      CopyStatementParser.S3CopyFrom spec, S3Service s3, IamService iamService,
                                      String clusterAccountId, List<String> associatedRoleArns,
                                      char txStatus, IntConsumer onStatusChange) throws IOException {
        CopyInput input;
        try {
            input = prepareCopy(spec, s3, iamService, clusterAccountId, associatedRoleArns);
        } catch (S3TransferException e) {
            LOG.debugv(e, "COPY preparation failed for s3://{0}/{1}", spec.bucket(), spec.keyOrPrefix());
            sendError(client, backend, e.sqlState(), e.getMessage(), txStatus, onStatusChange);
            return true;
        }

        try {
            List<String> discoveredColumns = null;
            if (spec.jsonAuto() && (spec.columns() == null || spec.columns().isEmpty())) {
                discoveredColumns = discoverTableColumns(client, backend, spec, txStatus, onStatusChange);
                if (discoveredColumns == null) {
                    return true;
                }
            }

            OutputStream backendOut = backend.getOutputStream();
            backendOut.write(PostgresWireDecoder.encodeQuery(copyBackendSql(spec, discoveredColumns)));
            backendOut.flush();

            PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
            PostgresWireDecoder.FrontendMessage first;
            try {
                first = nextNonAsync(backendDecoder, client);
            } catch (IOException e) {
                LOG.warnv(e, "backend read failed while awaiting CopyInResponse");
                closeQuietly(backend);
                sendError(client, null, SQLSTATE_INTERNAL, "S3 COPY failed: backend closed or timed out", txStatus, onStatusChange);
                closeQuietly(client);
                return true;
            }
            if (first == null) {
                LOG.warn("backend closed before answering the fabricated COPY");
                closeQuietly(backend);
                sendError(client, null, SQLSTATE_INTERNAL, "S3 COPY failed: backend closed before COPY started", txStatus, onStatusChange);
                closeQuietly(client);
                return true;
            }
            if (first.type() != 'G') {
                // Backend rejected the COPY itself (e.g. no such table). Its ErrorResponse and the
                // ReadyForQuery that follows are the client's one response.
                forward(client, first);
                drainToReadyForQuery(backendDecoder, client, onStatusChange);
                return true;
            }

            // The CopyIn stream is open. Any failure from here is resolved with exactly one response:
            // a CopyFail to the backend, whose ErrorResponse/ReadyForQuery is relayed to the client;
            // or, if the backend is unreachable, one synthesized ErrorResponse/ReadyForQuery.
            try {
                streamCopyInput(input, discoveredColumns, backendOut);
                writeCopyDone(backendOut);
                drainToReadyForQuery(backendDecoder, client, onStatusChange);
            } catch (RuntimeException | IOException e) {
                LOG.warnv(e, "S3 COPY streaming failed; aborting the open CopyIn");
                abortOpenCopyIn(client, backend, backendOut, backendDecoder, e, txStatus, onStatusChange);
            }
            return true;
        } finally {
            releaseCopySession(input);
        }
    }

    private static void abortOpenCopyIn(Socket client, Socket backend, OutputStream backendOut,
                                        PostgresWireDecoder backendDecoder, Exception cause,
                                        char txStatus, IntConsumer onStatusChange) throws IOException {
        try {
            writeCopyFail(backendOut, cause.getMessage());
            drainToReadyForQuery(backendDecoder, client, onStatusChange);
        } catch (IOException backendGone) {
            LOG.debugv(backendGone, "backend unreachable while aborting CopyIn; synthesizing client error");
            closeQuietly(backend);
            String detail = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            sendError(client, null, SQLSTATE_INTERNAL, "S3 COPY failed: " + detail, txStatus, onStatusChange);
            closeQuietly(client);
        }
    }

    private static List<String> resolveKeys(CopyStatementParser.S3CopyFrom spec, S3Service s3) {
        if (spec.manifest()) {
            return ManifestReader.resolveManifestKeys(spec.bucket(), spec.keyOrPrefix(), s3);
        }
        List<String> keys = new ArrayList<>();
        if (s3.objectExists(spec.bucket(), spec.keyOrPrefix())) {
            keys.add(spec.keyOrPrefix());
            return keys;
        }
        String continuationToken = null;
        do {
            S3Service.ListObjectsResult result = s3.listObjectsWithPrefixes(
                    spec.bucket(), spec.keyOrPrefix(), null, LIST_PAGE_SIZE, continuationToken, null);
            if (result != null && result.objects() != null) {
                for (S3Object object : result.objects()) {
                    keys.add(object.getKey());
                }
            }
            continuationToken = (result != null && result.isTruncated()) ? result.nextContinuationToken() : null;
        } while (continuationToken != null);

        keys.sort(String::compareTo);
        return keys;
    }

    private static String fabricateCopy(CopyStatementParser.S3CopyFrom spec, List<String> discoveredColumns) {
        StringBuilder sql = new StringBuilder("COPY ").append(spec.targetTable());
        if (discoveredColumns != null && !discoveredColumns.isEmpty()) {
            sql.append(" (")
                    .append(discoveredColumns.stream()
                            .map(c -> "\"" + c.replace("\"", "\"\"") + "\"")
                            .collect(Collectors.joining(", ")))
                    .append(")");
        } else if (spec.columns() != null && !spec.columns().isEmpty()) {
            sql.append(" (")
                    .append(String.join(", ", spec.columns()))
                    .append(")");
        }
        boolean csv = spec.csv() || spec.jsonAuto();
        sql.append(" FROM STDIN WITH (FORMAT ").append(csv ? "csv" : "text");
        String delimiter = spec.jsonAuto() ? "," : (spec.delimiter() != null ? spec.delimiter() : (csv ? "," : "|"));
        sql.append(", DELIMITER '").append(quoteLiteral(delimiter)).append("'");
        if (spec.nullAs() != null) {
            sql.append(", NULL '").append(quoteLiteral(spec.nullAs())).append("'");
        }
        sql.append(")");
        return sql.toString();
    }

    private static String unquoteIdentifier(String identifier) {
        if (identifier != null && identifier.length() >= 2
                && identifier.startsWith("\"") && identifier.endsWith("\"")) {
            return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
        }
        return identifier;
    }

    /**
     * Quote a value for a single-quoted SQL string literal. PostgreSQL defaults to
     * {@code standard_conforming_strings = on}, where a backslash is an ordinary character, so only
     * the quote itself is doubled: {@code NULL AS '\N'} must reach the backend as {@code '\N'}.
     */
    private static String quoteLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void streamObjects(CopyStatementParser.S3CopyFrom spec, List<String> discoveredColumns,
                                      S3Service s3, IamService iamService, RoleSession roleSession,
                                      List<String> keys, OutputStream backendOut) throws IOException {
        byte[] buffer = new byte[CHUNK];
        for (int i = 0; i < keys.size(); i++) {
            if (roleSession != null) {
                authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:GetObject", objectArn(spec.iamRoleArn(), spec.bucket(), keys.get(i)));
                s3.authorizeSignedGetObject(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket(), keys.get(i));
            } else {
                s3.authorizeAnonymousGetObject(spec.bucket(), keys.get(i));
            }
            S3Object object = s3.getObject(spec.bucket(), keys.get(i));
            byte[] data = object != null && object.getData() != null ? object.getData() : new byte[0];
            InputStream rawIn = new ByteArrayInputStream(data);
            try (InputStream in = spec.gzip() ? new GZIPInputStream(rawIn) : rawIn) {
                if (spec.jsonAuto()) {
                    List<String> targetCols = (discoveredColumns != null && !discoveredColumns.isEmpty())
                            ? discoveredColumns
                            : (spec.columns() != null
                                    ? spec.columns().stream().map(S3CopySimulator::unquoteIdentifier).toList()
                                    : List.of());
                    CopyDataOutputStream copyDataOut = new CopyDataOutputStream(backendOut);
                    JsonLinesToCsvConverter.convert(in, targetCols, copyDataOut, spec.jsonAutoIgnoreCase());
                    copyDataOut.flush();
                } else {
                    if (i == 0 && spec.headerLines() > 0) {
                        skipLines(in, spec.headerLines());
                    }
                    int read;
                    boolean endsWithNewline = false;
                    boolean hasData = false;
                    while ((read = in.read(buffer)) != -1) {
                        if (read > 0) {
                            hasData = true;
                            endsWithNewline = (buffer[read - 1] == '\n');
                            writeCopyData(backendOut, buffer, read);
                        }
                    }
                    if (hasData && !endsWithNewline) {
                        writeCopyData(backendOut, new byte[]{'\n'}, 1);
                    }
                }
            }
        }
        backendOut.flush();
    }

    private static List<String> discoverTableColumns(Socket client, Socket backend,
                                                     CopyStatementParser.S3CopyFrom spec,
                                                     char txStatus, IntConsumer onStatusChange) throws IOException {
        String query = "SELECT a.attname FROM pg_catalog.pg_attribute a "
                + "WHERE a.attrelid = to_regclass('" + quoteLiteral(spec.targetTable()) + "') "
                + "AND a.attnum > 0 AND NOT a.attisdropped "
                + "ORDER BY a.attnum";

        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(query));
        backendOut.flush();

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        List<String> cols = new ArrayList<>();
        PostgresWireDecoder.FrontendMessage msg;
        while ((msg = backendDecoder.nextMessage()) != null) {
            char type = msg.type();
            if (type == 'D') {
                byte[] body = msg.body();
                if (body.length >= 6) {
                    int colCount = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
                    if (colCount >= 1) {
                        int colLen = ((body[2] & 0xFF) << 24) | ((body[3] & 0xFF) << 16)
                                | ((body[4] & 0xFF) << 8) | (body[5] & 0xFF);
                        if (colLen > 0 && 6 + colLen <= body.length) {
                            cols.add(new String(body, 6, colLen, StandardCharsets.UTF_8));
                        }
                    }
                }
            } else if (type == 'E') {
                forward(client, msg);
                drainToReadyForQuery(backendDecoder, client, onStatusChange);
                return null;
            } else if (type == 'N' || type == 'A' || type == 'S') {
                forward(client, msg);
            } else if (type == 'Z') {
                if (onStatusChange != null && msg.body().length > 0) {
                    onStatusChange.accept(msg.body()[0]);
                }
                break;
            }
        }
        if (cols.isEmpty()) {
            sendError(client, backend, "42P01",
                    "relation \"" + spec.targetTable() + "\" does not exist or has no user columns",
                    txStatus, onStatusChange);
            return null;
        }
        return cols;
    }

    private static final class CopyDataOutputStream extends OutputStream {
        private final OutputStream backendOut;
        private final byte[] chunkBuf = new byte[CHUNK];
        private int count = 0;

        CopyDataOutputStream(OutputStream backendOut) {
            this.backendOut = backendOut;
        }

        @Override
        public void write(int b) throws IOException {
            chunkBuf[count++] = (byte) b;
            if (count == chunkBuf.length) {
                flushChunk();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                int space = chunkBuf.length - count;
                int toCopy = Math.min(remaining, space);
                System.arraycopy(b, offset, chunkBuf, count, toCopy);
                count += toCopy;
                offset += toCopy;
                remaining -= toCopy;
                if (count == chunkBuf.length) {
                    flushChunk();
                }
            }
        }

        private void flushChunk() throws IOException {
            if (count > 0) {
                writeCopyData(backendOut, chunkBuf, count);
                count = 0;
            }
        }

        @Override
        public void flush() throws IOException {
            flushChunk();
            backendOut.flush();
        }
    }

    private static void skipLines(InputStream in, int lines) throws IOException {
        int seen = 0;
        int b;
        while (seen < lines && (b = in.read()) != -1) {
            if (b == '\n') {
                seen++;
            }
        }
    }

    private static void writeCopyData(OutputStream out, byte[] buf, int len) throws IOException {
        out.write('d');
        out.write(intBytes(4 + len));
        out.write(buf, 0, len);
    }

    private static void writeCopyDone(OutputStream out) throws IOException {
        out.write('c');
        out.write(intBytes(4));
        out.flush();
    }

    static void writeCopyFail(OutputStream out, String reason) throws IOException {
        byte[] message = (reason == null ? "S3 COPY aborted" : reason).getBytes(StandardCharsets.UTF_8);
        out.write('f');
        out.write(intBytes(4 + message.length + 1));
        out.write(message);
        out.write(0);
        out.flush();
    }

    /** Read the next backend message, relaying asynchronous messages to the client as it goes. */
    private static PostgresWireDecoder.FrontendMessage nextNonAsync(PostgresWireDecoder decoder, Socket client)
            throws IOException {
        OutputStream clientOut = client.getOutputStream();
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                return null;
            }
            char type = message.type();
            if (type == 'N' || type == 'A' || type == 'S') {
                clientOut.write(message.toPacketBytes());
                clientOut.flush();
                continue;
            }
            return message;
        }
    }

    private static void drainToReadyForQuery(PostgresWireDecoder decoder, Socket client,
                                            IntConsumer onStatusChange) throws IOException {
        OutputStream clientOut = client.getOutputStream();
        while (true) {
            PostgresWireDecoder.FrontendMessage message = decoder.nextMessage();
            if (message == null) {
                return;
            }
            clientOut.write(message.toPacketBytes());
            clientOut.flush();
            if (message.type() == 'Z') {
                if (onStatusChange != null && message.body().length > 0) {
                    onStatusChange.accept(message.body()[0]);
                }
                return;
            }
        }
    }

    private static void forward(Socket client, PostgresWireDecoder.FrontendMessage message) throws IOException {
        client.getOutputStream().write(message.toPacketBytes());
        client.getOutputStream().flush();
    }

    private static void sendError(Socket client, Socket backend, String sqlState, String message,
                                  char txStatus, IntConsumer onStatusChange) throws IOException {
        if (txStatus == 'T' && backend != null && !backend.isClosed()) {
            if (!failBackendTransaction(backend, onStatusChange)) {
                closeQuietly(backend);
                // Backend transaction state could not be confirmed; do not report an unconfirmed
                // failed transaction ('E') or leave a desynchronized backend in the pump.
                try {
                    OutputStream out = client.getOutputStream();
                    byte[] body = errorBody(sqlState, message);
                    out.write('E');
                    out.write(intBytes(4 + body.length));
                    out.write(body);
                    out.flush();
                } catch (IOException e) {
                    LOG.debugv(e, "failed to send ErrorResponse to client before closing socket");
                } finally {
                    closeQuietly(client);
                }
                return;
            }
        }
        OutputStream out = client.getOutputStream();
        byte[] body = errorBody(sqlState, message);
        out.write('E');
        out.write(intBytes(4 + body.length));
        out.write(body);
        byte status = (txStatus == 'I' || txStatus == 0) ? (byte) 'I' : (byte) 'E';
        out.write(new byte[]{'Z', 0, 0, 0, 5, status});
        out.flush();
        if (onStatusChange != null) {
            onStatusChange.accept(status);
        }
    }

    private static boolean failBackendTransaction(Socket backend, IntConsumer onStatusChange) {
        try {
            OutputStream out = backend.getOutputStream();
            out.write(PostgresWireDecoder.encodeQuery("(FLOCI_ABORT_TX)"));
            out.flush();
            PostgresWireDecoder decoder = new PostgresWireDecoder(backend.getInputStream());
            while (true) {
                PostgresWireDecoder.FrontendMessage msg = decoder.nextMessage();
                if (msg == null) {
                    LOG.warn("backend closed before transaction abort could be synchronized");
                    return false;
                }
                if (msg.type() == 'Z') {
                    char status = (msg.body().length > 0) ? (char) msg.body()[0] : 0;
                    if (onStatusChange != null && status != 0) {
                        onStatusChange.accept(status);
                    }
                    if (status != 'E') {
                        LOG.warnv("backend returned unexpected transaction status {0} instead of 'E' after abort query", status);
                        return false;
                    }
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.warnv(e, "failed to synchronize backend transaction abort state");
            return false;
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null && !s.isClosed()) {
                s.close();
            }
        } catch (IOException e) {
            LOG.debugv(e, "failed to close socket: {0}", s);
        }
    }

    static byte[] errorBody(String sqlState, String message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeField(bytes, 'S', "ERROR");
        writeField(bytes, 'C', sqlState);
        writeField(bytes, 'M', message);
        bytes.write(0);
        return bytes.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream bytes, char tag, String value) {
        bytes.write(tag);
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        bytes.write(raw, 0, raw.length);
        bytes.write(0);
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    public static boolean runUnload(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, S3Service s3, IamService iamService, char txStatus) throws IOException {
        return runUnload(client, backend, spec, s3, iamService, txStatus, null);
    }

    public static boolean runUnload(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, S3Service s3, IamService iamService, char txStatus,
            IntConsumer onStatusChange) throws IOException {
        return runUnload(client, backend, spec, s3, iamService, null, txStatus, onStatusChange);
    }

    public static boolean runUnload(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, S3Service s3, IamService iamService, String clusterAccountId,
            char txStatus, IntConsumer onStatusChange) throws IOException {
        return runUnload(client, backend, spec, s3, iamService, clusterAccountId, null, txStatus, onStatusChange);
    }

    public static boolean runUnload(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, S3Service s3, IamService iamService, String clusterAccountId,
            List<String> associatedRoleArns, char txStatus, IntConsumer onStatusChange) throws IOException {
        UnloadCollector collector;
        try {
            collector = prepareUnload(spec, s3, iamService, clusterAccountId, associatedRoleArns);
        } catch (S3TransferException e) {
            LOG.debugv(e, "UNLOAD preparation failed for s3://{0}/{1}", spec.bucket(), spec.prefix());
            sendError(client, backend, e.sqlState(), e.getMessage(), txStatus, onStatusChange);
            return true;
        }

        try (collector) {
            return runUnloadStreaming(client, backend, spec, collector, txStatus, onStatusChange);
        }
    }

    private static boolean runUnloadStreaming(Socket client, Socket backend,
            CopyStatementParser.S3Unload spec, UnloadCollector collector, char txStatus,
            IntConsumer onStatusChange) throws IOException {

        PostgresWireDecoder.FrontendMessage cmdComplete = null;
        PostgresWireDecoder.FrontendMessage readyForQuery = null;

        OutputStream backendOut = backend.getOutputStream();
        backendOut.write(PostgresWireDecoder.encodeQuery(unloadBackendSql(spec)));
        backendOut.flush();

        PostgresWireDecoder backendDecoder = new PostgresWireDecoder(backend.getInputStream());
        PostgresWireDecoder.FrontendMessage first;
        try {
            first = nextNonAsync(backendDecoder, client);
        } catch (IOException e) {
            LOG.warnv(e, "backend read failed while awaiting CopyOutResponse");
            collector.abort();
            closeQuietly(backend);
            sendError(client, null, SQLSTATE_INTERNAL,
                    "UNLOAD failed: backend closed or timed out", txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        if (first == null) {
            collector.abort();
            closeQuietly(backend);
            sendError(client, null, SQLSTATE_INTERNAL,
                    "UNLOAD failed: backend closed before COPY started", txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        if (first.type() != 'H') {
            collector.abort();
            forward(client, first);
            drainToReadyForQuery(backendDecoder, client, onStatusChange);
            return true;
        }

        try {
            while (true) {
                PostgresWireDecoder.FrontendMessage message = backendDecoder.nextMessage();
                if (message == null) {
                    collector.abort();
                    closeQuietly(backend);
                    sendError(client, null, SQLSTATE_INTERNAL,
                            "UNLOAD failed: backend closed mid-stream", txStatus, onStatusChange);
                    closeQuietly(client);
                    return true;
                }
                char type = message.type();
                if (type == 'd') {
                    collector.accept(message.body());
                } else if (type == 'C') {
                    cmdComplete = message;
                } else if (type == 'Z') {
                    readyForQuery = message;
                    break;
                } else if (type == 'E') {
                    collector.abort();
                    forward(client, message);
                    drainToReadyForQuery(backendDecoder, client, onStatusChange);
                    return true;
                } else if (type == 'N' || type == 'A' || type == 'S') {
                    forward(client, message);
                }
            }
            collector.complete();
        } catch (S3TransferException e) {
            LOG.debugv(e, "UNLOAD S3 operation failed during streaming");
            if (readyForQuery != null) {
                sendError(client, backend, e.sqlState(), e.getMessage(), txStatus, onStatusChange);
                return true;
            }
            if (SQLSTATE_PROGRAM_LIMIT_EXCEEDED.equals(e.sqlState())) {
                closeQuietly(backend);
                sendError(client, null, e.sqlState(), e.getMessage(), txStatus, onStatusChange);
                closeQuietly(client);
                return true;
            }
            return abortUnload(client, backend, backendDecoder, collector,
                    e.sqlState(), e.getMessage(), txStatus, onStatusChange);
        } catch (RuntimeException | IOException e) {
            LOG.warnv(e, "UNLOAD streaming failed");
            String detail = e.getMessage() != null ? e.getMessage() : e.toString();
            if (readyForQuery != null) {
                sendError(client, backend, SQLSTATE_INTERNAL,
                        "UNLOAD failed: " + detail, txStatus, onStatusChange);
                return true;
            }
            return abortUnload(client, backend, backendDecoder, collector,
                    SQLSTATE_INTERNAL, "UNLOAD failed: " + detail, txStatus, onStatusChange);
        }

        if (cmdComplete != null) {
            forward(client, cmdComplete);
        }
        forward(client, readyForQuery);
        if (onStatusChange != null && readyForQuery.body().length > 0) {
            onStatusChange.accept(readyForQuery.body()[0]);
        }
        return true;
    }

    private static boolean targetPrefixHasObjects(CopyStatementParser.S3Unload spec, S3Service s3) {
        S3Service.ListObjectsResult r = s3.listObjectsWithPrefixes(
                spec.bucket(), spec.prefix(), null, 1, null, null);
        return r != null && r.objects() != null && !r.objects().isEmpty();
    }

    private static String fabricateUnloadCopy(CopyStatementParser.S3Unload spec) {
        boolean csvFraming = spec.csv() || spec.addQuotes() || spec.header();
        StringBuilder sql = new StringBuilder("COPY (").append(spec.selectQuery())
                .append(") TO STDOUT WITH (FORMAT ").append(csvFraming ? "csv" : "text");
        String delimiter = spec.delimiter() != null ? spec.delimiter() : (csvFraming ? "," : "|");
        sql.append(", DELIMITER '").append(quoteLiteral(delimiter)).append("'");
        if (spec.header()) {
            sql.append(", HEADER true");
        }
        if (spec.addQuotes()) {
            sql.append(", FORCE_QUOTE *");
        }
        if (spec.nullAs() != null) {
            sql.append(", NULL '").append(quoteLiteral(spec.nullAs())).append("'");
        }
        sql.append(")");
        return sql.toString();
    }

    private static final class S3UnloadCollector implements UnloadCollector {
        private final CopyStatementParser.S3Unload spec;
        private final S3Service s3;
        private final IamService iamService;
        private final RoleSession roleSession;
        private final long threshold;
        private final String contentType;
        private final List<String> writtenKeys = new ArrayList<>();
        private final List<Integer> writtenLengths = new ArrayList<>();

        private ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private OutputStream acc;
        private ByteArrayOutputStream headerBuf;
        private byte[] headerBytes;
        private long rawSlice;
        private long slicePayload;
        private long rawTotal;
        private boolean lastByteNewline = true;
        private boolean warned;
        private boolean capturingHeader;
        private boolean finished;
        private boolean aborted;
        private boolean closed;
        private int sliceIndex;
        private int heldMib = UNLOAD_INITIAL_MIB;

        private S3UnloadCollector(CopyStatementParser.S3Unload spec, S3Service s3,
                                  IamService iamService, RoleSession roleSession) throws IOException {
            this.spec = spec;
            this.s3 = s3;
            this.iamService = iamService;
            this.roleSession = roleSession;
            threshold = spec.maxFileSizeBytes() > 0 ? spec.maxFileSizeBytes() : UNLOAD_TARGET_FILE_BYTES;
            contentType = spec.gzip() ? "application/gzip" : "text/plain";
            acc = newAccumulator();
            headerBuf = spec.header() ? new ByteArrayOutputStream() : null;
            capturingHeader = spec.header();
        }

        @Override
        public void accept(byte[] body) throws IOException {
            ensureOpen();
            int dataStart = 0;
            if (capturingHeader) {
                int newline = -1;
                for (int i = 0; i < body.length; i++) {
                    if (body[i] == '\n') {
                        newline = i;
                        break;
                    }
                }
                int end = newline >= 0 ? newline + 1 : body.length;
                headerBuf.write(body, 0, end);
                if (newline >= 0) {
                    headerBytes = headerBuf.toByteArray();
                    headerBuf = null;
                    capturingHeader = false;
                }
                dataStart = end;
            } else if (headerBytes != null && sliceIndex > 0 && rawSlice == 0) {
                acc.write(headerBytes);
                rawSlice += headerBytes.length;
            }

            acc.write(body, 0, body.length);
            rawSlice += body.length;
            rawTotal += body.length;
            slicePayload += body.length - dataStart;
            if (body.length > 0) {
                lastByteNewline = body[body.length - 1] == '\n';
            }
            if (!warned && rawTotal > UNLOAD_WARN_BYTES) {
                warned = true;
                LOG.warnv("UNLOAD result passed {0} bytes and is buffered a slice at a time "
                        + "(bucket={1}, prefix={2})", UNLOAD_WARN_BYTES, spec.bucket(), spec.prefix());
            }
            acquireForCurrentSlice();
            if (rawTotal > UNLOAD_MAX_TOTAL_BYTES) {
                fail(SQLSTATE_PROGRAM_LIMIT_EXCEEDED,
                        "UNLOAD result exceeds the " + UNLOAD_MAX_TOTAL_BYTES + "-byte limit", null);
            }
            if (slicePayload >= threshold && lastByteNewline) {
                writeCurrentSlice();
                resetSlice();
            }
        }

        @Override
        public void complete() throws IOException {
            ensureOpen();
            byte[] payload = finishSlice(acc, sink);
            if (slicePayload > 0 || writtenKeys.isEmpty()) {
                writePayload(payload, sliceIndex);
            }
            if (spec.manifest()) {
                try {
                    String key = spec.prefix() + "manifest";
                    if (roleSession != null) {
                        authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:PutObject", objectArn(spec.iamRoleArn(), spec.bucket(), key));
                        s3.authorizeSignedPutObject(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket(), key);
                    } else {
                        s3.authorizeAnonymousPutObject(spec.bucket(), key);
                    }
                    s3.putObject(spec.bucket(), key,
                            manifestJson(spec.bucket(), writtenKeys, writtenLengths)
                                    .getBytes(StandardCharsets.UTF_8),
                            "application/json", Map.of());
                } catch (RuntimeException e) {
                    fail(SQLSTATE_INTERNAL, "UNLOAD manifest write failed", e);
                }
            }
            finished = true;
        }

        @Override
        public void abort() {
            if (aborted || finished) {
                return;
            }
            closeAcc(acc, sink);
            if (spec.manifest()) {
                deleteWritten(s3, iamService, spec, writtenKeys, roleSession);
            }
            aborted = true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (!finished && !aborted) {
                abort();
            }
            UNLOAD_HEAP_MIB.release(heldMib);
            heldMib = 0;
            releaseRoleSession(roleSession, spec.iamRoleArn(), iamService);
            closed = true;
        }

        private void acquireForCurrentSlice() {
            int wantedMib = (int) ((rawSlice * 3) / (1024 * 1024)) + 1;
            while (heldMib < wantedMib) {
                if (!UNLOAD_HEAP_MIB.tryAcquire(1)) {
                    fail(SQLSTATE_CONFIGURATION_LIMIT_EXCEEDED,
                            "UNLOAD memory budget exhausted; retry with a smaller result", null);
                }
                heldMib++;
            }
        }

        private void writeCurrentSlice() throws IOException {
            byte[] payload = finishSlice(acc, sink);
            writePayload(payload, sliceIndex);
            sliceIndex++;
        }

        private void writePayload(byte[] payload, int index) {
            String key = unloadDataKey(spec, index);
            try {
                if (roleSession != null) {
                    authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:PutObject", objectArn(spec.iamRoleArn(), spec.bucket(), key));
                    s3.authorizeSignedPutObject(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket(), key);
                } else {
                    s3.authorizeAnonymousPutObject(spec.bucket(), key);
                }
                s3.putObject(spec.bucket(), key, payload, contentType, Map.of());
            } catch (AwsException e) {
                fail(unloadWriteSqlState(e), unloadWriteMessage(e, spec), e);
            } catch (RuntimeException e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.toString();
                fail(SQLSTATE_INTERNAL, "UNLOAD failed: " + detail, e);
            }
            writtenKeys.add(key);
            writtenLengths.add(payload.length);
        }

        private void resetSlice() throws IOException {
            int release = heldMib - UNLOAD_INITIAL_MIB;
            if (release > 0) {
                UNLOAD_HEAP_MIB.release(release);
                heldMib = UNLOAD_INITIAL_MIB;
            }
            rawSlice = 0;
            slicePayload = 0;
            lastByteNewline = true;
            sink = new ByteArrayOutputStream();
            acc = newAccumulator();
        }

        private OutputStream newAccumulator() throws IOException {
            return spec.gzip() ? new GZIPOutputStream(sink) : sink;
        }

        private void ensureOpen() {
            if (finished || aborted || closed) {
                throw new IllegalStateException("UNLOAD collector is no longer open");
            }
        }

        private void fail(String sqlState, String message, Throwable cause) {
            abort();
            throw new S3TransferException(sqlState, message, cause);
        }
    }

    /** {@code 404} from the S3 layer means the bucket is absent, not that access was denied. */
    private static String unloadWriteSqlState(AwsException e) {
        return e.getHttpStatus() == 404 ? SQLSTATE_INTERNAL : SQLSTATE_INSUFFICIENT_PRIVILEGE;
    }

    private static String unloadWriteMessage(AwsException e, CopyStatementParser.S3Unload spec) {
        return e.getHttpStatus() == 404
                ? "S3 bucket s3://" + spec.bucket() + " not found"
                : "S3 access denied for s3://" + spec.bucket() + "/" + spec.prefix();
    }

    private static String unloadDataKey(CopyStatementParser.S3Unload spec, int index) {
        String base = spec.parallel()
                ? spec.prefix() + String.format("%04d_part_00", index)
                : spec.prefix() + String.format("%03d", index);
        return spec.gzip() ? base + ".gz" : base;
    }

    private static byte[] finishSlice(OutputStream acc, ByteArrayOutputStream sink) throws IOException {
        if (acc != sink) {
            acc.close(); // flush the GZIP trailer
        }
        return sink.toByteArray();
    }

    private static void closeAcc(OutputStream acc, ByteArrayOutputStream sink) {
        if (acc != sink) {
            try {
                acc.close();
            } catch (IOException e) {
                LOG.debugv(e, "error closing the UNLOAD accumulation stream");
            }
        }
    }

    private static boolean abortUnload(Socket client, Socket backend, PostgresWireDecoder backendDecoder,
            UnloadCollector collector, String sqlState, String message,
            char txStatus, IntConsumer onStatusChange) throws IOException {
        collector.abort();
        try {
            drainBackendDiscarding(backendDecoder);
        } catch (IOException backendGone) {
            LOG.debugv(backendGone, "backend unreachable while draining an aborted UNLOAD; synthesizing client error");
            closeQuietly(backend);
            sendError(client, null, sqlState, message, txStatus, onStatusChange);
            closeQuietly(client);
            return true;
        }
        sendError(client, backend, sqlState, message, txStatus, onStatusChange);
        return true;
    }

    /** Read backend messages, forwarding nothing, until its ReadyForQuery or EOF. Leaves the backend socket clean. */
    private static void drainBackendDiscarding(PostgresWireDecoder decoder) throws IOException {
        PostgresWireDecoder.FrontendMessage m;
        while ((m = decoder.nextMessage()) != null) {
            if (m.type() == 'Z') {
                return;
            }
        }
    }

    private static void deleteWritten(S3Service s3, IamService iamService,
                                      CopyStatementParser.S3Unload spec, List<String> keys,
                                      RoleSession roleSession) {
        if (spec.allowOverwrite()) {
            // Under ALLOWOVERWRITE a slice key may have replaced a pre-existing object, and there is no
            // reliable way to tell an overwrite from a fresh write (the check and the write are not
            // atomic, and a concurrent UNLOAD to the same prefix could own the stored object). Rather
            // than risk deleting data this operation did not create, leave every written object in
            // place. A failed MANIFEST + ALLOWOVERWRITE UNLOAD can therefore leave valid data objects
            // without a manifest; rerunning the same statement overwrites them cleanly.
            return;
        }
        // Non-overwrite runs verified the prefix was empty before streaming, so every written key was
        // created by this operation and is safe to remove.
        for (String k : keys) {
            try {
                if (roleSession != null) {
                    authorizeRoleAction(s3, iamService, spec.iamRoleArn(), "s3:DeleteObject", objectArn(spec.iamRoleArn(), spec.bucket(), k));
                    s3.authorizeSignedDeleteObject(roleSession.accessKeyId(), roleSession.sessionToken(), spec.bucket(), k);
                } else {
                    s3.authorizeAnonymousDeleteObject(spec.bucket(), k);
                }
                s3.deleteObject(spec.bucket(), k);
            } catch (RuntimeException e) {
                LOG.debugv(e, "could not remove partial UNLOAD object s3://{0}/{1}", spec.bucket(), k);
            }
        }
    }

    private static String manifestJson(String bucket, List<String> keys, List<Integer> lengths) {
        StringBuilder json = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"url\":\"s3://").append(escapeJson(bucket)).append('/').append(escapeJson(keys.get(i)))
                    .append("\",\"meta\":{\"content_length\":").append(lengths.get(i)).append("}}");
        }
        return json.append("]}").toString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
