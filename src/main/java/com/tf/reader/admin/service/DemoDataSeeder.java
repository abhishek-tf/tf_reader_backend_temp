package com.tf.reader.admin.service;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.connection.ServerDescription;
import org.springframework.data.mongodb.MongoDatabaseFactory;


import tools.jackson.databind.ObjectMapper;

import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.entity.AdminStatus;
import com.tf.reader.admin.entity.AdminUser;
import com.tf.reader.admin.repository.AdminUserRepository;
import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.BookCollection;
import com.tf.reader.catalogue.entity.Branding;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentState;
import com.tf.reader.catalogue.entity.ContentType;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.FeedSettings;
import com.tf.reader.catalogue.entity.Institution;
import com.tf.reader.catalogue.entity.InstitutionType;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.catalogue.entity.Shelf;
import com.tf.reader.catalogue.repository.BookCollectionRepository;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.EntitlementRepository;
import com.tf.reader.catalogue.repository.FeedSettingsRepository;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.common.model.RecordStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Task 8: seeds an empty local MongoDB with a fixed set of 23 dev records  so every developer works off the same data instead of five
 * different hand-typed databases. Audit logs are left out since they're meant to record
 * real events. Only inserts missing docs (never overwrites), only deletes docs on reset
 * (never drops collections/indexes), respects write/delete order for entity
 * dependencies, and refuses to run against a non-local database.
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "tnf.seed", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    static final String DATASET_PATH = "seed/demo-dataset.json";

    private final PublisherRepository publishers;
    private final BookCollectionRepository collections;
    private final InstitutionRepository institutions;
    private final CatalogueItemRepository items;
    private final EntitlementRepository entitlements;
    private final AdminUserRepository adminUsers;
    private final FeedSettingsRepository feedSettings;
    

    private final ObjectMapper mapper;

    private final MongoClient mongoClient;
    private final MongoDatabaseFactory mongoDatabaseFactory;
    private final String mongoUri;
    private final Set<String> allowedHosts;
    private final boolean reset;

    public DemoDataSeeder(
            PublisherRepository publishers,
            BookCollectionRepository collections,
            InstitutionRepository institutions,
            CatalogueItemRepository items,
            EntitlementRepository entitlements,
            AdminUserRepository adminUsers,
            FeedSettingsRepository feedSettings,
            ObjectMapper mapper,
            MongoClient mongoClient,
            MongoDatabaseFactory mongoDatabaseFactory,
            @Value("${spring.data.mongodb.uri:}") String mongoUri,
            @Value("${tnf.seed.allowed-hosts:localhost,127.0.0.1,::1,mongo}") String allowedHosts,
            @Value("${tnf.seed.reset:false}") boolean reset) {
        this.publishers = publishers;
        this.collections = collections;
        this.institutions = institutions;
        this.items = items;
        this.entitlements = entitlements;
        this.adminUsers = adminUsers;
        this.feedSettings = feedSettings;
        this.mapper = mapper;
        this.mongoClient = mongoClient;
        this.mongoDatabaseFactory = mongoDatabaseFactory;
        this.mongoUri = mongoUri;
        this.allowedHosts =
                Arrays.stream(allowedHosts.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        this.reset = reset;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        refuseIfNotLocal();
        logTheTarget();

        SeedDataset dataset = load(mapper);

        if (reset) {
            resetSeededCollections();
        }

        // Dependency order, and this time it is enforced rather than merely tidy: the item guard
        // rejects a book whose publisher is not already in the database.
        List<int[]> results =
                List.of(
                        insertMissing(
                                "publishers",
                                dataset.publishers(),
                                SeedDataset.SeedPublisher::id,
                                publishers,
                                this::toPublisher),
                        insertMissing(
                                "collections",
                                dataset.collections(),
                                SeedDataset.SeedCollection::id,
                                collections,
                                this::toCollection),
                        insertMissing(
                                "institutions",
                                dataset.institutions(),
                                SeedDataset.SeedInstitution::id,
                                institutions,
                                this::toInstitution),
                        insertMissing(
                                "catalogueItems",
                                dataset.catalogueItems(),
                                SeedDataset.SeedItem::id,
                                items,
                                this::toItem),
                        insertMissing(
                                "entitlements",
                                dataset.entitlements(),
                                SeedDataset.SeedEntitlement::id,
                                entitlements,
                                this::toEntitlement),
                        insertMissing(
                                "adminUsers",
                                dataset.adminUsers(),
                                SeedDataset.SeedAdminUser::id,
                                adminUsers,
                                this::toAdminUser),
                        insertMissing(
                                "feedSettings",
                                dataset.feedSettings(),
                                SeedDataset.SeedFeedSettings::id,
                                feedSettings,
                                this::toFeedSettings));

        int inserted = results.stream().mapToInt(r -> r[0]).sum();
        int present = results.stream().mapToInt(r -> r[1]).sum();

        if (inserted == 0) {
            log.info(
                    "seed: 0 inserted, {} already present. Run with -Dtnf.seed.reset=true to reapply"
                            + " a changed dataset.",
                    present);
        } else {
            log.info(
                    "seed: {} inserted, {} already present, {} documents in the dataset",
                    inserted,
                    present,
                    dataset.documentCount());
        }
    }

    /** Reads the dataset off the classpath. Package private so the tests can use it without Spring. */
    static SeedDataset load(ObjectMapper mapper) throws IOException {
        try (InputStream in = new ClassPathResource(DATASET_PATH).getInputStream()) {
            return mapper.readValue(in, SeedDataset.class);
        }
    }

    // Safety

    /**
     * The rail that matters. Everything else here is a convenience; this is the one that stops a
     * seed script becoming an incident. It runs before anything is read or written.
     */
    private void refuseIfNotLocal() {
        List<String> hosts = connectedHosts();

        if (hosts.isEmpty()) {
            throw new IllegalStateException(
                    "seed: cannot determine which MongoDB this application is connected to, so it"
                            + " cannot be verified as local. Set spring.data.mongodb.uri, or set"
                            + " tnf.seed.enabled=false to start without seeding.");
        }

        List<String> foreign = hosts.stream().filter(h -> !allowedHosts.contains(h)).toList();

        if (!foreign.isEmpty()) {
            throw new IllegalStateException(
                    "seed: refusing to run against non-local Mongo host(s) "
                            + foreign
                            + ". The seeder writes and, with tnf.seed.reset, deletes data. Allowed hosts"
                            + " are "
                            + allowedHosts
                            + " (tnf.seed.allowed-hosts). Set tnf.seed.enabled=false to start without"
                            + " seeding.");
        }
    }

    /**
     * The hosts the driver is really talking to, falling back to the configured URI.
     */
    private List<String> connectedHosts() {
        if (mongoClient != null) {
            try {
                List<String> live =
                        mongoClient.getClusterDescription().getServerDescriptions().stream()
                                .map(ServerDescription::getAddress)
                                .map(a -> normaliseHost(a.getHost()))
                                .distinct()
                                .toList();
                if (!live.isEmpty()) {
                    return live;
                }
            } catch (RuntimeException ex) {
                log.debug("seed: could not read the cluster description, falling back to the URI", ex);
            }
        }

        if (mongoUri == null || mongoUri.isBlank()) {
            return List.of();
        }
        try {
            return new ConnectionString(mongoUri)
                    .getHosts().stream().map(DemoDataSeeder::normaliseHost).distinct().toList();
        } catch (RuntimeException ex) {
            log.warn("seed: spring.data.mongodb.uri could not be parsed", ex);
            return List.of();
        }
    }

    /** Strips a port and IPv6 brackets */
    private static String normaliseHost(String host) {
        String h = host;
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            if (close > 0) {
                return h.substring(1, close);
            }
        }
        int colon = h.indexOf(':');
        if (colon >= 0 && h.indexOf(':', colon + 1) < 0) {
            h = h.substring(0, colon);
        }
        return h;
    }

    /**
     * Says which database was written to, at INFO, on every run.
     */
   private void logTheTarget() {
    String database = mongoDatabaseFactory.getMongoDatabase().getName();
    log.info("seed: writing to MongoDB host(s) {}, database {}", connectedHosts(), database);
}

    /**
     * Deletes the documents in the seven seeded collections. Never drops a collection: that would take
     * the indexes with it, and Spring only recreates them at startup.
     */
    private void resetSeededCollections() {
        log.warn("seed: reset requested, deleting documents from the seven seeded collections");
        deleteAllAndLog("feedSettings", feedSettings);
        deleteAllAndLog("adminUsers", adminUsers);
        deleteAllAndLog("entitlements", entitlements);
        deleteAllAndLog("catalogueItems", items);
        deleteAllAndLog("institutions", institutions);
        deleteAllAndLog("collections", collections);
        deleteAllAndLog("publishers", publishers);
        log.warn("seed: reset complete. Indexes are untouched.");
    }

    private void deleteAllAndLog(String label, MongoRepository<?, String> repo) {
        long before = repo.count();
        repo.deleteAll();
        log.warn("seed: reset {} deleted {} documents", label, before);
    }
    //Inser if absent

    /** @return {@code [inserted, alreadyPresent]} */
    private <S, E> int[] insertMissing(
            String label,
            List<S> rows,
            Function<S, String> idOf,
            MongoRepository<E, String> repo,
            Function<S, E> toEntity) {

        List<E> missing = new ArrayList<>();
        int present = 0;

        for (S row : rows) {
            if (repo.existsById(idOf.apply(row))) {
                present++;
            } else {
                missing.add(toEntity.apply(row));
            }
        }

        if (!missing.isEmpty()) {
            repo.saveAll(missing);
        }
        log.info("seed: {} inserted {}, {} already present", label, missing.size(), present);
        return new int[] {missing.size(), present};
    }

    //below toPublisher is the only place where the dataset and the entity differ in a way that matters. The dataset has RTLG and CRCP, which are already upper-case, so the value is unchanged. The entity has a hand-written constructor that upper-cases whatever it is given, so if the dataset were edited to say "rtlg" or "crcp", the entity would still get "RTLG" or "CRCP".

    private Publisher toPublisher(SeedDataset.SeedPublisher s) {
        return new Publisher(
                s.id(),
                s.code(),
                s.name(),
                s.description(),
                s.logoUrl(),
                RecordStatus.valueOf(s.status()),
                s.createdAt(),
                s.updatedAt());
    }

    private BookCollection toCollection(SeedDataset.SeedCollection s) {
        return new BookCollection(s.id(), s.publisherId(), s.code(), s.name(), s.description());
    }

    private Institution toInstitution(SeedDataset.SeedInstitution s) {
        return new Institution(
                s.id(),
                s.code(),
                s.name(),
                InstitutionType.valueOf(s.type()),
                s.country(),
                s.city(),
                new Branding(s.branding().logoUrl(), s.branding().primaryColor()),
                // SignIn is nested inside Institution, so there is no separate import for it.
                new Institution.SignIn(s.signIn().method(), s.signIn().idpHint()),
                RecordStatus.valueOf(s.status()),
                s.catalogueVersion(),
                s.createdAt(),
                s.updatedAt());
    }

    private CatalogueItem toItem(SeedDataset.SeedItem s) {
        List<CatalogueItem.Asset> assets = s.assets().stream().map(this::toAsset).toList();
        return new CatalogueItem(
                s.id(),
                s.publisherId(),
                s.collectionIds(),
                s.title(),
                s.subtitle(),
                s.authors(),
                s.editors(),
                s.narrators(),
                s.isbn(),
                s.language(),
                s.description(),
                s.subjects(),
                s.publishedAt(),
                null, // numberOfPages: not carried by the seed dataset yet, ingest's territory
                null, // duration: not carried by the seed dataset yet
                s.coverUrl(),
                ContentType.valueOf(s.contentType()),
                AccessTier.valueOf(s.accessTier()),
                ItemStatus.valueOf(s.status()),
                ContentState.valueOf(s.contentState()),
                s.contentError(),
                assets,
                // These three sit on the item, not on each asset. B's shape, not the handbook's.
                s.storageKey(),
                s.indexKey(),
                s.wrappedBek(),
                s.createdAt(),
                s.updatedAt());
    }

    private CatalogueItem.Asset toAsset(SeedDataset.SeedAsset a) {
        // cipherLength and indexTerms are primitives on the entity, so "not applicable" has to become
        // a number here. The dataset keeps the null because that is the true statement; this is the
        // one line where the two representations meet.
        return new CatalogueItem.Asset(
                ContentType.valueOf(a.format()),
                a.mimeType(),
                a.sizeBytes(),
                a.cipherLength() == null ? 0L : a.cipherLength(),
                a.encrypted(),
                a.hasSearchIndex(),
                a.indexTerms() == null ? 0 : a.indexTerms(),
                a.indexSkipReason(),
                a.keyId());
    }

    private Entitlement toEntitlement(SeedDataset.SeedEntitlement s) {
        return new Entitlement(
                s.id(),
                s.institutionId(),
                ScopeType.valueOf(s.scopeType()),
                s.scopeId(),
                s.copies(),
                s.loanPeriodDays(),
                s.validFrom(),
                s.validTo(),
                EntitlementStatus.valueOf(s.status()),
                // version: 0, and the dataset deliberately does not carry the field. B declared a
                // plain long with no @Version, so optimistic locking is not active and 0 is just a
                // number. If the annotation is added later, 0 is still the only safe value: Spring
                // reads a non-null version as "this document already exists" and the first insert
                // would fail with OptimisticLockingFailureException.
                0L,
                s.createdAt(),
                s.updatedAt());
    }

    private AdminUser toAdminUser(SeedDataset.SeedAdminUser s) {
        // The hash is pre-computed in the dataset, not produced here. Bcrypt is salted, so hashing at
        // load time would give every developer a different passwordHash and break the one property
        // this task exists to provide. It also keeps the seeder independent of D's PasswordEncoder.
        return new AdminUser(
                s.id(),
                s.email(),
                s.name(),
                s.passwordHash(),
                AdminRole.valueOf(s.role()),
                s.publisherId(),
                s.institutionId(),
                // AdminStatus, not RecordStatus. B introduced a separate enum whose third value is
                // DISABLED rather than RETIRED, which matches how handbook section 19 talks about a
                // disabled account.
                AdminStatus.valueOf(s.status()),
                s.lastLoginAt());
    }

    private FeedSettings toFeedSettings(SeedDataset.SeedFeedSettings s) {
        List<Shelf> shelves = s.shelves().stream().map(this::toShelf).toList();
        return new FeedSettings(
                s.id(),
                s.institutionId(),
                s.feedTitle(),
                s.pageSize(),
                s.defaultSort(),
                shelves,
                s.updatedAt(),
                0L);
    }

    private Shelf toShelf(SeedDataset.SeedShelf s) {
        return new Shelf(s.id(), s.title(), s.order(), s.itemIds());
    }
}