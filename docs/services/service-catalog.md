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
| Products | `CreateProduct`, `UpdateProduct`, `DescribeProduct`, `DescribeProductAsAdmin`, `SearchProductsAsAdmin`, `SearchProducts`, `CopyProduct`, `DeleteProduct` |
| Provisioning | `ListProvisioningArtifacts`, `DescribeProvisioningArtifact`, `ProvisionProduct`, `SearchProvisionedProducts` |
| TagOptions | `CreateTagOption`, `UpdateTagOption`, `DescribeTagOption`, `ListTagOptions`, `DeleteTagOption` |
| Associations | `AssociateProductWithPortfolio`, `DisassociateProductFromPortfolio`, `ListPortfoliosForProduct`, `AssociateTagOptionWithResource`, `DisassociateTagOptionFromResource` |
| Portfolio sharing | `CreatePortfolioShare`, `UpdatePortfolioShare`, `DescribePortfolioShares`, `DescribePortfolioShareStatus`, `DeletePortfolioShare`, `AcceptPortfolioShare`, `RejectPortfolioShare` |
| Organizations access | `EnableAWSOrganizationsAccess`, `DisableAWSOrganizationsAccess`, `GetAWSOrganizationsAccessStatus` |
| Budget associations | `AssociateBudgetWithResource`, `DisassociateBudgetFromResource`, `ListBudgetsForResource` |
| Constraints | `CreateConstraint`, `DescribeConstraint`, `UpdateConstraint`, `DeleteConstraint` |
| Service actions | `CreateServiceAction`, `UpdateServiceAction`, `DeleteServiceAction`, `ListServiceActions` |
| Principal/portfolio access | `AssociatePrincipalWithPortfolio`, `DisassociatePrincipalFromPortfolio`, `ListPrincipalsForPortfolio`, `ListPortfolioAccess`, `ListOrganizationPortfolioAccess` |
| Provisioning artifacts | `CreateProvisioningArtifact`, `UpdateProvisioningArtifact`, `DeleteProvisioningArtifact`, `ListProvisioningArtifactsForServiceAction` |
| Provisioned product plans | `CreateProvisionedProductPlan`, `DescribeProvisionedProductPlan`, `ExecuteProvisionedProductPlan`, `DeleteProvisionedProductPlan`, `ListProvisionedProductPlans` |
| Provisioned product queries | `ImportAsProvisionedProduct`, `GetProvisionedProductOutputs`, `ScanProvisionedProducts` |
| Engine workflow notifications | `NotifyProvisionProductEngineWorkflowResult`, `NotifyUpdateProvisionedProductEngineWorkflowResult`, `NotifyTerminateProvisionedProductEngineWorkflowResult` |
| Records and resource queries | `DescribeRecord`, `ListRecordHistory`, `ListResourcesForTagOption`, `ListStackInstancesForProvisionedProduct`, `ListServiceActionsForProvisioningArtifact` |
| Service action / artifact associations | `AssociateServiceActionWithProvisioningArtifact`, `DisassociateServiceActionFromProvisioningArtifact`, `BatchAssociateServiceActionWithProvisioningArtifact`, `BatchDisassociateServiceActionFromProvisioningArtifact` |
| Describe queries | `DescribeCopyProductStatus`, `DescribeProductView`, `DescribeProvisioningParameters`, `DescribeServiceActionExecutionParameters` |
| Provisioned product lifecycle | `DescribeProvisionedProduct`, `UpdateProvisionedProduct`, `UpdateProvisionedProductProperties`, `TerminateProvisionedProduct` |
| Portfolio queries | `ListAcceptedPortfolioShares`, `ListConstraintsForPortfolio`, `ListLaunchPaths` |

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
