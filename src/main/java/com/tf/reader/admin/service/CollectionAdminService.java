package com.tf.reader.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.tf.reader.admin.dto.CollectionItemsResult;
import com.tf.reader.admin.dto.CollectionItemsWrite;
import com.tf.reader.admin.dto.CollectionView;
import com.tf.reader.admin.dto.CollectionWrite;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.catalogue.entity.BookCollection;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.repository.BookCollectionRepository;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.catalogue.service.CatalogueVersionBumper;
import com.tf.reader.common.audit.AdminAuditWriter;
import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;

import lombok.RequiredArgsConstructor;


@Service
@RequiredArgsConstructor
public class CollectionAdminService {

	private final BookCollectionRepository bookCollectionRepository;
	private final CatalogueItemRepository catalogueItemRepository;
	private final PublisherRepository publisherRepository;
	private final CatalogueVersionBumper catalogueVersionBumper;
	private final AdminAuditWriter auditWriter;
	private final AdminScopeAuthorizer adminScope;

	// ---------------------------------------------------------------- list

	public PageResponse<CollectionView> list(String publisherId, PageQuery pageQuery) {
		requirePublisher(publisherId);

		Page<BookCollection> page = bookCollectionRepository.findByPublisherId(publisherId,
				PageRequest.of(pageQuery.page(), pageQuery.size(), Sort.by(Sort.Direction.ASC, "name")));

		List<CollectionView> views = page.getContent().stream().map(this::toView).toList();
		return new PageResponse<>(views, pageQuery.page(), pageQuery.size(), page.getTotalElements());
	}

	// ---------------------------------------------------------------- create

	public CollectionView create(String publisherId, CollectionWrite write) {
		requirePublisher(publisherId);

		bookCollectionRepository.findByPublisherIdAndCode(publisherId, write.code()).ifPresent(existing -> {
			throw new ApiException(ErrorCode.CODE_TAKEN,
					"Collection code '" + write.code() + "' is already taken for this publisher");
		});

		BookCollection collection = new BookCollection();
		collection.setId("col_" + UUID.randomUUID().toString().substring(0, 8));
		collection.setPublisherId(publisherId);
		collection.setCode(write.code());
		collection.setName(write.name());
		collection.setDescription(write.description());

		collection = bookCollectionRepository.save(collection);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.CREATE, "COLLECTION", collection.getId(), null,
				afterMap(collection));

		return toView(collection);
	}

	// ---------------------------------------------------------------- items

	public CollectionItemsResult setItems(String collectionId, CollectionItemsWrite write) {
		BookCollection collection = bookCollectionRepository.findById(collectionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such collection"));

		if (!adminScope.canAccessPublisher(collection.getPublisherId())) {
			throw new ApiException(ErrorCode.FORBIDDEN_SCOPE, "Not permitted to access this collection");
		}

		Set<String> requestedIds = new LinkedHashSet<>(write.itemIds());

		List<CatalogueItem> requestedItems = catalogueItemRepository.findAllById(requestedIds);
		if (requestedItems.size() != requestedIds.size()) {
			Set<String> foundIds = requestedItems.stream().map(CatalogueItem::getId)
					.collect(Collectors.toCollection(LinkedHashSet::new));
			Set<String> missing = new LinkedHashSet<>(requestedIds);
			missing.removeAll(foundIds);
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown catalogue item ids: " + missing);
		}

		List<CatalogueItem> currentMembers = catalogueItemRepository.findByCollectionIds(collectionId);
		List<String> beforeIds = currentMembers.stream().map(CatalogueItem::getId).toList();

		for (CatalogueItem item : requestedItems) {
			List<String> collectionIds = item.getCollectionIds() == null ? List.of() : item.getCollectionIds();
			if (!collectionIds.contains(collectionId)) {
				List<String> updated = new ArrayList<>(collectionIds);
				updated.add(collectionId);
				item.setCollectionIds(updated);
				catalogueItemRepository.save(item);
			}
		}

		for (CatalogueItem item : currentMembers) {
			if (!requestedIds.contains(item.getId())) {
				List<String> updated = new ArrayList<>(item.getCollectionIds());
				updated.remove(collectionId);
				item.setCollectionIds(updated);
				catalogueItemRepository.save(item);
			}
		}

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.UPDATE, "COLLECTION", collectionId,
				Map.of("itemIds", beforeIds), Map.of("itemIds", List.copyOf(requestedIds)));

		List<String> affectedInstitutions = catalogueVersionBumper.bump(CatalogueVersionBumper.Scope.COLLECTION,
				collectionId);

		return new CollectionItemsResult(collectionId, requestedIds.size(), affectedInstitutions);
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * 403 {@code FORBIDDEN_SCOPE} before 404: an admin scoped to a different
	 * publisher should not learn from the response whether a given publisher id
	 * exists, only a super admin or that publisher's own admin can.
	 */
	private Publisher requirePublisher(String publisherId) {
		if (!adminScope.canAccessPublisher(publisherId)) {
			throw new ApiException(ErrorCode.FORBIDDEN_SCOPE, "Not permitted to access this publisher");
		}
		return publisherRepository.findById(publisherId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such publisher"));
	}

	private CollectionView toView(BookCollection collection) {
		long itemCount = catalogueItemRepository.countByCollectionIds(collection.getId());
		return new CollectionView(collection.getId(), collection.getPublisherId(), collection.getCode(),
				collection.getName(), collection.getDescription(), itemCount);
	}

	private static Map<String, Object> afterMap(BookCollection collection) {
		return Map.of("publisherId", String.valueOf(collection.getPublisherId()), "code",
				String.valueOf(collection.getCode()), "name", String.valueOf(collection.getName()));
	}

}
