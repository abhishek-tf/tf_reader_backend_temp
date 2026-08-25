package com.tf.reader.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.entity.AdminStatus;
import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.audit.AuditLogRepository;

import tools.jackson.databind.JsonNode;

/**
 * GET /audit-logs over real HTTP against a real database.
 *
 * <p>Placed in this package so it can reuse {@link AbstractAdminAuthIntegrationTest} without that
 * shared base having to become public.
 */
class AuditLogApiTest extends AbstractAdminAuthIntegrationTest {

	private static final String AUDIT_LOGS_PATH = "/api/admin/v1/audit-logs";

	private static final Instant OLDEST = Instant.parse("2026-08-01T09:00:00Z");
	private static final Instant MIDDLE = Instant.parse("2026-08-10T09:00:00Z");
	private static final Instant NEWEST = Instant.parse("2026-08-20T09:00:00Z");

	@Autowired
	private AuditLogRepository auditLogRepository;

	/** The base clears admins and sessions; the trail is this test's own to manage. */
	@BeforeEach
	void clearTrail() {
		this.auditLogRepository.deleteAll();
	}

	private void saveRow(String entityType, String entityId, String actorId, AuditLog.Action action, Instant at) {
		AuditLog log = new AuditLog();
		log.setActorId(actorId);
		log.setAction(action);
		log.setEntityType(entityType);
		log.setEntityId(entityId);
		log.setAt(at);
		this.auditLogRepository.save(log);
	}

	private void saveThreeRows() {
		saveRow("ENTITLEMENT", "ent_1", "adm_a", AuditLog.Action.CREATE, OLDEST);
		saveRow("PUBLISHER", "pub_1", "adm_b", AuditLog.Action.UPDATE, MIDDLE);
		saveRow("ENTITLEMENT", "ent_2", "adm_a", AuditLog.Action.STATUS, NEWEST);
	}

	private String superAdminToken() throws Exception {
		saveAdmin("super@tandf.example", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
		return loginSuccessfully("super@tandf.example").accessToken();
	}

	private String publisherAdminToken() throws Exception {
		saveAdmin("pubadmin@tandf.example", AdminRole.PUBLISHER_ADMIN, AdminStatus.ACTIVE, "pub_r1", null);
		return loginSuccessfully("pubadmin@tandf.example").accessToken();
	}

	private MvcResult callAuditLogs(String query, String token) throws Exception {
		return this.mockMvc.perform(get(AUDIT_LOGS_PATH + query).header("Authorization", "Bearer " + token))
				.andReturn();
	}

	private JsonNode bodyAsJson(MvcResult result) throws Exception {
		return this.objectMapper.readTree(result.getResponse().getContentAsString());
	}

	// ---------------------------------------------------------------- shape

	@Test
	@DisplayName("a super admin gets 200 and the contract's AuditLogPage shape")
	void listReturnsTheContractsPageShape() throws Exception {
		String token = superAdminToken();
		saveThreeRows();

		MvcResult result = callAuditLogs("", token);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(bodyAsJson(result).propertyNames()).containsExactlyInAnyOrder("items", "page", "size", "total");
		assertThat(bodyAsJson(result).get("total").asInt()).isEqualTo(3);
	}

	@Test
	@DisplayName("each record carries exactly the contract's AuditLog fields and no credential")
	void recordsCarryTheContractsFields() throws Exception {
		String token = superAdminToken();
		saveThreeRows();

		MvcResult result = callAuditLogs("", token);

		JsonNode first = bodyAsJson(result).get("items").get(0);
		assertThat(first.propertyNames()).containsExactlyInAnyOrder("id", "actorId", "actorEmail", "action",
				"entityType", "entityId", "before", "after", "meta", "at");

		String raw = result.getResponse().getContentAsString();
		assertThat(raw).doesNotContain("passwordHash").doesNotContain("password").doesNotContain("$2a$");
	}

	// ---------------------------------------------------------------- ordering and paging

	@Test
	@DisplayName("records come back newest first")
	void recordsAreNewestFirst() throws Exception {
		String token = superAdminToken();
		saveThreeRows();

		JsonNode items = bodyAsJson(callAuditLogs("", token)).get("items");

		assertThat(items.get(0).get("entityId").asString()).isEqualTo("ent_2");
		assertThat(items.get(1).get("entityId").asString()).isEqualTo("pub_1");
		assertThat(items.get(2).get("entityId").asString()).isEqualTo("ent_1");
	}

	@Test
	@DisplayName("paging returns one record per page while reporting the full total")
	void pagingWorks() throws Exception {
		String token = superAdminToken();
		saveThreeRows();

		JsonNode firstPage = bodyAsJson(callAuditLogs("?page=0&size=1", token));
		assertThat(firstPage.get("items").size()).isEqualTo(1);
		assertThat(firstPage.get("page").asInt()).isZero();
		assertThat(firstPage.get("size").asInt()).isEqualTo(1);
		assertThat(firstPage.get("total").asInt()).isEqualTo(3);
		assertThat(firstPage.get("items").get(0).get("entityId").asString()).isEqualTo("ent_2");

		JsonNode secondPage = bodyAsJson(callAuditLogs("?page=1&size=1", token));
		assertThat(secondPage.get("items").get(0).get("entityId").asString()).isEqualTo("pub_1");
	}

	// ---------------------------------------------------------------- filters

	@Test
	@DisplayName("entityType narrows the trail")
	void filtersByEntityType() throws Exception {
		String token = superAdminToken();
		saveThreeRows();

		JsonNode body = bodyAsJson(callAuditLogs("?entityType=ENTITLEMENT", token));

		assertThat(body.get("total").asInt()).isEqualTo(2);
		assertThat(body.get("items").get(0).get("entityType").asString()).isEqualTo("ENTITLEMENT");
	}

	@Test
	@DisplayName("entityType and a date range combine")
	void filtersByEntityTypeAndDateRange() throws Exception {
		String token = superAdminToken();
		saveThreeRows();

		JsonNode body = bodyAsJson(
				callAuditLogs("?entityType=ENTITLEMENT&from=2026-08-15T00:00:00Z&to=2026-08-25T00:00:00Z", token));

		assertThat(body.get("total").asInt()).isEqualTo(1);
		assertThat(body.get("items").get(0).get("entityId").asString()).isEqualTo("ent_2");
	}

	@Test
	@DisplayName("actorId and action combine")
	void filtersByActorAndAction() throws Exception {
		String token = superAdminToken();
		saveThreeRows();

		JsonNode body = bodyAsJson(callAuditLogs("?actorId=adm_a&action=CREATE", token));

		assertThat(body.get("total").asInt()).isEqualTo(1);
		assertThat(body.get("items").get(0).get("entityId").asString()).isEqualTo("ent_1");
	}

	// ---------------------------------------------------------------- access

	@Test
	@DisplayName("a publisher admin is refused, per our SUPER_ADMIN-only decision")
	void nonSuperAdminIsForbidden() throws Exception {
		MvcResult result = callAuditLogs("", publisherAdminToken());

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(bodyAsJson(result).get("code").asString()).isEqualTo("FORBIDDEN_ROLE");
	}

	@Test
	@DisplayName("no bearer token is a 401 in the shared error envelope")
	void missingTokenIsUnauthenticated() throws Exception {
		MvcResult result = this.mockMvc.perform(get(AUDIT_LOGS_PATH)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(401);

		JsonNode body = bodyAsJson(result);
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path",
				"traceId");
		assertThat(body.get("code").asString()).isEqualTo("UNAUTHENTICATED");
	}

	// ---------------------------------------------------------------- sanitisation, end to end

	@Test
	@DisplayName("a sensitive value in meta never reaches the response, because it was never stored")
	void metaIsSanitisedOnTheWayIn() throws Exception {
		String token = superAdminToken();

		AuditLog log = new AuditLog();
		log.setActorId("adm_a");
		log.setAction(AuditLog.Action.UPDATE);
		log.setEntityType("ADMIN_USER");
		log.setEntityId("adm_9");
		log.setMeta(Map.of("passwordChanged", true));
		log.setAt(NEWEST);
		this.auditLogRepository.save(log);

		JsonNode meta = bodyAsJson(callAuditLogs("?entityType=ADMIN_USER", token)).get("items").get(0).get("meta");

		assertThat(meta.propertyNames()).containsExactly("passwordChanged");
	}

}
