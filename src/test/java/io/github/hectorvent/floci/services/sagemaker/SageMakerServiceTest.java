package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointConfigResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.ModelResource;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.TrainingJobResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SageMakerServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modelCrudAndDuplicateValidation() throws Exception {
        SageMakerService service = service();
        service.createModel(mapper.readTree("""
                {"ModelName":"m1","PrimaryContainer":{"Image":"busybox:stable"},"ExecutionRoleArn":"arn:aws:iam::000000000000:role/r"}
                """), "us-east-1");
        assertEquals("m1", service.describeModel(mapper.readTree("{\"ModelName\":\"m1\"}")).path("ModelName").asText());
        AwsException duplicate = assertThrows(AwsException.class, () -> service.createModel(mapper.readTree("""
                {"ModelName":"m1","PrimaryContainer":{"Image":"busybox:stable"}}
                """), "us-east-1"));
        assertEquals("ValidationException", duplicate.getErrorCode());
        service.deleteModel(mapper.readTree("{\"ModelName\":\"m1\"}"));
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeModel(mapper.readTree("{\"ModelName\":\"m1\"}")));
        assertEquals("ValidationException", missing.getErrorCode());
        AwsException deleteMissing = assertThrows(AwsException.class,
                () -> service.deleteModel(mapper.readTree("{\"ModelName\":\"m1\"}")));
        assertEquals("ValidationException", deleteMissing.getErrorCode());
    }

    @Test
    void deleteEndpointConfigRequiresExistingConfig() throws Exception {
        SageMakerService service = service();
        AwsException missing = assertThrows(AwsException.class,
                () -> service.deleteEndpointConfig(mapper.readTree("{\"EndpointConfigName\":\"missing\"}")));
        assertEquals("ValidationException", missing.getErrorCode());
    }

    @Test
    void endpointConfigRequiresExistingModel() throws Exception {
        SageMakerService service = service();
        AwsException missing = assertThrows(AwsException.class, () -> service.createEndpointConfig(mapper.readTree("""
                {"EndpointConfigName":"cfg","ProductionVariants":[{"VariantName":"AllTraffic","ModelName":"missing","InitialInstanceCount":1,"InstanceType":"ml.t2.medium"}]}
                """), "us-east-1"));
        assertEquals("ValidationException", missing.getErrorCode());
    }

    @Test
    void tagsRoundTrip() throws Exception {
        SageMakerService service = service();
        String arn = service.createModel(mapper.readTree("""
                {"ModelName":"tagged","PrimaryContainer":{"Image":"busybox:stable"},"Tags":[{"Key":"a","Value":"b"}]}
                """), "us-east-1").path("ModelArn").asText();
        service.addTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"c\",\"Value\":\"d\"}]}"));
        assertEquals(2, service.listTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\"}")).path("Tags").size());
        service.deleteTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"a\"]}"));
        assertEquals("c", service.listTags(mapper.readTree("{\"ResourceArn\":\"" + arn + "\"}")).path("Tags").get(0).path("Key").asText());
    }


    @Test
    void s3UriParsingCoversPrefixesAndValidation() {
        S3Uri object = S3Uri.parse("s3://bucket/path/to/object");
        assertEquals("bucket", object.bucket());
        assertEquals("path/to/object", object.key());
        S3Uri bucketOnly = S3Uri.parse("s3://bucket");
        assertEquals("bucket", bucketOnly.bucket());
        assertEquals("", bucketOnly.key());
        assertThrows(IllegalArgumentException.class, () -> S3Uri.parse("http://bucket/key"));
    }

    private SageMakerService service() {
        return new SageMakerService(new InMemoryStorage<String, ModelResource>(),
                new InMemoryStorage<String, EndpointConfigResource>(),
                new InMemoryStorage<String, EndpointResource>(),
                new InMemoryStorage<String, TrainingJobResource>(),
                new RegionResolver("us-east-1", "000000000000"), mapper, null, null);
    }
}
