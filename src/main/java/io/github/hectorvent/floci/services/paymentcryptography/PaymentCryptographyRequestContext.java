package io.github.hectorvent.floci.services.paymentcryptography;

import jakarta.enterprise.context.RequestScoped;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What this request resolved, kept for the request.
 *
 * <p>An alias names a key, and another request may move that alias to another key while this one
 * is running. The permission check resolves the alias to the key it names, and the request must act
 * on that key: a key resolved again afterwards could be one no policy was checked against.
 */
@RequestScoped
public class PaymentCryptographyRequestContext {

    /** The key an alias named, by the alias's name and the Region it is in. */
    private final Map<String, String> aliasKeyIds = new LinkedHashMap<>();

    /**
     * The id of the key the alias named when this request resolved it, an empty one when it named
     * no key, and null when this request has not resolved the alias yet.
     */
    String aliasKeyId(String region, String aliasName) {
        return aliasKeyIds.get(key(region, aliasName));
    }

    /** Remembers the key the alias named, so that the request acts on that key and not another. */
    void pinAliasKeyId(String region, String aliasName, String keyId) {
        aliasKeyIds.put(key(region, aliasName), keyId);
    }

    private static String key(String region, String aliasName) {
        return region + SEPARATOR + aliasName;
    }

    /** The separator between the Region and the alias name, which neither of them carries. */
    private static final String SEPARATOR = "\u0000";
}
