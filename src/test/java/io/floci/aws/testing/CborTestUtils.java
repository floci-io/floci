package io.floci.aws.testing;

import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;

import java.io.IOException;

/** Reads wire-level details that Jackson's JSON tree does not retain. */
public final class CborTestUtils {

    private static final CBORFactory FACTORY = new CBORFactory();

    private CborTestUtils() {
    }

    public static int tagOf(byte[] bytes, String member) throws IOException {
        try (CBORParser parser = FACTORY.createParser(bytes)) {
            while (parser.nextToken() != null) {
                if (member.equals(parser.currentName()) && parser.currentToken().isScalarValue()) {
                    return parser.getCurrentTag();
                }
            }
        }
        throw new AssertionError("Missing CBOR member: " + member);
    }
}
