# Shared context: both teams read this

Kept short on purpose. It is loaded into every session, so every line costs tokens for everyone.
Detail lives in the handbook; this is the index.

## The system in six lines

The Taylor and Francis Reader is a prototype library app. Institutions buy access to packages of
books. A reader signs in through their institution, browses a catalogue, and reads or downloads.
The backend is **one Spring Boot process** with a module per capability, not microservices, so
cross-team calls are Java method calls rather than HTTP. Content bytes go from object storage to the
device directly and never pass through this process. Encrypted books are locked with a per-book key
that is wrapped to the reading device's public key, so nothing in the middle can read the file.

## Who owns what

| Team | Modules | Owns |
|---|---|---|
| **wokay** | `catalogue admin content crypto ingest` | the catalogue, the console, keys, ingest |
| **flambeau** | `auth loan hold reading library` | identity, loans, copy limits, reading sessions |
| team1 | no module | the reader app. Calls our HTTP feeds |
| t4targaryen | no module | the reader experience. Reads the feeds, decrypts on device |

## The two seams, and the rule about them

flambeau calls wokay through exactly two Java interfaces, in the same process:

```java
// com.tf.reader.catalogue.api
EntitlementDecision check(SubjectRef subject, String itemId);

// com.tf.reader.content.api
ContentGrant grant(ContentGrantRequest request);
```

**A module may import another module's `api/` package and nothing else.** Not `entity/`, not
`repository/`, not `service/`. Nothing physically stops you, which is why it has to be a test.

## Three kinds of book, one set of words

`OPEN_ACCESS`, `SUBSCRIPTION`, `ELITE`. The same three values appear as `accessTier` on the book, as
`licenceModel` in a feed, and in the `?accessTier=` filter. **There is nothing to translate.**

| Tier | Encrypted | Download | Copy limit |
|---|---|---|---|
| `OPEN_ACCESS` | no | yes | no |
| `SUBSCRIPTION` | yes | yes | no |
| `ELITE` | yes | **no, online only** | yes, with a queue |

**Audio is never encrypted, in any tier**, because whole-file encryption cannot seek and readers need
to scrub. Open access is never encrypted either, because a key handed to anonymous readers protects
nothing.

**Overridden for one dev fixture by t4targaryen, 2026-08-25.** `dev-sample-audio-encrypted`
(`ContentAccessGrantImpl`'s `AUDIO_ENCRYPTED_SMALL_FIXTURE`) is real encrypted audio, deliberately.
t4targaryen's client already decrypts every format whole-file into RAM under one shared budget
(`MAX_DECRYPTED_BYTES`); treating audio as "just another encrypted format" costs them nothing they
weren't already paying, below that cap. This does **not** revisit the reasoning above — it stays
true for anything that needs to seek during a from-scratch decrypt of something long, which is
exactly why this fixture stays small. Every other audio item is still covered by the rule; see
`DemoDataSeederTest.audioIsNeverEncrypted`'s one named exclusion.

## Four facts that are easy to get wrong

1. **wokay serves no content over HTTP.** Every acquisition link in every feed points at flambeau,
   who calls `ContentAccessGrant`. There is no `access-url` endpoint.
2. **Entitlement is resolved from the database on every request**, never from a token claim, so
   revoking access takes effect on the next request.
3. **Shelf order is preserved exactly.** MongoDB returns `$in` results in index order, so Java must
   re-sort after the query, and `?sort=` must be ignored on a shelf.
4. **Nine MongoDB collections**, not eight. `adminSessions` is the ninth and needs a TTL index.

## Admin auth, as built

Implemented on both sides and consistent with the contract. The five things a screen or an
endpoint has to get right:

| | |
|---|---|
| **Refresh token** | an `HttpOnly` cookie, `adminRefresh`, `Secure`, `SameSite=Strict`, path `/api/admin/v1/auth`. No script can read it, so **a console reload survives** |
| **Access token** | a JWT held in memory only, 15 minutes. Every admin request re-checks its `adminSessions` row, which is why logout is immediate |
| **CSRF** | **on for refresh and logout, exempt for login.** Spring writes a readable `XSRF-TOKEN` cookie; the caller must echo it as an `X-XSRF-TOKEN` header |
| **Cookie first, body fallback** | the body still carries `refreshToken` for a non-browser caller. Presenting neither is a `401`, not a `400` |
| **Rotation never extends** | the new cookie's `Max-Age` is what is left of the original twelve hours, not a fresh twelve |

**The cookie path is narrow on purpose.** Only refresh and logout ever need it, so no other admin
request carries it and no other endpoint can leak it.


## Where to read more

| For | Go to |
|---|---|
| Exact request and response shapes | `.claude/context/api-contract-digest.md` |
| The full contract, 32 paths | the `wokay-api.yaml` published with the handbook |
| Why any of this is the way it is | the handbook, 30 sections |
| Decision history and what reversed | `CONTEXT.md` in the wokay docs repo, section 4 |
| Encryption, our side | `crypto-docs/encryption-masterkeys.md` |
| Encryption, for the reader team | `crypto-docs/t4targaryen-encryption.md` |

**Do not read the full YAML or the handbook unless the digest is genuinely not enough.** They are
147 KB and 194 KB. Reading either one costs more than the rest of the session.
