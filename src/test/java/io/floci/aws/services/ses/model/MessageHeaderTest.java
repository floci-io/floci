package io.floci.aws.services.ses.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageHeaderTest {

    @ParameterizedTest
    @ValueSource(strings = {"From", "To", "Cc", "Bcc", "Subject", "Date", "Message-ID", "Reply-To",
            "Return-Path", "MIME-Version", "Content-Type"})
    void isSupportedName_refusesTheHeadersSesSetsItself(String name) {
        assertFalse(MessageHeader.isSupportedName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "FROM", "message-id", "Mime-Version", "content-TYPE"})
    void isSupportedName_ignoresTheCaseOfTheName(String name) {
        assertFalse(MessageHeader.isSupportedName(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sender", "DKIM-Signature", "Received", "List-Unsubscribe", "List-Unsubscribe-Post",
            "X-Custom", "X-From", "From-Address"})
    void isSupportedName_acceptsEveryOtherName(String name) {
        assertTrue(MessageHeader.isSupportedName(name));
    }

    @Test
    void isSupportedName_leavesAMissingNameToTheOtherChecks() {
        assertTrue(MessageHeader.isSupportedName(null));
    }
}
