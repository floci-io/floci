package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

/**
 * How the attributes of a data plane request are read.
 *
 * <p>The data plane's requests name what they want in an attributes member, and the
 * attributes member holds one variant: the algorithms a MAC may use, the formats a PIN
 * block may be in, and the key a DUKPT derivation is made from. Reading them is the same
 * whatever the operation is, so it is read in one place.
 */
final class PaymentCryptographyAttributes {

    private PaymentCryptographyAttributes() {
    }

    /**
     * The text of a member, or the empty string when the member is not there.
     *
     * @param request the request or the attributes to read
     * @param member the member to read
     * @return the member's text
     */
    static String text(JsonNode request, String member) {
        if (request == null) {
            return "";
        }
        JsonNode declared = request.get(member);
        return declared == null || declared.isNull() ? "" : declared.asText("");
    }

    /**
     * The one variant an attributes member holds.
     *
     * <p>An attributes member holds exactly one variant, so a member with none or with two
     * is a request that cannot be read.
     *
     * @param attributes the attributes to read
     * @return the variant's name, or null when there is not exactly one
     */
    static String soleVariant(JsonNode attributes) {
        if (attributes == null || !attributes.isObject()) {
            return null;
        }
        String found = null;
        Iterator<Map.Entry<String, JsonNode>> fields = attributes.fields();
        while (fields.hasNext()) {
            if (found != null) {
                return null;
            }
            found = fields.next().getKey();
        }
        return found;
    }

    /**
     * Whether a value is hexadecimal.
     *
     * <p>The data plane carries its blobs as hexadecimal, so a value that is not hexadecimal
     * is not one of them.
     *
     * @param value the value to read
     * @return whether the value is an even number of hexadecimal digits
     */
    static boolean isHex(String value) {
        if (value == null || value.isEmpty() || value.length() % 2 != 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
