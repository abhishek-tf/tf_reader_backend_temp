package com.tf.reader.library;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.security.CurrentUserAuthenticationToken;
import com.tf.reader.common.error.GlobalExceptionHandler;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.controller.ChangesController;
import com.tf.reader.library.controller.LibraryController;
import com.tf.reader.library.dto.ChangeEntryView;
import com.tf.reader.library.dto.ChangesResponse;
import com.tf.reader.library.dto.LibraryHold;
import com.tf.reader.library.dto.LibraryLoan;
import com.tf.reader.library.dto.LibraryOffer;
import com.tf.reader.library.dto.LibraryResponse;
import com.tf.reader.library.service.ChangeCursor;
import com.tf.reader.library.service.ChangeFeedService;
import com.tf.reader.library.service.LibraryAssembler;
import com.tf.reader.library.support.CurrentReaderResolver;
import com.tf.reader.library.support.ReaderIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The wire shape, which is the actual deliverable: the app builds its screen against this JSON, so
 * the field names, the omissions and the timestamp format are the contract.
 *
 * <p>The real {@link CurrentReaderResolver} and the shared {@link GlobalExceptionHandler} are used
 * rather than mocked, so "identity comes from the token" and "errors carry the one envelope" are
 * both exercised here rather than asserted in isolation.
 */
@WebMvcTest(controllers = { LibraryController.class, ChangesController.class })
@Import({ CurrentReaderResolver.class, GlobalExceptionHandler.class })
class LibraryEndpointsWebTest {

	private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private LibraryAssembler assembler;

	@MockitoBean
	private ChangeFeedService changeFeed;

	@Test
	@DisplayName("the library response carries loans, holds, cursor and serverTime")
	void libraryShape() throws Exception {
		when(assembler.assemble(any())).thenReturn(new LibraryResponse(
				List.of(new LibraryLoan("loan_7c1", "item_42", "ELITE", "ACTIVE",
						NOW, Instant.parse("2026-09-03T10:00:00Z"), false)),
				List.of(new LibraryHold("hold_5d1", "item_77", "OFFERED", 1, 7, null,
						Instant.parse("2026-08-17T14:00:00Z"),
						new LibraryOffer("offer_a90", Instant.parse("2026-08-20T10:30:00Z")))),
				"1189",
				NOW));

		mvc.perform(get("/api/v1/library").with(authentication(readerToken())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loans[0].loanId").value("loan_7c1"))
				.andExpect(jsonPath("$.loans[0].itemId").value("item_42"))
				.andExpect(jsonPath("$.loans[0].canPersist").value(false))
				.andExpect(jsonPath("$.loans[0].dueAt").value("2026-09-03T10:00:00Z"))
				.andExpect(jsonPath("$.holds[0].position").value(1))
				.andExpect(jsonPath("$.holds[0].queueLength").value(7))
				.andExpect(jsonPath("$.holds[0].offer.offerId").value("offer_a90"))
				.andExpect(jsonPath("$.holds[0].offer.expiresAt").value("2026-08-20T10:30:00Z"))
				.andExpect(jsonPath("$.cursor").value("1189"))
				.andExpect(jsonPath("$.serverTime").value("2026-08-20T10:00:00Z"));
	}

	@Test
	@DisplayName("an empty shelf still sends both arrays, so the app parses one shape")
	void emptyShelfStillHasArrays() throws Exception {
		when(assembler.assemble(any()))
				.thenReturn(new LibraryResponse(List.of(), List.of(), "0", NOW));

		mvc.perform(get("/api/v1/library").with(authentication(readerToken())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loans").isArray())
				.andExpect(jsonPath("$.loans").isEmpty())
				.andExpect(jsonPath("$.holds").isArray())
				.andExpect(jsonPath("$.holds").isEmpty())
				.andExpect(jsonPath("$.cursor").value("0"));
	}

	@Test
	@DisplayName("an absent field is omitted rather than sent as null")
	void omitsRatherThanNulls() throws Exception {
		// Open access never expires, so it has no dueAt; a queued hold has no offer yet. The app
		// tests for presence, so a null would read as "there is a value here".
		when(assembler.assemble(any())).thenReturn(new LibraryResponse(
				List.of(new LibraryLoan("loan_1", "item_oa9", "OPEN_ACCESS", "ACTIVE", NOW, null, true)),
				List.of(new LibraryHold("hold_1", "item_42", "QUEUED", 3, 9, null, NOW, null)),
				"0",
				NOW));

		mvc.perform(get("/api/v1/library").with(authentication(readerToken())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loans[0].dueAt").doesNotExist())
				.andExpect(jsonPath("$.holds[0].offer").doesNotExist())
				.andExpect(jsonPath("$.holds[0].estimatedWaitDays").doesNotExist());
	}

	@Test
	@DisplayName("the shelf is the token's, and no parameter can point it elsewhere")
	void identityComesFromTheToken() throws Exception {
		when(assembler.assemble(any()))
				.thenReturn(new LibraryResponse(List.of(), List.of(), "0", NOW));

		mvc.perform(get("/api/v1/library")
						.param("userId", "user_someone_else")
						.param("institutionId", "inst_someone_else")
						.with(authentication(readerToken())))
				.andExpect(status().isOk());

		// Those parameters are ignored because nothing reads them: the assembler is handed the
		// identity the resolver built from the principal.
		verify(assembler).assemble(new ReaderIdentity("user_9c2", "inst_7f3"));
	}

	@Test
	@DisplayName("no token, no library — an unauthenticated request never reaches the shelf")
	void deniesWithoutAToken() throws Exception {
		// Asserting "not served" rather than a specific status on purpose: the status for a missing
		// token belongs to the auth module's entry point, and this slice runs the default chain
		// instead. What this module owns is that nothing reads a library without an identity.
		assertThat(mvc.perform(get("/api/v1/library")).andReturn().getResponse().getStatus())
				.isNotEqualTo(200);
		assertThat(mvc.perform(get("/api/v1/loans/changes")).andReturn().getResponse().getStatus())
				.isNotEqualTo(200);

		verify(assembler, never()).assemble(any());
		verify(changeFeed, never()).changesSince(any(), any(), anyInt());
	}

	@Test
	@DisplayName("the changes response carries the feed, its cursor and hasMore")
	void changesShape() throws Exception {
		when(changeFeed.changesSince(eq("user_9c2"), any(ChangeCursor.class), anyInt()))
				.thenReturn(new ChangesResponse(
						List.of(
								new ChangeEntryView(1188L, ChangeReason.HOLD_PROMOTED, "item_42",
										null, "hold_5d1", Instant.parse("2026-08-20T09:30:00Z")),
								new ChangeEntryView(1189L, ChangeReason.ENTITLEMENT_REVOKED,
										"item_77", null, null,
										Instant.parse("2026-08-20T09:45:00Z"))),
						"1189",
						false,
						NOW));

		mvc.perform(get("/api/v1/loans/changes").param("since", "1187")
						.with(authentication(readerToken())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.changes[0].sequence").value(1188))
				.andExpect(jsonPath("$.changes[0].reason").value("HOLD_PROMOTED"))
				.andExpect(jsonPath("$.changes[0].holdId").value("hold_5d1"))
				.andExpect(jsonPath("$.changes[0].loanId").doesNotExist())
				.andExpect(jsonPath("$.changes[1].reason").value("ENTITLEMENT_REVOKED"))
				.andExpect(jsonPath("$.changes[1].holdId").doesNotExist())
				.andExpect(jsonPath("$.nextCursor").value("1189"))
				.andExpect(jsonPath("$.hasMore").value(false))
				.andExpect(jsonPath("$.serverTime").value("2026-08-20T10:00:00Z"));
	}

	@Test
	@DisplayName("omitting since means from the beginning, which is what a fresh install needs")
	void sinceIsOptional() throws Exception {
		when(changeFeed.changesSince(eq("user_9c2"), any(ChangeCursor.class), anyInt()))
				.thenReturn(new ChangesResponse(List.of(), "0", false, NOW));

		mvc.perform(get("/api/v1/loans/changes").with(authentication(readerToken())))
				.andExpect(status().isOk());

		verify(changeFeed).changesSince("user_9c2", ChangeCursor.BEGINNING,
				ChangeFeedService.DEFAULT_SIZE);
	}

	@Test
	@DisplayName("a cursor we could not have issued is a 400 in the shared envelope")
	void rejectsAForeignCursor() throws Exception {
		mvc.perform(get("/api/v1/loans/changes").param("since", "2026-08-20T10:00:00Z")
						.with(authentication(readerToken())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.path").value("/api/v1/loans/changes"))
				.andExpect(jsonPath("$.timestamp").exists());

		verify(changeFeed, never()).changesSince(any(), any(), anyInt());
	}

	@Test
	@DisplayName("an out-of-range size is a 400 rather than a quietly shortened page")
	void rejectsAnOutOfRangeSize() throws Exception {
		mvc.perform(get("/api/v1/loans/changes").param("size", "500")
						.with(authentication(readerToken())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get("/api/v1/loans/changes").param("size", "0")
						.with(authentication(readerToken())))
				.andExpect(status().isBadRequest());

		verify(changeFeed, never()).changesSince(any(), any(), anyInt());
	}

	/** What the API filter chain leaves in the security context for a verified bearer token. */
	private static CurrentUserAuthenticationToken readerToken() {
		return new CurrentUserAuthenticationToken(
				new CurrentUser("user_9c2", UserType.INSTITUTION, "inst_7f3",
						List.of("MEMBER"), List.of("col_1")),
				null,
				List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
	}

}
