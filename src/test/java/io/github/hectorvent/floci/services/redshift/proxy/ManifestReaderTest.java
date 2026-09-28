package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ManifestReaderTest {

    @Test
    void parseManifest_validEntries() {
        S3Service s3 = Mockito.mock(S3Service.class);
        String manifestJson = "{\"entries\": ["
                + "{\"url\": \"s3://mybucket/data/file1.csv\", \"mandatory\": true},"
                + "{\"url\": \"s3://mybucket/data/file2.csv\", \"mandatory\": false}"
                + "]}";
        S3Object obj = new S3Object();
        obj.setData(manifestJson.getBytes(StandardCharsets.UTF_8));
        when(s3.getObject("mybucket", "manifest.json")).thenReturn(obj);
        when(s3.objectExists("mybucket", "data/file1.csv")).thenReturn(true);
        when(s3.objectExists("mybucket", "data/file2.csv")).thenReturn(false);

        List<String> keys = ManifestReader.resolveManifestKeys("mybucket", "manifest.json", s3);
        assertEquals(List.of("data/file1.csv"), keys);
    }

    @Test
    void parseManifest_missingMandatory_throwsException() {
        S3Service s3 = Mockito.mock(S3Service.class);
        String manifestJson = "{\"entries\": ["
                + "{\"url\": \"s3://mybucket/data/missing.csv\", \"mandatory\": true}"
                + "]}";
        S3Object obj = new S3Object();
        obj.setData(manifestJson.getBytes(StandardCharsets.UTF_8));
        when(s3.getObject("mybucket", "manifest.json")).thenReturn(obj);
        when(s3.objectExists("mybucket", "data/missing.csv")).thenReturn(false);

        S3CopySimulator.S3TransferException ex = assertThrows(S3CopySimulator.S3TransferException.class, () ->
                ManifestReader.resolveManifestKeys("mybucket", "manifest.json", s3));
        assertTrue(ex.getMessage().contains("missing.csv"));
    }

    @Test
    void parseManifest_malformedJson_throwsException() {
        S3Service s3 = Mockito.mock(S3Service.class);
        S3Object obj = new S3Object();
        obj.setData("not valid json".getBytes(StandardCharsets.UTF_8));
        when(s3.getObject("mybucket", "manifest.json")).thenReturn(obj);

        assertThrows(S3CopySimulator.S3TransferException.class, () ->
                ManifestReader.resolveManifestKeys("mybucket", "manifest.json", s3));
    }

    @Test
    void parseManifest_missingMandatoryFlagDefaultsToFalse_skipsMissingFile() {
        S3Service s3 = Mockito.mock(S3Service.class);
        String manifestJson = "{\"entries\": ["
                + "{\"url\": \"s3://mybucket/data/file1.csv\"},"
                + "{\"url\": \"s3://mybucket/data/missing.csv\"}"
                + "]}";
        S3Object obj = new S3Object();
        obj.setData(manifestJson.getBytes(StandardCharsets.UTF_8));
        when(s3.getObject("mybucket", "manifest.json")).thenReturn(obj);
        when(s3.objectExists("mybucket", "data/file1.csv")).thenReturn(true);
        when(s3.objectExists("mybucket", "data/missing.csv")).thenReturn(false);

        List<String> keys = ManifestReader.resolveManifestKeys("mybucket", "manifest.json", s3);
        assertEquals(List.of("data/file1.csv"), keys);
    }
}
