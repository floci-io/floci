package io.floci.aws.services.elasticache.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCommandRelayTest {

    @Test
    void ordinaryCommandsPassThroughByteForByte() throws Exception {
        byte[] value = new byte[20_000];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) index;
        }
        byte[] input = concat(array("SET".getBytes(StandardCharsets.US_ASCII),
                        "binary".getBytes(StandardCharsets.US_ASCII), value),
                bytes("PING\r\n"), bytes("*0\r\n"), command("GET", "binary"));
        Recording commands = new Recording(command -> command);

        assertArrayEquals(input, relay(input, commands));
        assertEquals(List.of("SET", "PING", "GET"), commands.asked);
        assertEquals(List.of(), commands.rewritten);
    }

    @Test
    void aCommandTheProxyAsksForIsHandedOverAndRelayedAsRewritten() throws Exception {
        Recording commands = new Recording(command -> List.of("AUTH", "backend-user", "backend-password"));

        byte[] relayed = relay(concat(command("AUTH", "app", "client-secret"), command("PING")), commands);

        assertArrayEquals(concat(command("AUTH", "backend-user", "backend-password"), command("PING")), relayed);
        assertEquals(List.of(List.of("AUTH", "app", "client-secret")), commands.rewritten);
    }

    @Test
    void aCommandHandedBackUnchangedKeepsTheBytesTheClientSent() throws Exception {
        byte[] input = array(bytes("HELLO"), bytes("3"), bytes("SETNAME"), new byte[]{(byte) 0xff, 'x'});
        Recording commands = new Recording(command -> command);

        assertArrayEquals(input, relay(input, commands));
        assertEquals(1, commands.rewritten.size());
    }

    @Test
    void aWantedCommandTooLargeToHoldIsDroppedForTheProxysReplacement() throws Exception {
        byte[] input = concat(command("AUTH", "app", "x".repeat(70_000), "trailing"), command("PING"));
        Recording commands = new Recording(command -> List.of("AUTH", "rewritten", "rewritten"));

        byte[] relayed = relay(input, commands);

        assertArrayEquals(concat(command("AUTH", "in-place-of", "AUTH"), command("PING")), relayed);
        assertEquals(List.of(), commands.rewritten);
    }

    @Test
    void anInlineCommandTheProxyWantsIsHandedOverAsValkeySplitsIt() throws Exception {
        byte[] input = concat(bytes("auth app \"client \\\"secret\\x21\" 'it\\'s'\r\n"), command("PING"));
        Recording commands = new Recording(command -> List.of("AUTH", "backend-user", "backend-password"));

        byte[] relayed = relay(input, commands);

        assertEquals(List.of(List.of("auth", "app", "client \"secret!", "it's")), commands.rewritten);
        assertArrayEquals(concat(command("AUTH", "backend-user", "backend-password"), command("PING")), relayed);
    }

    @Test
    void anInlineCommandHandedBackUnchangedKeepsItsLine() throws Exception {
        byte[] input = concat(bytes("HELLO 3\n"), bytes("MULTI\r\n"), bytes("\r\n"));
        Recording commands = new Recording(command -> command);

        assertArrayEquals(input, relay(input, commands));
        assertEquals(List.of(List.of("HELLO", "3"), List.of("MULTI")), commands.rewritten);
    }

    @Test
    void anInlineLineTheBackendCannotSplitIsPassedOnAsItCame() throws Exception {
        byte[] input = concat(bytes("AUTH app \"unbalanced\r\n"), bytes("AUTH app \"closed\"early\r\n"));
        Recording commands = new Recording(command -> List.of("AUTH", "rewritten", "rewritten"));

        assertArrayEquals(input, relay(input, commands));
        assertEquals(List.of(), commands.rewritten);
    }

    @Test
    void anInlineLineTooLongToHoldIsDroppedWhenWantedAndPassedOnOtherwise() throws Exception {
        byte[] longSet = bytes("SET key " + "x".repeat(70_000) + "\r\n");
        byte[] input = concat(bytes("AUTH app " + "x".repeat(70_000) + "\r\n"), longSet, command("PING"));
        Recording commands = new Recording(command -> List.of("AUTH", "rewritten", "rewritten"));

        byte[] relayed = relay(input, commands);

        assertArrayEquals(concat(command("AUTH", "in-place-of", "AUTH"), longSet, command("PING")), relayed);
        assertEquals(List.of(), commands.rewritten);
    }

    @Test
    void aMalformedArrayLeavesTheRestOfTheConnectionToAPlainRelay() throws Exception {
        byte[] input = concat(bytes("*1\r\n:5\r\n"), command("AUTH", "app", "client-secret"));
        Recording commands = new Recording(command -> List.of("AUTH", "rewritten", "rewritten"));

        assertArrayEquals(input, relay(input, commands));
        assertEquals(List.of(), commands.asked);
    }

    @Test
    void anArgumentWithoutItsLineEndIsPassedOnAsItCame() throws Exception {
        byte[] input = concat(bytes("*2\r\n$4\r\nAUTH\r\n$2\r\npwXX"), command("AUTH", "app", "client-secret"));
        Recording commands = new Recording(command -> List.of("AUTH", "rewritten", "rewritten"));

        assertArrayEquals(input, relay(input, commands));
        assertEquals(List.of("AUTH"), commands.asked);
        assertEquals(List.of(), commands.rewritten);
    }

    @Test
    void aClientThatStopsMidCommandHasWhatItSentPassedOn() throws Exception {
        byte[] input = bytes("*2\r\n$3\r\nGET\r\n$10\r\nabc");

        assertArrayEquals(input, relay(input, new Recording(command -> command)));
    }

    @Test
    void everythingReadIsPassedOnBeforeWaitingForMore() throws Exception {
        byte[] first = concat(command("GET", "a"), command("AUTH", "app", "client-secret"));
        Recording commands = new Recording(command -> List.of("AUTH", "backend-user", "backend-password"));
        RecordingOutput output = new RecordingOutput();
        List<String> flushedBeforeEachRead = new ArrayList<>();
        InputStream chunks = new InputStream() {
            private final List<byte[]> remaining = new ArrayList<>(List.of(first, command("PING")));

            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] buffer) {
                flushedBeforeEachRead.add(output.flushed());
                if (remaining.isEmpty()) {
                    return -1;
                }
                byte[] chunk = remaining.removeFirst();
                System.arraycopy(chunk, 0, buffer, 0, chunk.length);
                return chunk.length;
            }
        };

        new ClientCommandRelay(chunks, output, commands).run();

        assertEquals("", flushedBeforeEachRead.get(0));
        assertEquals(text(concat(command("GET", "a"), command("AUTH", "backend-user", "backend-password"))),
                flushedBeforeEachRead.get(1), "a backend must not wait for commands the proxy already holds");
        assertTrue(flushedBeforeEachRead.get(2).endsWith(text(command("PING"))));
    }

    private static byte[] relay(byte[] input, ClientCommandRelay.Commands commands) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new ClientCommandRelay(new ByteArrayInputStream(input), output, commands).run();
        return output.toByteArray();
    }

    private static byte[] command(String... arguments) {
        return array(Arrays.stream(arguments).map(ClientCommandRelayTest::bytes).toArray(byte[][]::new));
    }

    private static byte[] array(byte[]... arguments) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.writeBytes(bytes("*" + arguments.length + "\r\n"));
        for (byte[] argument : arguments) {
            encoded.writeBytes(bytes("$" + argument.length + "\r\n"));
            encoded.writeBytes(argument);
            encoded.writeBytes(bytes("\r\n"));
        }
        return encoded.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            joined.writeBytes(part);
        }
        return joined.toByteArray();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Wants AUTH, HELLO and MULTI, whatever their case, and records what it was asked. */
    private static final class Recording implements ClientCommandRelay.Commands {
        private final UnaryOperator<List<String>> rewrite;
        private final List<String> asked = new ArrayList<>();
        private final List<List<String>> rewritten = new ArrayList<>();

        private Recording(UnaryOperator<List<String>> rewrite) {
            this.rewrite = rewrite;
        }

        @Override
        public boolean wants(String name) {
            asked.add(name);
            return List.of("AUTH", "HELLO", "MULTI").contains(name.toUpperCase(Locale.ROOT));
        }

        @Override
        public List<String> rewrite(List<String> command) {
            rewritten.add(List.copyOf(command));
            return rewrite.apply(command);
        }

        @Override
        public List<String> inPlaceOfTooLarge(String name) {
            return List.of("AUTH", "in-place-of", name);
        }
    }

    /** Keeps apart what has been flushed from what is only written. */
    private static final class RecordingOutput extends ByteArrayOutputStream {
        private int flushedLength;

        @Override
        public void flush() {
            flushedLength = size();
        }

        String flushed() {
            return new String(toByteArray(), 0, flushedLength, StandardCharsets.UTF_8);
        }
    }
}
