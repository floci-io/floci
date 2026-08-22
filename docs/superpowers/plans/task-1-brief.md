# Task 1: Scaffolding and Models

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

- [ ] **Step 3: Commit**
Run: `git add src/main/java/io/github/hectorvent/floci/services/redshift/model/`
Run: `git commit -m "feat: add redshift domain models"`
