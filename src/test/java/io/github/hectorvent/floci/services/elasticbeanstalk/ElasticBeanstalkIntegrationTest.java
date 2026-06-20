package io.github.hectorvent.floci.services.elasticbeanstalk;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElasticBeanstalkIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/elasticbeanstalk/aws4_request";
    private static final String APP = "floci-eb-app";
    private static final String ENV = "floci-eb-env";

    @Test
    @Order(1)
    void createApplication_acceptsDocumentedOperationParameter() {
        given()
                .formParam("Operation", "CreateApplication")
                .formParam("ApplicationName", APP)
                .formParam("Description", "Elastic Beanstalk integration app")
                .formParam("Tags.member.1.Key", "Project")
                .formParam("Tags.member.1.Value", "Floci")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("CreateApplicationResponse.CreateApplicationResult.Application.ApplicationName", equalTo(APP))
                .body("CreateApplicationResponse.CreateApplicationResult.Application.ApplicationArn",
                        equalTo("arn:aws:elasticbeanstalk:us-east-1:000000000000:application/" + APP))
                .body("CreateApplicationResponse.CreateApplicationResult.Application.ConfigurationTemplates.member",
                        equalTo("default"));
    }

    @Test
    @Order(2)
    void createApplicationVersion() {
        given()
                .formParam("Action", "CreateApplicationVersion")
                .formParam("ApplicationName", APP)
                .formParam("VersionLabel", "v1")
                .formParam("Description", "Initial version")
                .formParam("SourceBundle.S3Bucket", "source-bucket")
                .formParam("SourceBundle.S3Key", "app-v1.zip")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateApplicationVersionResponse.CreateApplicationVersionResult.ApplicationVersion.ApplicationName",
                        equalTo(APP))
                .body("CreateApplicationVersionResponse.CreateApplicationVersionResult.ApplicationVersion.VersionLabel",
                        equalTo("v1"))
                .body("CreateApplicationVersionResponse.CreateApplicationVersionResult.ApplicationVersion.SourceBundle.S3Bucket",
                        equalTo("source-bucket"));
    }

    @Test
    @Order(3)
    void describeApplicationsIncludesVersion() {
        given()
                .formParam("Action", "DescribeApplications")
                .formParam("ApplicationNames.member.1", APP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeApplicationsResponse.DescribeApplicationsResult.Applications.member.ApplicationName",
                        equalTo(APP))
                .body("DescribeApplicationsResponse.DescribeApplicationsResult.Applications.member.ApplicationArn",
                        equalTo("arn:aws:elasticbeanstalk:us-east-1:000000000000:application/" + APP))
                .body("DescribeApplicationsResponse.DescribeApplicationsResult.Applications.member.Versions.member",
                        equalTo("v1"));
    }

    @Test
    @Order(4)
    void createEnvironment() {
        given()
                .formParam("Action", "CreateEnvironment")
                .formParam("ApplicationName", APP)
                .formParam("EnvironmentName", ENV)
                .formParam("VersionLabel", "v1")
                .formParam("CNAMEPrefix", "floci-eb")
                .formParam("SolutionStackName", "64bit Amazon Linux 2023 v4.3.0 running Docker")
                .formParam("OptionSettings.member.1.Namespace", "aws:elasticbeanstalk:application:environment")
                .formParam("OptionSettings.member.1.OptionName", "SPRING_PROFILES_ACTIVE")
                .formParam("OptionSettings.member.1.Value", "test")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateEnvironmentResponse.CreateEnvironmentResult.EnvironmentName", equalTo(ENV))
                .body("CreateEnvironmentResponse.CreateEnvironmentResult.ApplicationName", equalTo(APP))
                .body("CreateEnvironmentResponse.CreateEnvironmentResult.VersionLabel", equalTo("v1"))
                .body("CreateEnvironmentResponse.CreateEnvironmentResult.Status", equalTo("Ready"))
                .body("CreateEnvironmentResponse.CreateEnvironmentResult.Health", equalTo("Green"))
                .body("CreateEnvironmentResponse.CreateEnvironmentResult.CNAME",
                        equalTo("floci-eb.elasticbeanstalk.local"));
    }

    @Test
    @Order(5)
    void describeEnvironments() {
        given()
                .formParam("Action", "DescribeEnvironments")
                .formParam("ApplicationName", APP)
                .formParam("EnvironmentNames.member.1", ENV)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeEnvironmentsResponse.DescribeEnvironmentsResult.Environments.member.EnvironmentName",
                        equalTo(ENV))
                .body("DescribeEnvironmentsResponse.DescribeEnvironmentsResult.Environments.member.Status",
                        equalTo("Ready"));
    }

    @Test
    @Order(6)
    void describeConfigurationSettingsIncludesEnvironmentOptions() {
        given()
                .formParam("Action", "DescribeConfigurationSettings")
                .formParam("ApplicationName", APP)
                .formParam("EnvironmentName", ENV)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<OptionName>SPRING_PROFILES_ACTIVE</OptionName>"))
                .body(containsString("<Value>test</Value>"));
    }

    @Test
    @Order(7)
    void updateEnvironmentMergesOptionSettings() {
        given()
                .formParam("Action", "UpdateEnvironment")
                .formParam("EnvironmentName", ENV)
                .formParam("Description", "Updated environment")
                .formParam("OptionSettings.member.1.Namespace", "aws:elasticbeanstalk:application:environment")
                .formParam("OptionSettings.member.1.OptionName", "SPRING_PROFILES_ACTIVE")
                .formParam("OptionSettings.member.1.Value", "prod")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("UpdateEnvironmentResponse.UpdateEnvironmentResult.EnvironmentName", equalTo(ENV))
                .body("UpdateEnvironmentResponse.UpdateEnvironmentResult.Description",
                        equalTo("Updated environment"));

        given()
                .formParam("Action", "DescribeConfigurationSettings")
                .formParam("ApplicationName", APP)
                .formParam("EnvironmentName", ENV)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<Value>prod</Value>"))
                .body(not(containsString("<Value>test</Value>")));
    }

    @Test
    @Order(8)
    void terminateEnvironmentHidesDeletedUnlessRequested() {
        given()
                .formParam("Action", "TerminateEnvironment")
                .formParam("EnvironmentName", ENV)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("TerminateEnvironmentResponse.TerminateEnvironmentResult.Status", equalTo("Terminated"));

        given()
                .formParam("Action", "DescribeEnvironments")
                .formParam("ApplicationName", APP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString(ENV)));

        given()
                .formParam("Action", "DescribeEnvironments")
                .formParam("ApplicationName", APP)
                .formParam("IncludeDeleted", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<EnvironmentName>" + ENV + "</EnvironmentName>"))
                .body(containsString("<Status>Terminated</Status>"));
    }

    @Test
    @Order(9)
    void metadataOperations() {
        given()
                .formParam("Action", "CheckDNSAvailability")
                .formParam("CNAMEPrefix", "floci-eb-new")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CheckDNSAvailabilityResponse.CheckDNSAvailabilityResult.Available", equalTo("true"))
                .body("CheckDNSAvailabilityResponse.CheckDNSAvailabilityResult.FullyQualifiedCNAME",
                        equalTo("floci-eb-new.elasticbeanstalk.local"));

        given()
                .formParam("Action", "ListAvailableSolutionStacks")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("Amazon Linux 2023"));
    }

    @Test
    @Order(10)
    void missingRequiredParametersUseAwsMissingParameterCode() {
        given()
                .formParam("Action", "CreateApplication")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));

        given()
                .formParam("Action", "DescribeConfigurationSettings")
                .formParam("ApplicationName", APP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));

        given()
                .formParam("Action", "UpdateEnvironment")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("MissingParameter"));
    }
}
