package com.tf.reader.catalogue.opds.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentState;
import com.tf.reader.catalogue.entity.ContentType;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.entity.WorkType;
import com.tf.reader.catalogue.opds.dto.OpdsFeedMetadata;
import com.tf.reader.catalogue.opds.dto.OpdsLink;
import com.tf.reader.catalogue.opds.dto.OpdsNavigationFeed;
import com.tf.reader.catalogue.opds.dto.OpdsPublication;
import com.tf.reader.catalogue.opds.dto.OpdsPublicationDocument;
import com.tf.reader.catalogue.opds.dto.OpdsPublicationFeed;
import com.tf.reader.catalogue.repository.CatalogueItemPublicSearchRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.catalogue.service.CatalogueItemStore;
import com.tf.reader.catalogue.service.CatalogueUrlBuilder;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;

import lombok.RequiredArgsConstructor;


@Service
@RequiredArgsConstructor
public class OpdsPublicFeedService {

    private static final String OPDS_MEDIA_TYPE = "application/opds+json";
    private static final String FEED_TITLE = "Open access catalogue";
    private static final String JOURNALS_FEED_TITLE = "Journals";
    private static final EntitlementDecision OPEN_ACCESS_DECISION =
            new EntitlementDecision(true, AccessLevel.OPEN_ACCESS, null, null, 0, null, null);

    private final CatalogueItemStore catalogueItemStore;
    private final CatalogueItemPublicSearchRepository publicSearchRepository;
    private final PublisherRepository publisherRepository;
    private final OpdsPublicationMapper publicationMapper;
    private final CatalogueUrlBuilder catalogueUrlBuilder;

    public OpdsPublicationFeed catalogueFeed(PageQuery page) {
        // Top-level only - a container's ARTICLE/VOLUME/ISSUE children don't surface here
        // directly; a JOURNAL is reachable via workFeed(String) from its own entry below.
        // A JOURNAL carries no accessTier of its own (null, not OPEN_ACCESS - only its articles
        // are tiered), so it can never match the accessTier-filtered query below and has to be
        // fetched separately, same as the authenticated root feed already does.
        List<CatalogueItem> items = new ArrayList<>(catalogueItemStore
                .findTopLevelByAccessTierAndStatusAndContentState(AccessTier.OPEN_ACCESS, ItemStatus.PUBLISHED,
                        ContentState.READY, Sort.unsorted()));
        items.addAll(catalogueItemStore.findByWorkTypeAndStatus(WorkType.JOURNAL, ItemStatus.PUBLISHED));
        items.sort(Comparator.comparing(CatalogueItem::getPublishedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        int from = Math.min(page.page() * page.size(), items.size());
        int to = Math.min(from + page.size(), items.size());
        List<CatalogueItem> pageItems = items.subList(from, to);

        Map<String, Publisher> publishersById = publisherRepository
                .findAllById(pageItems.stream().map(CatalogueItem::getPublisherId).distinct().toList()).stream()
                .collect(Collectors.toMap(Publisher::getId, p -> p));
        List<OpdsPublication> publications = pageItems.stream()
                .map(item -> item.getWorkType() == WorkType.JOURNAL
                        ? publicationMapper.toContainerPublication(item,
                                catalogueUrlBuilder.publicWorkUrlFor(item.getId()), publishersById)
                        : publicationMapper.toPublicationWithSelfHref(item, OPEN_ACCESS_DECISION,
                                catalogueUrlBuilder.publicPublicationUrlFor(item.getId()), publishersById))
                .toList();

        OpdsFeedMetadata metadata = new OpdsFeedMetadata(FEED_TITLE, items.size(), page.size(), page.page(), null);
        List<OpdsLink> links = pageLinks(page, to < items.size());

        if (publications.isEmpty()) {
            OpdsLink back = new OpdsLink("subsection", catalogueUrlBuilder.publicCatalogueUrlFor(), OPDS_MEDIA_TYPE,
                    "Back to catalogue");
            return new OpdsPublicationFeed(metadata, links, null, List.of(back));
        }
        return new OpdsPublicationFeed(metadata, links, publications, null);
    }

    /**
     * Every published journal, as a flat list of container publications - the anonymous
     * counterpart to {@code OpdsFeedService.rootFeed()}'s "Journals" group, minus the institution
     * it doesn't have. A journal carries no acquisition/entitlement of its own (same reasoning as
     * {@code buildJournalsGroup}'s own doc), so unlike {@link #catalogueFeed}, there is no
     * entitlement decision to attach and no reason to page a handful of journals.
     */
    public OpdsPublicationFeed journalsFeed() {
        List<CatalogueItem> journals = catalogueItemStore
                .findByWorkTypeAndStatus(WorkType.JOURNAL, ItemStatus.PUBLISHED).stream()
                .sorted(Comparator.comparing(CatalogueItem::getSequence, Comparator.nullsLast(Integer::compareTo)))
                .toList();

        Map<String, Publisher> publishersById = publisherRepository
                .findAllById(journals.stream().map(CatalogueItem::getPublisherId).distinct().toList()).stream()
                .collect(Collectors.toMap(Publisher::getId, p -> p));
        List<OpdsPublication> publications = journals.stream()
                .map(journal -> publicationMapper.toContainerPublication(journal,
                        catalogueUrlBuilder.publicWorkUrlFor(journal.getId()), publishersById))
                .toList();

        OpdsFeedMetadata metadata = new OpdsFeedMetadata(JOURNALS_FEED_TITLE, publications.size(), null, null, null);
        OpdsLink self = new OpdsLink("self", catalogueUrlBuilder.publicJournalsUrlFor(), OPDS_MEDIA_TYPE);

        if (publications.isEmpty()) {
            OpdsLink back = new OpdsLink("subsection", catalogueUrlBuilder.publicCatalogueUrlFor(), OPDS_MEDIA_TYPE,
                    "Back to catalogue");
            return new OpdsPublicationFeed(metadata, List.of(self), null, List.of(back));
        }
        return new OpdsPublicationFeed(metadata, List.of(self), publications, null);
    }

    private List<OpdsLink> pageLinks(PageQuery page, boolean hasNext) {
        List<OpdsLink> links = new ArrayList<>();
        links.add(new OpdsLink("self", pageUrl(page.page(), page.size()), OPDS_MEDIA_TYPE));
        if (hasNext) {
            links.add(new OpdsLink("next", pageUrl(page.page() + 1, page.size()), OPDS_MEDIA_TYPE));
        }
        return links;
    }

    private String pageUrl(int pageNumber, int size) {
        return catalogueUrlBuilder.publicCatalogueUrlFor() + "?page=" + pageNumber + "&size=" + size;
    }

    // Not entitlement scoped (wokay-api.yaml): every PUBLISHED/READY book matches, obtainable or
    // not, so there is no per-caller entitlement check to run at all - only the mapper's choice
    // of link (real acquisition vs subscribe) reflects whether the book can be had.
    public OpdsPublicationFeed searchFeed(String query, PageQuery page, ContentType contentTypeFilter,
            AccessTier accessTierFilter) {
        CatalogueItemPublicSearchRepository.Results results = publicSearchRepository.search(query, contentTypeFilter,
                accessTierFilter, page.page(), page.size());

        Map<String, Publisher> publishersById = publisherRepository
                .findAllById(results.items().stream().map(CatalogueItem::getPublisherId).distinct().toList())
                .stream().collect(Collectors.toMap(Publisher::getId, p -> p));
        List<OpdsPublication> publications = results.items().stream()
                .map(item -> publicationMapper.toDiscoveryPublication(item,
                        catalogueUrlBuilder.publicPublicationUrlFor(item.getId()), publishersById))
                .toList();

        OpdsFeedMetadata metadata = new OpdsFeedMetadata("Search: " + query, (int) results.total(), page.size(),
                page.page(), null);
        boolean hasNext = (long) (page.page() + 1) * page.size() < results.total();
        List<OpdsLink> links = searchPageLinks(query, page, contentTypeFilter, accessTierFilter, hasNext);

        if (publications.isEmpty()) {
            OpdsLink back = new OpdsLink("subsection", catalogueUrlBuilder.publicCatalogueUrlFor(), OPDS_MEDIA_TYPE,
                    "Back to catalogue");
            return new OpdsPublicationFeed(metadata, links, null, List.of(back));
        }
        return new OpdsPublicationFeed(metadata, links, publications, null);
    }

    private List<OpdsLink> searchPageLinks(String query, PageQuery page, ContentType contentTypeFilter,
            AccessTier accessTierFilter, boolean hasNext) {
        List<OpdsLink> links = new ArrayList<>();
        links.add(new OpdsLink("self", searchPageUrl(query, page.page(), page.size(), contentTypeFilter,
                accessTierFilter), OPDS_MEDIA_TYPE));
        if (hasNext) {
            links.add(new OpdsLink("next", searchPageUrl(query, page.page() + 1, page.size(), contentTypeFilter,
                    accessTierFilter), OPDS_MEDIA_TYPE));
        }
        return links;
    }

    private String searchPageUrl(String query, int pageNumber, int size, ContentType contentTypeFilter,
            AccessTier accessTierFilter) {
        StringBuilder url = new StringBuilder(catalogueUrlBuilder.publicSearchUrlFor())
                .append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8))
                .append("&page=").append(pageNumber)
                .append("&size=").append(size);
        if (contentTypeFilter != null) {
            url.append("&contentType=").append(contentTypeFilter);
        }
        if (accessTierFilter != null) {
            url.append("&accessTier=").append(accessTierFilter);
        }
        return url.toString();
    }


    /**
     * The no-institution mirror of {@code OpdsFeedService.workFeed} - a JOURNAL/VOLUME returns
     * navigation to its published children; an ISSUE returns a publication feed of its own
     * children, but only the ones that are actually {@code OPEN_ACCESS} - there is no signed-in
     * reader here for an entitlement check to have excluded anything else instead. 404 for
     * anything not PUBLISHED, or not a JOURNAL/VOLUME/ISSUE - same indistinguishable-404 rule the
     * rest of OPDS already uses.
     */
    public Object workFeed(String workId) {
        CatalogueItem work = catalogueItemStore.findById(workId)
                .filter(item -> item.getStatus() == ItemStatus.PUBLISHED)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such work"));
        WorkType workType = work.getWorkType();

        if (workType == WorkType.JOURNAL || workType == WorkType.VOLUME) {
            List<CatalogueItem> children = catalogueItemStore.findByParentId(workId).stream()
                    .filter(child -> child.getStatus() == ItemStatus.PUBLISHED)
                    .sorted(Comparator.comparing(CatalogueItem::getSequence, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
            List<OpdsLink> navigation = children.stream()
                    .map(child -> new OpdsLink("subsection", catalogueUrlBuilder.publicWorkUrlFor(child.getId()),
                            OPDS_MEDIA_TYPE, child.getTitle()))
                    .toList();
            OpdsLink self = new OpdsLink("self", catalogueUrlBuilder.publicWorkUrlFor(workId), OPDS_MEDIA_TYPE);
            OpdsFeedMetadata metadata = new OpdsFeedMetadata(work.getTitle(), navigation.size(), null, null,
                    work.getUpdatedAt());
            return new OpdsNavigationFeed(metadata, List.of(self), navigation, null);
        }

        if (workType == WorkType.ISSUE) {
            List<CatalogueItem> articles = catalogueItemStore.findByParentId(workId).stream()
                    .filter(child -> child.getStatus() == ItemStatus.PUBLISHED
                            && child.getContentState() == ContentState.READY
                            && child.getAccessTier() == AccessTier.OPEN_ACCESS)
                    .sorted(Comparator.comparing(CatalogueItem::getSequence, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
            Map<String, Publisher> publishersById = publisherRepository
                    .findAllById(articles.stream().map(CatalogueItem::getPublisherId).distinct().toList()).stream()
                    .collect(Collectors.toMap(Publisher::getId, p -> p));
            List<OpdsPublication> publications = articles.stream()
                    .map(article -> publicationMapper.toPublicationWithSelfHref(article, OPEN_ACCESS_DECISION,
                            catalogueUrlBuilder.publicPublicationUrlFor(article.getId()), publishersById))
                    .toList();
            OpdsLink self = new OpdsLink("self", catalogueUrlBuilder.publicWorkUrlFor(workId), OPDS_MEDIA_TYPE);
            OpdsFeedMetadata metadata = new OpdsFeedMetadata(work.getTitle(), publications.size(), null, null,
                    work.getUpdatedAt());
            if (publications.isEmpty()) {
                // The OPDS schema forbids an empty publications array - a link back, same rule
                // catalogueFeed's own empty-result case already follows.
                List<OpdsLink> navigation = List.of(new OpdsLink("subsection",
                        catalogueUrlBuilder.publicCatalogueUrlFor(), OPDS_MEDIA_TYPE, "Back to catalogue"));
                return new OpdsPublicationFeed(metadata, List.of(self), null, navigation);
            }
            return new OpdsPublicationFeed(metadata, List.of(self), publications, null);
        }

        throw new ApiException(ErrorCode.NOT_FOUND, "No such work");
    }

    public OpdsPublicationDocument publicationDocument(String itemId) {
        CatalogueItem item = catalogueItemStore.findById(itemId)
                .filter(candidate -> candidate.getStatus() == ItemStatus.PUBLISHED)
                .filter(candidate -> candidate.getContentState() == ContentState.READY)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such item"));

        Map<String, Publisher> publishersById = publisherRepository.findById(item.getPublisherId())
                .map(publisher -> Map.of(publisher.getId(), publisher))
                .orElse(Map.of());
        OpdsPublication publication = publicationMapper.toDiscoveryPublication(item,
                catalogueUrlBuilder.publicPublicationUrlFor(item.getId()), publishersById);
        return new OpdsPublicationDocument(publication);
    }
}
