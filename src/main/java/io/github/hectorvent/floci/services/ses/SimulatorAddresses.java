package io.github.hectorvent.floci.services.ses;

/**
 * Recognises the AWS Amazon SES mailbox simulator addresses
 * (see https://docs.aws.amazon.com/ses/latest/dg/send-an-email-from-console.html#send-email-simulator)
 * so Floci can deterministically emit the matching event types.
 */
final class SimulatorAddresses {

    static final String SUCCESS = "success@simulator.amazonses.com";
    static final String BOUNCE = "bounce@simulator.amazonses.com";
    static final String COMPLAINT = "complaint@simulator.amazonses.com";
    static final String SUPPRESSION_LIST = "suppressionlist@simulator.amazonses.com";

    private SimulatorAddresses() {}

    static boolean isSuccess(String address) {
        return SUCCESS.equalsIgnoreCase(strip(address));
    }

    static boolean isBounce(String address) {
        return BOUNCE.equalsIgnoreCase(strip(address));
    }

    static boolean isComplaint(String address) {
        return COMPLAINT.equalsIgnoreCase(strip(address));
    }

    static boolean isSuppressionList(String address) {
        return SUPPRESSION_LIST.equalsIgnoreCase(strip(address));
    }

    private static String strip(String address) {
        return address == null ? null : address.trim();
    }
}
