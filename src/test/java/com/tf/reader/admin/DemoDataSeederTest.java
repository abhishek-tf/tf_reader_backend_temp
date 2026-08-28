package com.tf.reader.admin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.tf.reader.admin.service.SeedDataset;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8, unit level: tests the dataset on its own (no Spring, Mongo, Docker, or Person B's
 * entities needed) for silent data bugs like typo'd references, inconsistent timestamps, or
 * unlicensed shelf entries.
 */
class DemoDataSeederTest {

    private static final String DATASET = "seed/demo-dataset.json";
    private static final String DAY1_CANONICAL = "seed/canonical-items.json";

    /** Every timestamp in the dataset is this literal. Nothing is generated at load time. */
    private static final Instant SEED_EPOCH = Instant.parse("2026-08-10T09:00:00Z");

    /**
     * These six are not week 1 placeholders like the other eight items: their ids and their
     * {@code storageKey}s are matched by exact string equality in
     * {@code ContentAccessGrantImpl}, against real AES-256-GCM fixture files under
     * {@code src/main/resources/static/mock-content/}. The {@code item_}/{@code seed/}
     * conventions below don't apply to them - renaming either would silently disconnect them
     * from working content, not just fail a naming check.
     */
    private static final Set<String> DEV_CONTENT_FIXTURE_ITEM_IDS =
            Set.of(
                    "dev-sample-epub",
                    "dev-sample-pdf",
                    "dev-sample-audio",
                    "dev-sample-audio-encrypted",
                    "dev-fixture-epub",
                    "dev-fixture-pdf");

    private static ObjectMapper mapper;
    private static SeedDataset dataset;
    private static JsonNode raw;

    @BeforeAll
    static void parseOnce() throws IOException {
        // Jackson 3. java.time support is built into databind now, so there is no JavaTimeModule
        // to register: tools/jackson/databind/ext/javatime is part of the core jar.
        mapper = JsonMapper.builder().build();
        dataset = mapper.readValue(open(DATASET), SeedDataset.class);
        raw = mapper.readTree(open(DATASET));
    }

    private static InputStream open(String path) {
        InputStream in = DemoDataSeederTest.class.getClassLoader().getResourceAsStream(path);
        assertThat(in).as("classpath resource %s", path).isNotNull();
        return in;
    }

    @Test
    @DisplayName("the dataset is exactly the set of documents this task writes")
    void datasetParsesAndMatchesExpectedCounts() {
        assertThat(dataset.publishers()).hasSize(2);
        assertThat(dataset.collections()).hasSize(2);
        assertThat(dataset.institutions()).hasSize(3);
        // Eight week 1 placeholders plus the six real dev-content fixtures (see
        // DEV_CONTENT_FIXTURE_ITEM_IDS) that ContentAccessGrantImpl routes to real files.
        assertThat(dataset.catalogueItems()).hasSize(14);
        assertThat(dataset.entitlements()).hasSize(3);
        assertThat(dataset.adminUsers()).hasSize(3);
        assertThat(dataset.feedSettings()).hasSize(3);

        // One number, so an extra row cannot be added without someone updating the plan too.
        assertThat(dataset.documentCount()).isEqualTo(30);
    }

    @Test
    @DisplayName("every id is unique and carries its collection's prefix")
    void datasetIdsAreUniqueAndPrefixed() {
        assertPrefixed(ids(dataset.publishers(), SeedDataset.SeedPublisher::id), "pub_");
        assertPrefixed(ids(dataset.collections(), SeedDataset.SeedCollection::id), "col_");
        assertPrefixed(ids(dataset.institutions(), SeedDataset.SeedInstitution::id), "inst_");

        // The six dev-content fixtures are matched by exact id in ContentAccessGrantImpl, not
        // prefixed like the rest - carved out rather than renamed, see
        // DEV_CONTENT_FIXTURE_ITEM_IDS. Checked against the known set explicitly, so a future
        // item with a genuine typo'd prefix doesn't slip through this exception by accident.
        List<String> itemIds = ids(dataset.catalogueItems(), SeedDataset.SeedItem::id);
        Set<String> unprefixed =
                itemIds.stream().filter(id -> !id.startsWith("item_")).collect(Collectors.toSet());
        assertThat(unprefixed).isEqualTo(DEV_CONTENT_FIXTURE_ITEM_IDS);

        assertPrefixed(ids(dataset.entitlements(), SeedDataset.SeedEntitlement::id), "ent_");
        assertPrefixed(ids(dataset.adminUsers(), SeedDataset.SeedAdminUser::id), "adm_");
        assertPrefixed(ids(dataset.feedSettings(), SeedDataset.SeedFeedSettings::id), "fs_");

        assertThat(allIds()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every cross reference points at a row that exists in the same file")
    void datasetReferencesResolve() {
        // Mongo has no foreign keys, so most of these are completely silent. The exception is
        // publisherId: B's CatalogueItemPersistenceGuard rejects an unknown one at insert time, so
        // that single case fails loudly instead of seeding an unreachable book.
        Set<String> publisherIds = new HashSet<>(ids(dataset.publishers(), SeedDataset.SeedPublisher::id));
        Set<String> collectionIds = new HashSet<>(ids(dataset.collections(), SeedDataset.SeedCollection::id));
        Set<String> institutionIds = new HashSet<>(ids(dataset.institutions(), SeedDataset.SeedInstitution::id));
        Set<String> itemIds = new HashSet<>(ids(dataset.catalogueItems(), SeedDataset.SeedItem::id));

        for (SeedDataset.SeedCollection c : dataset.collections()) {
            assertThat(publisherIds).as("collection %s publisherId", c.id()).contains(c.publisherId());
        }

        for (SeedDataset.SeedItem i : dataset.catalogueItems()) {
            assertThat(publisherIds).as("item %s publisherId", i.id()).contains(i.publisherId());
            assertThat(collectionIds).as("item %s collectionIds", i.id()).containsAll(i.collectionIds());

            // A book may only sit in a collection owned by its own publisher.
            for (String cid : i.collectionIds()) {
                String owner =
                        dataset.collections().stream()
                                .filter(c -> c.id().equals(cid))
                                .findFirst()
                                .orElseThrow()
                                .publisherId();
                assertThat(owner)
                        .as("item %s is in collection %s, owned by a different publisher", i.id(), cid)
                        .isEqualTo(i.publisherId());
            }
        }

        for (SeedDataset.SeedEntitlement e : dataset.entitlements()) {
            assertThat(institutionIds).as("entitlement %s institutionId", e.id()).contains(e.institutionId());
            Set<String> valid =
                    switch (e.scopeType()) {
                        case "PUBLISHER" -> publisherIds;
                        case "COLLECTION" -> collectionIds;
                        case "ITEM" -> itemIds;
                        default -> throw new AssertionError("unknown scopeType " + e.scopeType());
                    };
            assertThat(valid).as("entitlement %s scopeId", e.id()).contains(e.scopeId());
        }

        for (SeedDataset.SeedAdminUser u : dataset.adminUsers()) {
            if (u.publisherId() != null) {
                assertThat(publisherIds).as("admin %s publisherId", u.id()).contains(u.publisherId());
            }
            if (u.institutionId() != null) {
                assertThat(institutionIds).as("admin %s institutionId", u.id()).contains(u.institutionId());
            }
        }

        for (SeedDataset.SeedFeedSettings f : dataset.feedSettings()) {
            assertThat(institutionIds).as("feedSettings %s institutionId", f.id()).contains(f.institutionId());
            for (SeedDataset.SeedShelf s : f.shelves()) {
                assertThat(itemIds).as("%s %s itemIds", f.id(), s.id()).containsAll(s.itemIds());
            }
        }
    }

    @Test
    @DisplayName("the eight canonical books still agree with the frozen day 1 fixtures")
    void datasetAgreesWithDay1Fixtures() throws IOException {
        // team1 built their browse screens against these ids, titles and byte lengths in week 1.
        // If the seed and the fixtures drift, their mocked screen shows different books than the real
        // API and nobody finds out until week 4. This is the guard.
        JsonNode canonical = mapper.readTree(open(DAY1_CANONICAL));
        Map<String, SeedDataset.SeedItem> seeded =
                dataset.catalogueItems().stream()
                        .collect(Collectors.toMap(SeedDataset.SeedItem::id, i -> i));

        int checked = 0;
        for (JsonNode c : canonical.get("items")) {
            String id = c.get("id").asText();
            SeedDataset.SeedItem s = seeded.get(id);
            assertThat(s).as("canonical book %s is missing from the seed", id).isNotNull();

            assertThat(s.title()).as("%s title", id).isEqualTo(c.get("title").asText());
            assertThat(s.publisherId()).as("%s publisherId", id).isEqualTo(c.get("publisherId").asText());
            assertThat(s.accessTier()).as("%s accessTier", id).isEqualTo(c.get("accessTier").asText());
            assertThat(s.contentType()).as("%s contentType", id).isEqualTo(c.get("contentType").asText());
            assertThat(s.contentState()).as("%s contentState", id).isEqualTo(c.get("contentState").asText());

            // The fixture calls the plaintext length originalLength; the entity calls it sizeBytes.
            List<JsonNode> canonicalAssets = new ArrayList<>();
            c.get("assets").forEach(canonicalAssets::add);
            assertThat(s.assets()).as("%s asset count", id).hasSameSizeAs(canonicalAssets);
            for (int a = 0; a < canonicalAssets.size(); a++) {
                assertThat(s.assets().get(a).sizeBytes())
                        .as("%s asset %d byte length", id, a)
                        .isEqualTo(canonicalAssets.get(a).get("originalLength").asLong());
                assertThat(s.assets().get(a).encrypted())
                        .as("%s asset %d encrypted", id, a)
                        .isEqualTo(canonicalAssets.get(a).get("encrypted").asBoolean());
            }
            checked++;
        }
        assertThat(checked).as("all eight canonical books were checked").isEqualTo(8);
    }

    @Test
    @DisplayName("cipherLength is 12 + sizeBytes + 16 wherever the asset is encrypted, and null otherwise")
    void cipherLengthArithmetic() {
        // Handbook revision 7 shipped this wrong once by counting the nonce twice. Compute it, do not
        // eyeball it.
        for (SeedDataset.SeedItem i : dataset.catalogueItems()) {
            for (SeedDataset.SeedAsset a : i.assets()) {
                if (a.encrypted()) {
                    assertThat(a.cipherLength())
                            .as("%s %s cipherLength", i.id(), a.format())
                            .isEqualTo(12 + a.sizeBytes() + 16);
                    assertThat(a.keyId()).as("%s %s keyId", i.id(), a.format()).isNotNull();
                } else {
                    // Null, not zero. B's entity field is a primitive long and DemoDataSeeder converts
                    // it, but "there is no ciphertext" and "the ciphertext is empty" are different
                    // facts and this file states the true one.
                    assertThat(a.cipherLength())
                            .as("%s %s is not encrypted so it has no cipherLength", i.id(), a.format())
                            .isNull();
                    assertThat(a.keyId())
                            .as("%s %s is not encrypted so it has no keyId", i.id(), a.format())
                            .isNull();
                }
            }
        }
    }

    @Test
    @DisplayName("audio is never encrypted, at any tier, except the one deliberate override fixture")
    void audioIsNeverEncrypted() {
        // The single most likely wrong assumption on both client teams, so it is data, not prose.
        //
        // dev-sample-audio-encrypted is EXCLUDED on purpose: team1/t4targaryen overrode shared.md's
        // rule 2026-08-25 (see ContentAccessGrantImpl's AUDIO_ENCRYPTED_SMALL_FIXTURE comment) so
        // their client can exercise whole-file decrypt-into-RAM for audio, same as EPUB/PDF, within
        // their RAM budget. Every OTHER audio item must still hold the original invariant — this
        // is a single named carve-out, not a loosening of the rule.
        dataset.catalogueItems().stream()
                .filter(i -> !"dev-sample-audio-encrypted".equals(i.id()))
                .flatMap(i -> i.assets().stream())
                .filter(a -> "AUDIO".equals(a.format()))
                .forEach(
                        a -> {
                            assertThat(a.encrypted()).as("audio asset encrypted").isFalse();
                            assertThat(a.hasSearchIndex()).as("audio asset indexed").isFalse();
                        });
    }

    @Test
    @DisplayName("the three server-only keys sit on the item, and only where an object could exist")
    void serverOnlyKeysAreOnTheItem() {
        // B moved storageKey, indexKey and wrappedBek off the asset and onto CatalogueItem, so the
        // dataset follows. A copy left on an asset would be a second source of truth for one fact, and
        // Jackson would drop it without a word because B's Asset has nine fields and none of them is
        // called storageKey.
        for (JsonNode item : raw.get("catalogueItems")) {
            String id = item.get("_id").asText();
            boolean hasAssets = item.get("assets").size() > 0;

            for (JsonNode asset : item.get("assets")) {
                assertThat(asset.has("storageKey")).as("%s asset storageKey", id).isFalse();
                assertThat(asset.has("indexKey")).as("%s asset indexKey", id).isFalse();
                assertThat(asset.has("wrappedBek")).as("%s asset wrappedBek", id).isFalse();
            }

            if (hasAssets) {
                if (DEV_CONTENT_FIXTURE_ITEM_IDS.contains(id)) {
                    // A real fixture, not a placeholder - ContentAccessGrantImpl serves this
                    // exact path from src/main/resources/static/mock-content/.
                    assertThat(item.get("storageKey").asText())
                            .as("%s storageKey is a real dev-content fixture", id)
                            .startsWith("static/mock-content/");
                } else {
                    assertThat(item.get("storageKey").asText())
                            .as("%s storageKey is a week 1 placeholder", id)
                            .startsWith("seed/");
                }
            } else {
                // A key pointing at nothing is worse than no key: it looks like content exists.
                for (String field : List.of("storageKey", "indexKey", "wrappedBek")) {
                    assertThat(item.get(field).isNull()).as("%s %s with no assets", id, field).isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("no entitlement carries a version field")
    void noSeededEntitlementCarriesAVersion() {
        // Asserted against the raw JSON, not the record, because the record deliberately has no such
        // component. B declared `private long version` with no @Version, so this is inert today; if
        // the annotation is added, Spring reads a non-null version as "this document already exists"
        // and the first insert fails with OptimisticLockingFailureException.
        for (JsonNode e : raw.get("entitlements")) {
            assertThat(e.has("version"))
                    .as("entitlement %s must not seed a version", e.get("_id").asText())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("every timestamp is a fixed literal, so two developers get identical databases")
    void everyTimestampIsALiteral() {
        dataset.publishers().forEach(p -> assertThat(p.createdAt()).isEqualTo(SEED_EPOCH));
        dataset.institutions().forEach(i -> assertThat(i.createdAt()).isEqualTo(SEED_EPOCH));
        dataset.catalogueItems().forEach(i -> assertThat(i.createdAt()).isEqualTo(SEED_EPOCH));
        dataset.entitlements().forEach(e -> assertThat(e.createdAt()).isEqualTo(SEED_EPOCH));
        dataset.feedSettings().forEach(f -> assertThat(f.updatedAt()).isEqualTo(SEED_EPOCH));
        dataset.adminUsers().forEach(u -> assertThat(u.lastLoginAt()).isNull());
        dataset.institutions().forEach(i -> assertThat(i.catalogueVersion()).isEqualTo(1L));
    }

    @Test
    @DisplayName("admin users are one of each role, correctly scoped, with BCrypt strength 10 hashes")
    void adminUsersCoverEveryRole() {
        assertThat(dataset.adminUsers())
                .extracting(SeedDataset.SeedAdminUser::role)
                .containsExactlyInAnyOrder("SUPER_ADMIN", "PUBLISHER_ADMIN", "INSTITUTION_ADMIN");

        assertThat(dataset.adminUsers())
                .extracting(SeedDataset.SeedAdminUser::email)
                .doesNotHaveDuplicates()
                .allSatisfy(e -> assertThat(e).isEqualTo(e.toLowerCase()));

        // AdminStatus, not RecordStatus: B split it out and its third value is DISABLED, not RETIRED.
        assertThat(dataset.adminUsers())
                .extracting(SeedDataset.SeedAdminUser::status)
                .allSatisfy(s -> assertThat(s).isIn("ACTIVE", "SUSPENDED", "DISABLED"));

        // Format check only. That the hashes verify the documented development password is a fact
        // about the data, checked once by hand and recorded in the runbook, not re-derived here:
        // a BCrypt verify per test run is deliberately slow for no added signal.
        dataset.adminUsers()
                .forEach(u -> assertThat(u.passwordHash()).matches("^\\$2[aby]\\$10\\$[./0-9A-Za-z]{53}$"));

        assertThat(dataset.adminUsers())
                .filteredOn(u -> "PUBLISHER_ADMIN".equals(u.role()))
                .allSatisfy(u -> assertThat(u.publisherId()).isNotNull());
        assertThat(dataset.adminUsers())
                .filteredOn(u -> "INSTITUTION_ADMIN".equals(u.role()))
                .allSatisfy(u -> assertThat(u.institutionId()).isNotNull());
        assertThat(dataset.adminUsers())
                .filteredOn(u -> "SUPER_ADMIN".equals(u.role()))
                .allSatisfy(
                        u -> {
                            assertThat(u.publisherId()).isNull();
                            assertThat(u.institutionId()).isNull();
                        });
    }

    @Test
    @DisplayName("feed settings satisfy B's persistence guard before the JVM ever loads them")
    void feedSettingsSatisfyThePersistenceGuard() {
        // FeedSettingsPersistenceGuard throws unless there are exactly three shelves and no shelf
        // holds more than fifty items. Failing here costs a second; failing there aborts startup.
        assertThat(dataset.feedSettings())
                .extracting(SeedDataset.SeedFeedSettings::institutionId)
                .as("institutionId is @Indexed(unique = true), so one row per institution")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        ids(dataset.institutions(), SeedDataset.SeedInstitution::id));

        for (SeedDataset.SeedFeedSettings f : dataset.feedSettings()) {
            assertThat(f.id())
                    .as("the id convention pairs the two by eye")
                    .isEqualTo("fs_" + f.institutionId());
            assertThat(f.shelves()).as("%s shelf count", f.id()).hasSize(3);
            assertThat(f.shelves())
                    .extracting(SeedDataset.SeedShelf::id)
                    .as("%s shelf ids never change", f.id())
                    .containsExactly("shelf_1", "shelf_2", "shelf_3");
            assertThat(f.shelves())
                    .extracting(SeedDataset.SeedShelf::order)
                    .as("%s shelves render by order, not by array position", f.id())
                    .containsExactly(1, 2, 3);
            assertThat(f.pageSize()).isPositive();

            for (SeedDataset.SeedShelf s : f.shelves()) {
                assertThat(s.itemIds()).as("%s %s size cap", f.id(), s.id()).hasSizeLessThanOrEqualTo(50);
                assertThat(s.itemIds()).as("%s %s duplicates", f.id(), s.id()).doesNotHaveDuplicates();
                assertThat(s.title())
                        .as("%s %s needs a title even when empty", f.id(), s.id())
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("a shelf only ever lists books that institution can actually reach and render")
    void shelvesNeverWidenAccess() {
        // A shelf displays; it never grants. Handbook section 07 states it, and handbook section 18
        // returns 400 when a saved shelf lists a book the institution is not entitled to. A shelf
        // entry that breaks this rule does not error at seed time: it silently vanishes from the feed,
        // which looks like a feed bug rather than a data bug.
        Map<String, SeedDataset.SeedItem> byId =
                dataset.catalogueItems().stream()
                        .collect(Collectors.toMap(SeedDataset.SeedItem::id, i -> i));

        for (SeedDataset.SeedFeedSettings f : dataset.feedSettings()) {
            Set<String> reachable = reachableBy(f.institutionId());
            for (SeedDataset.SeedShelf s : f.shelves()) {
                for (String itemId : s.itemIds()) {
                    assertThat(reachable)
                            .as("%s %s lists %s, which %s cannot reach", f.id(), s.id(), itemId, f.institutionId())
                            .contains(itemId);
                    assertThat(byId.get(itemId).isFeedVisible())
                            .as("%s %s lists %s, which is not both PUBLISHED and READY", f.id(), s.id(), itemId)
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("the composition covers what the rest of the project needs to test")
    void compositionIsDeliberate() {
        // These assertions are the composition table in the approach document, made executable. They
        // exist so that "tidying" a row that another team's test depends on fails here first.
        // Six of the original eight (item_q7 is QUEUED, item_f3 is FAILED) plus all six
        // dev-content fixtures, which are PUBLISHED and READY.
        assertThat(dataset.catalogueItems()).filteredOn(SeedDataset.SeedItem::isFeedVisible).hasSize(12);

        assertThat(dataset.catalogueItems())
                .extracting(SeedDataset.SeedItem::accessTier)
                .contains("OPEN_ACCESS", "SUBSCRIPTION", "ELITE");
        assertThat(dataset.catalogueItems())
                .extracting(SeedDataset.SeedItem::contentType)
                .contains("PDF", "EPUB", "AUDIO");
        assertThat(dataset.catalogueItems())
                .extracting(SeedDataset.SeedItem::contentState)
                .contains("READY", "QUEUED", "FAILED");

        // One institution suspended, so "an inactive institution does not appear in the public list"
        // has something to prove. There is no INACTIVE: RecordStatus is ACTIVE, SUSPENDED, RETIRED.
        assertThat(dataset.institutions())
                .extracting(SeedDataset.SeedInstitution::status)
                .containsExactlyInAnyOrder("ACTIVE", "ACTIVE", "SUSPENDED");

        // Every institution is ACADEMIC: B's InstitutionType has no UNIVERSITY.
        assertThat(dataset.institutions())
                .extracting(SeedDataset.SeedInstitution::type)
                .allSatisfy(t -> assertThat(t).isEqualTo("ACADEMIC"));

        // Two access models, so the resolver has both to distinguish — ent_dev_elite adds a
        // second CONCURRENT row (dev-sample-pdf's own ITEM-scope grant), not a third model.
        assertThat(dataset.entitlements())
                .extracting(SeedDataset.SeedEntitlement::copies)
                .containsExactlyInAnyOrder(2, null, 2);

        // At least one book with no cover and one with two assets.
        assertThat(dataset.catalogueItems()).anySatisfy(i -> assertThat(i.coverUrl()).isNull());
        assertThat(dataset.catalogueItems()).anySatisfy(i -> assertThat(i.assets()).hasSize(2));

        // One empty shelf, so the hidden-shelf case is real data, and one shelf with several books so
        // display order can be tested.
        assertThat(dataset.feedSettings())
                .anySatisfy(
                        f -> assertThat(f.shelves()).anySatisfy(s -> assertThat(s.itemIds()).isEmpty()));
        assertThat(dataset.feedSettings())
                .anySatisfy(
                        f -> assertThat(f.shelves()).anySatisfy(s -> assertThat(s.itemIds()).hasSizeGreaterThan(1)));

        // Publisher codes are uppercase, institution codes are lowercase. Handbook section 06, and now
        // enforced by Publisher's own constructor.
        dataset.publishers().forEach(p -> assertThat(p.code()).isEqualTo(p.code().toUpperCase()));
        dataset.institutions().forEach(i -> assertThat(i.code()).isEqualTo(i.code().toLowerCase()));
    }

    /**
     * Ids this institution can see: open access needs no grant, and an ACTIVE entitlement covers a
     * book if its scope contains it. Mirrors the scope rules in handbook section 06.
     */
    private static Set<String> reachableBy(String institutionId) {
        Set<String> reachable =
                dataset.catalogueItems().stream()
                        .filter(i -> "OPEN_ACCESS".equals(i.accessTier()))
                        .map(SeedDataset.SeedItem::id)
                        .collect(Collectors.toCollection(HashSet::new));

        for (SeedDataset.SeedEntitlement e : dataset.entitlements()) {
            if (!e.institutionId().equals(institutionId) || !"ACTIVE".equals(e.status())) {
                continue;
            }
            for (SeedDataset.SeedItem i : dataset.catalogueItems()) {
                boolean covered =
                        switch (e.scopeType()) {
                            case "PUBLISHER" -> i.publisherId().equals(e.scopeId());
                            case "COLLECTION" -> i.collectionIds().contains(e.scopeId());
                            case "ITEM" -> i.id().equals(e.scopeId());
                            default -> false;
                        };
                if (covered) {
                    reachable.add(i.id());
                }
            }
        }
        return reachable;
    }

    private static List<String> allIds() {
        List<String> all = new ArrayList<>();
        all.addAll(ids(dataset.publishers(), SeedDataset.SeedPublisher::id));
        all.addAll(ids(dataset.collections(), SeedDataset.SeedCollection::id));
        all.addAll(ids(dataset.institutions(), SeedDataset.SeedInstitution::id));
        all.addAll(ids(dataset.catalogueItems(), SeedDataset.SeedItem::id));
        all.addAll(ids(dataset.entitlements(), SeedDataset.SeedEntitlement::id));
        all.addAll(ids(dataset.adminUsers(), SeedDataset.SeedAdminUser::id));
        all.addAll(ids(dataset.feedSettings(), SeedDataset.SeedFeedSettings::id));
        return all;
    }

    private static <T> List<String> ids(List<T> rows, Function<T, String> idOf) {
        return rows.stream().map(idOf).toList();
    }

    private static void assertPrefixed(List<String> ids, String prefix) {
        assertThat(ids).allSatisfy(id -> assertThat(id).startsWith(prefix));
    }
}