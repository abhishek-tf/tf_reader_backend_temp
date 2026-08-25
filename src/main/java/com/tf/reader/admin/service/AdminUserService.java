package com.tf.reader.admin.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.tf.reader.admin.dto.AdminProfileResponse;
import com.tf.reader.admin.dto.AdminUserCreate;
import com.tf.reader.admin.dto.AdminUserUpdate;
import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.entity.AdminStatus;
import com.tf.reader.admin.entity.AdminUser;
import com.tf.reader.admin.repository.AdminUserRepository;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.common.audit.AdminAuditWriter;
import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * Console operator administration. Creating an operator is SUPER_ADMIN only.
 *
 * <p>
 * The controller is HTTP-only. Every rule lives here so a second endpoint added
 * later cannot bypass them.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

	private static final String ENTITY_TYPE = "ADMIN_USER";

	private final AdminUserRepository adminUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final AdminAuditWriter auditWriter;
	private final AdminScopeAuthorizer adminScope;
	private final MongoTemplate mongo;

	// ---------------------------------------------------------------- list

	/** Any authenticated admin may list, but a scoped one only ever sees their own operators. */
	public PageResponse<AdminProfileResponse> list(PageQuery pageQuery) {
		Query query = new Query(scopeCriteria());
		query.with(Sort.by(Sort.Direction.ASC, "email"));

		long total = mongo.count(Query.of(query).limit(0).skip(0), AdminUser.class);

		query.skip((long) pageQuery.page() * pageQuery.size()).limit(pageQuery.size());
		List<AdminProfileResponse> items = mongo.find(query, AdminUser.class).stream()
				.map(AdminProfileResponse::from)
				.toList();

		return new PageResponse<>(items, pageQuery.page(), pageQuery.size(), total);
	}

	/**
	 * One scope dimension per role, applied in the query so a scoped caller never reads a row they
	 * may not see. A missing scope claim yields a sentinel that matches nothing, and an unknown role
	 * is denied outright rather than falling through to everything.
	 */
	private Criteria scopeCriteria() {
		AdminRole role = adminScope.currentRole();
		if (role == AdminRole.SUPER_ADMIN) {
			return new Criteria();
		}
		if (role == AdminRole.PUBLISHER_ADMIN) {
			return Criteria.where("publisherId").is(adminScope.currentPublisherScope());
		}
		if (role == AdminRole.INSTITUTION_ADMIN) {
			return Criteria.where("institutionId").is(adminScope.currentInstitutionScope());
		}
		throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Not permitted to list admin users");
	}

	// ---------------------------------------------------------------- create

	public AdminProfileResponse create(AdminUserCreate create) {
		adminScope.requireSuperAdmin();
		validateScope(create.role(), create.scopePublisherId(), create.scopeInstitutionId());

		adminUserRepository.findByEmail(create.email()).ifPresent(existing -> {
			throw new ApiException(ErrorCode.CODE_TAKEN, "Email '" + create.email() + "' is already taken");
		});

		AdminUser adminUser = new AdminUser();
		adminUser.setId("adm_" + UUID.randomUUID().toString().substring(0, 8));
		adminUser.setEmail(create.email());
		adminUser.setName(create.name());
		adminUser.setPasswordHash(passwordEncoder.encode(create.password()));
		adminUser.setRole(create.role());
		adminUser.setPublisherId(create.scopePublisherId());
		adminUser.setInstitutionId(create.scopeInstitutionId());
		adminUser.setStatus(AdminStatus.ACTIVE);

		AdminUser saved = adminUserRepository.save(adminUser);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.CREATE, ENTITY_TYPE, saved.getId(), null,
				afterMap(saved));

		return AdminProfileResponse.from(saved);
	}

	// ---------------------------------------------------------------- update

	/** Replaces the editable fields. Never touches the email, which is the operator's identity. */
	public AdminProfileResponse update(String adminUserId, AdminUserUpdate update) {
		adminScope.requireSuperAdmin();
		validateScope(update.role(), update.scopePublisherId(), update.scopeInstitutionId());

		AdminUser existing = adminUserRepository.findById(adminUserId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such admin user"));

		Map<String, Object> beforeSnapshot = afterMap(existing);

		existing.setName(update.name());
		existing.setRole(update.role());
		existing.setPublisherId(update.scopePublisherId());
		existing.setInstitutionId(update.scopeInstitutionId());

		// A null password means leave the stored hash alone.
		boolean passwordChanged = !isBlank(update.password());
		if (passwordChanged) {
			existing.setPasswordHash(passwordEncoder.encode(update.password()));
		}

		AdminUser saved = adminUserRepository.save(existing);

		// Only record what actually changed, so the audit trail stays readable.
		Map<String, Object> before = new LinkedHashMap<>();
		Map<String, Object> after = new LinkedHashMap<>();
		Map<String, Object> afterSnapshot = afterMap(saved);
		for (String key : beforeSnapshot.keySet()) {
			if (!Objects.equals(beforeSnapshot.get(key), afterSnapshot.get(key))) {
				before.put(key, beforeSnapshot.get(key));
				after.put(key, afterSnapshot.get(key));
			}
		}

		// The hash is never in before/after, so this flag is the only trace of a reset.
		Map<String, Object> meta = passwordChanged ? Map.of("passwordChanged", true) : null;

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.UPDATE, ENTITY_TYPE, adminUserId, before, after,
				meta);

		return AdminProfileResponse.from(saved);
	}

	// ---------------------------------------------------------------- deactivate

	/** Sets status to DISABLED. The document is kept, so the operator's audit history still resolves. */
	public void deactivate(String adminUserId) {
		adminScope.requireSuperAdmin();

		AdminUser existing = adminUserRepository.findById(adminUserId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such admin user"));

		AdminStatus previousStatus = existing.getStatus();
		existing.setStatus(AdminStatus.DISABLED);
		adminUserRepository.save(existing);

		auditWriter.record(adminScope.currentAdminId(), AuditLog.Action.STATUS, ENTITY_TYPE, adminUserId,
				Map.of("status", previousStatus), Map.of("status", AdminStatus.DISABLED));
	}

	// ---------------------------------------------------------------- validation

	/** Each role owns exactly one scope dimension; the contract requires the other to be null. */
	private static void validateScope(AdminRole role, String scopePublisherId, String scopeInstitutionId) {
		boolean hasPublisher = !isBlank(scopePublisherId);
		boolean hasInstitution = !isBlank(scopeInstitutionId);

		if (role == AdminRole.PUBLISHER_ADMIN && !hasPublisher) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "scopePublisherId is required for PUBLISHER_ADMIN");
		}
		if (role == AdminRole.INSTITUTION_ADMIN && !hasInstitution) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "scopeInstitutionId is required for INSTITUTION_ADMIN");
		}
		if (role != AdminRole.PUBLISHER_ADMIN && hasPublisher) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "scopePublisherId is only valid for PUBLISHER_ADMIN");
		}
		if (role != AdminRole.INSTITUTION_ADMIN && hasInstitution) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"scopeInstitutionId is only valid for INSTITUTION_ADMIN");
		}
	}

	// ---------------------------------------------------------------- mapping

	/** Deliberately carries neither the password nor its hash. */
	private static Map<String, Object> afterMap(AdminUser adminUser) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("email", adminUser.getEmail());
		fields.put("name", adminUser.getName());
		fields.put("role", adminUser.getRole());
		fields.put("scopePublisherId", adminUser.getPublisherId());
		fields.put("scopeInstitutionId", adminUser.getInstitutionId());
		fields.put("status", adminUser.getStatus());
		return fields;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
