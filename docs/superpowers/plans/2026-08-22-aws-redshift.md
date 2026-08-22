# AWS Redshift Service Emulator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Amazon Redshift service emulator (Control Plane + Docker Data Plane) in Floci.

**Architecture:** Use AWS Query Protocol to parse requests and return XML responses. Start a Docker container (`postgres:15-alpine`) per cluster to emulate the Data Plane.

**Tech Stack:** Java, Quarkus, JUnit 5, RestAssured, Testcontainers (or Docker API).

## Global Constraints

- Must follow existing Floci patterns (e.g. `StorageFactory`, `EmulatorConfig`).
- Output XML must match AWS Query format.
- Code must be formatted according to Floci's existing checkstyle/conventions.

---

### Task 1: Scaffolding and Models

**Files:**
- Create: `src/main/java/io/github/hectorvent/floci/services/redshift/model/Endpoint.java`
- Create: `src/main/java/io/github/hectorvent/floci/services/redshift/model/Cluster.java`

**Interfaces:**
- Produces: `Cluster` (with properties `clusterIdentifier`, `clusterStatus`, `masterUsername`, `endpoint`) and `Endpoint` (with properties `address`, `port`).

- [ ] **Step 1: Write Endpoint model**

```java
package io.github.hectorvent.floci.services.redshift.model;

public class Endpoint {
    private String address;
    private int port;

    public Endpoint() {}

    public Endpoint(String address, int port) {
        this.address = address;
        this.port = port;
    }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}
```

- [ ] **Step 2: Write Cluster model**

```java
package io.github.hectorvent.floci.services.redshift.model;

public class Cluster {
    private String clusterIdentifier;
    private String nodeType;
    private String masterUsername;
    private String clusterStatus;
    private Endpoint endpoint;

    public String getClusterIdentifier() { return clusterIdentifier; }
    public void setClusterIdentifier(String clusterIdentifier) { this.clusterIdentifier = clusterIdentifier; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String masterUsername) { this.masterUsername = masterUsername; }
    public String getClusterStatus() { return clusterStatus; }
    public void setClusterStatus(String clusterStatus) { this.clusterStatus = clusterStatus; }
    public Endpoint getEndpoint() { return endpoint; }
    public void setEndpoint(Endpoint endpoint) { this.endpoint = endpoint; }
}
```

---

### Task 2: Service Layer & Container Management Stub

**Files:**
- Create: `src/main/java/io/github/hectorvent/floci/services/redshift/RedshiftService.java`
- Create: `src/test/java/io/github/hectorvent/floci/services/redshift/RedshiftServiceTest.java`

**Interfaces:**
- Consumes: `Cluster`, `Endpoint`
- Produces: `RedshiftService.createCluster(...)`

- [ ] **Step 1: Write failing test for RedshiftService**

```java
package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RedshiftServiceTest {
    @Test
    void testCreateCluster() {
        RedshiftService service = new RedshiftService();
        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123");
        assertNotNull(cluster);
        assertEquals("my-cluster", cluster.getClusterIdentifier());
        assertEquals("creating", cluster.getClusterStatus());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `./mvnw test -Dtest=RedshiftServiceTest`
Expected: Compilation failure or test failure (RedshiftService not found).

- [ ] **Step 3: Implement RedshiftService**

```java
package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@ApplicationScoped
public class RedshiftService {
    private final Map<String, Cluster> clusters = new ConcurrentHashMap<>();

    public Cluster createCluster(String identifier, String nodeType, String username, String password) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setNodeType(nodeType);
        cluster.setMasterUsername(username);
        cluster.setClusterStatus("creating");
        clusters.put(identifier, cluster);
        return cluster;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**
Run: `./mvnw test -Dtest=RedshiftServiceTest`
Expected: PASS

---

### Task 3: Query Handler & Routing

**Files:**
- Create: `src/main/java/io/github/hectorvent/floci/services/redshift/RedshiftQueryHandler.java`
- Modify: `src/main/java/io/github/hectorvent/floci/core/common/AwsQueryController.java`

**Interfaces:**
- Consumes: `RedshiftService`

- [ ] **Step 1: Implement RedshiftQueryHandler**

```java
package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class RedshiftQueryHandler {
    private final RedshiftService service;

    @Inject
    public RedshiftQueryHandler(RedshiftService service) {
        this.service = service;
    }

    public Response handle(String action, MultivaluedMap<String, String> params) {
        if ("CreateCluster".equals(action)) {
            String identifier = params.getFirst("ClusterIdentifier");
            String nodeType = params.getFirst("NodeType");
            String masterUsername = params.getFirst("MasterUsername");
            String masterUserPassword = params.getFirst("MasterUserPassword");

            Cluster cluster = service.createCluster(identifier, nodeType, masterUsername, masterUserPassword);
            
            XmlBuilder xml = XmlBuilder.create("CreateClusterResponse")
                .start("CreateClusterResult")
                    .start("Cluster")
                        .element("ClusterIdentifier", cluster.getClusterIdentifier())
                        .element("ClusterStatus", cluster.getClusterStatus())
                    .end()
                .end()
                .start("ResponseMetadata").element("RequestId", "test-req-id").end();
                
            return AwsQueryResponse.ok(xml.build());
        }
        return Response.status(400).build();
    }
}
```

- [ ] **Step 2: Register in AwsQueryController**
Update `AwsQueryController.java` to route Redshift calls. (Note: Inject `RedshiftQueryHandler redshiftQueryHandler` and update the dispatch logic inside `AwsQueryController.java`).

- [ ] **Step 3: Commit**
Run: `git add src/main/java/io/github/hectorvent/floci/services/redshift/ src/main/java/io/github/hectorvent/floci/core/common/AwsQueryController.java src/test/java/io/github/hectorvent/floci/services/redshift/`
Run: `git commit -m "feat: add basic redshift query handler and model"`
