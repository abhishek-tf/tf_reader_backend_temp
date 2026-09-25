package com.tf.reader.catalogue.opds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentState;
import com.tf.reader.catalogue.entity.ContentType;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.entity.WorkType;
import com.tf.reader.catalogue.opds.dto.OpdsNavigationFeed;
import com.tf.reader.catalogue.opds.dto.OpdsPublicationFeed;
import com.tf.reader.catalogue.opds.service.OpdsPublicFeedService;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.model.RecordStatus;
import com.tf.reader.common.page.PageQuery;

/**
 * The no-sign-in home feed a signed-out reader actually browses (Workstream 9's other half of
 * {@link OpdsPublicFeedServiceIT}, which covers the publication-detail endpoint only):
 * {@link OpdsPublicFeedService#catalogueFeed} - open access titles a signed-out reader can
 * actually open, covers included - and {@link OpdsPublicFeedService#journalsFeed}, its
 * institution-free counterpart to {@code OpdsFeedService.rootFeed}'s "Journals" group.
 */
@SpringBootTest(properties = {
		"tnf.auth.jwt.secret=" + ContainerisedInfrastructure.JWT_SECRET,
		"tnf.seed.enabled=false" })
class OpdsPublicCatalogueFeedIT extends ContainerisedInfrastructure {

	@Autowired private OpdsPublicFeedService publicFeedService;
	@Autowired private PublisherRepository publisherRepository;
	@Autowired private CatalogueItemRepository catalogueItemRepository;

	private Publisher newPublisher(String code) {
		return publisherRepository.save(
				new Publisher(null, code, code + " Press", null, null, RecordStatus.ACTIVE, null, null));
	}

	private CatalogueItem newLeaf(String publisherId, WorkType workType, String title) {
		CatalogueItem item = new CatalogueItem();
		item.setPublisherId(publisherId);
		item.setCollectionIds(List.of());
		item.setWorkType(workType);
		item.setTitle(title);
		item.setAccessTier(AccessTier.OPEN_ACCESS);
		item.setStatus(ItemStatus.PUBLISHED);
		item.setContentState(ContentState.READY);
		item.setContentType(ContentType.EPUB);
		item.setPublishedAt(LocalDate.of(2026, 1, 1));
		item.setUpdatedAt(Instant.parse("2026-08-10T09:00:00Z"));
		return catalogueItemRepository.save(item);
	}

	private CatalogueItem newJournal(String publisherId, String title) {
		return newContainer(publisherId, WorkType.JOURNAL, null, title);
	}

	// Same shape as OpdsFeedServiceIT's own helper of this name - a pure container, no content,
	// no accessTier/contentState of its own (WorkType's own doc: "JOURNAL/VOLUME/ISSUE are pure
	// containers").
	private CatalogueItem newContainer(String publisherId, WorkType workType, String parentId, String title) {
		CatalogueItem item = new CatalogueItem();
		item.setPublisherId(publisherId);
		item.setWorkType(workType);
		item.setParentId(parentId);
		item.setTitle(title);
		item.setStatus(ItemStatus.PUBLISHED);
		item.setUpdatedAt(Instant.parse("2026-08-10T09:00:00Z"));
		return catalogueItemRepository.save(item);
	}

	// The actual bug this pins: articles belong to their journal's own drill-down (journalsFeed()
	// -> workFeed() -> Volumes -> Issues -> Articles), not to this flat "every open access thing"
	// list — an article mixed in here has no cover of its own besides (only its ancestor journal
	// does), which is what made an earlier, rejected version of this fix look "boxy".
	@Test
	void catalogueFeedExcludesArticlesButKeepsBooks() {
		Publisher publisher = newPublisher("OPDS-PUBLIC-FEED-FILTER-PUB");
		CatalogueItem book = newLeaf(publisher.getId(), WorkType.BOOK, "An Open Access Book");
		newLeaf(publisher.getId(), WorkType.ARTICLE, "A Bare Journal Article");

		OpdsPublicationFeed feed = publicFeedService.catalogueFeed(new PageQuery(0, 20));

		assertThat(feed.publications()).extracting(p -> p.metadata().title())
				.contains("An Open Access Book")
				.doesNotContain("A Bare Journal Article");
		assertThat(feed.publications()).extracting(p -> p.links().get(0).href())
				.anyMatch(href -> href.contains(book.getId()));
	}

	// Two legacy dev fixtures predate the workType field and carry no value at all - this pins
	// that they are NOT swept up by the article exclusion above, matching how every other reader
	// of this field in the codebase (IngestService, CatalogueItemAdminService) treats an absent
	// workType as BOOK, not as "unknown, so exclude it."
	@Test
	void catalogueFeedKeepsAnItemWithNoWorkTypeSetAtAll() {
		Publisher publisher = newPublisher("OPDS-PUBLIC-FEED-NO-WORKTYPE-PUB");
		newLeaf(publisher.getId(), null, "Predates The workType Field");

		OpdsPublicationFeed feed = publicFeedService.catalogueFeed(new PageQuery(0, 20));

		assertThat(feed.publications()).extracting(p -> p.metadata().title())
				.contains("Predates The workType Field");
	}

	@Test
	void journalsFeedListsEveryPublishedJournalWithItsCover() {
		Publisher publisher = newPublisher("OPDS-PUBLIC-JOURNALS-PUB");
		CatalogueItem journal = newJournal(publisher.getId(), "Journal Of Public Browsing");
		journal.setCoverKey("items/" + journal.getId() + "/cover");
		journal.setCoverMimeType("image/jpeg");
		catalogueItemRepository.save(journal);

		try {
			OpdsPublicationFeed feed = publicFeedService.journalsFeed();

			var publication = feed.publications().stream()
					.filter(p -> p.metadata().title().equals("Journal Of Public Browsing")).findFirst()
					.orElseThrow(() -> new AssertionError("journal missing from " + feed.publications()));
			assertThat(publication.images()).isNotNull().hasSize(1);
			assertThat(publication.images().get(0).href()).contains(journal.getCoverKey());
			assertThat(publication.links()).singleElement().satisfies(link -> {
				assertThat(link.rel()).isEqualTo("subsection");
				assertThat(link.href()).contains(journal.getId());
			});
		} finally {
			// journalsFeed()'s query is GLOBAL (every PUBLISHED journal, no publisher/scope filter),
			// and ContainerisedInfrastructure's Mongo is shared, un-truncated, across the whole
			// suite for the JVM's life - left behind, this journal is exactly the kind of stray
			// fixture that broke OpdsFeedServiceIT's own rootFeed() assertions the first time this
			// test ran without this cleanup.
			catalogueItemRepository.delete(journal);
		}
	}

	// No "empty catalogue" test for journalsFeed(), deliberately, matching
	// OpdsFeedServiceIT's own rootFeed() Journals-group tests: this query is global (every
	// PUBLISHED journal, no publisher/scope filter - same as rootFeed()'s), and
	// ContainerisedInfrastructure's Mongo container is shared, un-truncated, across the WHOLE
	// suite for the life of the JVM. "Zero journals exist anywhere" is not a state any single
	// test can establish once other test classes are free to leave their own journals behind.

	// The actual feature this pins: a signed-out reader taps a JOURNAL and sees its VOLUMEs -
	// no institution, no sign-in, same navigation shape OpdsFeedService.workFeed already gives a
	// signed-in reader, just addressed at public/works/{id} instead of institutions/{id}/works/{id}.
	@Test
	void workFeedListsAJournalsVolumesAsNavigation() {
		Publisher publisher = newPublisher("OPDS-PUBLIC-WORK-JOURNAL-PUB");
		// Any published JOURNAL is visible to OpdsFeedServiceIT's own rootFeed() assertions too
		// (that query is global, same as journalsFeed()'s) - cleaned up in `finally`, same
		// reasoning as journalsFeedListsEveryPublishedJournalWithItsCover above.
		CatalogueItem journal = newJournal(publisher.getId(), "Journal With Volumes");
		try {
			CatalogueItem volume = newContainer(publisher.getId(), WorkType.VOLUME, journal.getId(), "Volume 01");

			Object feed = publicFeedService.workFeed(journal.getId());

			assertThat(feed).isInstanceOf(OpdsNavigationFeed.class);
			OpdsNavigationFeed navigationFeed = (OpdsNavigationFeed) feed;
			assertThat(navigationFeed.navigation()).singleElement().satisfies(link -> {
				assertThat(link.title()).isEqualTo("Volume 01");
				assertThat(link.href()).contains(volume.getId());
			});
		} finally {
			catalogueItemRepository.delete(journal);
		}
	}

	// The other half: an ISSUE returns its ARTICLEs as publications, each mapped through
	// toDiscoveryPublication - open access gets a real acquisition link, anything else gets a
	// subscribe link, with NO EntitlementQuery call at all (there is no subject to check against).
	@Test
	void workFeedGivesAnIssuesArticlesTheRightAcquisitionLinkByTier() {
		Publisher publisher = newPublisher("OPDS-PUBLIC-WORK-ISSUE-PUB");
		CatalogueItem issue = newContainer(publisher.getId(), WorkType.ISSUE, null, "Issue With Articles");
		CatalogueItem openArticle = newLeaf(publisher.getId(), WorkType.ARTICLE, "Open Access Article");
		openArticle.setParentId(issue.getId());
		catalogueItemRepository.save(openArticle);
		CatalogueItem lockedArticle = newLeaf(publisher.getId(), WorkType.ARTICLE, "Subscription Article");
		lockedArticle.setParentId(issue.getId());
		lockedArticle.setAccessTier(AccessTier.SUBSCRIPTION);
		catalogueItemRepository.save(lockedArticle);

		Object feed = publicFeedService.workFeed(issue.getId());

		assertThat(feed).isInstanceOf(OpdsPublicationFeed.class);
		OpdsPublicationFeed publicationFeed = (OpdsPublicationFeed) feed;
		var open = publicationFeed.publications().stream()
				.filter(p -> p.metadata().title().equals("Open Access Article")).findFirst().orElseThrow();
		assertThat(open.links()).extracting("rel").contains("http://opds-spec.org/acquisition/open-access");
		var locked = publicationFeed.publications().stream()
				.filter(p -> p.metadata().title().equals("Subscription Article")).findFirst().orElseThrow();
		assertThat(locked.links()).extracting("rel").contains("http://opds-spec.org/acquisition/subscribe");
	}

	@Test
	void workFeedIsNotFoundForAnUnknownId() {
		assertThatThrownBy(() -> publicFeedService.workFeed("work_does_not_exist"))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo(ErrorCode.NOT_FOUND));
	}
}
