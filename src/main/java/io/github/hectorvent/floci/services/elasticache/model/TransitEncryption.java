package io.github.hectorvent.floci.services.elasticache.model;

/**
 * Which connections a cache endpoint takes. {@code TransitEncryptionMode} preferred takes
 * encrypted and unencrypted ones, so clients can move to TLS; required takes only TLS.
 */
public enum TransitEncryption {
    NONE, PREFERRED, REQUIRED
}
