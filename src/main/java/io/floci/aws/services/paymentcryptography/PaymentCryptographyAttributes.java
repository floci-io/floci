package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;

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
     * A hexadecimal member, checked to be one the cipher can read.
     *
     * <p>The model states the length of a hexadecimal member and does not always state an
     * even one, so a member of an odd length is a request the model allows and the cipher
     * cannot read. It is refused with the error the model names, rather than answered with
     * a failure the caller cannot catch.
     *
     * @param member the member's path, for the refusal
     * @param value the member's text
     * @return the member's text, unchanged
     */
    static String hex(String member, String value) {
        if (value.length() % 2 != 0) {
            throw new AwsException("ValidationException",
                    member + " must be an even number of hexadecimal characters", 400);
        }
        return value;
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

}
