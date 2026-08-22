package com.tf.reader.loan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.token.JwtTokenService;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;

/**
 * The personal library listing, {@code GET /api/v1/loans} (Day 8).
 *
 * <p>Drives the real app resource-server chain and a real Mongo (Testcontainers). Pins the three
 * things the contract cares about: the list is scoped to the caller's {@link TnfUser#userId()}
 * (never a query param — invariant #5), an optional {@code ?status=} filter narrows it, and every
 * response carries {@code serverTime}. The token is a genuine reader-auth token minted by
 * {@link JwtTokenService}, so it passes the same {@code jwtDecoder} + {@code CurrentUser} converter
 * a real reader's request goes through (the app chain was unified onto that in the week-2 merge).
 */
@SpringBootTest(properties = "tnf.auth.jwt.secret=" + LoanListEndpointTest.SECRET)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoanListEndpointTest {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	private static final TnfUser CALLER = new TnfUser(
			"user_1", UserType.INSTITUTION, "inst_1", List.of("MEMBER"), List.of("col_1"));

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LoanRepository loans;

	@BeforeEach
	void clean() {
		loans.deleteAll();
	}

	@Test
	void listsOnlyTheCallersOwnLoans() throws Exception {
		loans.save(active("loan_mine", "user_1", "item_a"));
		loans.save(active("loan_other", "user_2", "item_b"));

		mockMvc.perform(get("/api/v1/loans").header("Authorization", "Bearer " + token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.loans[0].loanId").value("loan_mine"))
				.andExpect(jsonPath("$.serverTime").isNotEmpty());
	}

	@Test
	void narrowsTheListByStatusFilter() throws Exception {
		loans.save(active("loan_active", "user_1", "item_a"));
		loans.save(returned("loan_returned", "user_1", "item_b"));

		mockMvc.perform(get("/api/v1/loans").queryParam("status", "RETURNED")
						.header("Authorization", "Bearer " + token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.loans[0].loanId").value("loan_returned"))
				.andExpect(jsonPath("$.loans[0].status").value("RETURNED"));
	}

	@Test
	void rejectsAnUnknownStatusValueWith400() throws Exception {
		mockMvc.perform(get("/api/v1/loans").queryParam("status", "BOGUS")
						.header("Authorization", "Bearer " + token()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void refusesAnAnonymousRequest() throws Exception {
		mockMvc.perform(get("/api/v1/loans"))
				.andExpect(status().isUnauthorized());
	}

	/** A genuine reader-auth token for CALLER, signed with the same secret the app verifies with. */
	private String token() {
		return new JwtTokenService(new JwtProperties(SECRET, Duration.ofHours(1)), Clock.systemUTC())
				.issue(CALLER).token();
	}

	private Loan active(String loanId, String userId, String itemId) {
		return Loan.builder()
				.loanId(loanId).userId(userId).itemId(itemId)
				.licenceModel(LicenceModel.SUBSCRIPTION).status(LoanStatus.ACTIVE)
				.canPersist(true).borrowedAt(Instant.now()).build();
	}

	private Loan returned(String loanId, String userId, String itemId) {
		Loan loan = active(loanId, userId, itemId);
		loan.setStatus(LoanStatus.RETURNED);
		loan.setReturnedAt(Instant.now());
		return loan;
	}
}
