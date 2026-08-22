# Task 3: Query Handler & Routing

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
Update `AwsQueryController.java` to route Redshift calls. 
- Inject `RedshiftQueryHandler redshiftQueryHandler`
- Update the dispatch logic inside `AwsQueryController.java` (e.g. handle the Redshift service name in the switch statement).

- [ ] **Step 3: Commit**
Run: `git add src/main/java/io/github/hectorvent/floci/services/redshift/ src/main/java/io/github/hectorvent/floci/core/common/AwsQueryController.java`
Run: `git commit -m "feat: add redshift query handler and routing"`
