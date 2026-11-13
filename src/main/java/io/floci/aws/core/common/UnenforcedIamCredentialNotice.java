package io.floci.aws.core.common;

import io.floci.aws.services.iam.IamService;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logs one warning when a request is signed with an IAM identity's credentials while policy
 * enforcement is off. Floci then allows the request without evaluating the identity's policies,
 * which nothing else tells the caller: the startup banner says enforcement is disabled, but it is
 * written before any identity exists and before the first request.
 *
 * <p>An IAM identity's credential is an IAM user's access key or a session Floci issued, such as
 * an assumed role or a Lambda function's execution role. The {@code test} key and a bare
 * account-id key are the account root's on purpose and never warn.
 *
 * <p>Once the warning is logged this costs one read per request. Before that, each other access
 * key id is looked up once: a key Floci did not issue never becomes one it issued, so it is
 * remembered as checked, up to {@link #MAX_CHECKED_KEYS} of them.
 */
final class UnenforcedIamCredentialNotice {

    private static final Logger LOG = Logger.getLogger(UnenforcedIamCredentialNotice.class);
    static final int MAX_CHECKED_KEYS = 1024;

    private final IamService iamService;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final Set<String> checkedKeys = ConcurrentHashMap.newKeySet();

    UnenforcedIamCredentialNotice(IamService iamService) {
        this.iamService = iamService;
    }

    /**
     * Looks at the credential a request was signed with and logs the warning the first time it is
     * an IAM identity's. Returns whether this call logged it.
     */
    boolean observe(String accessKeyId, String accountId) {
        if (warned.get() || accessKeyId == null || accessKeyId.isBlank()
                || "test".equals(accessKeyId) || accessKeyId.equals(accountId)
                || checkedKeys.contains(accessKeyId)) {
            return false;
        }
        if (!iamService.isKnownAccessKey(accessKeyId)) {
            if (checkedKeys.size() < MAX_CHECKED_KEYS) {
                checkedKeys.add(accessKeyId);
            }
            return false;
        }
        if (!warned.compareAndSet(false, true)) {
            return false;
        }
        checkedKeys.clear();
        String credential = IamService.isTemporaryAccessKey(accessKeyId)
                ? "a session Floci issued" : "an IAM user's access key";
        // No apostrophes in the pattern: warnv formats it with MessageFormat, which reads one as a quote.
        LOG.warnv("A request in account {0} was signed with {1} ({2}), but IAM policy enforcement is off, "
                        + "so Floci allowed it without evaluating the policies that apply to it. Set "
                        + "FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true to evaluate them as AWS does. "
                        + "This is logged once.",
                accountId, credential, accessKeyId);
        return true;
    }
}
