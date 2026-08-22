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
import com.tf.reader.admin.dto.EntitlementUpdate;
import com.tf.reader.admin.dto.EntitlementView;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.catalogue.entity.BookCollection;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.catalogue.repository.BookCollectionRepository;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.EntitlementRepository;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
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
	private final BookCollectionRepository bookCollectionRepository;
	private final CatalogueItemRepository catalogueItemRepository;
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

		Instant now = Instant.now();
		Entitlement entitlement = new Entitlement();
		entitlement.setId(newId());
		entitlement.setInstitutionId(institutionId);
		entitlement.setScopeType(write.scopeType());
		entitlement.setScopeId(write.scopeId());
		entitlement.setCopies(write.copies());
		entitlement.setLoanPeriodDays(write.loanPeriodDays() != null ? write.loanPeriodDays() : DEFAULT_LOAN_PERIOD_DAYS);
		entitlement.setValidFrom(write.validFrom() != null ? write.validFrom() : LocalDate.now());
		entitlement.setValidTo(write.validTo());
		entitlement.setStatus(EntitlementStatus.ACTIVE);
		entitlement.setVersion(0);
		entitlement.setCreatedAt(now);
		entitlement.setUpdatedAt(now);

		entitlement = entitlementRepository.save(entitlement);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.CREATE, "ENTITLEMENT", entitlement.getId(),
				null, creationMap(entitlement));
		catalogueVersionBumper.bump(CatalogueVersionBumper.Scope.INSTITUTION, institutionId);

		return toView(entitlement);
	}

	// update

	public EntitlementView update(String entitlementId, EntitlementUpdate write) {
		Entitlement entitlement = findOrThrow(entitlementId);
		requireInstitutionAccess(entitlement.getInstitutionId());

		if (entitlement.getVersion() != write.version()) {
			throw new ApiException(ErrorCode.STALE_VERSION,
					"This entitlement was changed since you last read it.");
		}

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

	//Soft delete: marks the grant REVOKED rather than removing the row.
	public void revoke(String entitlementId) {
		Entitlement entitlement = findOrThrow(entitlementId);
		requireInstitutionAccess(entitlement.getInstitutionId());

		Map<String, Object> before = Map.of("status", String.valueOf(entitlement.getStatus()));

		entitlement.setStatus(EntitlementStatus.REVOKED);
		entitlement.setUpdatedAt(Instant.now());
		entitlementRepository.save(entitlement);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.UPDATE, "ENTITLEMENT", entitlementId, before,
				Map.of("status", String.valueOf(EntitlementStatus.REVOKED)));
		catalogueVersionBumper.bump(CatalogueVersionBumper.Scope.INSTITUTION, entitlement.getInstitutionId());
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
			case COLLECTION -> bookCollectionRepository.existsById(scopeId);
			case ITEM -> catalogueItemRepository.existsById(scopeId);
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
			case COLLECTION -> bookCollectionRepository.findById(scopeId).map(BookCollection::getName).orElse(null);
			case ITEM -> catalogueItemRepository.findById(scopeId).map(CatalogueItem::getTitle).orElse(null);
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
			case PUBLISHER -> catalogueItemRepository.countByPublisherId(scopeId);
			case COLLECTION -> catalogueItemRepository.countByCollectionIds(scopeId);
			case ITEM -> catalogueItemRepository.findById(scopeId)
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
