package io.floci.aws.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.ED25519_KEY;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.createdKeyPairId;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.deleteKeyPair;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.ec2;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.importedKeyPairId;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * DescribeKeyPairs reports each key pair's keyType and createTime, and Filter.N narrows the result
 * by the filters AWS documents and by key-type.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeKeyPairs.html">DescribeKeyPairs</a>
 */
@QuarkusTest
class Ec2KeyPairDescribeIntegrationTest {

    @Test
    void createdAndImportedKeyPairsAreDescribedWithTheirTypeAndCreateTime() {
        String prefix = "described-" + UUID.randomUUID();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String created = createdKeyPairId(prefix + "-created");
        String importedRsa = importedKeyPairId(prefix + "-rsa", Ec2KeyMaterial.generateRsa().openSshPublicKey());
        String importedEd25519 = importedKeyPairId(prefix + "-ed25519", ED25519_KEY + " someone@example.com");
        Instant after = Instant.now();
        try {
            for (String[] expected : new String[][] {{created, "rsa"}, {importedRsa, "rsa"}, {importedEd25519, "ed25519"}}) {
                XmlPath item = ec2("DescribeKeyPairs").formParam("KeyPairId.1", expected[0])
                        .post("/").then().statusCode(200)
                        .body("DescribeKeyPairsResponse.keySet.item.keyType", equalTo(expected[1]))
                        .extract().xmlPath();
                Instant createTime = Instant.parse(item.getString("DescribeKeyPairsResponse.keySet.item.createTime"));
                assertFalse(createTime.isBefore(before), createTime + " is before " + before);
                assertFalse(createTime.isAfter(after), createTime + " is after " + after);
            }
        } finally {
            deleteKeyPair(created);
            deleteKeyPair(importedRsa);
            deleteKeyPair(importedEd25519);
        }
    }

    /**
     * Every filter name DescribeKeyPairs documents, plus key-type, with wildcards; the values of
     * one filter are ORed, separate filters ANDed. The key pairs share a prefix so the other key
     * pairs this application holds stay out of the answers.
     */
    @Test
    void filtersNarrowTheKeyPairsDescribed() {
        String prefix = "filtered-" + UUID.randomUUID();
        String alpha = createdKeyPairId(prefix + "-alpha");
        String beta = importedKeyPairId(prefix + "-beta", ED25519_KEY);
        String gamma = importedKeyPairId(prefix + "-gamma", Ec2KeyMaterial.generateRsa().openSshPublicKey());
        try {
            ec2("CreateTags")
                    .formParam("ResourceId.1", beta).formParam("Tag.1.Key", "Team").formParam("Tag.1.Value", "blue")
                    .post("/").then().statusCode(200);
            String gammaFingerprint = ec2("DescribeKeyPairs").formParam("KeyPairId.1", gamma)
                    .post("/").then().statusCode(200)
                    .extract().path("DescribeKeyPairsResponse.keySet.item.keyFingerprint");

            assertEquals(List.of("alpha", "beta", "gamma"), described(prefix));
            assertEquals(List.of("beta"), described(prefix, "key-pair-id", beta));
            assertEquals(List.of("alpha", "gamma"), described(prefix, "key-name", prefix + "-alpha", prefix + "-gamma"));
            assertEquals(List.of("beta"), described(prefix, "key-name", prefix + "-b?ta"));
            assertEquals(List.of("gamma"), described(prefix, "fingerprint", gammaFingerprint));
            assertEquals(List.of("alpha", "gamma"), described(prefix, "key-type", "rsa"));
            assertEquals(List.of("beta"), described(prefix, "key-type", "ed25519"));
            assertEquals(List.of("beta"), described(prefix, "tag-key", "Team"));
            assertEquals(List.of("beta"), described(prefix, "tag:Team", "blue"));
            assertEquals(List.of(), described(prefix, "tag:Team", "red"));
        } finally {
            deleteKeyPair(alpha);
            deleteKeyPair(beta);
            deleteKeyPair(gamma);
        }
    }

    /** A filter name DescribeKeyPairs does not know is an error, not a filter that matches everything. */
    @Test
    void anUnknownFilterIsRefused() {
        ec2("DescribeKeyPairs")
                .formParam("Filter.1.Name", "owner-id").formParam("Filter.1.Value.1", "000000000000")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("The filter 'owner-id' is invalid"));
    }

    /**
     * AWS joins every Filter.N with AND, a repeated name included: two key-name filters narrow the
     * answer to the key pairs matching both, rather than the second replacing the first.
     */
    @Test
    void aRepeatedFilterNameIsAndedWithTheFirst() {
        String prefix = "repeated-" + UUID.randomUUID();
        String prodWeb = createdKeyPairId(prefix + "-prod-web");
        String devWeb = createdKeyPairId(prefix + "-dev-web");
        String prodDb = createdKeyPairId(prefix + "-prod-db");
        try {
            List<String> ids = ec2("DescribeKeyPairs")
                    .formParam("Filter.1.Name", "key-name").formParam("Filter.1.Value.1", prefix + "-prod-*")
                    .formParam("Filter.2.Name", "key-name").formParam("Filter.2.Value.1", prefix + "-*-web")
                    .post("/").then().statusCode(200).extract().xmlPath()
                    .getList("DescribeKeyPairsResponse.keySet.item.keyPairId", String.class);

            assertEquals(List.of(prodWeb), ids);
            assertEquals(List.of("prod-db", "prod-web"), described(prefix, "key-name", prefix + "-prod-*"));
        } finally {
            deleteKeyPair(prodWeb);
            deleteKeyPair(devWeb);
            deleteKeyPair(prodDb);
        }
    }

    /**
     * The key pairs DescribeKeyPairs returns for the given filter, a name and its values, ANDed with
     * a key-name filter of {@code prefix-*}: their names without the prefix, sorted. Without that
     * first filter every key pair this application holds would come back. A key-name filter under
     * test is a second key-name filter, which has to narrow the first.
     */
    private static List<String> described(String prefix, String... filter) {
        RequestSpecification request = ec2("DescribeKeyPairs")
                .formParam("Filter.1.Name", "key-name").formParam("Filter.1.Value.1", prefix + "-*");
        if (filter.length > 0) {
            request.formParam("Filter.2.Name", filter[0]);
            for (int i = 1; i < filter.length; i++) {
                request.formParam("Filter.2.Value." + i, filter[i]);
            }
        }
        List<String> names = new ArrayList<>();
        for (String name : request.post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeKeyPairsResponse.keySet.item.keyName", String.class)) {
            names.add(name.startsWith(prefix + "-") ? name.substring(prefix.length() + 1) : name);
        }
        names.sort(null);
        return names;
    }
}
