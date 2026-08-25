package com.tf.reader.admin.dto;

import java.time.Instant;
import java.util.Map;

import com.tf.reader.common.audit.AuditLog;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The contract's {@code AuditLog} shape. An explicit projection rather than the entity, so a field
 * added to the document later cannot reach a response by accident.
 */
@Schema(name = "AuditLog", description = "One recorded change or content access.")
public record AuditLogView(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,

		String actorId,

		String actorEmail,

		@Schema(example = "UPDATE", requiredMode = Schema.RequiredMode.REQUIRED) AuditLog.Action action,

		String entityType,

		String entityId,

		Map<String, Object> before,

		Map<String, Object> after,

		@Schema(description = "On a CONTENT_ACCESS record this carries the device public key "
				+ "fingerprint, the intent and the format.")
		Map<String, Object> meta,

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant at) {

	public static AuditLogView from(AuditLog log) {
		return new AuditLogView(log.getId(), log.getActorId(), log.getActorEmail(), log.getAction(),
				log.getEntityType(), log.getEntityId(), log.getBefore(), log.getAfter(), log.getMeta(), log.getAt());
	}

}
