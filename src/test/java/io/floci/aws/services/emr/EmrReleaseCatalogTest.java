package io.floci.aws.services.emr;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmrReleaseCatalogTest {

    @Test
    void theCatalogueIsInListingOrder() {
        List<String> labels = EmrReleaseCatalog.RELEASES.stream().map(EmrReleaseCatalog.Release::label).toList();
        List<String> sorted = new ArrayList<>(labels);
        Collections.shuffle(sorted, new Random(77));
        sorted.sort(EmrReleaseCatalog.LISTING_ORDER);
        assertEquals(labels, sorted);
        assertEquals(labels.size(), new HashSet<>(labels).size());
    }

    @Test
    void numberedReleasesComeNewestFirstByNumberAndTheSparkRuntimeOnesAfterOldestFirst() {
        List<String> labels = new ArrayList<>(List.of("emr-spark-8.3.0", "emr-6.9.0", "emr-7.15.0",
                "emr-spark-8.0.0", "emr-6.15.0", "emr-5.36.2", "emr-7.9.0", "emr-7.10.0"));
        labels.sort(EmrReleaseCatalog.LISTING_ORDER);
        assertEquals(List.of("emr-7.15.0", "emr-7.10.0", "emr-7.9.0", "emr-6.15.0", "emr-6.9.0", "emr-5.36.2",
                "emr-spark-8.0.0", "emr-spark-8.3.0"), labels);
    }

    @Test
    void applicationsCarryAwsNamesAndConciseVersions() {
        for (EmrReleaseCatalog.Release release : EmrReleaseCatalog.RELEASES) {
            assertTrue(release.applications().stream().anyMatch(a -> a.name().equals("Spark")), release.label());
            for (EmrReleaseCatalog.Application app : release.applications()) {
                assertFalse(app.version().contains("-amzn-"), release.label() + " " + app);
                assertTrue(Character.isUpperCase(app.name().charAt(0)), release.label() + " " + app);
            }
        }
    }
}
