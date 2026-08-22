package com.tf.reader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.admin.controller.EntitlementAdminController;
import com.tf.reader.admin.dto.EntitlementView;
import com.tf.reader.admin.service.EntitlementAdminService;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.error.GlobalExceptionHandler;
import com.tf.reader.common.page.PageResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * HTTP surface of the four entitlement admin endpoints: status codes, serialised shape, and the
 * error envelope. The service is mocked; business rules are tested in
 * {@link EntitlementAdminServiceTest}.
 *
 * <p>Security filters are excluded ({@code addFilters = false}) so shape and status-code
 * assertions are fast and don't need a JWT.
 */
@WebMvcTest(controllers = EntitlementAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class EntitlementAdminControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	ObjectMapper json;

	@MockitoBean
	EntitlementAdminService service;

	private static EntitlementView view() {
		return new EntitlementView("ent_5a1", "inst_7f3", ScopeType.COLLECTION, "col_law2024",
				"Collection - Law and Technology 2024", true, 2, 14, LocalDate.parse("2026-08-01"),
				LocalDate.parse("2026-12-31"), EntitlementStatus.ACTIVE, 2, 0);
	}

	// ---------------------------------------------------------------- list

	@Test
	@DisplayName("GET list returns 200 with the four page keys")
	@SuppressWarnings("unchecked")
	void listReturns200WithPageShape() throws Exception {
		when(service.list(eq("inst_7f3"), any())).thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1));

		String body = mvc.perform(get("/api/admin/v1/institutions/inst_7f3/entitlements"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andReturn().getResponse()
				.getContentAsString();

		Map<String, Object> parsed = json.readValue(body, Map.class);
		assertThat(parsed.keySet()).containsExactlyInAnyOrder("items", "page", "size", "total");
	}

	@Test
	@DisplayName("GET list for an unknown institution returns 404 NOT_FOUND")
	void listForUnknownInstitutionIs404() throws Exception {
		when(service.list(eq("inst_ghost"), any()))
				.thenThrow(new ApiException(ErrorCode.NOT_FOUND, "No such institution"));

		mvc.perform(get("/api/admin/v1/institutions/inst_ghost/entitlements")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	// ---------------------------------------------------------------- create

	@Test
	@DisplayName("POST create returns 201 with the entitlement body")
	void createReturns201() throws Exception {
		when(service.create(eq("inst_7f3"), any())).thenReturn(view());

		mvc.perform(post("/api/admin/v1/institutions/inst_7f3/entitlements").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"scopeType":"COLLECTION","scopeId":"col_law2024","copies":2,
						 "loanPeriodDays":14,"validFrom":"2026-08-01","validTo":"2026-12-31"}
						""")).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value("ent_5a1"))
				.andExpect(jsonPath("$.copyLimited").value(true)).andExpect(jsonPath("$.resolvedItemCount").value(2));
	}

	@Test
	@DisplayName("POST with a missing scopeType returns 400 VALIDATION_FAILED")
	void missingScopeTypeIs400() throws Exception {
		mvc.perform(post("/api/admin/v1/institutions/inst_7f3/entitlements").contentType(MediaType.APPLICATION_JSON)
				.content("{\"scopeId\":\"col_law2024\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("POST with an unknown scopeId returns 400 VALIDATION_FAILED in the envelope")
	void unknownScopeIdIs400() throws Exception {
		when(service.create(eq("inst_7f3"), any()))
				.thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED, "No collection exists with id 'col_ghost'"));

		mvc.perform(post("/api/admin/v1/institutions/inst_7f3/entitlements").contentType(MediaType.APPLICATION_JSON)
				.content("{\"scopeType\":\"COLLECTION\",\"scopeId\":\"col_ghost\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("An admin scoped to a different institution gets 403 FORBIDDEN_SCOPE")
	void wrongInstitutionScopeIs403() throws Exception {
		when(service.create(eq("inst_7f3"), any()))
				.thenThrow(new ApiException(ErrorCode.FORBIDDEN_SCOPE, "Not permitted to access this institution"));

		mvc.perform(post("/api/admin/v1/institutions/inst_7f3/entitlements").contentType(MediaType.APPLICATION_JSON)
				.content("{\"scopeType\":\"PUBLISHER\",\"scopeId\":\"pub_rtlg\"}")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE"));
	}

	// ---------------------------------------------------------------- update

	@Test
	@DisplayName("PUT update returns 200 with the updated entitlement")
	void updateReturns200() throws Exception {
		when(service.update(eq("ent_5a1"), any())).thenReturn(view());

		mvc.perform(put("/api/admin/v1/entitlements/ent_5a1").contentType(MediaType.APPLICATION_JSON).content("""
				{"copies":2,"loanPeriodDays":14,"validFrom":"2026-08-01","validTo":"2026-12-31","version":0}
				""")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("ent_5a1"));
	}

	@Test
	@DisplayName("PUT with a missing version returns 400 VALIDATION_FAILED")
	void missingVersionIs400() throws Exception {
		mvc.perform(put("/api/admin/v1/entitlements/ent_5a1").contentType(MediaType.APPLICATION_JSON)
				.content("{\"copies\":2}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("PUT with a stale version returns 409 STALE_VERSION")
	void staleVersionIs409() throws Exception {
		when(service.update(eq("ent_5a1"), any()))
				.thenThrow(new ApiException(ErrorCode.STALE_VERSION, "This entitlement was changed since you last read it."));

		mvc.perform(put("/api/admin/v1/entitlements/ent_5a1").contentType(MediaType.APPLICATION_JSON)
				.content("{\"version\":1}")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("STALE_VERSION"));
	}

	@Test
	@DisplayName("PUT on an unknown entitlement returns 404 NOT_FOUND")
	void updateUnknownEntitlementIs404() throws Exception {
		when(service.update(eq("ent_ghost"), any())).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "No such entitlement"));

		mvc.perform(put("/api/admin/v1/entitlements/ent_ghost").contentType(MediaType.APPLICATION_JSON)
				.content("{\"version\":0}")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	// ---------------------------------------------------------------- revoke

	@Test
	@DisplayName("DELETE revoke returns 204 with no body")
	void revokeReturns204() throws Exception {
		String body = mvc.perform(delete("/api/admin/v1/entitlements/ent_5a1")).andExpect(status().isNoContent())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).isEmpty();
	}

	@Test
	@DisplayName("DELETE on an unknown entitlement returns 404 NOT_FOUND")
	void revokeUnknownEntitlementIs404() throws Exception {
		org.mockito.Mockito.doThrow(new ApiException(ErrorCode.NOT_FOUND, "No such entitlement"))
				.when(service).revoke("ent_ghost");

		mvc.perform(delete("/api/admin/v1/entitlements/ent_ghost")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

}
