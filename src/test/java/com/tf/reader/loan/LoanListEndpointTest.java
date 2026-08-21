package com.tf.reader.loan;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.common.security.JwtProperties;
import com.tf.reader.common.security.TokenAudience;
import com.tf.reader.common.security.TokenClaims;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;

/**
 * The personal library listing, {@code GET /api/v1/loans} (Day 8).
 *
 * <p>Drives the real app resource-server chain and a real Mongo (Testcontainers). Pins the three
 * things the contract cares about: the list is scoped to the token's subject (never a query param —
 * invariant #5), an optional {@code ?status=} filter narrows it, and every response carries
 * {@code serverTime}. The token is a genuine {@code tf-app} access token, minted with the
 * application's own encoder so it passes the same decoder a real reader's token would.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoanListEndpointTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LoanRepository loans;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private JwtProperties jwtProperties;

	@BeforeEach
	void clean() {
		loans.deleteAll();
	}

	@Test
	void listsOnlyTheCallersOwnLoans() throws Exception {
		loans.save(active("loan_mine", "user_1", "item_a"));
		loans.save(active("loan_other", "user_2", "item_b"));

		mockMvc.perform(get("/api/v1/loans").header("Authorization", "Bearer " + appToken("user_1")))
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
						.header("Authorization", "Bearer " + appToken("user_1")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.loans[0].loanId").value("loan_returned"))
				.andExpect(jsonPath("$.loans[0].status").value("RETURNED"));
	}

	@Test
	void rejectsAnUnknownStatusValueWith400() throws Exception {
		mockMvc.perform(get("/api/v1/loans").queryParam("status", "BOGUS")
						.header("Authorization", "Bearer " + appToken("user_1")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void refusesAnAnonymousRequest() throws Exception {
		mockMvc.perform(get("/api/v1/loans"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	/** A genuine tf-app access token: correct issuer, audience and intent, signed with the app key. */
	private String appToken(String userId) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(jwtProperties.issuer())
				.subject(userId)
				.audience(List.of(TokenAudience.APP))
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofMinutes(15)))
				.claim(TokenClaims.TOKEN_USE, TokenClaims.USE_ACCESS)
				.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
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
