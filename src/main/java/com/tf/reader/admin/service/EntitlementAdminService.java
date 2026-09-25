package com.tf.reader.admin.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.tf.reader.admin.dto.EntitlementCreate;
import com.tf.reader.admin.dto.EntitlementStatusChange;
import com.tf.reader.admin.dto.EntitlementUpdate;
import com.tf.reader.admin.dto.EntitlementView;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.BookCollection;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.catalogue.repository.EntitlementRepository;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.catalogue.service.BookCollectionStore;
import com.tf.reader.catalogue.service.CatalogueItemStore;
import com.tf.reader.catalogue.service.CatalogueVersionBumper;
import com.tf.reader.common.audit.AdminAuditWriter;
import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * Grant, amend and revoke institution access to a publisher, collection or item.
 *
 * <p>The controller is HTTP-only. Every business rule - the scope check, the existence check on
 * create, optimistic locking on update, the catalogue version bump - lives here, so a second
 * entry point cannot bypass any of them.
 */
@Service
@RequiredArgsConstructor
public class EntitlementAdminService {

	private static final int DEFAULT_LOAN_PERIOD_DAYS = 14;

	private final EntitlementRepository entitlementRepository;
	private final InstitutionRepository institutionRepository;
	private final PublisherRepository publisherRepository;
	private final BookCollectionStore bookCollectionStore;
	private final CatalogueItemStore catalogueItemStore;
	private final CatalogueVersionBumper catalogueVersionBumper;
	private final AdminAuditWriter auditWriter;
	private final AdminScopeAuthorizer adminScope;

	//list

	public PageResponse<EntitlementView> list(String institutionId, PageQuery pageQuery) {
		requireInstitutionAccess(institutionId);
		requireInstitutionExists(institutionId);

		Page<Entitlement> page = entitlementRepository.findByInstitutionId(institutionId,
				PageRequest.of(pageQuery.page(), pageQuery.size(), Sort.by(Sort.Direction.DESC, "createdAt")));

		return new PageResponse<>(page.getContent().stream().map(this::toView).toList(), pageQuery.page(),
				pageQuery.size(), page.getTotalElements());
	}

	//create

	public EntitlementView create(String institutionId, EntitlementCreate write) {
		requireInstitutionAccess(institutionId);
		requireInstitutionExists(institutionId);

		if (!scopeExists(write.scopeType(), write.scopeId())) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"No " + write.scopeType().name().toLowerCase() + " exists with id '" + write.scopeId() + "'");
		}
		requireCopiesForElite(write.scopeType(), write.scopeId(), write.copies());

		// A REVOKED row still occupies the {institutionId, scopeType, scopeId} unique index, so a
		// re-request for the same scope must reuse and reopen that row rather than insert a
		// second one and hit a duplicate key error.
		Entitlement revoked = entitlementRepository
				.findByInstitutionIdAndScopeTypeAndScopeId(institutionId, write.scopeType(), write.scopeId())
				.filter(existing -> existing.getStatus() == EntitlementStatus.REVOKED)
				.orElse(null);

		Instant now = Instant.now();
		Entitlement entitlement = revoked != null ? revoked : new Entitlement();
		// Full snapshot, not just status: a re-request silently overwrites the prior grant's
		// terms (copies, loan period, validity), and those are exactly what the audit trail
		// needs to show were replaced.
		Map<String, Object> before = revoked != null ? afterMap(revoked) : null;
		if (revoked == null) {
			entitlement.setId(newId());
			entitlement.setInstitutionId(institutionId);
			entitlement.setScopeType(write.scopeType());
			entitlement.setScopeId(write.scopeId());
			entitlement.setVersion(0);
			entitlement.setCreatedAt(now);
		}
		else {
			entitlement.setVersion(entitlement.getVersion() + 1);
		}
		entitlement.setCopies(write.copies());
		entitlement.setLoanPeriodDays(write.loanPeriodDays() != null ? write.loanPeriodDays() : DEFAULT_LOAN_PERIOD_DAYS);
		entitlement.setValidFrom(write.validFrom() != null ? write.validFrom() : LocalDate.now());
		entitlement.setValidTo(write.validTo());
		entitlement.setStatus(resolveCreateStatus(write.status()));
		entitlement.setUpdatedAt(now);

		entitlement = entitlementRepository.save(entitlement);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.CREATE, "ENTITLEMENT", entitlement.getId(),
				before, creationMap(entitlement));
		catalogueVersionBumper.bump(CatalogueVersionBumper.Scope.INSTITUTION, institutionId);

		return toView(entitlement);
	}


	private EntitlementStatus resolveCreateStatus(EntitlementStatus requested) {
		if (!adminScope.isSuperAdmin()) {
			return EntitlementStatus.PENDING;
		}
		return requested != null ? requested : EntitlementStatus.ACTIVE;
	}

	// update

	/**
	 * A non-super-admin may still amend a grant while it is PENDING - nothing is live yet, so
	 * there is nothing to disrupt. Once a super admin has approved it, only a super admin may
	 * touch its terms; unlike {@link #resolveCreateStatus}, this never silently reopens approval
	 * by changing status, because an ACTIVE grant already has readers depending on it and demoting
	 * it out from under them would revoke access as a side effect of an unrelated edit.
	 */
	public EntitlementView update(String entitlementId, EntitlementUpdate write) {
		Entitlement entitlement = findOrThrow(entitlementId);
		requireInstitutionAccess(entitlement.getInstitutionId());

		if (!adminScope.isSuperAdmin() && entitlement.getStatus() != EntitlementStatus.PENDING) {
			throw new ApiException(ErrorCode.FORBIDDEN_ROLE,
					"Only a super admin may amend a grant that is not PENDING.");
		}

		if (entitlement.getVersion() != write.version()) {
			throw new ApiException(ErrorCode.STALE_VERSION,
					"This entitlement was changed since you last read it.");
		}
		requireCopiesForElite(entitlement.getScopeType(), entitlement.getScopeId(), write.copies());

		Map<String, Object> before = afterMap(entitlement);

		entitlement.setCopies(write.copies());
		entitlement.setLoanPeriodDays(write.loanPeriodDays());
		entitlement.setValidFrom(write.validFrom());
		entitlement.setValidTo(write.validTo());
		entitlement.setVersion(entitlement.getVersion() + 1);
		entitlement.setUpdatedAt(Instant.now());

		entitlement = entitlementRepository.save(entitlement);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.UPDATE, "ENTITLEMENT", entitlement.getId(),
				before, afterMap(entitlement));
		catalogueVersionBumper.bump(CatalogueVersionBumper.Scope.INSTITUTION, entitlement.getInstitutionId());

		return toView(entitlement);
	}

	// ---------------------------------------------------------------- revoke

	//Soft delete: marks the grant REVOKED rather than removing the row. Now that an institution
	//can no longer self-grant access, it can no longer self-revoke it either.
	public void revoke(String entitlementId) {
		adminScope.requireSuperAdmin();
		Entitlement entitlement = findOrThrow(entitlementId);

		Map<String, Object> before = Map.of("status", String.valueOf(entitlement.getStatus()));

		entitlement.setStatus(EntitlementStatus.REVOKED);
		entitlement.setUpdatedAt(Instant.now());
		entitlementRepository.save(entitlement);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.STATUS, "ENTITLEMENT", entitlementId, before,
				Map.of("status", String.valueOf(EntitlementStatus.REVOKED)));
		catalogueVersionBumper.bump(CatalogueVersionBumper.Scope.INSTITUTION, entitlement.getInstitutionId());
	}

	// ---------------------------------------------------------------- approve / reject

	/**
	 * The only two transitions a super admin may make through this endpoint: approve a pending
	 * request into ACTIVE, or reject it into REVOKED. Every other combination is a 400, not a
	 * 409, because the request itself is invalid, not merely stale.
	 */
	public EntitlementView changeStatus(String entitlementId, EntitlementStatusChange change) {
		adminScope.requireSuperAdmin();
		Entitlement entitlement = findOrThrow(entitlementId);

		EntitlementStatus from = entitlement.getStatus();
		EntitlementStatus to = change.status();
		if (from != EntitlementStatus.PENDING
				|| (to != EntitlementStatus.ACTIVE && to != EntitlementStatus.REVOKED)) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"Cannot change entitlement status from " + from + " to " + to);
		}

		Map<String, Object> before = Map.of("status", String.valueOf(from));

		entitlement.setStatus(to);
		entitlement.setUpdatedAt(Instant.now());
		entitlement = entitlementRepository.save(entitlement);

		Map<String, Object> meta = change.reason() != null && !change.reason().isBlank()
				? Map.of("reason", change.reason())
				: null;
		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.STATUS, "ENTITLEMENT", entitlement.getId(),
				before, Map.of("status", String.valueOf(to)), meta);


		if (to == EntitlementStatus.ACTIVE) {
			catalogueVersionBumper.bump(CatalogueVersionBumper.Scope.INSTITUTION, entitlement.getInstitutionId());
		}

		return toView(entitlement);
	}

//access

	/**
	 * 403 FORBIDDEN_SCOPE before 404: an admin scoped to a different institution should not learn
	 * from the response whether a given institution id exists.
	 */
	private void requireInstitutionAccess(String institutionId) {
		if (!adminScope.canAccessInstitution(institutionId)) {
			throw new ApiException(ErrorCode.FORBIDDEN_SCOPE, "Not permitted to access this institution");
		}
	}

	private void requireInstitutionExists(String institutionId) {
		if (!institutionRepository.existsById(institutionId)) {
			throw new ApiException(ErrorCode.NOT_FOUND, "No such institution");
		}
	}

	private Entitlement findOrThrow(String entitlementId) {
		return entitlementRepository.findById(entitlementId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such entitlement"));
	}

	private boolean scopeExists(ScopeType scopeType, String scopeId) {
		return switch (scopeType) {
			case PUBLISHER -> publisherRepository.existsById(scopeId);
			case COLLECTION -> bookCollectionStore.existsById(scopeId);
			case ITEM -> catalogueItemStore.existsById(scopeId);
		};
	}

	/**
	 * ELITE is copy-limited by design (shared.md's own tier table: "yes, with a queue") - a grant
	 * with {@code copies == null} is read as UNLIMITED regardless of tier
	 * ({@code EntitlementQueryImpl.accessLevelFor}), which would silently let every reader in at
	 * once for a book the product treats as scarce. Checked here, not as a bean-validation
	 * annotation on {@link EntitlementCreate}/{@link EntitlementUpdate}, because "does this scope
	 * touch an ELITE item" needs a catalogue lookup a DTO cannot make on its own.
	 */
	private void requireCopiesForElite(ScopeType scopeType, String scopeId, Integer copies) {
		if (copies != null || !scopeHasEliteItem(scopeType, scopeId)) {
			return;
		}
		throw new ApiException(ErrorCode.VALIDATION_FAILED,
				"This grant covers at least one Elite-tier item, so copies is required - "
						+ "Elite access is always copy-limited, and leaving copies unset would make it unlimited.");
	}

	/**
	 * PUBLISHER/COLLECTION scopes can span many items of mixed tiers - published only, same
	 * reasoning as {@link #resolvedItemCount}: a draft item grants no real access yet, so its tier
	 * cannot make an otherwise-fine grant fail validation.
	 */
	private boolean scopeHasEliteItem(ScopeType scopeType, String scopeId) {
		return switch (scopeType) {
			case ITEM -> catalogueItemStore.findById(scopeId)
					.filter(item -> item.getStatus() == ItemStatus.PUBLISHED)
					.map(item -> item.getAccessTier() == AccessTier.ELITE)
					.orElse(false);
			case PUBLISHER -> catalogueItemStore.findByPublisherIdAndStatus(scopeId, ItemStatus.PUBLISHED).stream()
					.anyMatch(item -> item.getAccessTier() == AccessTier.ELITE);
			case COLLECTION -> catalogueItemStore.findByCollectionIds(scopeId).stream()
					.filter(item -> item.getStatus() == ItemStatus.PUBLISHED)
					.anyMatch(item -> item.getAccessTier() == AccessTier.ELITE);
		};
	}

	//mapping

	private EntitlementView toView(Entitlement entitlement) {
		return new EntitlementView(
				entitlement.getId(),
				entitlement.getInstitutionId(),
				entitlement.getScopeType(),
				entitlement.getScopeId(),
				scopeLabel(entitlement.getScopeType(), entitlement.getScopeId()),
				entitlement.getCopies() != null,
				entitlement.getCopies(),
				entitlement.getLoanPeriodDays(),
				entitlement.getValidFrom(),
				entitlement.getValidTo(),
				entitlement.getStatus(),
				resolvedItemCount(entitlement.getScopeType(), entitlement.getScopeId()),
				entitlement.getVersion());
	}

	/** Human readable, for the console. For example "Collection - Law and Technology 2024". */
	private String scopeLabel(ScopeType scopeType, String scopeId) {
		String name = switch (scopeType) {
			case PUBLISHER -> publisherRepository.findById(scopeId).map(Publisher::getName).orElse(null);
			case COLLECTION -> bookCollectionStore.findById(scopeId).map(BookCollection::getName).orElse(null);
			case ITEM -> catalogueItemStore.findById(scopeId).map(CatalogueItem::getTitle).orElse(null);
		};
		String prefix = scopeType.name().charAt(0) + scopeType.name().substring(1).toLowerCase();
		return prefix + " - " + (name != null ? name : "unknown");
	}

	/**
	 * How many books this grant currently resolves to. Overlapping grants each count the same
	 * book, so two grants' counts do not add up to a distinct total.
	 */
	private long resolvedItemCount(ScopeType scopeType, String scopeId) {
		return switch (scopeType) {
			case PUBLISHER -> catalogueItemStore.countByPublisherId(scopeId);
			case COLLECTION -> catalogueItemStore.countByCollectionIds(scopeId);
			case ITEM -> catalogueItemStore.findById(scopeId)
					.filter(item -> item.getStatus() == ItemStatus.PUBLISHED)
					.isPresent() ? 1 : 0;
		};
	}

	private static Map<String, Object> afterMap(Entitlement entitlement) {
		return Map.of(
				"copies", String.valueOf(entitlement.getCopies()),
				"loanPeriodDays", String.valueOf(entitlement.getLoanPeriodDays()),
				"validFrom", String.valueOf(entitlement.getValidFrom()),
				"validTo", String.valueOf(entitlement.getValidTo()),
				"status", String.valueOf(entitlement.getStatus()));
	}

	/**
	 * The create-time audit entry additionally names what was granted and to whom, where {@code institutionId}, {@code scopeType}
	 * and {@code scopeId} never change and would only be noise.
	 */
	private static Map<String, Object> creationMap(Entitlement entitlement) {
		return Map.of(
				"institutionId", entitlement.getInstitutionId(),
				"scopeType", String.valueOf(entitlement.getScopeType()),
				"scopeId", entitlement.getScopeId(),
				"copies", String.valueOf(entitlement.getCopies()),
				"loanPeriodDays", String.valueOf(entitlement.getLoanPeriodDays()),
				"validFrom", String.valueOf(entitlement.getValidFrom()),
				"validTo", String.valueOf(entitlement.getValidTo()),
				"status", String.valueOf(entitlement.getStatus()));
	}

	private static String newId() {
		return "ent_" + UUID.randomUUID().toString().substring(0, 8);
	}

}
