package io.github.hectorvent.floci.services.ses;

/**
 * Recognises the AWS Amazon SES mailbox simulator addresses
 * (see https://docs.aws.amazon.com/ses/latest/dg/send-an-email-from-console.html#send-email-simulator)
 * so Floci can deterministically emit the matching event types.
 *
 * <p>The simulator supports a label (subaddressing) on the local part so senders can distinguish test
 * messages, e.g. {@code bounce+order-123@simulator.amazonses.com} triggers a bounce just like the bare
 * {@code bounce@simulator.amazonses.com}. Matching strips a {@code +label} suffix from the local part
 * (only {@code +} is a separator — {@code bounce-label@...} is not a bounce) before comparing the type
 * case-insensitively.
 */
final class SimulatorAddresses {

    static final String SUCCESS = "success@simulator.amazonses.com";
    static final String BOUNCE = "bounce@simulator.amazonses.com";
    static final String COMPLAINT = "complaint@simulator.amazonses.com";
    static final String SUPPRESSION_LIST = "suppressionlist@simulator.amazonses.com";

    private SimulatorAddresses() {}

    static boolean isSuccess(String address) {
        return SUCCESS.equalsIgnoreCase(canonicalize(address));
    }

    static boolean isBounce(String address) {
        return BOUNCE.equalsIgnoreCase(canonicalize(address));
    }

    static boolean isComplaint(String address) {
        return COMPLAINT.equalsIgnoreCase(canonicalize(address));
    }

    static boolean isSuppressionList(String address) {
        return SUPPRESSION_LIST.equalsIgnoreCase(canonicalize(address));
    }

    // Drop a +label subaddress from the local part so labelled simulator addresses match their type,
    // leaving the domain untouched. A non-simulator or malformed address is returned trimmed as-is.
    private static String canonicalize(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        int at = trimmed.indexOf('@');
        if (at < 0) {
            return trimmed;
        }
        String localPart = trimmed.substring(0, at);
        int plus = localPart.indexOf('+');
        if (plus < 0) {
            return trimmed;
        }
        return localPart.substring(0, plus) + trimmed.substring(at);
    }
}
