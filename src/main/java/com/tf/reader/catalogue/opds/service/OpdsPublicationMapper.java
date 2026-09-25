package com.tf.reader.catalogue.opds.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.CatalogueItem.Asset;
import com.tf.reader.catalogue.entity.ContentType;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.entity.WorkType;
import com.tf.reader.catalogue.opds.dto.Copies;
import com.tf.reader.catalogue.opds.dto.EncryptedInfo;
import com.tf.reader.catalogue.opds.dto.OpdsAvailability;
import com.tf.reader.catalogue.opds.dto.IndirectAcquisition;
import com.tf.reader.catalogue.opds.dto.OpdsContributor;
import com.tf.reader.catalogue.opds.dto.OpdsImageLink;
import com.tf.reader.catalogue.opds.dto.OpdsLink;
import com.tf.reader.catalogue.opds.dto.OpdsLinkProperties;
import com.tf.reader.catalogue.opds.dto.OpdsPublication;
import com.tf.reader.catalogue.opds.dto.OpdsPublicationMetadata;
import com.tf.reader.catalogue.service.CatalogueUrlBuilder;
import com.tf.reader.catalogue.service.FlambeauUrlBuilder;
import com.tf.reader.ingest.service.CoverUrlResolver;

import lombok.RequiredArgsConstructor;

/**
 * Turns one entitled {@link CatalogueItem} into the {@code OpdsPublication} shape
 * {@code wokay-api.yaml} defines. Reused by the root feed and the group feed; the
 * publication-detail endpoint (out of scope this week) will reuse it too.
 */
@Component
@RequiredArgsConstructor
class OpdsPublicationMapper {

    private static final String BOOK_TYPE = "http://schema.org/Book";
    private static final String AUDIOBOOK_TYPE = "http://schema.org/Audiobook";
    // team1's Q-1b: this is the value they now map to their own 'article' WorkType, agreed with
    // them directly rather than left as a guess on either side.
    private static final String ARTICLE_TYPE = "http://schema.org/ScholarlyArticle";

    // The acquisition href returns flambeau JSON, never the book itself - the real media
    // type lives in properties.indirectAcquisition[0].type instead (wokay-api.yaml).
    private static final String ACQUISITION_LINK_TYPE = "application/json";
    private static final String PUBLICATION_LINK_TYPE = "application/opds-publication+json";
    private static final String NAVIGATION_LINK_TYPE = "application/opds+json";
    private static final String ENCRYPTION_ALGORITHM = "http://www.w3.org/2009/xmlenc11#aes256-gcm";
    private static final String SUBSCRIBE_LINK_TITLE = "Available through your institution";

    // Open access needs no grant lookup at all (EntitlementQueryImpl), so a fixed "entitled"
    // decision is exactly as correct here as a real one - there is nothing a real check could add.
    private static final EntitlementDecision OPEN_ACCESS_DECISION =
            new EntitlementDecision(true, AccessLevel.OPEN_ACCESS, null, null, 0, null, null);

    private final CatalogueUrlBuilder catalogueUrlBuilder;
    private final FlambeauUrlBuilder flambeauUrlBuilder;
    private final CoverUrlResolver coverUrlResolver;

    OpdsPublication toPublication(CatalogueItem item, EntitlementDecision decision, String institutionId,
            Map<String, Publisher> publishersById) {
        return toPublicationWithSelfHref(item, decision,
                catalogueUrlBuilder.publicationUrlFor(institutionId, item.getId()), publishersById);
    }

    /**
     * A journal (or any other container) has no content of its own to acquire - only a cover and
     * a way to browse into it - so this skips {@link #acquisitionLink}, which assumes every item
     * has an {@link EntitlementDecision} and a downloadable asset. Follows OPDS 2.0's rule that
     * images belong on a Publication, never on a plain navigation link - the frontend gets a
     * journal's cover the same way it already gets a book's, just from this container shape.
     */
    OpdsPublication toContainerPublication(CatalogueItem item, String subsectionHref,
            Map<String, Publisher> publishersById) {
        List<OpdsLink> links = List.of(new OpdsLink("subsection", subsectionHref, NAVIGATION_LINK_TYPE, item.getTitle()));
        return new OpdsPublication(metadata(item, publishersById), links, coverImages(item));
    }


    OpdsPublication toPublicationWithSelfHref(CatalogueItem item, EntitlementDecision decision, String selfHref,
            Map<String, Publisher> publishersById) {
        List<OpdsLink> links = List.of(
                new OpdsLink("self", selfHref, PUBLICATION_LINK_TYPE),
                acquisitionLink(item, decision));
        return new OpdsPublication(metadata(item, publishersById), links, coverImages(item));
    }

    // Discovery search (Workstream 9) has no institution and runs no entitlement check at all -
    // an open access book gets the real acquisition link, anything else gets a subscribe link,
    // regardless of what any caller happens to hold, since there is no caller to check against.
    OpdsPublication toDiscoveryPublication(CatalogueItem item, String selfHref, Map<String, Publisher> publishersById) {
        OpdsLink link = item.getAccessTier() == AccessTier.OPEN_ACCESS
                ? acquisitionLink(item, OPEN_ACCESS_DECISION)
                : subscribeLink(item);
        List<OpdsLink> links = List.of(new OpdsLink("self", selfHref, PUBLICATION_LINK_TYPE), link);
        return new OpdsPublication(metadata(item, publishersById), links, coverImages(item));
    }

    private OpdsLink subscribeLink(CatalogueItem item) {
        OpdsLinkProperties properties = new OpdsLinkProperties(item.getAccessTier(), OpdsAvailability.UNAVAILABLE);
        return new OpdsLink("http://opds-spec.org/acquisition/subscribe", catalogueUrlBuilder.institutionsUrl(),
                ACQUISITION_LINK_TYPE, SUBSCRIBE_LINK_TITLE, null, properties);
    }

    private OpdsPublicationMetadata metadata(CatalogueItem item, Map<String, Publisher> publishersById) {
        boolean audio = item.getContentType() == ContentType.AUDIO;
        Publisher publisher = publishersById.get(item.getPublisherId());
        return new OpdsPublicationMetadata(
                schemaTypeFor(item, audio),
                identifierFor(item),
                item.getTitle(),
                item.getSubtitle(),
                contributors(item.getAuthors()),
                contributors(item.getEditors()),
                contributors(item.getNarrators()),
                publisher == null ? null : new OpdsContributor(publisher.getName(), null),
                item.getLanguage(),
                item.getPublishedAt(),
                item.getUpdatedAt(),
                item.getDescription(),
                audio ? null : item.getNumberOfPages(),
                audio ? item.getDuration() : null,
                contributors(item.getSubjects()));
    }

    // ARTICLE is the only WorkType this needs to special-case: BOOK, a null workType (every item
    // that predates the field, per CatalogueItem's own comment) and the JOURNAL/VOLUME/ISSUE
    // containers all keep the existing audio-or-book heuristic, since nothing downstream reads
    // metadata().type for a container - the frontend's journal-cover path never parses it (it
    // reads the subsection link instead), so there is nothing to gain from guessing a Periodical/
    // PublicationVolume/PublicationIssue type nobody consumes yet.
    private String schemaTypeFor(CatalogueItem item, boolean audio) {
        if (item.getWorkType() == WorkType.ARTICLE) {
            return ARTICLE_TYPE;
        }
        return audio ? AUDIOBOOK_TYPE : BOOK_TYPE;
    }

    private String identifierFor(CatalogueItem item) {
        String isbn = item.getIsbn();
        return isbn == null || isbn.isBlank() ? null : "urn:isbn:" + isbn;
    }

    private List<OpdsContributor> contributors(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        return names.stream().map(name -> new OpdsContributor(name, null)).toList();
    }

    private List<OpdsImageLink> coverImages(CatalogueItem item) {
        String coverUrl = coverUrlResolver.resolve(item);
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        // An uploaded cover knows its real content type; a pasted-in external link does not,
        // so the extension is all we have there, and an unrecognised one is omitted rather
        // than guessed.
        String mimeType = item.getCoverMimeType() != null ? item.getCoverMimeType() : imageMimeType(coverUrl);
        return List.of(new OpdsImageLink(coverUrl, mimeType, null, null));
    }

    private String imageMimeType(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return null;
    }

    private OpdsLink acquisitionLink(CatalogueItem item, EntitlementDecision decision) {
        AccessTier tier = item.getAccessTier();
        String rel = switch (tier) {
            case OPEN_ACCESS -> "http://opds-spec.org/acquisition/open-access";
            case SUBSCRIPTION -> "http://opds-spec.org/acquisition";
            case ELITE -> "http://opds-spec.org/acquisition/borrow";
        };
        String href = tier == AccessTier.OPEN_ACCESS
                ? flambeauUrlBuilder.readingSessionsUrlFor(item.getId())
                : flambeauUrlBuilder.loansUrlFor(item.getId());
        return new OpdsLink(rel, href, ACQUISITION_LINK_TYPE, null, null, propertiesFor(item, tier, decision));
    }

    private OpdsLinkProperties propertiesFor(CatalogueItem item, AccessTier tier, EntitlementDecision decision) {
        Asset asset = matchingAsset(item);
        // A reader still acquires one book through one acquisition link, whether it has one part
        // or thirty (chapter selection happens through ContentAccessGrant, not OPDS) - so what
        // crosses the wire here is part 1's own numbers, matching what a non-chaptered item has
        // always reported.
        CatalogueItem.Part part = firstPartOf(asset);
        Copies copies = tier == AccessTier.ELITE && decision.copies() != null
                ? new Copies(decision.copies())
                : null;
        EncryptedInfo encrypted = asset != null && asset.isEncrypted() && part != null
                ? new EncryptedInfo(ENCRYPTION_ALGORITHM, part.getSizeBytes())
                : null;
        List<IndirectAcquisition> indirect = asset == null
                ? null
                : List.of(new IndirectAcquisition(asset.getMimeType()));
        boolean hasSearchIndex = part != null && part.isHasSearchIndex();
        boolean canPersist = tier != AccessTier.ELITE;
        // What actually crosses the wire: cipherLength for an encrypted asset (sizeBytes is the
        // plaintext length, already used for EncryptedInfo.originalLength above), sizeBytes
        // otherwise - same distinction content/api/SignedUrl draws between the two fields.
        Long fileSize = part == null ? null
                : (asset.isEncrypted() ? part.getCipherLength() : part.getSizeBytes());
        return new OpdsLinkProperties(tier, indirect, copies, encrypted, hasSearchIndex, canPersist, fileSize, null);
    }

    private Asset matchingAsset(CatalogueItem item) {
        if (item.getAssets() == null) {
            return null;
        }
        return item.getAssets().stream()
                .filter(asset -> asset.getFormat() == item.getContentType())
                .findFirst()
                .orElse(null);
    }

    private CatalogueItem.Part firstPartOf(Asset asset) {
        if (asset == null || asset.getParts() == null) {
            return null;
        }
        return asset.getParts().stream().filter(p -> p.getPartNumber() == 1).findFirst()
                .orElse(asset.getParts().isEmpty() ? null : asset.getParts().get(0));
    }
}
