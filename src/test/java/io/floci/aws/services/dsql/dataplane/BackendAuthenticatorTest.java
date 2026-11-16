package io.floci.aws.services.dsql.dataplane;

import io.floci.aws.core.common.PostgresWire;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void authenticationOkBeforeTheServerSignatureIsRefused() throws Exception {
        BackendAuthenticator afterInitial = new BackendAuthenticator("user", "pencil", NONCE, "user");
        afterInitial.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        assertThrows(IOException.class, () -> afterInitial.answer(auth(0)));

        BackendAuthenticator afterContinue = new BackendAuthenticator("user", "pencil", NONCE, "user");
        afterContinue.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        afterContinue.answer(auth(11, text(SERVER_FIRST)));
        IOException refused = assertThrows(IOException.class, () -> afterContinue.answer(auth(0)));
        assertEquals("backend accepted the login before proving its SCRAM signature", refused.getMessage());
    }

    @Test
    void authenticationOkAfterAVerifiedServerSignatureIsAccepted() throws Exception {
        BackendAuthenticator authenticator = new BackendAuthenticator("user", "pencil", NONCE, "user");
        authenticator.answer(auth(10, saslMechanisms("SCRAM-SHA-256")));
        authenticator.answer(auth(11, text(SERVER_FIRST)));
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
    void unknownAuthenticationCodeFails() {
        assertThrows(IOException.class, () -> new BackendAuthenticator("u", "p").answer(auth(7)));
    }
}
