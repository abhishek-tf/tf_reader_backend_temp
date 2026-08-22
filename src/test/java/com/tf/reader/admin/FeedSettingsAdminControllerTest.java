package com.tf.reader.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.admin.controller.FeedSettingsAdminController;
import com.tf.reader.admin.dto.FeedSettingsView;
import com.tf.reader.admin.dto.ShelfView;
import com.tf.reader.admin.service.FeedSettingsAdminService;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.error.GlobalExceptionHandler;

/**
 * HTTP surface of the feed-settings admin endpoints: status codes, serialised shape, and the
 * error envelope. The service is mocked; business rules are tested in
 * {@link FeedSettingsAdminServiceTest}.
 */
@WebMvcTest(controllers = FeedSettingsAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class FeedSettingsAdminControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	FeedSettingsAdminService service;

	private static FeedSettingsView view() {
		List<ShelfView> shelves = List.of(new ShelfView("shelf_1", "New this month", 1, List.of("item_42")),
				new ShelfView("shelf_2", "Law essentials", 2, List.of()),
				new ShelfView("shelf_3", "Audio picks", 3, List.of()));
		return new FeedSettingsView("inst_7f3", "Imperial College Library", 20, "publishedAt.desc", shelves, 5L, 1L);
	}

	@Test
	@DisplayName("GET returns 200 with the feed settings shape")
	void getReturns200() throws Exception {
		when(service.get("inst_7f3")).thenReturn(view());

		mvc.perform(get("/api/admin/v1/institutions/inst_7f3/feed-settings")).andExpect(status().isOk())
				.andExpect(jsonPath("$.institutionId").value("inst_7f3"))
				.andExpect(jsonPath("$.shelves.length()").value(3))
				.andExpect(jsonPath("$.catalogueVersion").value(5))
				.andExpect(jsonPath("$.version").value(1));
	}

	@Test
	@DisplayName("GET for an unknown institution returns 404 NOT_FOUND in the envelope")
	void getUnknownInstitutionIs404() throws Exception {
		when(service.get("inst_nope")).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "No such institution"));

		mvc.perform(get("/api/admin/v1/institutions/inst_nope/feed-settings")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	@DisplayName("PUT save returns 200 with the saved feed settings")
	void saveReturns200() throws Exception {
		when(service.save(eq("inst_7f3"), any())).thenReturn(view());

		mvc.perform(put("/api/admin/v1/institutions/inst_7f3/feed-settings").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"feedTitle":"Imperial College Library","pageSize":20,"defaultSort":"publishedAt.desc",
						 "shelves":[
						   {"id":"shelf_1","title":"New this month","order":1,"itemIds":["item_42"]},
						   {"id":"shelf_2","title":"Law essentials","order":2,"itemIds":[]},
						   {"id":"shelf_3","title":"Audio picks","order":3,"itemIds":[]}
						 ],"version":0}
						""")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
	}

	@Test
	@DisplayName("PUT with a stale version returns 409 STALE_VERSION in the envelope")
	void staleVersionIs409() throws Exception {
		when(service.save(eq("inst_7f3"), any()))
				.thenThrow(new ApiException(ErrorCode.STALE_VERSION, "This record was changed by somebody else"));

		mvc.perform(put("/api/admin/v1/institutions/inst_7f3/feed-settings").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"feedTitle":"Title","pageSize":20,
						 "shelves":[
						   {"id":"shelf_1","title":"A","order":1,"itemIds":[]},
						   {"id":"shelf_2","title":"B","order":2,"itemIds":[]},
						   {"id":"shelf_3","title":"C","order":3,"itemIds":[]}
						 ],"version":2}
						""")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));
	}

	@Test
	@DisplayName("PUT with a missing version field returns 400 VALIDATION_FAILED")
	void missingVersionIs400() throws Exception {
		mvc.perform(put("/api/admin/v1/institutions/inst_7f3/feed-settings").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"feedTitle":"Title","pageSize":20,
						 "shelves":[
						   {"id":"shelf_1","title":"A","order":1,"itemIds":[]},
						   {"id":"shelf_2","title":"B","order":2,"itemIds":[]},
						   {"id":"shelf_3","title":"C","order":3,"itemIds":[]}
						 ]}
						""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	@DisplayName("PUT with a blank feed title returns 400 VALIDATION_FAILED")
	void blankFeedTitleIs400() throws Exception {
		mvc.perform(put("/api/admin/v1/institutions/inst_7f3/feed-settings").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"feedTitle":"","pageSize":20,
						 "shelves":[
						   {"id":"shelf_1","title":"A","order":1,"itemIds":[]},
						   {"id":"shelf_2","title":"B","order":2,"itemIds":[]},
						   {"id":"shelf_3","title":"C","order":3,"itemIds":[]}
						 ],"version":0}
						""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

}
