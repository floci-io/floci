package io.floci.aws.core.common;

import io.floci.aws.services.iam.IamService;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logs one warning when a request is signed with an IAM identity's credentials while policy
 * enforcement is off. Floci then allows the request without evaluating the identity's policies,
 * which nothing else tells the caller: the startup banner says enforcement is disabled, but it is
 * written before any identity exists and before the first request.
 *
 * <p>An IAM identity's credential is an IAM user's access key or a session Floci issued, such as
 * an assumed role, a Lambda function's execution role or a role Floci signs its own calls with.
 * These never warn: the account root's credentials (the {@code test} key, a bare account-id key and
 * a GetSessionToken session minted with a key IAM does not hold, which acts as the root), the
 * credentials of a URL Floci presigned for its own use, scoped to that URL rather than an
 * identity's, and the seeded {@code floci} deployer key, which holds AdministratorAccess, so its
 * policies would allow the request anyway.
 *
 * <p>Once the warning is logged this costs one read per request. Before that, each other access
 * key id is looked up once: a key Floci did not issue never becomes one it issued, and a session
 * the root minted stays the root's, so it is remembered as checked. The {@link #MAX_CHECKED_KEYS}
 * keys seen most recently are remembered, each added in one step that drops the one seen longest
 * ago, so concurrent requests cannot take it past that.
 */
final class UnenforcedIamCredentialNotice {

    private static final Logger LOG = Logger.getLogger(UnenforcedIamCredentialNotice.class);
    static final int MAX_CHECKED_KEYS = 1024;

    private final IamService iamService;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final Map<String, Boolean> checkedKeys = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_CHECKED_KEYS;
                }
            });

    UnenforcedIamCredentialNotice(IamService iamService) {
        this.iamService = iamService;
    }

    /**
     * Looks at the credential a request was signed with and logs the warning the first time it is
     * an IAM identity's. Returns whether this call logged it.
     */
    boolean observe(String accessKeyId, String accountId) {
        if (warned.get() || accessKeyId == null || accessKeyId.isBlank()
                || "test".equals(accessKeyId) || AccountResolver.isAccountId(accessKeyId)
                || checkedKeys.get(accessKeyId) != null) {
            return false;
        }
        if (!iamService.isKnownAccessKey(accessKeyId) || iamService.isRootIssuedSessionToken(accessKeyId)
                || iamService.presignedScope(accessKeyId).isPresent()
                || iamService.isSeededDeployerAccessKey(accessKeyId)) {
            checkedKeys.put(accessKeyId, Boolean.TRUE);
            return false;
        }
        if (!warned.compareAndSet(false, true)) {
            return false;
        }
        checkedKeys.clear();
        String credential = IamService.isTemporaryAccessKey(accessKeyId)
                ? "a session Floci issued" : "an IAM user's access key";
        LOG.warnv("A request in account {0} was signed with {1} ({2}), but IAM policy enforcement is off, "
                        + "so Floci allowed it without evaluating the policies that apply to it. Set "
                        + "FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true to evaluate them as AWS does. "
                        + "This is logged once.",
                accountId, credential, accessKeyId);
        return true;
    }

    /** How many access key ids it remembers as checked. */
    int rememberedKeys() {
        return checkedKeys.size();
    }
}
