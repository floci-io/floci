package io.floci.aws.services.rolesanywhere;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A fixed test CA and an empty CRL it signed, made once with OpenSSL (an EC P-256 root valid for a hundred years and
 * a CRL whose next update is as far away), so every test reads the same material without generating keys. The partition smoke test reads the CA too.
 */
public final class RolesAnywhereTestMaterial {

    public static final String CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIBwTCCAWegAwIBAgIUD3JHnuHRiTShjlcPjMIvt2sMyFYwCgYIKoZIzj0EAwIw
            LTEbMBkGA1UEAwwSRmxvY2kgVGVzdCBSb290IENBMQ4wDAYDVQQKDAVGbG9jaTAg
            Fw0yNjExMjcwOTI3NDJaGA8yMTI2MTEwMzA5Mjc0MlowLTEbMBkGA1UEAwwSRmxv
            Y2kgVGVzdCBSb290IENBMQ4wDAYDVQQKDAVGbG9jaTBZMBMGByqGSM49AgEGCCqG
            SM49AwEHA0IABFKXI0ucyB/8snrNgxlhJ28pjJoy9WMtntQG/v6Tmj79d04uERJs
            oftCvw+xAzvmy8/Irc3leVB3+/snv5w0SoyjYzBhMB0GA1UdDgQWBBST3W07p8PL
            o7CMtR39TfbFkiQDhzAfBgNVHSMEGDAWgBST3W07p8PLo7CMtR39TfbFkiQDhzAP
            BgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjAKBggqhkjOPQQDAgNIADBF
            AiEA+wAG4UodsKFZKzzwmNJjf7ahRQnZ1mUquqsunf5vJoACICEIbdzHzvi9HQ4x
            JU3gHpx30DS4USYVNBulmE03K+WG
            -----END CERTIFICATE-----
            """;

    public static final String CRL_PEM = """
            -----BEGIN X509 CRL-----
            MIHHMG8CAQEwCgYIKoZIzj0EAwIwLTEbMBkGA1UEAwwSRmxvY2kgVGVzdCBSb290
            IENBMQ4wDAYDVQQKDAVGbG9jaRcNMjYxMTI3MDkyNzQyWhgPMjEyNjExMDMwOTI3
            NDJaoA8wDTALBgNVHRQEBAICEAAwCgYIKoZIzj0EAwIDSAAwRQIhAM478bBuBcwa
            9dnB4STiqTp6vo43FriThj44Giv0AoTBAiA4v14JlxRGf23PKyc+is02RkkVVr3h
            6QJLJKmJtyaI7g==
            -----END X509 CRL-----
            """;

    private RolesAnywhereTestMaterial() {
    }

    /** The CRL as {@code ImportCrl} carries it: the PEM bytes, base64 encoded as the REST JSON protocol sends a blob. */
    public static String crlData() {
        return Base64.getEncoder().encodeToString(CRL_PEM.getBytes(StandardCharsets.US_ASCII));
    }
}
