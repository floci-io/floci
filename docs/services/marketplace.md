# AWS Marketplace

Floci emulates AWS Marketplace APIs under the shared `aws-marketplace` SigV4 signing scope.

## Supported operations

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `BatchDescribeEntities` | Describes up to 20 catalog entities in one request |
| `CancelChangeSet` | Cancels a change set while it is preparing or applying |
| `DeleteResourcePolicy` | Deletes an entity resource policy |
| `DescribeAssessment` | Returns an assessment and control results |
| `DescribeChangeSet` | Returns change set status and change details |
| `DescribeEntity` | Returns the current entity revision |
| `GetResourcePolicy` | Returns an entity resource policy |
| `ListAssessments` | Lists Marketplace Catalog assessments |
| `ListChangeSets` | Lists change sets with pagination |
| `ListEntities` | Lists catalog entities by entity type |
| `ListTagsForResource` | Lists tags on a Marketplace Catalog resource |
| `PutResourcePolicy` | Creates or replaces an entity resource policy |
| `StartChangeSet` | Starts an idempotent Marketplace Catalog change set |
| `TagResource` | Adds or replaces tags on a Marketplace Catalog resource |
| `UntagResource` | Removes tag keys from a Marketplace Catalog resource |
| `AcceptAgreementCancellationRequest` | Accepts an agreement cancellation request |
| `AcceptAgreementPaymentRequest` | Accepts an agreement payment request |
| `AcceptAgreementRequest` | Accepts an agreement request |
| `BatchCreateBillingAdjustmentRequest` | Creates billing adjustment requests in a batch |
| `CancelAgreement` | Cancels an agreement |
| `CancelAgreementCancellationRequest` | Cancels an agreement cancellation request |
| `CancelAgreementPaymentRequest` | Cancels an agreement payment request |
| `CreateAgreementRequest` | Creates an agreement request |
| `DescribeAgreement` | Describes an agreement |
| `GetAgreementCancellationRequest` | Gets an agreement cancellation request |
| `GetAgreementEntitlements` | Gets entitlements for an agreement |
| `GetAgreementPaymentRequest` | Gets an agreement payment request |
| `GetAgreementTerms` | Gets accepted agreement terms |
| `GetBillingAdjustmentRequest` | Gets a billing adjustment request |
| `ListAgreementCancellationRequests` | Lists agreement cancellation requests |
| `ListAgreementCharges` | Lists agreement charges |
| `ListAgreementInvoiceLineItems` | Lists agreement invoice line items |
| `ListAgreementPaymentRequests` | Lists agreement payment requests |
| `ListBillingAdjustmentRequests` | Lists billing adjustment requests |
| `RejectAgreementCancellationRequest` | Rejects an agreement cancellation request |
| `RejectAgreementPaymentRequest` | Rejects an agreement payment request |
| `SearchAgreements` | Searches agreements |
| `SendAgreementCancellationRequest` | Sends an agreement cancellation request |
| `SendAgreementPaymentRequest` | Sends an agreement payment request |
| `UpdatePurchaseOrders` | Updates purchase orders for an agreement |
| `GetEntitlements` | - |
| `PutDeploymentParameter` | Creates or updates an AWS Marketplace deployment parameter |
| `GetBuyerDashboard` | Returns an embeddable AWS Marketplace buyer dashboard URL |
| `BatchMeterUsage` | Submits a batch of Marketplace usage records |
| `MeterUsage` | Submits metered usage for a Marketplace product |
| `RegisterUsage` | Registers container product usage and returns a signed token |
| `ResolveCustomer` | Resolves a Marketplace registration token to customer identity |
| `GetListing` | Returns full buyer-facing listing details |
| `GetOffer` | Returns an offer and its associated product information |
| `GetOfferSet` | Returns an offer set and its associated offers and products |
| `GetOfferTerms` | Returns the terms attached to an offer with pagination |
| `GetProduct` | Returns Marketplace product details |
| `ListFulfillmentOptions` | Lists fulfillment options available for a product |
| `ListPurchaseOptions` | Lists buyer purchase options with filtering and pagination |
| `SearchFacets` | Returns paginated facet values for matching listings |
| `SearchListings` | Searches listings with filtering, sorting, and pagination |
<!-- floci:actions:end -->

## Marketplace Catalog

Catalog change sets use AWS states (`PREPARING`, `APPLYING`, `SUCCEEDED`, and `CANCELLED`). Floci applies supported entity mutations locally when a change set is observed and persists entities, change sets, tags, resource policies, and assessments through `StorageFactory`, isolated by AWS account.

### Known deviations

- `ListEntities` rejects the `EntityTypeFilters` and `EntityTypeSort` request members with a `ValidationException`. AWS accepts these entity-type-specific filter and sort documents; Floci only supports the generic `FilterList` and `Sort` members.
- Change sets do not progress asynchronously. A change set stays in `PREPARING` until it is next observed through `DescribeChangeSet` or `ListChangeSets`, at which point Floci applies it and moves it straight to `SUCCEEDED` in the same call. The `APPLYING` state is never returned, and change sets never reach `FAILED`; `CancelChangeSet` only succeeds while a change set is still unobserved.
- The Marketplace Catalog API is served only in `us-east-1`, matching the single AWS endpoint. Requests signed for any other region are rejected with a `ValidationException` instead of being routed to a regional endpoint.

## Marketplace Agreement

Agreement request acceptance persists the resulting agreement and exposes it through subsequent read and search operations. State is isolated by AWS account through `StorageFactory`.

### Known deviations

- Accepted agreements use a local 365-day duration instead of deriving the end time from requested terms.
- `PartyType` is validated for AWS-compatible values but does not otherwise change which locally stored agreements match.

## Marketplace Entitlement

AWS exposes this service as read-only: `GetEntitlements` is the only public operation. Floci therefore does not add a non-AWS mutation endpoint. Entitlement records are loaded from the shared `StorageFactory` backend (`marketplace-entitlements.json` in persistent mode), so tests and local environments can pre-seed AWS-shaped entitlement state while preserving account isolation.


## Marketplace Deployment

Deployment parameters and idempotency records are persisted through `StorageFactory` and isolated by AWS account.

## Marketplace Reporting

Marketplace Reporting validates buyer dashboard requests and returns account-scoped local embed URLs for supported dashboard identifiers.

### Known deviations

- AWS limits `GetBuyerDashboard` to an AWS Organizations management account or a delegated administrator registered for procurement insights. Floci does not currently enforce that Organizations-role prerequisite.

## Marketplace Metering

Metering records and idempotency state are persisted through `StorageFactory`, isolated by AWS account and region. `RegisterUsage` produces locally signed PS256 JWTs with an emulator-generated RSA key.

## Marketplace Discovery

Marketplace Discovery reads the shared Marketplace Catalog entity backend and exposes listing, offer, purchase-option, facet, filtering, sorting, and pagination behavior across the supported Discovery regions.



## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MARKETPLACE_ENABLED` | `true` | Enable or disable AWS Marketplace emulation |

See the [AWS Marketplace API Reference](https://docs.aws.amazon.com/marketplace/latest/APIReference/).
