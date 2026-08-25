package com.tf.reader.admin.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tf.reader.admin.dto.AuditLogView;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.audit.AuditLogSearchRepository;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;

import lombok.RequiredArgsConstructor;

/**
 * Reads the audit trail for the console. SUPER_ADMIN only: an audit row names other operators and
 * the entities they touched, which is more than a scoped admin is entitled to see.
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

	private final AuditLogSearchRepository auditLogSearchRepository;
	private final AdminScopeAuthorizer adminScope;

	/** Every filter is optional and they combine. Newest first, always. */
	public PageResponse<AuditLogView> list(String entityType, String entityId, String actorId, AuditLog.Action action,
			Instant from, Instant to, PageQuery pageQuery) {

		adminScope.requireSuperAdmin();

		AuditLogSearchRepository.Results results = auditLogSearchRepository.search(entityType, entityId, actorId,
				action, from, to, pageQuery.page(), pageQuery.size());

		List<AuditLogView> items = results.items().stream().map(AuditLogView::from).toList();
		return new PageResponse<>(items, pageQuery.page(), pageQuery.size(), results.total());
	}

}
