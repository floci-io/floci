# Task 2: Service Layer & Container Management Stub

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

- [ ] **Step 5: Commit**
Run: `git add src/main/java/io/github/hectorvent/floci/services/redshift/RedshiftService.java src/test/java/io/github/hectorvent/floci/services/redshift/RedshiftServiceTest.java`
Run: `git commit -m "feat: add redshift service and test"`
