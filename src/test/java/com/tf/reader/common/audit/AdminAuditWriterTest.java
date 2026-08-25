package com.tf.reader.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminAuditWriterTest {

	private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
	private final AdminAuditWriter writer = new AdminAuditWriter(auditLogRepository);

	@Test
	void savesTheChangedFieldsWithTheGivenActorId() {
		writer.record("adm_01", AuditLog.Action.STATUS, "PUBLISHER", "pub_5",
				Map.of("status", "ACTIVE"), Map.of("status", "SUSPENDED"));

		AuditLog saved = savedLog();
		assertThat(saved.getActorId()).isEqualTo("adm_01");
		assertThat(saved.getActorEmail()).isNull();
		assertThat(saved.getAction()).isEqualTo(AuditLog.Action.STATUS);
		assertThat(saved.getEntityType()).isEqualTo("PUBLISHER");
		assertThat(saved.getEntityId()).isEqualTo("pub_5");
		assertThat(saved.getBefore()).containsEntry("status", "ACTIVE");
		assertThat(saved.getAfter()).containsEntry("status", "SUSPENDED");
		assertThat(saved.getAt()).isNotNull();
	}

	@Test
	void writesWithNoActorIdWhenTheCallerPassesNone() {
		writer.record(null, AuditLog.Action.STATUS, "PUBLISHER", "pub_5", Map.of(), Map.of());

		assertThat(savedLog().getActorId()).isNull();
	}

	@Test
	void storesOnlyTheFieldsItWasGivenAndNeverAPasswordOrToken() {
		Map<String, Object> before = Map.of("status", "ACTIVE", "passwordHash", "old-hash");
		Map<String, Object> after = Map.of(
				"status", "SUSPENDED", "token", "t", "accessToken", "a", "refreshToken", "r");

		writer.record("adm_02", AuditLog.Action.UPDATE, "ADMIN_USER", "adm_9", before, after);

		AuditLog saved = savedLog();
		assertThat(saved.getBefore()).containsOnlyKeys("status");
		assertThat(saved.getAfter()).containsOnlyKeys("status");
	}

	@Test
	void stripsSensitiveKeysFromMetaToo() {
		Map<String, Object> meta = Map.of("passwordChanged", true, "password", "hunter2", "refreshToken", "r");

		writer.record("adm_03", AuditLog.Action.UPDATE, "ADMIN_USER", "adm_9", null, null, meta);

		assertThat(savedLog().getMeta()).containsOnlyKeys("passwordChanged");
	}

	@Test
	void leavesMetaNullWhenTheCallerPassesNone() {
		writer.record("adm_04", AuditLog.Action.UPDATE, "ADMIN_USER", "adm_9", null, null);

		assertThat(savedLog().getMeta()).isNull();
	}

	private AuditLog savedLog() {
		ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
		verify(auditLogRepository).save(captor.capture());
		return captor.getValue();
	}

}
