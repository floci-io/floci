# AWS Service Catalog

**Protocol:** AWS JSON 1.1  
**Signing name:** `servicecatalog`

Floci persists portfolios, products and provisioning-artifact metadata, TagOptions,
resource associations, provisioned products, and organization/account portfolio shares.
Portfolio-share operations complete immediately while preserving the AWS status-token
workflow.

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
| `ProvisionProduct` | Provision a product the catalog holds; the Control Tower Account Factory product creates a real Organizations member account and moves it to the requested OU, any other product persists a provisioned-product record only. |
| `SearchProvisionedProducts` | Search provisioned products; only `status:` and `physicalid:` search terms are honoured and results are not paginated. |
| `DeleteProduct` | Delete a product. |
| `CreateTagOption` | Create a TagOption key/value pair. |
| `UpdateTagOption` | Update a TagOption's value or active flag. |
| `DescribeTagOption` | Return a TagOption's details. |
| `ListTagOptions` | List all TagOptions. |
| `DeleteTagOption` | Delete a TagOption. |
| `AssociateProductWithPortfolio` | Associate a product with a portfolio. |
| `DisassociateProductFromPortfolio` | Remove a product from a portfolio; the delete is unconditional, so unknown ids still succeed. |
| `AssociateTagOptionWithResource` | Attach a TagOption to a portfolio or product. |
| `DisassociateTagOptionFromResource` | Detach a TagOption from a resource; the delete is unconditional, so unknown ids still succeed. |
| `UpdatePortfolioShare` | Create or overwrite a portfolio share with a fresh token; identical to `CreatePortfolioShare` and no existing share is required. |
| `CreatePortfolioShare` | Share a portfolio with an account or organization node; completes immediately. |
| `DescribePortfolioShareStatus` | Report the status of a share operation by its token; always terminal. |
| `DescribeProduct` | Return a product's consumer view; omits `Budgets` and `LaunchPaths`, and a `Name` given instead of `Id` is still resolved as an id. |
| `DescribePortfolioShares` | List a portfolio's shares; every share reports `Accepted: true` and no pagination token is emitted. |
| `ListPortfoliosForProduct` | List the portfolios a product is associated with. |
| `CopyProduct` | Copy a product's metadata synchronously; the returned copy token is already terminal. |
| `DescribeProvisioningArtifact` | Return a provisioning artifact's details; omits the template `Info` map. |
| `AcceptPortfolioShare` | Validate the shared portfolio exists; acceptance state is not persisted. |
| `DeletePortfolioShare` | Delete the share row keyed by portfolio and resolved principal. |
| `RejectPortfolioShare` | Validate the portfolio exists; rejection is a no-op beyond validation. |
| `AssociateBudgetWithResource` | Record a budget-name association with a resource; no Budgets service integration exists. |
| `AssociatePrincipalWithPortfolio` | Record an IAM principal association on a portfolio; the association is metadata only and grants no access. |
| `AssociateServiceActionWithProvisioningArtifact` | Store a service-action/provisioning-artifact association; no list operation ever reads it back. |
| `BatchAssociateServiceActionWithProvisioningArtifact` | Store several service-action associations in one call, reporting per-entry failures; no list operation ever reads them back. |
| `BatchDisassociateServiceActionFromProvisioningArtifact` | Remove multiple service-action associations in one call. |
| `CreateConstraint` | Create a constraint on a product/portfolio pair; `Parameters` is echoed back in the response but never persisted. |
| `CreateProvisionedProductPlan` | Create a provisioned product plan record; no `ResourceChanges` preview is computed. |
| `CreateServiceAction` | Create a self-service action definition. |
| `DeleteConstraint` | Delete a constraint. |
| `DeleteProvisionedProductPlan` | Delete a provisioned product plan. |
| `DeleteProvisioningArtifact` | Delete a provisioning artifact from a product. |
| `DeleteServiceAction` | Delete a service action. |
| `DescribeConstraint` | Return a constraint's details; `ConstraintParameters` is never returned. |
| `DescribeProductView` | Return a product's consumer view; product-view ids are not modelled, so `Id` must be a product or provisioning-artifact id. |
| `DescribeProvisionedProduct` | Return a provisioned product's details. |
| `DescribeProvisionedProductPlan` | Return a provisioned product plan's identity fields; no `Status` or `ResourceChanges` is reported. |
| `DescribeProvisioningParameters` | Return the provisioning parameters recorded on the product at creation; the response is empty if none were supplied. |
| `DescribeRecord` | Look up a record by id; `ProvisionProduct` and `ExecuteProvisionedProductPlan` return a `RecordId` they never persist, so those are not found. |
| `DescribeServiceActionExecutionParameters` | Return a service action's execution parameters; always an empty list. |
| `DisableAWSOrganizationsAccess` | Accept the disable request without persisting any state. |
| `DisassociateBudgetFromResource` | Remove a budget-name association from a resource. |
| `DisassociatePrincipalFromPortfolio` | Revoke a principal's access to a portfolio. |
| `DisassociateServiceActionFromProvisioningArtifact` | Remove a service action's association with a provisioning artifact. |
| `EnableAWSOrganizationsAccess` | Accept the enable request without persisting any state. |
| `ExecuteProvisionedProductPlan` | Mark a plan executed with a `SUCCEEDED` record; no provisioned product is created and the record is not persisted. |
| `ExecuteProvisionedProductServiceAction` | Persist a `SUCCEEDED` execution record against a provisioned product; the `ServiceActionId` is required but never resolved and no action runs. |
| `GetAWSOrganizationsAccessStatus` | Report the Organizations access status; always `ENABLED`. |
| `GetProvisionedProductOutputs` | Return a provisioned product's stack outputs; always an empty list. |
| `ImportAsProvisionedProduct` | Register the given `PhysicalId` as an `AVAILABLE` provisioned product and persist an `IMPORT` record; no CloudFormation stack is looked up. |
| `ListAcceptedPortfolioShares` | List portfolios; returns every portfolio, not just accepted shares. |
| `ListBudgetsForResource` | List the budget names associated with a resource. |
| `ListConstraintsForPortfolio` | List the constraints for a portfolio, optionally filtered by product. |
| `ListLaunchPaths` | Synthesise one launch path per portfolio the product is associated with, keyed by the portfolio's own id. |
| `ListOrganizationPortfolioAccess` | List the organization nodes a portfolio is shared with. |
| `ListPortfolioAccess` | List the account IDs a portfolio is shared with; `OrganizationParentId` is accepted but ignored. |
| `ListPrincipalsForPortfolio` | List the principals associated with a portfolio. |
| `ListProvisionedProductPlans` | List provisioned product plans with offset-based pagination; `PageSize` is capped at 20. |
| `ListProvisioningArtifactsForServiceAction` | List provisioning artifacts for a service action; always an empty list. |
| `ListRecordHistory` | Synthesise one `SUCCEEDED` record per stored provisioned product; the entries carry no `RecordId` and the real record store is not consulted. |
| `ListResourcesForTagOption` | List the resources a TagOption is attached to. |
| `ListServiceActions` | List every service action in the account, without context scoping. |
| `ListServiceActionsForProvisioningArtifact` | List service actions for a provisioning artifact; always an empty list. |
| `ListStackInstancesForProvisionedProduct` | Return one synthetic stack instance derived from the provisioned product; always exactly one, always `CURRENT`. |
| `NotifyProvisionProductEngineWorkflowResult` | Validate the workflow-result fields and return empty; nothing is recorded. |
| `NotifyTerminateProvisionedProductEngineWorkflowResult` | Validate the workflow-result fields and return empty; nothing is recorded. |
| `NotifyUpdateProvisionedProductEngineWorkflowResult` | Validate the workflow-result fields and return empty; nothing is recorded. |
| `ScanProvisionedProducts` | List all provisioned products with page-token pagination. |
| `UpdateConstraint` | Update a constraint's parameters or description. |
| `UpdateProvisionedProduct` | Validate the provisioned product exists and persist a `SUCCEEDED` record; no field on the product itself changes. |
| `UpdateProvisionedProductProperties` | Replace a provisioned product's properties wholesale and persist a record; property keys are not validated. |
| `UpdateServiceAction` | Update a service action's definition. |
| `CreateProvisioningArtifact` | Add a new provisioning artifact (version) to a product. |
| `DescribeCopyProductStatus` | Report a product-copy status; always `SUCCEEDED` immediately. |
| `TerminateProvisionedProduct` | Mark a provisioned product `TERMINATED` and persist a record; the row is kept and any account it created is left in the organization. |
| `UpdateProvisioningArtifact` | Rename a provisioning artifact; only `Name` is applied and the artifact is always reported `Active`. |
<!-- floci:actions:end -->

Floci lazily creates the managed **AWS Control Tower Account Factory Portfolio** and its
Account Factory product the first time `ListPortfolios`, `ListAcceptedPortfolioShares`,
`SearchProducts` or `ProvisionProduct` is called. `SearchProductsAsAdmin` does not trigger
this, so the Account Factory product is absent from the admin view until one of those
operations has run.
`ProvisionProduct` resolves the product named by `ProductId` or `ProductName` and rejects one the catalog does not hold with `ResourceNotFoundException`; a request naming neither is rejected with `InvalidParametersException`. A supplied `ProvisioningArtifactId` or `ProvisioningArtifactName` must belong to that product, and when neither is supplied the product's first artifact is used.

When the resolved product is the Account Factory product, `ProvisionProduct` creates a real Organizations member account through the Organizations service — reusing an existing account when `AccountEmail` already matches one — moves it under the OU named by the `ManagedOrganizationalUnit` provisioning parameter, and persists an `AVAILABLE` provisioned product of type `CONTROL_TOWER_ACCOUNT`. Organizational-unit display labels such as `Infrastructure (ou-...)` are resolved to their underlying OU IDs. Any other product persists an `AVAILABLE` provisioned product of type `CFN_STACK` and never touches Organizations.

## Limitations

- **`ProvisionProduct` provisions nothing outside the Account Factory path.** A product created with `CreateProduct` gets an `AVAILABLE` `CFN_STACK` provisioned product record and nothing else: no CloudFormation template is rendered, no stack exists, and the record carries no `PhysicalId`. Provisioning parameters are not applied or stored, and provisioning is idempotent on `ProvisionedProductName` — a repeat call returns the existing provisioned product unchanged, whichever product it names.
- **`TerminateProvisionedProduct` does not release anything.** It flips the provisioned
  product's `Status` to `TERMINATED` and persists a record, but the provisioned product row is
  kept (so it still appears in `ScanProvisionedProducts` and `SearchProvisionedProducts`) and
  an Organizations account created for it is left in place. `RetainPhysicalResources` and
  `IgnoreErrors` have no effect.
- **The three `Notify*EngineWorkflowResult` operations record nothing.** Each validates its
  fields — `Status` must be `SUCCEEDED` or `FAILED` — and returns an empty response. No
  workflow token, record, or status is stored, so a notified result is not observable
  afterwards through any operation.
- **`ListStackInstancesForProvisionedProduct` returns one synthetic instance.** No stack set
  or stack instances are modelled; the single entry is derived from the provisioned product
  itself — `Account` from its `PhysicalId`, `Region` parsed out of its ARN, and
  `StackInstanceStatus` always `CURRENT`.
- **Constraint parameters are never persisted.** `CreateConstraint` requires `Parameters` and
  echoes it back in its own response, but only the constraint's ids, type, owner and
  description are stored — `DescribeConstraint` and `ListConstraintsForPortfolio` never return
  `ConstraintParameters`. `UpdateConstraint` does store `Parameters`, but nothing reads them
  back either.
- **Service-action associations are write-only.** `AssociateServiceActionWithProvisioningArtifact`
  and its batch form validate the product and artifact and store an association (rejecting
  duplicates with `DuplicateResourceException`), and the disassociate operations remove it, but
  no operation reads it back — both `ListServiceActionsForProvisioningArtifact` and
  `ListProvisioningArtifactsForServiceAction` always return empty (see below).
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
  provisioned product exists and persists a `SUCCEEDED` record (matching this codebase's
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
- **`ListProvisioningArtifactsForServiceAction` always returns an empty list.** It validates
  that `ServiceActionId` was supplied and returns no views, even when associations for that
  service action were stored (see above).
- **`ExecuteProvisionedProductPlan` completes synchronously and does not create an actual
  provisioned product.** It validates the plan exists and returns a `SUCCEEDED` record, but no
  `ProvisionedProduct` entry is created as a side effect — `DescribeProvisionedProduct` will not
  find anything from an executed plan.
- **`GetProvisionedProductOutputs` always returns an empty `Outputs` list.** No CloudFormation
  stack outputs are modelled or persisted for any provisioned product.
- **Two operations return a `RecordId` they never persist.** `ImportAsProvisionedProduct`,
  `ExecuteProvisionedProductServiceAction`, `UpdateProvisionedProduct`,
  `UpdateProvisionedProductProperties` and `TerminateProvisionedProduct` all store a record
  that `DescribeRecord` can later find. `ProvisionProduct` and `ExecuteProvisionedProductPlan`
  mint a `RecordId` for their response only, so looking either one up afterwards raises
  `ResourceNotFoundException`.
- **`ListRecordHistory` does not read the record store.** It synthesises one `SUCCEEDED`
  entry per stored provisioned product, so the entries carry no `RecordId` and cannot be fed
  back into `DescribeRecord`, and records from operations that persisted one never appear.
  Request filters and pagination are ignored.
- **`ListServiceActionsForProvisioningArtifact` always returns an empty list.** It validates
  that the product and provisioning artifact exist and then returns no summaries, even when
  associations for that artifact were stored (the inverse case,
  `ListProvisioningArtifactsForServiceAction`, behaves the same way).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_SERVICECATALOG_ENABLED` | `true` | Enable or disable AWS Service Catalog |
