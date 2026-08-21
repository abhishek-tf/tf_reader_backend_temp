# API contract digest

**GENERATED FILE. Do not edit.** Run `./scripts/gen-api-digest.sh` after the contract changes.
Source `api-docs/wokay-api.yaml`, generated 2026-08-20.

44 operations across 32 paths, 81 schemas. **`FROZEN` means another team is already building against it: changing one needs a cohort conversation.**

## Public institutions

| | Path | Stability | Called by |
|---|---|---|---|
| GET | `/api/v1/institutions` | FROZEN | team1 |
| GET | `/api/v1/institutions/{institutionId}` | FROZEN | team1, flambeau |

## OPDS institution

| | Path | Stability | Called by |
|---|---|---|---|
| GET | `/opds/v1/institutions/{institutionId}/catalogue` | FROZEN | team1 |
| GET | `/opds/v1/institutions/{institutionId}/groups/{groupId}` | FROZEN | team1 |
| GET | `/opds/v1/institutions/{institutionId}/search` | FROZEN | team1 |
| GET | `/opds/v1/institutions/{institutionId}/publications/{itemId}` | FROZEN | team1 |

## OPDS public

| | Path | Stability | Called by |
|---|---|---|---|
| GET | `/opds/v1/public/catalogue` | FROZEN | team1 |
| GET | `/opds/v1/public/search` | DRAFT | team1 |
| GET | `/opds/v1/public/publications/{itemId}` | DRAFT | team1 |

## App

| | Path | Stability | Called by |
|---|---|---|---|
| POST | `/api/v1/catalogue/items:batch` | FROZEN | flambeau, team1 |

## Admin

| | Path | Stability | Called by |
|---|---|---|---|
| POST | `/api/admin/v1/auth/login` | FROZEN | wokay |
| POST | `/api/admin/v1/auth/refresh` | DRAFT | wokay |
| POST | `/api/admin/v1/auth/logout` | DRAFT | wokay |
| GET | `/api/admin/v1/auth/me` | FROZEN | wokay |
| GET | `/api/admin/v1/publishers` | DRAFT | wokay |
| POST | `/api/admin/v1/publishers` | DRAFT | wokay |
| GET | `/api/admin/v1/publishers/{publisherId}` | DRAFT | wokay |
| PUT | `/api/admin/v1/publishers/{publisherId}` | DRAFT | wokay |
| PATCH | `/api/admin/v1/publishers/{publisherId}/status` | DRAFT | wokay |
| GET | `/api/admin/v1/publishers/{publisherId}/collections` | DRAFT | wokay |
| POST | `/api/admin/v1/publishers/{publisherId}/collections` | DRAFT | wokay |
| PUT | `/api/admin/v1/collections/{collectionId}/items` | DRAFT | wokay |
| GET | `/api/admin/v1/catalogue-items` | DRAFT | wokay |
| POST | `/api/admin/v1/catalogue-items` | DRAFT | wokay |
| GET | `/api/admin/v1/catalogue-items/{itemId}` | DRAFT | wokay |
| PUT | `/api/admin/v1/catalogue-items/{itemId}` | DRAFT | wokay |
| POST | `/api/admin/v1/catalogue-items/{itemId}/content` | DRAFT | wokay |
| GET | `/api/admin/v1/catalogue-items/{itemId}/ingest-status` | DRAFT | wokay |
| GET | `/api/admin/v1/institutions` | DRAFT | wokay |
| POST | `/api/admin/v1/institutions` | DRAFT | wokay |
| GET | `/api/admin/v1/institutions/{institutionId}` | DRAFT | wokay |
| PUT | `/api/admin/v1/institutions/{institutionId}` | DRAFT | wokay |
| PATCH | `/api/admin/v1/institutions/{institutionId}/status` | DRAFT | wokay |
| GET | `/api/admin/v1/institutions/{institutionId}/entitlements` | DRAFT | wokay |
| POST | `/api/admin/v1/institutions/{institutionId}/entitlements` | DRAFT | wokay |
| PUT | `/api/admin/v1/entitlements/{entitlementId}` | DRAFT | wokay |
| DELETE | `/api/admin/v1/entitlements/{entitlementId}` | DRAFT | wokay |
| GET | `/api/admin/v1/institutions/{institutionId}/feed-settings` | DRAFT | wokay |
| PUT | `/api/admin/v1/institutions/{institutionId}/feed-settings` | DRAFT | wokay |
| GET | `/api/admin/v1/admin-users` | DRAFT | wokay |
| POST | `/api/admin/v1/admin-users` | DRAFT | wokay |
| PUT | `/api/admin/v1/admin-users/{adminUserId}` | DRAFT | wokay |
| DELETE | `/api/admin/v1/admin-users/{adminUserId}` | DRAFT | wokay |
| GET | `/api/admin/v1/audit-logs` | DRAFT | wokay |

## Enums a client switches on

- **`AccessTier`**: OPEN_ACCESS, SUBSCRIPTION, ELITE
- **`ItemStatus`**: DRAFT, PUBLISHED, ARCHIVED
- **`ContentState`**: NONE, QUEUED, PROCESSING, READY, FAILED
- **`AdminRole`**: SUPER_ADMIN, PUBLISHER_ADMIN, INSTITUTION_ADMIN
- **`EntitlementScope`**: PUBLISHER, COLLECTION, ITEM
- **`EntitlementStatus`**: ACTIVE, SUSPENDED, REVOKED
- **`SortOrder`**: publishedAt.desc, publishedAt.asc, title.asc, title.desc
- **`ContentType`**: PDF, EPUB, AUDIO
- **`Intent`**: STREAM, DOWNLOAD

## Error codes, all of them

`UNAUTHENTICATED`, `FORBIDDEN_SCOPE`, `FORBIDDEN_INSTITUTION_MISMATCH`, `NO_ENTITLEMENT`, `CONTENT_NOT_READY`, `DOWNLOAD_NOT_PERMITTED`, `NOT_FOUND`, `CODE_TAKEN`, `TOO_MANY_IDS`, `VALIDATION_FAILED`, `STALE_VERSION`

Every one is reachable. There are no spare codes, so do not write a handler for a code that is not in this list.

## Auth mechanics

| | |
|---|---|
| **Refresh token delivery** | an **`HttpOnly` cookie** named `adminRefresh`, set by login and rotated by refresh |
| **Why a cookie** | a store JavaScript can read is a store an XSS can read. `HttpOnly` is the only one it cannot, so **a console reload survives** without the token ever entering JavaScript |
| **Cookie attributes** | `adminRefresh=8Kd2mXqR7vT1nP4wZ0aB3cE6gH9jL5sY; HttpOnly; Secure; SameSite=Strict; Path=/api/admin/v1/auth; Max-Age=43200` |
| **Reading it from JS** | **you cannot, and must not try.** The browser sends it on its own. Call refresh with no body and let the cookie do the work |
| **Body fallback** | `refreshToken` in the body still works, for a non-browser caller. Neither is required; presenting neither is a `401`, not a `400` |

## Schemas

`Error`, `ErrorCode`, `PageMeta`, `RecordStatus`, `SortOrder`, `Isbn`, `ContentType`, `AssetFormat`, `AccessTier`, `ItemStatus`, `EntitlementScope`, `EntitlementStatus`, `Intent`, `InstitutionType`, `AdminRole`, `ContentState`, `StatusChange`, `InstitutionSummary`, `InstitutionPage`, `Branding`, `SignIn`, `SignInWrite`, `InstitutionDetail`, `OpdsLink`, `OpdsNavigationLink`, `OpdsPublicationLink`, `OpdsImageLink`, `OpdsLinkProperties`, `EncryptedInfo`, `OpdsFeedMetadata`, `OpdsGroupMetadata`, `OpdsNavigationFeed`, `OpdsPublicationFeed`, `OpdsGroup`, `OpdsPublicationDocument`, `OpdsPublication`, `OpdsContributor`, `OpdsPublicationMetadata`, `BatchItemsRequest`, `BatchItem`, `BatchItemsResponse`, `ContentGrantRequest`, `SubjectRef`, `LoanProof`, `SignedUrl`, `IndexUrl`, `Encryption`, `ContentGrant`, `AdminLoginRequest`, `TokenPair`, `AdminLoginResponse`, `RefreshResponse`, `RefreshRequest`, `AdminSession`, `AdminUser`, `AdminUserPage`, `AdminUserCreate`, `AdminUserUpdate`, `Publisher`, `PublisherWrite`, `PublisherPage`, `Collection`, `CollectionPage`, `CollectionWrite`, `Asset`, `CatalogueItem`, `CatalogueItemWrite`, `CatalogueItemPage`, `IngestStatus`, `AdminInstitution`, `AdminInstitutionPage`, `InstitutionWrite`, `Entitlement`, `EntitlementPage`, `EntitlementCreate`, `EntitlementUpdate`, `Shelf`, `FeedSettings`, `FeedSettingsWrite`, `AuditLog`, `AuditLogPage`

## When this is not enough

Read the relevant part of `api-docs/wokay-api.yaml` for exact field names, required flags and examples. **Do not read the whole file**: it is about 147 KB, and grep or a targeted read is always cheaper.
