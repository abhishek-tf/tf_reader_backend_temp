package com.tf.reader.loan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.token.JwtTokenService;
import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentState;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.EntitlementRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.loan.repository.LoanRepository;

/**
 * Integration tests for Module B — the full loan lifecycle against a real Mongo (Testcontainers).
 *
 * <p>DoD #2: every path is exercised through the real HTTP stack, security filter chain, and
 * MongoDB indexes — not mocked. Covers:
 * <ul>
 *   <li>borrow SUBSCRIPTION → 201, ELITE → 201, OPEN_ACCESS → 201</li>
 *   <li>idempotent re-borrow → 200 same loanId (duplicate check before any lease)</li>
 *   <li>list (all) → 200 with serverTime; list filtered by status</li>
 *   <li>return → 200; double-return → 409 LOAN_NOT_ACTIVE (write order invariant)</li>
 *   <li>return unknown → 404; return with no token → 401</li>
 * </ul>
 */
@SpringBootTest(properties = "tnf.auth.jwt.secret=" + LoanLifecycleIT.SECRET)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoanLifecycleIT {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	// Unique item IDs so this IT never collides with LoanRepositoryTest's fixtures
	private static final String SUBSCRIPTION_ITEM = "it_sub_01";
	private static final String ELITE_ITEM        = "it_elite_01";
	private static final String OPEN_ACCESS_ITEM  = "it_oa_01";

	private static final TnfUser CALLER = new TnfUser(
			"usr_it_01", UserType.INSTITUTION, "inst_7f3", List.of("MEMBER"), List.of("col_law2024"));

	@Autowired private MockMvc mockMvc;
	@Autowired private LoanRepository loans;
	@Autowired private CatalogueItemRepository items;
	@Autowired private EntitlementRepository entitlements;
	@Autowired private PublisherRepository publishers;

	private static final String TEST_PUBLISHER = "pub_test";

	@BeforeEach
	void seed() {
		loans.deleteAll();
		items.deleteAll();
		entitlements.deleteAll();
		publishers.deleteAll();

		// Publisher must exist before items can be saved (CatalogueItemPersistenceGuard)
		Publisher pub = new Publisher();
		pub.setId(TEST_PUBLISHER);
		pub.setCode("TEST");
		pub.setName("Test Publisher");
		publishers.save(pub);

		// Seed one PUBLISHED+READY item per tier — enough to exercise every borrow path
		items.save(item(SUBSCRIPTION_ITEM, AccessTier.SUBSCRIPTION));
		items.save(item(ELITE_ITEM,        AccessTier.ELITE));
		items.save(item(OPEN_ACCESS_ITEM,  AccessTier.OPEN_ACCESS));

		// Entitle inst_7f3 for SUBSCRIPTION and ELITE (OPEN_ACCESS needs no grant)
		entitlements.save(entitlement("ent_sub",   SUBSCRIPTION_ITEM, null, 30));
		entitlements.save(entitlement("ent_elite", ELITE_ITEM,        2,    14));
	}

	private CatalogueItem item(String id, AccessTier tier) {
		CatalogueItem it = new CatalogueItem();
		it.setId(id);
		it.setPublisherId(TEST_PUBLISHER);
		it.setAccessTier(tier);
		it.setStatus(ItemStatus.PUBLISHED);
		it.setContentState(ContentState.READY);
		return it;
	}

	private Entitlement entitlement(String id, String itemId, Integer copies, int loanDays) {
		Entitlement e = new Entitlement();
		e.setId(id);
		e.setInstitutionId("inst_7f3");
		e.setScopeType(ScopeType.ITEM);
		e.setScopeId(itemId);
		e.setCopies(copies);
		e.setLoanPeriodDays(loanDays);
		e.setStatus(EntitlementStatus.ACTIVE);
		return e;
	}

	// ── Borrow ──────────────────────────────────────────────────────────────

	@Test
	void borrowSubscriptionCreatesNewLoan201() throws Exception {
		mockMvc.perform(post("/api/v1/loans")
						.header("Authorization", "Bearer " + token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"itemId\":\"" + SUBSCRIPTION_ITEM + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.loanId").isNotEmpty())
				.andExpect(jsonPath("$.itemId").value(SUBSCRIPTION_ITEM))
				.andExpect(jsonPath("$.licenceModel").value("SUBSCRIPTION"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.canPersist").value(true))
				.andExpect(jsonPath("$.borrowedAt").isNotEmpty())
				.andExpect(jsonPath("$.serverTime").isNotEmpty());
	}

	@Test
	void borrowEliteCreatesNewLoan201WithDueDate() throws Exception {
		mockMvc.perform(post("/api/v1/loans")
						.header("Authorization", "Bearer " + token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"itemId\":\"" + ELITE_ITEM + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.licenceModel").value("ELITE"))
				.andExpect(jsonPath("$.canPersist").value(false))
				.andExpect(jsonPath("$.dueAt").isNotEmpty());
	}

	@Test
	void borrowOpenAccessCreatesNewLoan201() throws Exception {
		mockMvc.perform(post("/api/v1/loans")
						.header("Authorization", "Bearer " + token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"itemId\":\"" + OPEN_ACCESS_ITEM + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.licenceModel").value("OPEN_ACCESS"))
				.andExpect(jsonPath("$.canPersist").value(true))
				.andExpect(jsonPath("$.dueAt").doesNotExist());
	}

	@Test
	void reBorrowingSameTitleReturnsExistingLoan200() throws Exception {
		// First borrow — 201
		String loanId = borrow(SUBSCRIPTION_ITEM);

		// Second borrow — 200, same loanId, no second row created (invariant #2)
		mockMvc.perform(post("/api/v1/loans")
						.header("Authorization", "Bearer " + token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"itemId\":\"" + SUBSCRIPTION_ITEM + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loanId").value(loanId));
	}

	@Test
	void borrowWithoutTokenReturns401() throws Exception {
		mockMvc.perform(post("/api/v1/loans")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"itemId\":\"" + SUBSCRIPTION_ITEM + "\"}"))
				.andExpect(status().isUnauthorized());
	}

	// ── List ────────────────────────────────────────────────────────────────

	@Test
	void listReturnsOnlyCallersLoansWithServerTime() throws Exception {
		borrow(SUBSCRIPTION_ITEM);

		mockMvc.perform(get("/api/v1/loans").header("Authorization", "Bearer " + token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.serverTime").isNotEmpty())
				.andExpect(jsonPath("$.loans[0].itemId").value(SUBSCRIPTION_ITEM));
	}

	@Test
	void listFilteredByStatusReturnsOnlyMatchingLoans() throws Exception {
		String loanId = borrow(SUBSCRIPTION_ITEM);
		returnLoan(loanId);
		borrow(ELITE_ITEM); // still ACTIVE

		mockMvc.perform(get("/api/v1/loans?status=RETURNED")
						.header("Authorization", "Bearer " + token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.loans[0].status").value("RETURNED"));

		mockMvc.perform(get("/api/v1/loans?status=ACTIVE")
						.header("Authorization", "Bearer " + token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.loans[0].status").value("ACTIVE"));
	}

	@Test
	void listWithBadStatusReturns400() throws Exception {
		mockMvc.perform(get("/api/v1/loans?status=BOGUS")
						.header("Authorization", "Bearer " + token()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// ── Return ──────────────────────────────────────────────────────────────

	@Test
	void returnActiveLoanCloses200() throws Exception {
		String loanId = borrow(SUBSCRIPTION_ITEM);

		mockMvc.perform(post("/api/v1/loans/{id}/return", loanId)
						.header("Authorization", "Bearer " + token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RETURNED"))
				.andExpect(jsonPath("$.returnedAt").isNotEmpty())
				.andExpect(jsonPath("$.serverTime").isNotEmpty());
	}

	@Test
	void doubleReturnReturns409() throws Exception {
		String loanId = borrow(SUBSCRIPTION_ITEM);
		returnLoan(loanId);

		// Second return — must be safe (409, not 500)
		mockMvc.perform(post("/api/v1/loans/{id}/return", loanId)
						.header("Authorization", "Bearer " + token()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("LOAN_NOT_ACTIVE"));
	}

	@Test
	void returnUnknownLoanReturns404() throws Exception {
		mockMvc.perform(post("/api/v1/loans/{id}/return", "loan_does_not_exist")
						.header("Authorization", "Bearer " + token()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void returnWithoutTokenReturns401() throws Exception {
		mockMvc.perform(post("/api/v1/loans/{id}/return", "loan_any"))
				.andExpect(status().isUnauthorized());
	}

	// ── Helpers ─────────────────────────────────────────────────────────────

	private String borrow(String itemId) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/loans")
						.header("Authorization", "Bearer " + token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"itemId\":\"" + itemId + "\"}"))
				.andReturn();
		String body = result.getResponse().getContentAsString();
		// extract loanId from JSON body — "loanId":"loan_abc123"
		int start = body.indexOf("\"loanId\":\"") + 10;
		int end = body.indexOf("\"", start);
		return body.substring(start, end);
	}

	private void returnLoan(String loanId) throws Exception {
		mockMvc.perform(post("/api/v1/loans/{id}/return", loanId)
				.header("Authorization", "Bearer " + token()));
	}

	private String token() {
		return JwtTokenService.forTest(SECRET, Duration.ofHours(1), Clock.systemUTC())
				.issue(CALLER).token();
	}
}
