package com.tf.reader.admin.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.entity.AdminStatus;
import com.tf.reader.admin.entity.AdminUser;

import tools.jackson.databind.JsonNode;

/**
 * The four admin user endpoints over real HTTP, with real session-backed tokens and a real database.
 *
 * <p>Placed in this package so it can reuse {@link AbstractAdminAuthIntegrationTest} without that
 * shared base having to become public.
 */
class AdminUserApiTest extends AbstractAdminAuthIntegrationTest {

	private static final String ADMIN_USERS_PATH = "/api/admin/v1/admin-users";

	/** A signed-in SUPER_ADMIN's access token, which is what every write here needs. */
	private String superAdminToken() throws Exception {
		saveAdmin("super@tandf.example", AdminRole.SUPER_ADMIN, AdminStatus.ACTIVE);
		return loginSuccessfully("super@tandf.example").accessToken();
	}

	private String publisherAdminToken() throws Exception {
		saveAdmin("pubadmin@tandf.example", AdminRole.PUBLISHER_ADMIN, AdminStatus.ACTIVE, "pub_r1", null);
		return loginSuccessfully("pubadmin@tandf.example").accessToken();
	}

	private static String createBody(String email, String password) {
		return """
				{"email": "%s", "name": "Priya Ops", "password": "%s", "role": "SUPER_ADMIN"}"""
				.formatted(email, password);
	}

	private MvcResult send(MockHttpServletRequestBuilder builder, String token, String body) throws Exception {
		MockHttpServletRequestBuilder request = builder.header("Authorization", "Bearer " + token);
		if (body != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return this.mockMvc.perform(request).andReturn();
	}

	private JsonNode bodyAsJson(MvcResult result) throws Exception {
		return this.objectMapper.readTree(result.getResponse().getContentAsString());
	}

	/** Every error in the system carries these six fields; a new envelope here would be a bug. */
	private void assertErrorEnvelope(MvcResult result, int status, String code) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(status);

		JsonNode body = bodyAsJson(result);
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("timestamp", "status", "code", "message", "path",
				"traceId");
		assertThat(body.get("status").asInt()).isEqualTo(status);
		assertThat(body.get("code").asString()).isEqualTo(code);
		assertThat(body.get("traceId").asString()).isNotBlank();
	}

	// ---------------------------------------------------------------- happy paths

	@Test
	@DisplayName("GET returns 200 and a page")
	void listReturnsOk() throws Exception {
		MvcResult result = send(get(ADMIN_USERS_PATH), superAdminToken(), null);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(bodyAsJson(result).propertyNames()).containsExactlyInAnyOrder("items", "page", "size", "total");
	}

	@Test
	@DisplayName("POST returns 201 and never echoes the password")
	void createReturnsCreated() throws Exception {
		MvcResult result = send(post(ADMIN_USERS_PATH), superAdminToken(),
				createBody("new.ops@tandf.example", "Correct#Horse#Battery1"));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);

		JsonNode body = bodyAsJson(result);
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("id", "email", "name", "role", "scopePublisherId",
				"scopeInstitutionId", "status");
		assertThat(body.get("email").asString()).isEqualTo("new.ops@tandf.example");
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");

		String raw = result.getResponse().getContentAsString();
		assertThat(raw).doesNotContain("Correct#Horse#Battery1").doesNotContain("passwordHash").doesNotContain("$2a$");
	}

	@Test
	@DisplayName("PUT returns 200 and applies the new name")
	void updateReturnsOk() throws Exception {
		String token = superAdminToken();
		AdminUser target = this.adminUserRepository
				.findByEmail("super@tandf.example")
				.orElseThrow();

		MvcResult result = send(put(ADMIN_USERS_PATH + "/" + target.getId()), token, """
				{"name": "Renamed Ops", "role": "SUPER_ADMIN"}""");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(bodyAsJson(result).get("name").asString()).isEqualTo("Renamed Ops");
		// The email is the operator's identity and must survive an update untouched.
		assertThat(bodyAsJson(result).get("email").asString()).isEqualTo("super@tandf.example");
	}

	@Test
	@DisplayName("DELETE returns 204 and disables rather than removing the document")
	void deactivateReturnsNoContent() throws Exception {
		String token = superAdminToken();
		String targetId = this.adminUserRepository.findByEmail("super@tandf.example").orElseThrow().getId();

		MvcResult result = send(delete(ADMIN_USERS_PATH + "/" + targetId), token, null);

		assertThat(result.getResponse().getStatus()).isEqualTo(204);
		assertThat(result.getResponse().getContentAsString()).isEmpty();

		AdminUser stillThere = this.adminUserRepository.findById(targetId).orElseThrow();
		assertThat(stillThere.getStatus()).isEqualTo(AdminStatus.DISABLED);
	}

	// ---------------------------------------------------------------- failures

	@Test
	@DisplayName("a password under twelve characters is a 400 in the shared error envelope")
	void shortPasswordIsValidationFailed() throws Exception {
		MvcResult result = send(post(ADMIN_USERS_PATH), superAdminToken(),
				createBody("short.ops@tandf.example", "tooshort"));

		assertErrorEnvelope(result, 400, "VALIDATION_FAILED");
	}

	@Test
	@DisplayName("no bearer token is a 401 in the shared error envelope")
	void missingTokenIsUnauthenticated() throws Exception {
		MvcResult result = this.mockMvc.perform(get(ADMIN_USERS_PATH)).andReturn();

		assertErrorEnvelope(result, 401, "UNAUTHENTICATED");
	}

	@Test
	@DisplayName("a publisher admin creating an operator is a 403 in the shared error envelope")
	void nonSuperAdminCreateIsForbidden() throws Exception {
		MvcResult result = send(post(ADMIN_USERS_PATH), publisherAdminToken(),
				createBody("denied.ops@tandf.example", "Correct#Horse#Battery1"));

		assertErrorEnvelope(result, 403, "FORBIDDEN_ROLE");
	}

	@Test
	@DisplayName("a publisher admin may still list, since listing is scoped rather than refused")
	void nonSuperAdminMayList() throws Exception {
		MvcResult result = send(get(ADMIN_USERS_PATH), publisherAdminToken(), null);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("a duplicate email is a 409 CODE_TAKEN")
	void duplicateEmailIsCodeTaken() throws Exception {
		String token = superAdminToken();
		String body = createBody("dupe.ops@tandf.example", "Correct#Horse#Battery1");

		assertThat(send(post(ADMIN_USERS_PATH), token, body).getResponse().getStatus()).isEqualTo(201);

		assertErrorEnvelope(send(post(ADMIN_USERS_PATH), token, body), 409, "CODE_TAKEN");
	}

	@Test
	@DisplayName("PUT on an unknown id is a 404 NOT_FOUND")
	void updateUnknownIsNotFound() throws Exception {
		MvcResult result = send(put(ADMIN_USERS_PATH + "/adm_nope"), superAdminToken(), """
				{"name": "Nobody", "role": "SUPER_ADMIN"}""");

		assertErrorEnvelope(result, 404, "NOT_FOUND");
	}

	@Test
	@DisplayName("DELETE on an unknown id is a 404 NOT_FOUND")
	void deactivateUnknownIsNotFound() throws Exception {
		MvcResult result = send(delete(ADMIN_USERS_PATH + "/adm_nope"), superAdminToken(), null);

		assertErrorEnvelope(result, 404, "NOT_FOUND");
	}

}
