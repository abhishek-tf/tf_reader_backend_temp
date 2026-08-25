package com.tf.reader.common.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AdminAuditWriter {

	private static final Set<String> SENSITIVE_KEYS = Set.of("passwordhash", "password", "token", "accesstoken",
			"refreshtoken");

	private final AuditLogRepository auditLogRepository;

	public void record(String actorId, AuditLog.Action action, String entityType, String entityId,
			Map<String, Object> before, Map<String, Object> after) {
		record(actorId, action, entityType, entityId, before, after, null);
	}

	public void record(String actorId, AuditLog.Action action, String entityType, String entityId,
			Map<String, Object> before, Map<String, Object> after, Map<String, Object> meta) {
		AuditLog log = new AuditLog();
		log.setActorId(actorId);
		log.setActorEmail(null);
		log.setAction(action);
		log.setEntityType(entityType);
		log.setEntityId(entityId);
		log.setBefore(strip(before));
		log.setAfter(strip(after));
		log.setMeta(strip(meta));
		log.setAt(Instant.now());

		auditLogRepository.save(log);
	}

	private static Map<String, Object> strip(Map<String, Object> fields) {
		if (fields == null) {
			return null;
		}
		Map<String, Object> copy = new LinkedHashMap<>(fields);
		copy.keySet().removeIf(key -> SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT)));
		return copy;
	}

}
