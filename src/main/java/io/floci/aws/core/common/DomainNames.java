package io.floci.aws.core.common;

/** Relations between domain names. */
public final class DomainNames {

    private DomainNames() {
    }

    /**
     * Whether {@code name} is {@code domain} itself or one of its subdomains. The names are compared
     * as given, so a caller that wants a case-insensitive match passes both in one case.
     */
    public static boolean isWithin(String name, String domain) {
        return name.equals(domain) || name.endsWith("." + domain);
    }
}
