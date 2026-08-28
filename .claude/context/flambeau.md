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
| `auth/` | Hemanth | SAML + OIDC sign-in, JWT issue/validate, `/auth/me`, `/dev-token` |
| `loan/` | Shashank Kumar Lal | Borrow, return, list, the expiry sweeper |
| `hold/` | Khushi Gupta | Join queue, offers, promotion, the offer sweeper, availability |
| `reading/` | Sai Deepak Varanasi | The read/download broker, the Redis copy lease, device cap, the reconciler |
| `library/` | Haripriya | The personal shelf, the change feed, the outbox |

Anything under `catalogue/ admin/ content/ crypto/ ingest/` is **wokay's**. Do not edit those files.
If something there blocks you, say so rather than fixing it — see `catalogue/api/` and `content/api/`
below for the two seams you're actually allowed to call into.

## The collections we own

```
devices  loans  holds  changeLog  changeSeq  changeLogOutbox
```

Ids are prefixed strings, matching wokay's convention: `lease_ hold_ loan_ sess_ authTxn_`. `devices`
is one document per reader (an array of observed fingerprints, not one document per device) — see
"things that bite" below for why.

## The two seams into wokay, published as code

**Only these two packages.** Importing `catalogue/entity`, `catalogue/repository`, `catalogue/service`,
or their `content/` and `admin/` equivalents directly is the mistake the `api/` package exists to
prevent — nothing stops it at compile time, so it's a review-time check.

```java
// com.tf.reader.catalogue.api
EntitlementDecision check(SubjectRef subject, String itemId);

// com.tf.reader.content.api
ContentGrant grant(ContentGrantRequest request);
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
3. **`reading`'s reconciler rebuilds Redis lease state from Mongo on every app startup** — active
   ELITE loans (`loan.api.ActiveLoanQuery.findAllActiveElite()`) plus live hold offers
   (`hold.api.LiveOfferQuery.findAllLiveOffers()`). It does not wipe-and-replace: an in-flight
   `claim()` with no DB row yet (mid-request, not yet committed) is deliberately left alone inside
   the claim's TTL window, or a concurrent reconcile could evict a slot that's about to be
   legitimately spent. **`hold` has no equivalent reconciler yet** for its own queue-side Redis
   drift — a `holds.reconcile-interval` config property exists with nothing implementing it.
4. **Token audiences.** App tokens (`aud=tf-app`) and admin tokens (`aud=tf-admin`) are structurally
   different — see wokay's admin session/claim work before assuming a token shape.
5. **No refresh token exists today.** `GET /api/v1/auth/me` re-issues a fresh 1-hour JWT on every
   call; that's the entire "refresh" mechanism. A proposed design (`ReaderSession`, `/auth/refresh`,
   `/auth/logout`, a one-time-code exchange) exists as a doc but **nothing in it is built** — don't
   assume it's live.
6. **`/auth/dev-token` is a full auth bypass if reachable outside dev/test** — mints a signed JWT
   for any `userId`/`institutionId`, no checks at all. Same risk category as the mock SAML/OIDC
   provider controllers (`saml-mock.enabled` / `mock-oidc.enabled`, both off by default).
7. **Real sign-in currently resolves against a hardcoded 4-user list** (`MockUserRepository`), not a
   real Mongo-backed directory. `ReaderUserRepository`/`ReaderUser` — the real thing — are empty
   stub files. A real institutional user cannot sign in today.
8. **The weekly fork sync.** Same caveat as wokay: this repo is a fork, and context files reach it on
   the weekly sync, not immediately.

## Our HTTP surface, in one glance

| Group | Count | Token |
|---|---|---|
| `auth` (sign-in, `/me`, `/dev-token`) | 6 | none for sign-in start/callback, app token for `/me` |
| `loan` (`/api/v1/loans**`) | 3 | app token |
| `hold` (`/api/v1/holds**`, availability) | 5 | app token |
| `reading` (`/api/v1/reading-sessions`) | 1 | app token |
| `library` (shelf, change feed) | 2 | app token |

Full request/response shapes are in `documents/api-endpoints-till-week-3.md`. A readiness/gap
assessment per endpoint is in `documents/flambeau-week3-progress-report.md`.

## Branch naming

`<firstname>/flambeau/<feature>`, matching what's already in use: `khushi/hold`,
`shashank-loan`, `read-access-and-concurrency`.

## Current known gaps

Worth knowing so you don't rediscover them:

- `loan.api.LoanRights` — empty placeholder class, no implementation, no consumers.
- `hold.api.HoldPromotion.promote(itemId)` carries no scope parameter, so a promotion triggered by a
  loan return always does a fresh Redis claim rather than the safer atomic reassignment same-module
  cancel/lapse paths get. Needs a signature change, agreed jointly between `loan` and `hold`.
- `hold`'s own Redis/Mongo drift has no reconciler (see "things that bite" #3).
- `AvailabilitySnapshot.myPosition` is declared, never populated.
- `library`'s change-feed path (`GET /api/v1/loans/changes`) is acknowledged-wrong in its own code
  comment — should probably be `/api/v1/changes`. Deferred, not yet fixed.
- Whether `reading` ever emits `ENTITLEMENT_REVOKED` into the change feed is still an open question
  (matters for downloaded/offline titles that never call back into the broker on revocation).
- `auth.api.SessionQuery`/`SessionView` are explicitly marked **PROPOSED**, not frozen — `library`
  already depends on them existing eventually but currently reaches into `auth.model` directly as a
  documented workaround.

Read `.claude/context/shared.md` and `docs/FORK-SYNC.md` as well.
