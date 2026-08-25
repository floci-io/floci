# AWS Service Catalog

**Protocol:** AWS JSON 1.1  
**Signing name:** `servicecatalog`

Floci persists portfolios, products and provisioning-artifact metadata, TagOptions,
resource associations, and organization/account portfolio shares. Portfolio-share
operations complete immediately while preserving the AWS status-token workflow.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreatePortfolio` | Create a portfolio to organise products. |
| `UpdatePortfolio` | Update a portfolio's display name, description, or provider. |
| `DescribePortfolio` | Return a portfolio's details. |
| `ListPortfolios` | List all portfolios in the account. |
| `DeletePortfolio` | Delete a portfolio. |
| `CreateProduct` | Create a product with its initial provisioning artifact. |
| `UpdateProduct` | Update a product's metadata. |
| `DescribeProductAsAdmin` | Return a product's details and provisioning artifacts from the admin view. |
| `SearchProductsAsAdmin` | List every product in the account from the admin view. |
| `SearchProducts` | Search the products available to the caller. |
| `ListProvisioningArtifacts` | List the provisioning artifacts (versions) of a product. |
| `ProvisionProduct` | Provision a product; the Control Tower Account Factory product creates a real Organizations account. |
| `SearchProvisionedProducts` | Search provisioned products. |
| `DeleteProduct` | Delete a product. |
| `CreateTagOption` | Create a TagOption key/value pair. |
| `UpdateTagOption` | Update a TagOption's value or active flag. |
| `DescribeTagOption` | Return a TagOption's details. |
| `ListTagOptions` | List all TagOptions. |
| `DeleteTagOption` | Delete a TagOption. |
| `AssociateProductWithPortfolio` | Associate a product with a portfolio. |
| `DisassociateProductFromPortfolio` | Remove a product from a portfolio. |
| `AssociateTagOptionWithResource` | Attach a TagOption to a portfolio or product. |
| `DisassociateTagOptionFromResource` | Detach a TagOption from a resource. |
| `UpdatePortfolioShare` | Update an existing portfolio share; completes immediately with a terminal status token. |
| `CreatePortfolioShare` | Share a portfolio with an account or organization node; completes immediately. |
| `DescribePortfolioShareStatus` | Report the status of a share operation by its token; always terminal. |
| `DescribeProduct` | Return a product's consumer view; omits `Budgets` and `LaunchPaths`. |
| `DescribePortfolioShares` | List a portfolio's shares; every share reports `Accepted: true` and no pagination token is emitted. |
| `ListPortfoliosForProduct` | List the portfolios a product is associated with. |
| `CopyProduct` | Copy a product's metadata synchronously; the returned copy token is already terminal. |
| `DescribeProvisioningArtifact` | Return a provisioning artifact's details; omits the template `Info` map. |
| `AcceptPortfolioShare` | Validate the shared portfolio exists; acceptance state is not persisted. |
| `DeletePortfolioShare` | Delete the share row keyed by portfolio and resolved principal. |
| `RejectPortfolioShare` | Validate the portfolio exists; rejection is a no-op beyond validation. |
| `AssociateBudgetWithResource` | Record a budget-name association with a resource; no Budgets service integration exists. |
| `AssociatePrincipalWithPortfolio` | Grant an IAM principal access to a portfolio. |
| `AssociateServiceActionWithProvisioningArtifact` | Associate a service action with a provisioning artifact. |
| `BatchAssociateServiceActionWithProvisioningArtifact` | Associate multiple service actions with provisioning artifacts in one call. |
| `BatchDisassociateServiceActionFromProvisioningArtifact` | Remove multiple service-action associations in one call. |
| `CreateConstraint` | Create a constraint on a product/portfolio pair. |
| `CreateProvisionedProductPlan` | Create a provisioned product plan. |
| `CreateServiceAction` | Create a self-service action definition. |
| `DeleteConstraint` | Delete a constraint. |
| `DeleteProvisionedProductPlan` | Delete a provisioned product plan. |
| `DeleteProvisioningArtifact` | Delete a provisioning artifact from a product. |
| `DeleteServiceAction` | Delete a service action. |
| `DescribeConstraint` | Return a constraint's details. |
| `DescribeProductView` | Return a product by its product-view id. |
| `DescribeProvisionedProduct` | Return a provisioned product's details. |
| `DescribeProvisionedProductPlan` | Return a provisioned product plan's details. |
| `DescribeProvisioningParameters` | Return the parameters needed to provision a product. |
| `DescribeRecord` | Look up a record; only records from `ImportAsProvisionedProduct` and `ExecuteProvisionedProductServiceAction` are found. |
| `DescribeServiceActionExecutionParameters` | Return a service action's execution parameters; always an empty list. |
| `DisableAWSOrganizationsAccess` | Accept the disable request without persisting any state. |
| `DisassociateBudgetFromResource` | Remove a budget-name association from a resource. |
| `DisassociatePrincipalFromPortfolio` | Revoke a principal's access to a portfolio. |
| `DisassociateServiceActionFromProvisioningArtifact` | Remove a service action's association with a provisioning artifact. |
| `EnableAWSOrganizationsAccess` | Accept the enable request without persisting any state. |
| `ExecuteProvisionedProductPlan` | Mark a plan executed with a `SUCCEEDED` record; no provisioned product is created. |
| `ExecuteProvisionedProductServiceAction` | Execute a service action against a provisioned product and persist the record. |
| `GetAWSOrganizationsAccessStatus` | Report the Organizations access status; always `ENABLED`. |
| `GetProvisionedProductOutputs` | Return a provisioned product's stack outputs; always an empty list. |
| `ImportAsProvisionedProduct` | Import an existing CloudFormation stack as a provisioned product and persist the record. |
| `ListAcceptedPortfolioShares` | List portfolios; returns every portfolio, not just accepted shares. |
| `ListBudgetsForResource` | List the budget names associated with a resource. |
| `ListConstraintsForPortfolio` | List the constraints for a portfolio, optionally filtered by product. |
| `ListLaunchPaths` | List the launch paths available for a product. |
| `ListOrganizationPortfolioAccess` | List the organization nodes a portfolio is shared with. |
| `ListPortfolioAccess` | List the account IDs a portfolio is shared with. |
| `ListPrincipalsForPortfolio` | List the principals associated with a portfolio. |
| `ListProvisionedProductPlans` | List provisioned product plans. |
| `ListProvisioningArtifactsForServiceAction` | List provisioning artifacts for a service action; always an empty list. |
| `ListRecordHistory` | List provisioning records synthesised from the stored provisioned products. |
| `ListResourcesForTagOption` | List the resources a TagOption is attached to. |
| `ListServiceActions` | List every service action in the account, without context scoping. |
| `ListServiceActionsForProvisioningArtifact` | List service actions for a provisioning artifact; always an empty list. |
| `ListStackInstancesForProvisionedProduct` | List stack instances for a provisioned product. |
| `NotifyProvisionProductEngineWorkflowResult` | Record a provisioning-engine workflow result notification. |
| `NotifyTerminateProvisionedProductEngineWorkflowResult` | Record a terminate-engine workflow result notification. |
| `NotifyUpdateProvisionedProductEngineWorkflowResult` | Record an update-engine workflow result notification. |
| `ScanProvisionedProducts` | List all provisioned products with page-token pagination. |
| `UpdateConstraint` | Update a constraint's parameters or description. |
| `UpdateProvisionedProduct` | Validate the provisioned product exists and return a `SUCCEEDED` record; no fields change. |
| `UpdateProvisionedProductProperties` | Update a provisioned product's properties. |
| `UpdateServiceAction` | Update a service action's definition. |
| `CreateProvisioningArtifact` | Add a new provisioning artifact (version) to a product. |
| `DescribeCopyProductStatus` | Report a product-copy status; always `SUCCEEDED` immediately. |
| `TerminateProvisionedProduct` | Terminate a provisioned product. |
| `UpdateProvisioningArtifact` | Update a provisioning artifact's metadata. |
<!-- floci:actions:end -->

When Control Tower is enabled, Floci lazily exposes the managed **AWS Control Tower
Account Factory Portfolio** and its Account Factory product. Provisioning that product
creates the requested Organizations account and persists an `AVAILABLE` provisioned
product. Organizational-unit display labels such as `Infrastructure (ou-...)` are
resolved to their underlying OU IDs.

## Limitations

- **`CopyProduct` completes synchronously.** Real AWS returns a `CopyProductToken` for an
  asynchronous copy whose progress is polled with `DescribeCopyProductStatus`. Floci creates
  the copied product before returning, so the token it hands back is already terminal —
  `DescribeCopyProductStatus` immediately reports `SUCCEEDED`. A caller that treats the token
  as pending work will not see any intermediate state.
- **`DescribeServiceActionExecutionParameters` always returns an empty parameter list.** No
  parameter schema is modelled for service action definitions.
- **`ListAcceptedPortfolioShares` returns every portfolio, not just accepted shares.** Consistent
  with `AcceptPortfolioShare` not tracking acceptance as distinct state (see above): there is no
  way to distinguish a portfolio the caller owns from one shared and accepted from elsewhere.
- **`UpdateProvisionedProduct` does not apply any request fields.** It validates the
  provisioned product exists and returns a `SUCCEEDED` record (matching this codebase's
  validate-and-echo convention for unimplementable state transitions), but no field on
  the provisioned product itself changes — a real re-provisioning artifact/parameter
  change is not modelled.
- **`CopyProduct` copies product metadata only**, including provisioning-artifact ids and
  names. `SourceProvisioningArtifactIdentifiers` and `CopyOptions` in the request are not
  honoured — every artifact is carried over and no `CopyOption` is applied.
- **`DescribePortfolioShares` reports every share as `Accepted: true`.** Shares complete
  immediately, so there is no pending or declined state to report, and `ShareTagOptions` and
  `SharePrincipals` are always `false` because neither is modelled.
- **`AcceptPortfolioShare` and `RejectPortfolioShare` validate the portfolio exists and return
  successfully, but neither persists any state.** Acceptance is unconditional in this emulator —
  every share already reads back as `Accepted: true` (see above) — so reject is likewise a no-op
  beyond validation. There is no way to observe a rejected share through any other operation.
- **`DeletePortfolioShare` removes the share row keyed by `PortfolioId` + the resolved principal**
  (`ACCOUNT:<AccountId>` or `<OrganizationNode.Type>:<OrganizationNode.Value>`), the same key
  `CreatePortfolioShare`/`UpdatePortfolioShare` write. It does not accept `PortfolioShareToken` as
  an alternate lookup key, matching the request shape AWS documents for this operation.
- **`DescribePortfolioShares` does not paginate.** All shares for a portfolio are returned in
  one response and no `NextPageToken` is emitted, rather than advertising a token the emulator
  would not honour.
- **`DescribeProduct` omits `Budgets` and `LaunchPaths`.** Neither budgets nor launch paths are
  modelled, so the fields are absent rather than returned empty.
- **`DescribeProvisioningArtifact` omits `Info`.** The CloudFormation template URL map is not
  stored, and `Verbose` in the request has no effect.
- **`EnableAWSOrganizationsAccess` / `DisableAWSOrganizationsAccess` do not persist any state.**
  `GetAWSOrganizationsAccessStatus` always reports `ENABLED` regardless of which was last called.
- **Budget associations are name-only.** `AssociateBudgetWithResource` records the pairing of a
  `BudgetName` string with a resource; no Budgets service integration exists, so any name is
  accepted and no actual budget or cost data is checked or returned.
- **`ListServiceActions` returns every service action in the account.** There is no portfolio,
  product, or provisioning-artifact scoping — real AWS only lists actions associated with a
  given context. `DefinitionType` is always reported as `SSM_AUTOMATION`, matching AWS's only
  currently defined value for that enum.
- **`ListProvisioningArtifactsForServiceAction` always returns an empty list.** No association
  between service actions and provisioning artifacts is tracked for this specific query.
- **`ExecuteProvisionedProductPlan` completes synchronously and does not create an actual
  provisioned product.** It validates the plan exists and returns a `SUCCEEDED` record, but no
  `ProvisionedProduct` entry is created as a side effect — `DescribeProvisionedProduct` will not
  find anything from an executed plan.
- **`GetProvisionedProductOutputs` always returns an empty `Outputs` list.** No CloudFormation
  stack outputs are modelled or persisted for any provisioned product.
- **`DescribeRecord` only finds records from `ImportAsProvisionedProduct` and
  `ExecuteProvisionedProductServiceAction`.** Other record-producing operations
  (`TerminateProvisionedProduct`, `ProvisionProduct`) may still generate a `RecordId` in
  their response without persisting a lookup-able record — not yet investigated.
- **`ListServiceActionsForProvisioningArtifact` always returns an empty list.** No association
  between service actions and specific provisioning artifacts is tracked for this query (see
  `ListProvisioningArtifactsForServiceAction` above for the inverse case, same limitation).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SERVICECATALOG_ENABLED` | `true` | Enable or disable AWS Service Catalog |
