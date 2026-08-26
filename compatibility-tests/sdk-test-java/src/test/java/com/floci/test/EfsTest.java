package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.efs.model.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EFS Elastic File System")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EfsTest {

    private static EfsClient efs;

    @BeforeAll
    static void setup() {
        efs = TestFixtures.efsClient();
    }

    @AfterAll
    static void cleanup() {
        if (efs != null) {
            efs.close();
        }
    }

    @Test
    @Order(1)
    void createWithElasticThroughputMode() {
        String token = TestFixtures.uniqueName("efs-");
        CreateFileSystemResponse response = efs.createFileSystem(r -> r
                .creationToken(token)
                .throughputMode(ThroughputMode.ELASTIC)
        );

        assertThat(response.throughputMode()).isEqualTo(ThroughputMode.ELASTIC);
        assertThat(response.fileSystemId()).startsWith("fs-");
    }

    @Test
    @Order(2)
    void duplicateCreationTokenThrowsFileSystemAlreadyExists() {
        String token = TestFixtures.uniqueName("efs-dup-");
        
        CreateFileSystemResponse response1 = efs.createFileSystem(r -> r.creationToken(token));
        String fsId = response1.fileSystemId();
        
        FileSystemAlreadyExistsException ex = catchThrowableOfType(
                () -> efs.createFileSystem(r -> r.creationToken(token).throughputMode(ThroughputMode.PROVISIONED)),
                FileSystemAlreadyExistsException.class
        );
        
        assertThat(ex).isNotNull();
        assertThat(ex.fileSystemId()).isEqualTo(fsId);
    }

    @Test
    @Order(3)
    void duplicateClientTokenThrowsAccessPointAlreadyExists() {
        String fsToken = TestFixtures.uniqueName("efs-ap-");
        CreateFileSystemResponse fsResponse = efs.createFileSystem(r -> r.creationToken(fsToken));
        String fsId = fsResponse.fileSystemId();
        
        String clientToken = TestFixtures.uniqueName("ap-dup-");
        
        CreateAccessPointResponse apResponse1 = efs.createAccessPoint(r -> r
                .fileSystemId(fsId)
                .clientToken(clientToken)
        );
        String apId = apResponse1.accessPointId();
        
        AccessPointAlreadyExistsException ex = catchThrowableOfType(
                () -> efs.createAccessPoint(r -> r
                        .fileSystemId(fsId)
                        .clientToken(clientToken)
                        .tags(software.amazon.awssdk.services.efs.model.Tag.builder().key("foo").value("bar").build())
                ),
                AccessPointAlreadyExistsException.class
        );
        
        assertThat(ex).isNotNull();
        assertThat(ex.accessPointId()).isEqualTo(apId);
    }
}
