package io.floci.aws.core.common;

import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catches a regional ARN minted with a blank region. The partition is derived from the region, so
 * a blank one silently gives the commercial partition, which is wrong in every other partition.
 * {@code make partition-check} only sees a literal blank argument; this sees one that arrives
 * through a variable.
 *
 * <p>The test build sets {@value #FAIL_PROPERTY}, so such a mint throws and the test that reached
 * it fails. Anywhere else the ARN is still minted as before and the call site is logged once, so
 * an untested path keeps its response instead of turning into a 500.
 */
final class BlankRegionGuard {

    /**
     * System property that turns a blank-region mint into an {@link IllegalStateException}. It sits
     * outside {@code floci.*} on purpose: it is not emulator configuration, and SmallRye rejects an
     * unknown key under a mapped root.
     */
    static final String FAIL_PROPERTY = "io.floci.aws.arn.fail-on-blank-region";

    private static final Logger LOG = Logger.getLogger(BlankRegionGuard.class);
    private static final Set<String> WARNED_SITES = ConcurrentHashMap.newKeySet();

    private BlankRegionGuard() {
    }

    static void check(String region, String service, String resource) {
        if (region != null && !region.isBlank()) {
            return;
        }
        String site = callSite();
        if (Boolean.getBoolean(FAIL_PROPERTY)) {
            throw new IllegalStateException("ARN for " + service + " resource '" + resource
                    + "' minted with a blank region at " + site
                    + "; mint a regionless ARN through Arn.global or RegionResolver.buildGlobalArn");
        }
        if (WARNED_SITES.add(site)) {
            LOG.warnv("ARN for {0} minted with a blank region at {1}; it falls back to the commercial partition",
                    service, site);
        }
    }

    /** The first frame outside the ARN helpers: the code that asked for the ARN. */
    private static String callSite() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().equals(BlankRegionGuard.class.getName())
                        && !frame.getClassName().equals(AwsArnUtils.Arn.class.getName()))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("unknown"));
    }
}
