package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.DocumentationPart;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.BadRequestException;
import software.amazon.awssdk.services.apigateway.model.ConflictException;
import software.amazon.awssdk.services.apigateway.model.CreateDocumentationPartResponse;
import software.amazon.awssdk.services.apigateway.model.DocumentationPartLocation;
import software.amazon.awssdk.services.apigateway.model.DocumentationPartType;
import software.amazon.awssdk.services.apigateway.model.GetDocumentationPartsResponse;
import software.amazon.awssdk.services.apigateway.model.LocationStatusType;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ApiGatewayDocumentationPartIntegrationTest {

    @Inject
    StorageFactory storageFactory;

    @Inject
    ApiGatewayService service;

    private ApiGatewayClient client() {
        return client(Region.US_EAST_1);
    }

    private ApiGatewayClient client(Region region) {
        return AwsTestClients.create(ApiGatewayClient.builder(), "test", region, Apache5HttpClient.builder());
    }

    @Test
    void createDocumentationPartReturnsSdkResponse() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-sdk")).id();
            try {
                DocumentationPartLocation location = DocumentationPartLocation.builder()
                        .type(DocumentationPartType.MODEL).name("Example").build();
                String properties = "{\"title\":\"Example\"}";
                CreateDocumentationPartResponse created = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(location).properties(properties));
                assertNotNull(created.id());
                assertEquals(6, created.id().length());
                assertEquals(location, created.location());
                assertEquals(properties, created.properties());
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void duplicateApiLocationReportsAwsConflictMessage() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-conflict")).id();
            try {
                DocumentationPartLocation location = DocumentationPartLocation.builder()
                        .type(DocumentationPartType.API).build();
                client.createDocumentationPart(request -> request.restApiId(apiId)
                        .location(location).properties("{}"));
                ConflictException conflict = assertThrows(ConflictException.class,
                        () -> client.createDocumentationPart(request -> request.restApiId(apiId)
                                .location(location).properties("{}")));
                assertEquals("Documentation part already exists for the specified location: type 'API'.",
                        conflict.awsErrorDetails().errorMessage());
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void modeledDocumentationConstraintsRejectInvalidFields() {
        String region = "us-east-1";
        String apiId = service.createRestApi(region, Map.of("name", "documentation-part-model-constraints")).getId();
        try {
            AwsException missing = assertThrows(AwsException.class,
                    () -> service.createDocumentationPart(region, apiId,
                            Map.of("location", Map.of("type", "API"))));
            assertEquals("CreateDocumentationPart.properties is required.", missing.getMessage());

            AwsException type = assertThrows(AwsException.class,
                    () -> service.createDocumentationPart(region, apiId,
                            Map.of("location", Map.of("type", "UNKNOWN"), "properties", "{}")));
            assertEquals("CreateDocumentationPart.location.type is not a modeled value.", type.getMessage());

            AwsException statusCode = assertThrows(AwsException.class,
                    () -> service.createDocumentationPart(region, apiId,
                            Map.of("location", Map.of("type", "RESPONSE", "statusCode", "600"),
                                    "properties", "{}")));
            assertEquals("CreateDocumentationPart.location.statusCode does not match the modeled pattern.",
                    statusCode.getMessage());

            AwsException listType = assertThrows(AwsException.class,
                    () -> service.getDocumentationParts(region, apiId, "UNKNOWN", null, null, null, null, null));
            assertEquals("GetDocumentationParts.type is not a modeled value.", listType.getMessage());

            AwsException locationStatus = assertThrows(AwsException.class,
                    () -> service.getDocumentationParts(region, apiId, null, null, null, "UNKNOWN", null, null));
            assertEquals("GetDocumentationParts.locationStatus is not a modeled value.",
                    locationStatus.getMessage());
        } finally {
            service.deleteRestApi(region, apiId);
        }
    }

    @Test
    void defaultLocationFieldsAreReturnedAndPreventDuplicateParts() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-defaults")).id();
            try {
                CreateDocumentationPartResponse resource = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.RESOURCE).build())
                        .properties("{\"description\":\"Root\"}"));
                assertEquals("/", resource.location().path());
                assertEquals("/", client.getDocumentationPart(request -> request.restApiId(apiId)
                        .documentationPartId(resource.id())).location().path());
                assertThrows(ConflictException.class, () -> client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.RESOURCE).path("/").build())
                        .properties("{\"description\":\"Duplicate\"}")));

                CreateDocumentationPartResponse response = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.RESPONSE).build())
                        .properties("{\"description\":\"Default response\"}"));
                assertEquals("/", response.location().path());
                assertEquals("*", response.location().method());
                assertEquals("*", response.location().statusCode());
                assertThrows(ConflictException.class, () -> client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.RESPONSE).path("/").method("*")
                                .statusCode("*").build())
                        .properties("{\"description\":\"Duplicate response\"}")));
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void requestBodyLocationDoesNotRequireName() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-request-body")).id();
            try {
                CreateDocumentationPartResponse created = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.REQUEST_BODY).path("/pets").method("POST").build())
                        .properties("{\"description\":\"Pet request\"}"));
                assertNull(created.location().name());
                assertEquals("/pets", created.location().path());
                assertEquals("POST", created.location().method());
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void blankLocationFieldsAreTreatedAsAbsent() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-blank-fields")).id();
            try {
                CreateDocumentationPartResponse api = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.API).method("").name("").path("").statusCode("").build())
                        .properties("{}"));
                assertEquals(DocumentationPartLocation.builder().type(DocumentationPartType.API).build(),
                        api.location());
                assertThrows(ConflictException.class, () -> client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.API).build())
                        .properties("{}")));

                CreateDocumentationPartResponse model = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.MODEL).name("Example").method("").path("")
                                .statusCode("").build())
                        .properties("{\"title\":\"Example\"}"));
                assertEquals(DocumentationPartLocation.builder().type(DocumentationPartType.MODEL)
                        .name("Example").build(), model.location());
                BadRequestException blankName = assertThrows(BadRequestException.class,
                        () -> client.createDocumentationPart(request -> request.restApiId(apiId)
                                .location(DocumentationPartLocation.builder().type(DocumentationPartType.MODEL)
                                        .name("").build())
                                .properties("{}")));
                assertEquals("Documentation part location name is required", blankName.awsErrorDetails().errorMessage());

                CreateDocumentationPartResponse response = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.RESPONSE).method("POST").path("/example")
                                .statusCode("200").name("").build())
                        .properties("{\"description\":\"OK\"}"));
                assertEquals(DocumentationPartLocation.builder().type(DocumentationPartType.RESPONSE)
                        .method("POST").path("/example").statusCode("200").build(), response.location());

                CreateDocumentationPartResponse header = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(DocumentationPartLocation.builder()
                                .type(DocumentationPartType.RESPONSE_HEADER).name("X-Request-Id").statusCode("")
                                .build())
                        .properties("{}"));
                assertEquals(DocumentationPartLocation.builder().type(DocumentationPartType.RESPONSE_HEADER)
                        .name("X-Request-Id").path("/").method("*").statusCode("*").build(), header.location());
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void locationFieldInvalidForTypeIsRejected() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-invalid-field")).id();
            try {
                BadRequestException invalid = assertThrows(BadRequestException.class,
                        () -> client.createDocumentationPart(request -> request.restApiId(apiId)
                                .location(DocumentationPartLocation.builder().type(DocumentationPartType.MODEL)
                                        .name("Example").method("GET").build())
                                .properties("{}")));
                assertEquals("Invalid documentation part location field", invalid.awsErrorDetails().errorMessage());
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void nullRequestBodyReturnsBadRequest() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-null-body")).id();
            try {
                given().contentType("application/json").body("null")
                        .post("/restapis/" + apiId + "/documentation/parts")
                        .then().statusCode(400);
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void absentRequestBodyReturnsBadRequest() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-absent-body")).id();
            try {
                given().contentType("application/json")
                        .post("/restapis/" + apiId + "/documentation/parts")
                        .then().statusCode(400);
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void concurrentCreatesAtSameLocationConflict() throws Exception {
        String region = "us-east-1";
        String apiId = service.createRestApi(region, Map.of("name", "documentation-part-concurrent")).getId();
        try {
            int attempts = 24;
            CountDownLatch ready = new CountDownLatch(attempts);
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService workers = Executors.newFixedThreadPool(attempts)) {
                List<Future<Boolean>> results = new ArrayList<>();
                for (int index = 0; index < attempts; index++) {
                    results.add(workers.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            service.createDocumentationPart(region, apiId, Map.of(
                                    "location", Map.of("type", "MODEL", "name", "Concurrent"),
                                    "properties", "{}"));
                            return true;
                        } catch (AwsException e) {
                            assertEquals("ConflictException", e.getErrorCode());
                            return false;
                        }
                    }));
                }
                boolean allReady;
                try {
                    allReady = ready.await(30, TimeUnit.SECONDS);
                } finally {
                    start.countDown();
                }
                assertTrue(allReady);
                int successes = 0;
                for (Future<Boolean> result : results) {
                    if (result.get()) {
                        successes++;
                    }
                }
                assertEquals(1, successes);
                assertEquals(1, service.getDocumentationParts(region, apiId, null, null, null,
                        null, null, null).items().size());
            }
        } finally {
            service.deleteRestApi(region, apiId);
        }
    }

    @Test
    void generatedIdCollisionPreservesBothParts() {
        String region = "us-east-1";
        String apiId = service.createRestApi(region, Map.of("name", "documentation-part-id-collision")).getId();
        UUID collision = UUID.fromString("abcdef00-0000-0000-0000-000000000001");
        UUID retry = UUID.fromString("12345678-0000-0000-0000-000000000002");
        try {
            try (MockedStatic<UUID> ids = Mockito.mockStatic(UUID.class,
                    Mockito.withSettings().mockMaker(MockMakers.INLINE).defaultAnswer(Mockito.CALLS_REAL_METHODS))) {
                ids.when(UUID::randomUUID).thenReturn(collision, collision, retry);
                DocumentationPart first = service.createDocumentationPart(region, apiId, Map.of(
                        "location", Map.of("type", "MODEL", "name", "First"), "properties", "{}"));
                DocumentationPart second = service.createDocumentationPart(region, apiId, Map.of(
                        "location", Map.of("type", "MODEL", "name", "Second"), "properties", "{}"));

                assertNotEquals(first.getId(), second.getId());
                assertEquals("First", service.getDocumentationPart(region, apiId, first.getId())
                        .getLocation().get("name"));
                assertEquals("Second", service.getDocumentationPart(region, apiId, second.getId())
                        .getLocation().get("name"));
            }
        } finally {
            service.deleteRestApi(region, apiId);
        }
    }

    @Test
    void knownApiIdDoesNotCrossAccountBoundary() {
        String region = "us-east-1";
        String owner = "111122223333";
        String other = "444455556666";
        String apiId = RequestScopes.callAs(owner, region,
                () -> service.createRestApi(region, Map.of("name", "documentation-part-owner")).getId());
        try {
            String partId = RequestScopes.callAs(owner, region, () -> service.createDocumentationPart(region,
                    apiId, Map.of("location", Map.of("type", "MODEL", "name", "Owner"),
                            "properties", "{}")).getId());
            RequestScopes.runAs(other, region, () -> {
                assertEquals("NotFoundException", assertThrows(AwsException.class,
                        () -> service.getDocumentationPart(region, apiId, partId)).getErrorCode());
                assertEquals("NotFoundException", assertThrows(AwsException.class,
                        () -> service.getDocumentationParts(region, apiId, null, null, null,
                                null, null, null)).getErrorCode());
                assertEquals("NotFoundException", assertThrows(AwsException.class,
                        () -> service.updateDocumentationPart(region, apiId, partId,
                                List.of(Map.of("op", "replace", "path", "/properties", "value", "{}"))))
                        .getErrorCode());
                assertEquals("NotFoundException", assertThrows(AwsException.class,
                        () -> service.deleteDocumentationPart(region, apiId, partId)).getErrorCode());
            });
            assertNotNull(RequestScopes.callAs(owner, region,
                    () -> service.getDocumentationPart(region, apiId, partId)));
        } finally {
            RequestScopes.runAs(owner, region, () -> service.deleteRestApi(region, apiId));
        }
    }

    @Test
    void deletingRestApiRemovesDocumentationPartKeys() {
        try (ApiGatewayClient client = client()) {
            StorageBackend<String, DocumentationPart> parts = storageFactory.create("apigateway",
                    "apigateway-documentation-parts.json",
                    new TypeReference<Map<String, DocumentationPart>>() {});
            String apiId = client.createRestApi(request -> request.name("documentation-part-cascade")).id();
            boolean deleted = false;
            try {
                String partId = client.createDocumentationPart(request -> request.restApiId(apiId)
                        .location(DocumentationPartLocation.builder().type(DocumentationPartType.MODEL)
                                .name("Example").build())
                        .properties("{\"title\":\"Example\"}")).id();
                String key = "us-east-1::" + apiId + "::" + partId;
                assertTrue(parts.keys().contains(key));

                client.deleteRestApi(request -> request.restApiId(apiId));
                deleted = true;
                assertFalse(parts.keys().contains(key));
                assertThrows(NotFoundException.class, () -> client.getDocumentationPart(request -> request
                        .restApiId(apiId).documentationPartId(partId)));

                String newApiId = client.createRestApi(request -> request.name("documentation-part-cascade")).id();
                try {
                    assertTrue(client.getDocumentationParts(request -> request.restApiId(newApiId)).items().isEmpty());
                    assertThrows(NotFoundException.class, () -> client.getDocumentationPart(request -> request
                            .restApiId(newApiId).documentationPartId(partId)));
                } finally {
                    client.deleteRestApi(request -> request.restApiId(newApiId));
                }
            } finally {
                if (!deleted) {
                    client.deleteRestApi(request -> request.restApiId(apiId));
                }
            }
        }
    }

    @Test
    void documentationPartRoundTripsThroughReadUpdateAndDelete() {
        try (ApiGatewayClient client = client(); ApiGatewayClient otherRegion = client(Region.EU_WEST_1)) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-lifecycle")).id();
            String otherApiId = client.createRestApi(request -> request.name("documentation-part-other-api")).id();
            try {
                DocumentationPartLocation location = DocumentationPartLocation.builder()
                        .type(DocumentationPartType.MODEL).name("Widget").build();
                CreateDocumentationPartResponse created = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(location).properties("{\"title\":\"Before\"}"));
                String partId = created.id();
                assertEquals("{\"title\":\"Before\"}", client.getDocumentationPart(request -> request
                        .restApiId(apiId).documentationPartId(partId)).properties());
                assertEquals(partId, client.getDocumentationParts(request -> request.restApiId(apiId))
                        .items().getFirst().id());

                assertThrows(NotFoundException.class, () -> client.getDocumentationPart(request -> request
                        .restApiId(otherApiId).documentationPartId(partId)));
                assertThrows(NotFoundException.class, () -> otherRegion.getDocumentationPart(request -> request
                        .restApiId(apiId).documentationPartId(partId)));

                String updated = "{\"title\":\"After\"}";
                assertEquals(updated, client.updateDocumentationPart(request -> request.restApiId(apiId)
                        .documentationPartId(partId).patchOperations(PatchOperation.builder()
                                .op("replace").path("/properties").value(updated).build())).properties());
                assertEquals(updated, client.getDocumentationPart(request -> request.restApiId(apiId)
                        .documentationPartId(partId)).properties());

                client.deleteDocumentationPart(request -> request.restApiId(apiId).documentationPartId(partId));
                assertThrows(NotFoundException.class, () -> client.getDocumentationPart(request -> request
                        .restApiId(apiId).documentationPartId(partId)));
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
                client.deleteRestApi(request -> request.restApiId(otherApiId));
            }
        }
    }

    @Test
    void documentationPartsFilterAndPage() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-list")).id();
            try {
                Set<String> ids = new HashSet<>();
                for (String name : new String[] {"Alpha", "Beta", "Gamma"}) {
                    ids.add(client.createDocumentationPart(request -> request.restApiId(apiId)
                            .location(DocumentationPartLocation.builder().type(DocumentationPartType.MODEL)
                                    .name(name).build()).properties("{\"title\":\"" + name + "\"}")).id());
                }
                String resourceId = client.createDocumentationPart(request -> request.restApiId(apiId)
                        .location(DocumentationPartLocation.builder().type(DocumentationPartType.RESOURCE)
                                .path("/pets").build()).properties("{\"description\":\"Pets\"}")).id();

                assertEquals("Beta", client.getDocumentationParts(request -> request.restApiId(apiId)
                        .nameQuery("Beta")).items().getFirst().location().name());
                assertEquals(resourceId, client.getDocumentationParts(request -> request.restApiId(apiId)
                        .path("/pets")).items().getFirst().id());
                assertEquals(3, client.getDocumentationParts(request -> request.restApiId(apiId)
                        .type(DocumentationPartType.MODEL).locationStatus(LocationStatusType.DOCUMENTED)).items().size());
                GetDocumentationPartsResponse page = client.getDocumentationParts(request -> request.restApiId(apiId)
                        .type(DocumentationPartType.MODEL).limit(1));
                Set<String> pagedIds = new HashSet<>();
                for (int index = 0; index < ids.size(); index++) {
                    assertEquals(1, page.items().size());
                    pagedIds.add(page.items().getFirst().id());
                    if (index == ids.size() - 1) {
                        assertNull(page.position());
                    } else {
                        assertNotNull(page.position());
                        String position = page.position();
                        page = client.getDocumentationParts(request -> request.restApiId(apiId)
                                .type(DocumentationPartType.MODEL).limit(1).position(position));
                    }
                }
                assertEquals(ids, pagedIds);
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }

    @Test
    void invalidPatchDoesNotChangeDocumentationPart() {
        try (ApiGatewayClient client = client()) {
            String apiId = client.createRestApi(request -> request.name("documentation-part-validation")).id();
            try {
                DocumentationPartLocation location = DocumentationPartLocation.builder()
                        .type(DocumentationPartType.MODEL).name("Example").build();
                String original = "{\"title\":\"Original\"}";
                CreateDocumentationPartResponse created = client.createDocumentationPart(request -> request
                        .restApiId(apiId).location(location).properties(original));

                assertThrows(BadRequestException.class,
                        () -> client.updateDocumentationPart(request -> request.restApiId(apiId)
                                .documentationPartId(created.id()).patchOperations(
                                        PatchOperation.builder().op("replace").path("/properties")
                                                .value("{\"title\":\"Changed\"}").build(),
                                        PatchOperation.builder().op("add").path("/properties")
                                                .value("{\"title\":\"Invalid\"}").build())));
                assertThrows(BadRequestException.class,
                        () -> client.updateDocumentationPart(request -> request.restApiId(apiId)
                                .documentationPartId(created.id()).patchOperations(PatchOperation.builder()
                                        .op("replace").path("/properties").value("{} trailing").build())));
                assertEquals(original, client.getDocumentationPart(request -> request.restApiId(apiId)
                        .documentationPartId(created.id())).properties());
                assertThrows(BadRequestException.class,
                        () -> client.createDocumentationPart(request -> request.restApiId(apiId)
                                .location(DocumentationPartLocation.builder().type(DocumentationPartType.MODEL).build())
                                .properties(original)));
            } finally {
                client.deleteRestApi(request -> request.restApiId(apiId));
            }
        }
    }
}
