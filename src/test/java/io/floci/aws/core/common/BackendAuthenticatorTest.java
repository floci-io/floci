package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendAuthenticatorTest {

    private static final String NONCE = "rOprNGfwEbeRWgbNEkqO";
    private static final String SERVER_FIRST =
            "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096";
    private static final String CLIENT_FINAL =
            "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,"
                    + "p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=";
    private static final String SERVER_FINAL = "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=";

    private static PostgresWire.Message auth(int code, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(PostgresWire.int32(code));
        for (byte[] part : parts) {
            body.writeBytes(part);
        }
        return new PostgresWire.Message((byte) 'R', body.toByteArray());
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] saslMechanisms(String... mechanisms) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String mechanism : mechanisms) {
            out.writeBytes(PostgresWire.cStringBytes(mechanism));
        }
        out.write(0);
        return out.toByteArray();
    }

    @Test
    void scramMatchesRfc7677Vector() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");

        PostgresWire.Message initial = authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256"))).orElseThrow();
        assertEquals('p', initial.type());
        PostgresWire.CString mechanism = PostgresWire.cString(initial.body(), 0);
        assertEquals("SCRAM-SHA-256", mechanism.value());
        int length = PostgresWire.int32(initial.body(), mechanism.next());
        String clientFirst = new String(initial.body(), mechanism.next() + 4, length, StandardCharsets.UTF_8);
        assertEquals("n,,n=user,r=" + NONCE, clientFirst);
        assertEquals(mechanism.next() + 4 + length, initial.body().length);

        PostgresWire.Message response = authenticator.answer(auth(11, text(SERVER_FIRST))).orElseThrow();
        assertEquals('p', response.type());
        assertEquals(CLIENT_FINAL, new String(response.body(), StandardCharsets.UTF_8));

        assertTrue(authenticator.answer(auth(12, text(SERVER_FINAL))).isEmpty());
    }

    @Test
    void wrongServerSignatureFails() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        authenticator.answer(auth(11, text(SERVER_FIRST)));

        IOException failure = assertThrows(IOException.class,
                () -> authenticator.answer(auth(12, text("v=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))));
        assertEquals("backend server signature mismatch", failure.getMessage());
    }

    @Test
    void foreignServerNonceFails() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));

        assertThrows(IOException.class,
                () -> authenticator.answer(auth(11, text("r=other,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"))));
    }

    @Test
    void aServerNonceEqualToTheClientNonceFails() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));

        IOException refused = assertThrows(IOException.class,
                () -> authenticator.answer(auth(11, text("r=" + NONCE + ",s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"))));
        assertEquals("backend SCRAM nonce does not extend the client nonce", refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authenticationOkBeforeTheServerSignatureIsRefused(boolean scramOnly) throws Exception {
        BackendAuthenticator afterInitial = new BackendAuthenticator("user", "pencil", NONCE, "user", scramOnly);
        afterInitial.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        assertThrows(IOException.class, () -> afterInitial.answer(auth(0)));

        BackendAuthenticator afterContinue = new BackendAuthenticator("user", "pencil", NONCE, "user", scramOnly);
        afterContinue.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        afterContinue.answer(auth(11, text(SERVER_FIRST)));
        IOException refused = assertThrows(IOException.class, () -> afterContinue.answer(auth(0)));
        assertEquals("backend accepted the login before proving its SCRAM signature", refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void authenticationOkAfterAVerifiedServerSignatureIsAccepted(boolean scramOnly) throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user", scramOnly);
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        PostgresWire.Message response = authenticator.answer(auth(11, text(SERVER_FIRST))).orElseThrow();
        assertEquals(CLIENT_FINAL, new String(response.body(), StandardCharsets.UTF_8));
        authenticator.answer(auth(12, text(SERVER_FINAL)));

        assertTrue(authenticator.answer(auth(0)).isEmpty());
    }

    @Test
    void tooManyServerIterationsAreRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));

        IOException refused = assertThrows(IOException.class, () -> authenticator.answer(auth(11,
                text("r=" + NONCE + "x,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=" + (BackendAuthenticator.MAX_ITERATIONS + 1)))));
        assertEquals("backend asks for 1000001 SCRAM iterations, more than 1000000", refused.getMessage());
    }

    @Test
    void anEmptySaltIsRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));

        IOException refused = assertThrows(IOException.class,
                () -> authenticator.answer(auth(11, text("r=" + NONCE + "x,s=,i=4096"))));
        assertEquals("backend SCRAM salt is empty", refused.getMessage());
    }

    @Test
    void aMandatoryExtensionIsRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));

        IOException refused = assertThrows(IOException.class,
                () -> authenticator.answer(auth(11, text("m=ext," + SERVER_FIRST))));
        assertEquals("backend SCRAM message asks for a mandatory extension", refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {",r=" + NONCE + "y", ",s=QUJD", ",i=1"})
    void aRepeatedServerFirstAttributeIsRefused(String repeated) throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));

        IOException refused = assertThrows(IOException.class,
                () -> authenticator.answer(auth(11, text(SERVER_FIRST + repeated))));
        assertEquals("malformed SCRAM message from the backend", refused.getMessage());
    }

    @Test
    void anOptionalServerFirstExtensionIsIgnored() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));

        assertTrue(authenticator.answer(auth(11, text(SERVER_FIRST + ",x=future"))).isPresent());
    }

    @Test
    void anOversizedServerFirstMessageIsRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        String padded = SERVER_FIRST + ",x=" + "a".repeat(BackendAuthenticator.MAX_SCRAM_MESSAGE);

        IOException refused = assertThrows(IOException.class, () -> authenticator.answer(auth(11, text(padded))));
        assertEquals("backend SCRAM message is " + padded.length() + " bytes, more than 4096", refused.getMessage());
    }

    @Test
    void aServerFirstMessageAtTheLimitIsAnswered() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        String prefix = SERVER_FIRST + ",x=";
        String padded = prefix + "a".repeat(BackendAuthenticator.MAX_SCRAM_MESSAGE - prefix.length());

        assertTrue(authenticator.answer(auth(11, text(padded))).isPresent());
    }

    @ParameterizedTest
    @ValueSource(ints = {BackendAuthenticator.MAX_SALT_BYTES + 1, BackendAuthenticator.MAX_SALT_BYTES * 2})
    void anOversizedSaltIsRefused(int bytes) throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        String salt = Base64.getEncoder().encodeToString(new byte[bytes]);

        IOException refused = assertThrows(IOException.class,
                () -> authenticator.answer(auth(11, text("r=" + NONCE + "x,s=" + salt + ",i=4096"))));
        assertEquals("backend SCRAM salt is longer than 1024 bytes", refused.getMessage());
    }

    @Test
    void aSaltAtTheLimitIsTaken() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        String salt = Base64.getEncoder().encodeToString(new byte[BackendAuthenticator.MAX_SALT_BYTES]);

        assertTrue(authenticator.answer(auth(11, text("r=" + NONCE + "x,s=" + salt + ",i=1"))).isPresent());
    }

    @Test
    void anOversizedServerFinalMessageIsRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        authenticator.answer(auth(11, text(SERVER_FIRST)));

        assertThrows(IOException.class, () -> authenticator.answer(auth(12,
                text(SERVER_FINAL + "a".repeat(BackendAuthenticator.MAX_SCRAM_MESSAGE)))));
    }

    @Test
    void unsupportedMechanismFails() {
        BackendAuthenticator authenticator = new BackendAuthenticator("u", "p");

        assertThrows(IOException.class,
                () -> authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256-PLUS"))));
    }

    @Test
    void productionClientFirstUsesAnEmptyUserNameAndAFreshNonce() throws Exception {
        PostgresWire.Message first = new BackendAuthenticator("u", "p")
                .answer(auth(10, saslMechanisms("SCRAM-SHA-256"))).orElseThrow();
        PostgresWire.CString mechanism = PostgresWire.cString(first.body(), 0);
        String clientFirst = new String(first.body(), mechanism.next() + 4,
                first.body().length - mechanism.next() - 4, StandardCharsets.UTF_8);

        assertTrue(clientFirst.startsWith("n,,n=,r="));
        assertTrue(clientFirst.length() > "n,,n=,r=".length() + 20);
    }

    @Test
    void md5AnswerMatchesPostgresFormula() throws Exception {
        byte[] salt = {1, 2, 3, 4};
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        String inner = HexFormat.of().formatHex(md5.digest(text("pu")));
        byte[] outerInput = new byte[inner.length() + 4];
        System.arraycopy(text(inner), 0, outerInput, 0, inner.length());
        System.arraycopy(salt, 0, outerInput, inner.length(), 4);
        String expected = "md5" + HexFormat.of().formatHex(md5.digest(outerInput));

        PostgresWire.Message answer = new BackendAuthenticator("u", "p").answer(auth(5, salt)).orElseThrow();

        assertEquals('p', answer.type());
        assertArrayEquals(PostgresWire.cStringBytes(expected), answer.body());
    }

    @Test
    void cleartextAnswerSendsThePassword() throws Exception {
        PostgresWire.Message answer = new BackendAuthenticator("u", "secret").answer(auth(3)).orElseThrow();

        assertEquals('p', answer.type());
        assertArrayEquals(PostgresWire.cStringBytes("secret"), answer.body());
    }

    @Test
    void authenticationOkNeedsNoAnswer() throws Exception {
        assertTrue(new BackendAuthenticator("u", "p").answer(auth(0)).isEmpty());
    }

    @Test
    void scramOnlyRefusesACleartextRequestWithoutSendingThePassword() {
        BackendAuthenticator authenticator = new BackendAuthenticator("u", "secret", true);

        IOException refused = assertThrows(IOException.class, () -> authenticator.answer(auth(3)));

        assertEquals("backend asks for a cleartext password where only SCRAM-SHA-256 is accepted",
                refused.getMessage());
        assertFalse(refused.getMessage().contains("secret"));
    }

    @Test
    void scramOnlyRefusesAnMd5Request() {
        BackendAuthenticator authenticator = new BackendAuthenticator("u", "p", true);

        IOException refused = assertThrows(IOException.class, () -> authenticator.answer(auth(5, new byte[] {1, 2, 3, 4})));

        assertEquals("backend asks for an MD5 password where only SCRAM-SHA-256 is accepted", refused.getMessage());
    }

    @Test
    void scramOnlyAcceptsATrustingServerThatAsksForNothing() throws Exception {
        assertTrue(new BackendAuthenticator("u", "p", true).answer(auth(0)).isEmpty());
    }

    @Test
    void theGeneralModeStillAnswersEveryMethod() throws Exception {
        assertTrue(new BackendAuthenticator("u", "p", false).answer(auth(3)).isPresent());
        assertTrue(new BackendAuthenticator("u", "p", false).answer(auth(5, new byte[] {1, 2, 3, 4})).isPresent());
        assertTrue(new BackendAuthenticator("u", "p", false).answer(auth(0)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5})
    void aPasswordRequestMidScramIsRefusedWithoutSendingThePassword(int code) throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user", false);
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));

        IOException afterInitial = assertThrows(IOException.class,
                () -> authenticator.answer(auth(code, new byte[] {1, 2, 3, 4})));
        assertTrue(afterInitial.getMessage().endsWith("after the authentication started"));
        assertFalse(afterInitial.getMessage().contains("pencil"));

        authenticator.answer(auth(11, text(SERVER_FIRST)));
        assertThrows(IOException.class, () -> authenticator.answer(auth(code, new byte[] {1, 2, 3, 4})));
    }

    @Test
    void aSecondPasswordRequestIsRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("u", "secret");
        authenticator.answer(auth(5, new byte[] {1, 2, 3, 4}));

        IOException refused = assertThrows(IOException.class, () -> authenticator.answer(auth(3)));
        assertEquals("backend asks for a cleartext password after the authentication started", refused.getMessage());
    }

    @Test
    void aSecondSaslExchangeIsRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        authenticator.answer(auth(11, text(SERVER_FIRST)));
        authenticator.answer(auth(12, text(SERVER_FINAL)));

        IOException refused = assertThrows(IOException.class,
                () -> authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256"))));
        assertEquals("backend starts a second authentication exchange", refused.getMessage());
    }

    @Test
    void aSaslExchangeAfterAPasswordIsRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("u", "secret");
        authenticator.answer(auth(3));

        assertThrows(IOException.class, () -> authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256"))));
    }

    @Test
    void aRepeatedSaslContinueOrFinalIsRefused() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        authenticator.answer(auth(11, text(SERVER_FIRST)));
        assertThrows(IOException.class, () -> authenticator.answer(auth(11, text(SERVER_FIRST))));

        authenticator.answer(auth(12, text(SERVER_FINAL)));
        IOException refused = assertThrows(IOException.class, () -> authenticator.answer(auth(12, text(SERVER_FINAL))));
        assertEquals("unexpected SASL final from the backend", refused.getMessage());
    }

    @Test
    void unknownAuthenticationCodeFails() {
        assertThrows(IOException.class, () -> new BackendAuthenticator("u", "p").answer(auth(7)));
    }
}
