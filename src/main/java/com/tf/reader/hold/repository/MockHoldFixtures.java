package com.tf.reader.hold.repository;

/**
 * Seeded fixture reference for manually testing join-the-queue against a real, running app.
 *
 * <p>Same idea as {@code auth.repository.MockInstitutionRepository} and {@code MockUserRepository}
 * — a documented seam onto data that already exists, not a bean of its own. Everything named here
 * comes from {@code DemoDataSeeder}, which runs automatically on every startup (idempotent — "X
 * already present" on a repeat run). Nothing here needs to be created by hand except one optional
 * entitlement, noted below, since the seed data alone has no institution with a plain copy limit.
 *
 * <p><b>Institutions.</b> {@code INSTITUTION_WITH_UNLIMITED_ENTITLEMENT} (inst_7f3, "Imperial
 * College London") has two seeded entitlements: one COLLECTION-scoped grant on
 * {@code col_law2024} with {@code copies: 2}, and one PUBLISHER-scoped grant on {@code pub_rtlg}
 * with no copy limit at all. Every ELITE item in the seed belongs to {@code pub_rtlg}, so this
 * institution always resolves to the unlimited grant — {@code join()} correctly answers
 * {@code 400 "no copy limit"} here. That is the seed data's shape, not a bug.
 *
 * <p>{@code INSTITUTION_WITH_NO_ENTITLEMENTS} (inst_ucl, "University College London") and
 * {@code inst_leeds} ("University of Leeds") have zero seeded entitlements — join() answers
 * {@code 403 NO_ENTITLEMENT} for either, on {@code item_42}. To see a genuine {@code 201}
 * instead, add this one entitlement for inst_ucl before joining:
 *
 * <pre>{@code
 * db.entitlements.insertOne({
 *   institutionId: 'inst_ucl',
 *   scopeType: 'COLLECTION',
 *   scopeId: 'col_law2024',
 *   copies: 3,
 *   loanPeriodDays: 14,
 *   validFrom: new Date('2026-07-31T18:30:00.000Z'),
 *   validTo: new Date('2026-12-30T18:30:00.000Z'),
 *   status: 'ACTIVE',
 *   version: 0,
 *   createdAt: new Date(),
 *   updatedAt: new Date()
 * });
 * }</pre>
 *
 * <p><b>{@code INSTITUTION_NOT_SEEDED} (inst_test)</b> isn't a real institution at all. Verified
 * behaviour: join() still answers {@code 403 NO_ENTITLEMENT}, not {@code 404} — the real
 * entitlement lookup treats "no institution" and "no grant" the same way, one honest sentence
 * rather than a distinction nothing downstream needs.
 *
 * <p><b>{@code ITEM_NOT_SEEDED} (item_test)</b> isn't a real catalogue item. Verified behaviour:
 * join() answers {@code 404 NOT_FOUND} for it regardless of institution — even
 * {@code INSTITUTION_WITH_UNLIMITED_ENTITLEMENT}, a real, entitled institution, gets 404 here.
 * So {@code 403 NO_ENTITLEMENT} vs {@code 404 NOT_FOUND} is decided entirely by whether the
 * item exists, not the institution — verified by isolating each variable separately, not
 * inferred from one combined case.
 *
 * <p><b>Items — verified, not assumed.</b> {@code ELITE_ITEM_ONE} (item_42) has
 * {@code contentState: READY} and is the one to use for every scenario above.
 * {@code ELITE_ITEM_TWO} (item_q7) has {@code contentState: QUEUED} — join() answers
 * {@code 409 CONTENT_NOT_READY} for it regardless of institution or entitlement. Keep it as a
 * fixture for that specific refusal path; do not swap it in expecting item_42's behaviour.
 *
 * <p><b>Getting a token.</b> {@code POST /api/v1/auth/dev-token?userId=...&institutionId=...}
 * issues a real, correctly-signed token — no hand-crafted JWTs needed.
 *
 * <p><b>Which database.</b> Despite {@code MONGODB_URI} naming {@code tnfreader}, this app
 * currently ends up reading/writing a database literally named {@code test} — a known,
 * unresolved Spring Boot / Docker Compose quirk, unrelated to hold's own code. Point any direct
 * {@code mongosh} inspection at {@code test}, not {@code tnfreader}, until that's tracked down.
 */
public final class MockHoldFixtures {

    public static final String INSTITUTION_WITH_UNLIMITED_ENTITLEMENT = "inst_7f3";
    public static final String INSTITUTION_WITH_NO_ENTITLEMENTS = "inst_ucl";
    public static final String INSTITUTION_WITH_NO_ENTITLEMENTS_ALT = "inst_leeds";
    public static final String INSTITUTION_NOT_SEEDED = "inst_test";

    public static final String ELITE_ITEM_ONE = "item_42";
    public static final String ELITE_ITEM_TWO = "item_q7";
    public static final String ITEM_NOT_SEEDED = "item_test";

    public static final String ENTITLED_COLLECTION = "col_law2024";
    public static final String ENTITLED_PUBLISHER = "pub_rtlg";

    private MockHoldFixtures() {
    }
}
