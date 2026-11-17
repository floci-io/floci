package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsAdvertisePortProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.RdsRequests.rds;
import static org.hamcrest.Matchers.containsString;

/** With advertise-requested-port on, Describe* reports the Port the client asked for. */
@QuarkusTest
@TestProfile(RdsAdvertisePortProfile.class)
class RdsAdvertiseRequestedPortIntegrationTest {

    @AfterEach
    void cleanUp() {
        rds("DeleteDBInstance").formParam("DBInstanceIdentifier", "adv-instance")
                .formParam("SkipFinalSnapshot", "true").when().post("/");
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", "adv-cluster")
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void instanceAndClusterDescribeReportRequestedPort() {
        rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", "adv-instance")
                .formParam("Engine", "postgres")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("AllocatedStorage", "20")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("Port", "5432")
                .when().post("/").then().statusCode(200);
        rds("DescribeDBInstances").formParam("DBInstanceIdentifier", "adv-instance")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>5432</Port>"));

        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", "adv-cluster")
                .formParam("Engine", "aurora-mysql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("Port", "3306")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>3306</Port>"));
        rds("DescribeDBClusters").formParam("DBClusterIdentifier", "adv-cluster")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Port>3306</Port>"));
    }
}
