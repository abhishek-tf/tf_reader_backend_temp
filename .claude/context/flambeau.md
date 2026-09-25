# flambeau context

You are working as a member of **team flambeau**. Five people: Sai Deepak Varanasi, Shashank Kumar
Lal, Khushi Gupta (`Ks-Gupta` / `KHUSHI GUPTA` in git history), Hemanth (`hemanthb1412`),
Haripriya (`hariii-1122`).

## What we own

**CAP-4 and CAP-6** — the reading/lending lifecycle and reader identity. That makes us the team a
reader actually interacts with end to end: sign in, browse (wokay's feeds), borrow or queue, read or
download, see it on a shelf.

| Module | Owner | Holds |
|---|---|---|
| `auth/` | Hemanth | SAML + OIDC sign-in, JWT issue/validate, session lifecycle, `/auth/me`, `/dev-token` |
| `loan/` | Shashank Kumar Lal | Borrow, return, list, the expiry sweeper |
| `hold/` | Khushi Gupta | Join queue, offers, promotion, the offer sweeper, availability, queue reconciler |
| `reading/` | Sai Deepak Varanasi | The read/download broker, the Redis copy lease, device cap, the lease reconciler, `/ops/reconcile` |
| `library/` | Haripriya | The personal shelf, the change feed, the outbox |

Anything under `catalogue/ admin/ content/ crypto/ ingest/` is **wokay's**. Do not edit those files.
If something there blocks you, say so rather than fixing it — see `catalogue/api/` and `content/api/`
below for the two seams you're actually allowed to call into.

## The collections we own

```
readerSessions  devices  loans  holds  changeLog  changeSeq  changeLogOutbox
```

Ids are prefixed strings, matching wokay's convention: `rsess_ lease_ hold_ loan_ sess_ authTxn_ offer_`.
`devices` is one document per reader (an array of observed fingerprints, not one document per device).

## The two seams into wokay, published as code

**Only these two packages.** Importing `catalogue/entity`, `catalogue/repository`, `catalogue/service`,
or their `content/` and `admin/` equivalents directly is the mistake the `api/` package exists to
prevent — nothing stops it at compile time, so it's a review-time check.

```java
// com.tf.reader.catalogue.api
EntitlementDecision check(SubjectRef subject, String itemId);
Map<String, EntitlementDecision> checkAll(SubjectRef subject, List<String> itemIds);

// com.tf.reader.content.api
ContentGrant grant(ContentGrantRequest request);  // partNumber field added for chapter-level access
```

`EntitlementQuery` is called from `loan` (borrow), `reading` (every read/download), and `hold` (join,
wait-estimate) — it is the single most-imported wokay seam in this codebase. `ContentAccessGrant` is
called from `reading` only, at the last step of the broker, after a licence already exists.

## Things that bite

1. **The Redis copy lease is the one invariant everyone bends around.** `reading.api.CopyLease`
   (`claim`/`extend`/`release`/`reassign`) is the only thing allowed to touch a `lease:{scope}:{itemId}`
   ZSET. `loan` calls it on borrow/return/expiry; `hold` calls it on promotion. If you're touching a
   copy-limited title's count anywhere else, you're building a second, competing invariant.
2. **The hold queue lives in Redis, not Mongo.** `QueueService` reads position via `ZRANK` on
   `queue:{scope}:{itemId}`, never from a stored field on the `Hold` document — there deliberately
   is no `position` column. If Redis and Mongo disagree, `GET /api/v1/holds` throws a loud `500`
   rather than guessing; that's intentional, not a bug to paper over.
3. **Both reconcilers run at startup and on a schedule.**
   - `ReconcilerService` (`reading`) rebuilds Redis lease ZSET from active ELITE loans and live
     hold offers on every app start (via `@EventListener(ApplicationReadyEvent.class)`) and is
     also callable on-demand from `POST /api/v1/ops/reconcile`.
   - `QueueReconciler` (`hold`) reconciles Redis queue ZSETs from Mongo holds every
     `holds.reconcile-interval` (default 5 minutes). It reads Redis before Mongo to avoid evicting
     a hold that is mid-join.
4. **Token audiences.** App tokens (`aud=tf-app`, 1-hour TTL) and admin tokens (`aud=tf-admin`) are
   structurally different. `/api/v1/ops/**` accepts admin tokens (same chain as `/api/admin/**`).
5. **Session lifecycle is real — refresh tokens exist.** `ReaderSession` entities are stored in Mongo.
   Refresh tokens are 256-bit random values stored as SHA-256 fingerprints (never the raw token).
   `/auth/refresh` rotates the refresh token; `/auth/logout` revokes it. GET `/auth/me` re-issues an
   access token from the verified refresh token, not from the access token itself.
6. **`/auth/dev-token` is a full auth bypass if reachable outside dev/test** — mints a signed JWT
   for any `userId`/`institutionId`, no checks at all. `tnf.dev-auth.enabled` must be explicitly
   `true`; it is off in all deployed profiles.
7. **Real sign-in uses `ReaderUserDirectory` backed by Mongo.** Institutional users are resolved from
   the `readerUsers` collection and provisioned automatically on first sign-in. `MockUserRepository`
   is gone. Individual (B2C) users are provisioned on first OIDC login via `findOrProvisionIndividual`.
8. **The weekly fork sync.** Same caveat as wokay: this repo is a fork, and context files reach it on
   the weekly sync, not immediately.

## Our HTTP surface, in one glance

| Group | Count | Token |
|---|---|---|
| `auth` (sign-in, refresh, logout, `/me`, `/dev-token`) | 8 | none for sign-in/token/refresh/logout, app token for `/me` |
| `loan` (`/api/v1/loans/**`) | 3 | app token |
| `hold` (`/api/v1/holds/**`, availability) | 5 | app token |
| `reading` (`/api/v1/reading-sessions`) | 1 | app token |
| `library` (shelf, change feed at `/api/v1/changes`) | 2 | app token |
| `ops` (`/api/v1/ops/reconcile`) | 1 | **admin token** (`tf-admin`) |

## Branch naming

`<firstname>/flambeau/<feature>`, matching what's already in use: `khushi/hold`,
`shashank-loan`, `read-access-and-concurrency`.

## Current known gaps

- `loan.api.LoanRights` — empty placeholder class, no implementation, no consumers.
- Per-item targeted queries in `ActiveLoanQuery` and `LiveOfferQuery` are not yet exposed
  (`findActiveEliteByItem` / `findByItem`), so `ReconcilerService.reconcile(itemId)` currently
  does a full scan instead of a scoped one. Tracked as TODO(2026-W5) in the service comment.

Read `.claude/context/shared.md` as well.
