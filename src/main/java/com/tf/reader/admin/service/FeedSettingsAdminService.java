package com.tf.reader.admin.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.tf.reader.admin.dto.FeedSettingsView;
import com.tf.reader.admin.dto.FeedSettingsWrite;
import com.tf.reader.admin.dto.ShelfView;
import com.tf.reader.admin.dto.ShelfWrite;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.FeedSettings;
import com.tf.reader.catalogue.entity.Institution;
import com.tf.reader.catalogue.entity.Shelf;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.FeedSettingsRepository;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.catalogue.service.CatalogueVersionBumper;
import com.tf.reader.common.audit.AdminAuditWriter;
import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * Read and write access to one institution's three curated shelves.
 *
 * <p>
 * The controller is HTTP-only. Every business rule, including who may call
 * what, lives here.
 */
@Service
@RequiredArgsConstructor
public class FeedSettingsAdminService {

	private static final int REQUIRED_SHELF_COUNT = 3;
	private static final int MAX_ITEMS_PER_SHELF = 50;
	private static final List<String> EXPECTED_SHELF_IDS = List.of("shelf_1", "shelf_2", "shelf_3");
	private static final int DEFAULT_PAGE_SIZE = 20;

	private final FeedSettingsRepository feedSettingsRepository;
	private final InstitutionRepository institutionRepository;
	private final CatalogueItemRepository catalogueItemRepository;
	private final EntitlementQuery entitlementQuery;
	private final CatalogueVersionBumper catalogueVersionBumper;
	private final AdminAuditWriter auditWriter;
	private final AdminScopeAuthorizer adminScope;

	// ------------------------------------------------------------------------------------- get

	public FeedSettingsView get(String institutionId) {
		Institution institution = requireInstitution(institutionId);

		return feedSettingsRepository.findByInstitutionId(institutionId)
				.map(existing -> toView(existing, institution.getCatalogueVersion()))
				.orElseGet(() -> defaultView(institutionId, institution.getCatalogueVersion()));
	}

	// ------------------------------------------------------------------------------------- save

	public FeedSettingsView save(String institutionId, FeedSettingsWrite write) {
		Institution institution = requireInstitution(institutionId);

		FeedSettings existing = feedSettingsRepository.findByInstitutionId(institutionId).orElse(null);
		long currentVersion = existing == null ? 0L : existing.getVersion();
		if (!write.version().equals(currentVersion)) {
			throw new ApiException(ErrorCode.STALE_VERSION,
					"This record was changed by somebody else. Reload and try again");
		}

		validateShelves(write.shelves(), institutionId);

		Map<String, Object> before = existing == null ? Map.of() : fieldsOf(existing);

		FeedSettings toSave = existing == null ? new FeedSettings() : existing;
		toSave.setInstitutionId(institutionId);
		toSave.setFeedTitle(write.feedTitle());
		toSave.setPageSize(write.pageSize());
		toSave.setDefaultSort(write.defaultSort());
		toSave.setShelves(write.shelves().stream()
				.map(s -> new Shelf(s.id(), s.title(), s.order(), s.itemIds() == null ? List.of() : s.itemIds()))
				.toList());
		toSave.setUpdatedAt(Instant.now());
		toSave.setVersion(currentVersion + 1);

		FeedSettings saved = feedSettingsRepository.save(toSave);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.UPDATE, "FEED_SETTINGS", institutionId,
				before, fieldsOf(saved));

		catalogueVersionBumper.bump(CatalogueVersionBumper.Scope.INSTITUTION, institutionId);

		Institution refreshed = institutionRepository.findById(institutionId).orElse(institution);
		return toView(saved, refreshed.getCatalogueVersion());
	}

	// ---------------------------------------------------------------------------------- helpers

	/**
	 * 403 before 404: an admin scoped to a different institution should not learn from the
	 * response whether a given institution id exists, only a super admin or that
	 * institution's own admin may.
	 */
	private Institution requireInstitution(String institutionId) {
		if (!adminScope.canAccessInstitution(institutionId)) {
			throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Not permitted to access this institution");
		}
		return institutionRepository.findById(institutionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such institution"));
	}

	private void validateShelves(List<ShelfWrite> shelves, String institutionId) {
		if (shelves == null || shelves.size() != REQUIRED_SHELF_COUNT) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Exactly 3 shelves are required");
		}

		Set<String> seenIds = new LinkedHashSet<>();
		Set<Integer> seenOrders = new LinkedHashSet<>();
		Set<String> allItemIds = new LinkedHashSet<>();

		for (ShelfWrite shelf : shelves) {
			if (shelf.id() == null || !EXPECTED_SHELF_IDS.contains(shelf.id()) || !seenIds.add(shelf.id())) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED,
						"Shelf ids must be exactly shelf_1, shelf_2 and shelf_3, each appearing once");
			}
			if (shelf.title() == null || shelf.title().isBlank()) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED,
						"Shelf '" + shelf.id() + "' must have a non-blank title");
			}
			if (shelf.order() < 1 || shelf.order() > REQUIRED_SHELF_COUNT || !seenOrders.add(shelf.order())) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED,
						"Shelf order values must be 1, 2 and 3, each used exactly once");
			}
			List<String> itemIds = shelf.itemIds() == null ? List.of() : shelf.itemIds();
			if (itemIds.size() > MAX_ITEMS_PER_SHELF) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED,
						"Shelf '" + shelf.id() + "' must not exceed " + MAX_ITEMS_PER_SHELF + " items");
			}
			allItemIds.addAll(itemIds);
		}

		if (allItemIds.isEmpty()) {
			return;
		}

		List<CatalogueItem> found = catalogueItemRepository.findAllById(allItemIds);
		Map<String, CatalogueItem> byId = found.stream()
				.collect(Collectors.toMap(CatalogueItem::getId, Function.identity()));

		Set<String> unknown = new LinkedHashSet<>(allItemIds);
		unknown.removeAll(byId.keySet());
		if (!unknown.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown catalogue item ids: " + unknown);
		}

		// Delegates to the real entitlement seam rather than re-deriving the rule here.
		// Any deny reason blocks the save (not entitled, not ready, or not found) - per
		// Abhishek, all three are treated the same way at this endpoint.
		SubjectRef subject = new SubjectRef(adminScope.currentAdminId(), institutionId);
		Set<String> notEntitled = allItemIds.stream()
				.filter(id -> !entitlementQuery.check(subject, id).entitled())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (!notEntitled.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"This institution is not entitled to: " + notEntitled);
		}
	}

	private FeedSettingsView defaultView(String institutionId, long catalogueVersion) {
		List<ShelfView> shelves = EXPECTED_SHELF_IDS.stream().map(id -> {
			int order = EXPECTED_SHELF_IDS.indexOf(id) + 1;
			return new ShelfView(id, "", order, List.of());
		}).toList();
		return new FeedSettingsView(institutionId, "", DEFAULT_PAGE_SIZE, null, shelves, catalogueVersion, 0L);
	}

	private FeedSettingsView toView(FeedSettings feedSettings, long catalogueVersion) {
		List<ShelfView> shelves = feedSettings.getShelves().stream()
				.map(s -> new ShelfView(s.getId(), s.getTitle(), s.getOrder(), s.getItemIds())).toList();
		return new FeedSettingsView(feedSettings.getInstitutionId(), feedSettings.getFeedTitle(),
				feedSettings.getPageSize(), feedSettings.getDefaultSort(), shelves, catalogueVersion,
				feedSettings.getVersion());
	}

	private static Map<String, Object> fieldsOf(FeedSettings feedSettings) {
		return Map.of("feedTitle", String.valueOf(feedSettings.getFeedTitle()), "pageSize",
				feedSettings.getPageSize(), "defaultSort", String.valueOf(feedSettings.getDefaultSort()), "shelves",
				feedSettings.getShelves());
	}

}
