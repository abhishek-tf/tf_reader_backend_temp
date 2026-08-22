package com.tf.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.audit.AuditLogRepository;

@SpringBootTest(properties = "tnf.auth.jwt.secret=" + ContainerisedInfrastructure.JWT_SECRET)
class AuditLogRepositoryTest extends ContainerisedInfrastructure {

	@Autowired
	private AuditLogRepository auditLogRepository;

	@Test
	void savesAndReadsBackAnAuditLogEntry() {
		AuditLog log = new AuditLog();
		log.setActorId("adm_01");
		log.setActorEmail("ops@tandf.example");
		log.setAction(AuditLog.Action.UPDATE);
		log.setEntityType("ENTITLEMENT");
		log.setEntityId("ent_5a1");
		log.setBefore(Map.of("copies", 2));
		log.setAfter(Map.of("copies", 5));
		log.setAt(Instant.now());

		AuditLog saved = auditLogRepository.save(log);
		AuditLog found = auditLogRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getAction()).isEqualTo(AuditLog.Action.UPDATE);
		assertThat(found.getAfter()).containsEntry("copies", 5);
	}

	@Test
	void findsByEntityTypeAndEntityId() {
		AuditLog log = new AuditLog();
		log.setActorId("adm_02");
		log.setAction(AuditLog.Action.CREATE);
		log.setEntityType("CATALOGUE_ITEM");
		log.setEntityId("item_42");
		log.setAt(Instant.now());
		auditLogRepository.save(log);

		List<AuditLog> found = auditLogRepository.findByEntityTypeAndEntityIdOrderByAtDesc("CATALOGUE_ITEM", "item_42");

		assertThat(found).extracting(AuditLog::getActorId).contains("adm_02");
	}

}
