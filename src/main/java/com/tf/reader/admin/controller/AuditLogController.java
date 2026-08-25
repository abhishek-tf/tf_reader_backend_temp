package com.tf.reader.admin.controller;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.admin.dto.AuditLogView;
import com.tf.reader.admin.service.AuditLogService;
import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;

/**
 * The audit trail.
 *
 * <pre>
 *   GET /api/admin/v1/audit-logs   list, newest first
 * </pre>
 *
 * <p>
 * HTTP only. Who may read the trail lives in {@link AuditLogService}.
 */
@RestController
@RequestMapping("/api/admin/v1/audit-logs")
public class AuditLogController {

	private final AuditLogService auditLogs;

	public AuditLogController(AuditLogService auditLogs) {
		this.auditLogs = auditLogs;
	}

	@GetMapping
	public PageResponse<AuditLogView> list(@RequestParam(required = false) String entityType,
			@RequestParam(required = false) String entityId, @RequestParam(required = false) String actorId,
			@RequestParam(required = false) AuditLog.Action action, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, PageQuery pageQuery) {

		return auditLogs.list(entityType, entityId, actorId, action, from, to, pageQuery);
	}

}
