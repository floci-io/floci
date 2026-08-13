# AWS Service Catalog

**Protocol:** AWS JSON 1.1  
**Signing name:** `servicecatalog`

Floci persists portfolios, products and provisioning-artifact metadata, TagOptions,
resource associations, and organization/account portfolio shares. Portfolio-share
operations complete immediately while preserving the AWS status-token workflow.

## Supported operations

| Area | Operations |
|---|---|
| Portfolios | `CreatePortfolio`, `UpdatePortfolio`, `DescribePortfolio`, `ListPortfolios`, `DeletePortfolio` |
| Products | `CreateProduct`, `UpdateProduct`, `DescribeProductAsAdmin`, `SearchProductsAsAdmin`, `SearchProducts`, `DeleteProduct` |
| Provisioning | `ListProvisioningArtifacts`, `ProvisionProduct`, `SearchProvisionedProducts` |
| TagOptions | `CreateTagOption`, `UpdateTagOption`, `DescribeTagOption`, `ListTagOptions`, `DeleteTagOption` |
| Associations | `AssociateProductWithPortfolio`, `DisassociateProductFromPortfolio`, `AssociateTagOptionWithResource`, `DisassociateTagOptionFromResource` |
| Portfolio sharing | `CreatePortfolioShare`, `UpdatePortfolioShare`, `DescribePortfolioShareStatus` |

When Control Tower is enabled, Floci lazily exposes the managed **AWS Control Tower
Account Factory Portfolio** and its Account Factory product. Provisioning that product
creates the requested Organizations account and persists an `AVAILABLE` provisioned
product. Organizational-unit display labels such as `Infrastructure (ou-...)` are
resolved to their underlying OU IDs.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SERVICECATALOG_ENABLED` | `true` | Enable or disable AWS Service Catalog |
