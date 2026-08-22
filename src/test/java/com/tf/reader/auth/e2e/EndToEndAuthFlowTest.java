package com.tf.reader.auth.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.authorization.AuthorizationService;
import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.Role;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.saml.SamlAuthenticationService;
import com.tf.reader.auth.saml.SamlAuthenticationService.SamlLoginResult;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.transaction.AuthTransaction;
import com.tf.reader.auth.transaction.AuthTransactionStore;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * Steps 1–7 as <b>one</b> flow, not seven independently-passing units.
 *
 * <p>The chain proved here: a SAML assertion the framework has validated → the institution the
 * backend chose for that sign-in → the mapped TnfUser → a real signed JWT → that JWT presented
 * over HTTP through the real filter chain → the CurrentUser it produces → the authorization
 * decisions taken from it. Every hop uses the application's own beans; nothing is stubbed except
 * the assertion, which is the one thing only the external IdP can produce.
 */
@SpringBootTest(properties = "tnf.auth.jwt.secret=" + EndToEndAuthFlowTest.SECRET)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EndToEndAuthFlowTest {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	/** The claim the mock IdP asserts the email in. A wire contract, so stated literally. */
	private static final String EMAIL_CLAIM =
			"http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private SamlAuthenticationService samlAuthentication;

	@Autowired
	private AuthTransactionStore transactions;

	@Autowired
	private AuthorizationService authorization;

	// ───────────────────────────── the happy path, whole ─────────────────────────────

	@Test
	void aSamlIdentityBecomesAJwtBecomesACurrentUserWithoutDrifting() throws Exception {
		// STEP 1 — the backend records which institution this sign-in is for.
		AuthTransaction transaction = transactions.open("inst_imperial");

		// STEP 2+3 — a validated assertion plus that transaction produce a TnfUser and a token.
		SamlLoginResult login =
				samlAuthentication.complete(samlAuthentication("john.doe@example.com"), transaction.id());

		assertThat(login.user().userId()).isEqualTo("usr_6712ab");
		assertThat(login.user().institutionId()).isEqualTo("inst_imperial");
		assertThat(login.token()).isNotBlank();

		// STEP 4+5 — the same token, over HTTP, through the real filter chain to a controller.
		String body = mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + login.token()))
				.andExpect(status().isOk())
				// The identity the controller saw must equal the identity SAML produced. If any
				// hop rewrote it, these differ.
				.andExpect(jsonPath("$.userId").value(login.user().userId()))
				.andExpect(jsonPath("$.type").value(login.user().type().name()))
				.andExpect(jsonPath("$.institutionId").value(login.user().institutionId()))
				.andExpect(jsonPath("$.roles[0]").value(login.user().roles().get(0)))
				.andExpect(jsonPath("$.collections[0]").value(login.user().collections().get(0)))
				.andReturn().getResponse().getContentAsString();

		// STEP 6 — the authorization decisions that identity supports.
		CurrentUser asRequested = new CurrentUser(login.user().userId(), login.user().type(),
				login.user().institutionId(), login.user().roles(), login.user().collections());
		authorization.requireRole(asRequested, Role.MEMBER);
		authorization.requireSameInstitution(asRequested, "inst_imperial");
		assertThatThrownBy(() -> authorization.requireRole(asRequested, Role.ADMIN))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> authorization.requireSameInstitution(asRequested, "inst_dsu"))
				.isInstanceOf(ApiException.class);

		// STEP 7 — the token handed back is usable, and the secret never appears in a response.
		assertThat(body).doesNotContain(SECRET);
	}

	@Test
	void oneIdPServesTwoInstitutionsAndTheTokensDoNotCross() throws Exception {
		// The architecture's headline claim, end to end: identical assertion, two transactions,
		// two different users, and each token reports only its own institution.
		Authentication sameIdentity = samlAuthentication("john.doe@example.com");

		SamlLoginResult imperial =
				samlAuthentication.complete(sameIdentity, transactions.open("inst_imperial").id());
		SamlLoginResult dsu =
				samlAuthentication.complete(sameIdentity, transactions.open("inst_dsu").id());

		assertThat(imperial.user().userId()).isNotEqualTo(dsu.user().userId());

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + imperial.token()))
				.andExpect(jsonPath("$.userId").value("usr_6712ab"))
				.andExpect(jsonPath("$.institutionId").value("inst_imperial"));
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + dsu.token()))
				.andExpect(jsonPath("$.userId").value("usr_8c14de"))
				.andExpect(jsonPath("$.institutionId").value("inst_dsu"));
	}

	// ───────────────────────────── the failure matrix ─────────────────────────────

	@Nested
	class Failures {

		@Test
		void case2_unknownInstitutionCannotEvenStart() throws Exception {
			mockMvc.perform(post("/api/v1/auth/saml/start")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"institutionId\":\"inst_nowhere\"}"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}

		@Test
		void case3_anIdentityWithNoMembershipGetsNoToken() {
			// Authenticated by the IdP is not provisioned by us: no token is minted at all.
			String relayState = transactions.open("inst_imperial").id();

			assertThatThrownBy(() -> samlAuthentication.complete(
					samlAuthentication("stranger@example.com"), relayState))
					.isInstanceOf(ApiException.class)
					.extracting(thrown -> ((ApiException) thrown).getCode())
					.isEqualTo(ErrorCode.USER_NOT_PROVISIONED);
		}

		@Test
		void case4to7_everyBadTokenIsRefusedWithTheRightCode() throws Exception {
			assertRefusal(null, "TOKEN_MISSING");
			assertRefusal(expiredToken(), "TOKEN_EXPIRED");
			assertRefusal(tamperedToken(), "TOKEN_INVALID");
			assertRefusal("not-a-jwt", "TOKEN_INVALID");
			assertRefusal("..", "TOKEN_INVALID");
			assertRefusal(foreignlySignedToken(), "TOKEN_INVALID");
		}

		@Test
		void case8and9_roleAndInstitutionRefusalsAreDistinguishable() {
			CurrentUser member = new CurrentUser("usr_6712ab", UserType.INSTITUTION, "inst_imperial",
					List.of("MEMBER"), List.of("col_medicine"));

			assertThatThrownBy(() -> authorization.requireRole(member, Role.ADMIN))
					.extracting(t -> ((ApiException) t).getCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE);
			assertThatThrownBy(() -> authorization.requireSameInstitution(member, "inst_dsu"))
					.extracting(t -> ((ApiException) t).getCode()).isEqualTo(ErrorCode.WRONG_INSTITUTION);
		}

		@Test
		void case10_anIndividualNeverPassesAnInstitutionCheck() {
			CurrentUser individual = new CurrentUser("usr_9f01cd", UserType.INDIVIDUAL, null,
					List.of("SUBSCRIBER"), List.of("col_open"));

			// Including against an unscoped resource, where null == null would have said yes.
			for (String resource : List.of("inst_imperial", "inst_dsu", "inst_xyz")) {
				assertThatThrownBy(() -> authorization.requireSameInstitution(individual, resource))
						.extracting(t -> ((ApiException) t).getCode())
						.isEqualTo(ErrorCode.WRONG_INSTITUTION);
			}
			assertThatThrownBy(() -> authorization.requireSameInstitution(individual, null))
					.isInstanceOf(ApiException.class);
		}

		@Test
		void case11to14_noRequestInputCanRewriteTheIdentity() throws Exception {
			String token = validToken();

			mockMvc.perform(get("/api/v1/auth/me")
							.header("Authorization", "Bearer " + token)
							.queryParam("userId", "usr_admin")
							.queryParam("institutionId", "inst_dsu")
							.queryParam("roles", "ADMIN")
							.queryParam("collections", "col_everything")
							.header("X-User-Id", "usr_admin")
							.header("X-Institution-Id", "inst_dsu")
							.header("X-Roles", "ADMIN")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"userId":"usr_admin","institutionId":"inst_dsu",
									 "roles":["ADMIN"],"collections":["col_everything"]}"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.userId").value("usr_6712ab"))
					.andExpect(jsonPath("$.institutionId").value("inst_imperial"))
					.andExpect(jsonPath("$.roles[0]").value("MEMBER"))
					.andExpect(jsonPath("$.roles", org.hamcrest.Matchers.hasSize(1)))
					.andExpect(jsonPath("$.collections[0]").value("col_medicine"));
		}
	}

	// ───────────────────────────── transaction state edge cases ─────────────────────────────

	@Nested
	class Transactions {

		@Test
		void aTransactionCannotBeCompletedTwice() {
			AuthTransaction transaction = transactions.open("inst_imperial");
			Authentication identity = samlAuthentication("john.doe@example.com");
			samlAuthentication.complete(identity, transaction.id());

			assertThatThrownBy(() -> samlAuthentication.complete(identity, transaction.id()))
					.extracting(t -> ((ApiException) t).getCode())
					.isEqualTo(ErrorCode.SAML_AUTHENTICATION_FAILED);
		}

		@Test
		void anUnknownOrMissingRelayStateIsRefused() {
			Authentication identity = samlAuthentication("john.doe@example.com");

			for (String relayState : List.of("authTxn_invented", "", "   ")) {
				assertThatThrownBy(() -> samlAuthentication.complete(identity, relayState))
						.extracting(t -> ((ApiException) t).getCode())
						.isEqualTo(ErrorCode.SAML_AUTHENTICATION_FAILED);
			}
			assertThatThrownBy(() -> samlAuthentication.complete(identity, null))
					.isInstanceOf(ApiException.class);
		}

		@Test
		void aTransactionForOneInstitutionCannotYieldAnother() {
			// The institution is read from the transaction, so a DSU transaction can only ever
			// produce the DSU membership - there is no input through which to ask for another.
			SamlLoginResult login = samlAuthentication.complete(
					samlAuthentication("john.doe@example.com"), transactions.open("inst_dsu").id());

			assertThat(login.institution().institutionId()).isEqualTo("inst_dsu");
			assertThat(login.user().institutionId()).isEqualTo("inst_dsu");
		}

	}

	// ───────────────────────────── malformed claims ─────────────────────────────

	@Nested
	class MalformedClaims {

		@Test
		void aTokenWhoseClaimsHaveTheWrongTypesIsRefused() throws Exception {
			// Spring's claim accessors coerce - a numeric roles claim would become ["123"] and a
			// numeric userId "99". Only we can sign, so this is defence in depth; a token in a
			// shape we never issue is still refused rather than reinterpreted.
			assertRefusal(signed(Map.of("userId", "u", "type", "INSTITUTION",
					"roles", 123, "collections", List.of("c"))), "TOKEN_INVALID");
			assertRefusal(signed(Map.of("userId", 99, "type", "INSTITUTION",
					"roles", List.of("MEMBER"), "collections", List.of("c"))), "TOKEN_INVALID");
			assertRefusal(signed(Map.of("userId", "u", "type", "INSTITUTION",
					"roles", "MEMBER", "collections", List.of("c"))), "TOKEN_INVALID");
			assertRefusal(signed(Map.of("userId", "u", "type", "INSTITUTION",
					"roles", List.of("MEMBER"), "collections", Map.of("a", "b"))), "TOKEN_INVALID");
			assertRefusal(signed(Map.of("userId", "u", "type", 42,
					"roles", List.of("MEMBER"), "collections", List.of("c"))), "TOKEN_INVALID");
			assertRefusal(signed(Map.of()), "TOKEN_INVALID");
		}

		@Test
		void aTokenWhoseTypeAndInstitutionDisagreeIsRefused() throws Exception {
			// Signed with the real secret, so only the claim check can catch it. The first is the
			// one that matters: an INDIVIDUAL carrying an institutionId would pass
			// requireSameInstitution for that institution, because CurrentUser reads membership
			// from the id alone.
			assertRefusal(signed(Map.of("userId", "usr_9f01cd", "type", "INDIVIDUAL",
					"institutionId", "inst_imperial", "roles", List.of("SUBSCRIBER"),
					"collections", List.of("col_open"))), "TOKEN_INVALID");
			assertRefusal(signed(Map.of("userId", "usr_6712ab", "type", "INSTITUTION",
					"roles", List.of("MEMBER"), "collections", List.of("col_medicine"))),
					"TOKEN_INVALID");
		}

		@Test
		void ourOwnTokensStillPassTheStricterCheck() throws Exception {
			// The tightening must not have narrowed what we actually issue.
			mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + validToken()))
					.andExpect(status().isOk());
		}

		@Test
		void hostileAuthorizationHeadersNeverProduceAServerError() throws Exception {
			// A 500 would turn a bad client into an error in our logs and a stack trace into a
			// response body. Every one of these must be a clean 401.
			for (String header : List.of("Bearer ", "Bearer", "Basic dXNlcjpwYXNz", "Bearer abc def",
					"Bearer " + "A".repeat(200_000), "Bearer ..", validToken())) {
				int status = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", header))
						.andReturn().getResponse().getStatus();
				assertThat(status).describedAs("Authorization: %s",
						header.length() > 40 ? header.substring(0, 40) + "…" : header)
						.isEqualTo(401);
			}
		}

		@Test
		void aLowercaseBearerSchemeIsAccepted() throws Exception {
			// Not a defect and not laxity: RFC 7235 makes the auth-scheme case-insensitive, and
			// Spring's resolver matches accordingly. Pinned because "bearer" looking wrong is the
			// sort of thing somebody later tightens by hand and breaks a conforming client with.
			mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "bearer " + validToken()))
					.andExpect(status().isOk());
		}
	}

	// ───────────────────────────── helpers ─────────────────────────────

	private void assertRefusal(String token, String expectedCode) throws Exception {
		var request = get("/api/v1/auth/me");
		if (token != null) {
			request = request.header("Authorization", "Bearer " + token);
		}
		mockMvc.perform(request)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(expectedCode))
				// A refused request must never reach the controller.
				.andExpect(jsonPath("$.userId").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	private String validToken() {
		return samlAuthentication.complete(samlAuthentication("john.doe@example.com"),
				transactions.open("inst_imperial").id()).token();
	}

	private String expiredToken() {
		return new com.tf.reader.auth.token.JwtTokenService(
				new JwtProperties(SECRET, Duration.ofHours(1)),
				Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC))
				.issue(new com.tf.reader.auth.model.TnfUser("usr_6712ab", UserType.INSTITUTION,
						"inst_imperial", List.of("MEMBER"), List.of("col_medicine")))
				.token();
	}

	private String foreignlySignedToken() {
		return new com.tf.reader.auth.token.JwtTokenService(
				new JwtProperties("a-different-secret-of-sufficient-length-9876543210abc", null),
				Clock.systemUTC())
				.issue(new com.tf.reader.auth.model.TnfUser("usr_6712ab", UserType.INSTITUTION,
						"inst_imperial", List.of("MEMBER"), List.of("col_medicine")))
				.token();
	}

	private String tamperedToken() {
		String[] parts = validToken().split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
		return parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
				payload.replace("usr_6712ab", "usr_admin1").getBytes()) + "." + parts[2];
	}

	/** A token signed with the real secret but carrying arbitrary claims. */
	private String signed(Map<String, Object> claims) {
		var encoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(new JwtProperties(SECRET, null).signingKey()));
		var builder = JwtClaimsSet.builder()
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600));
		claims.forEach(builder::claim);
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), builder.build())).getTokenValue();
	}

	private Authentication samlAuthentication(String email) {
		Saml2ResponseAssertionAccessor assertion = new StubAssertion(email,
				Map.of(EMAIL_CLAIM, List.of(email)));
		return new Saml2AssertionAuthentication(assertion, List.of(), "tf-reader");
	}

	/** The one thing only the external IdP can produce; everything else here is the real bean. */
	private record StubAssertion(String nameId, Map<String, List<Object>> attributes)
			implements Saml2ResponseAssertionAccessor {

		@Override
		public String getNameId() {
			return nameId;
		}

		@Override
		public List<String> getSessionIndexes() {
			return List.of();
		}

		@Override
		public Map<String, List<Object>> getAttributes() {
			return attributes;
		}

		@Override
		public String getResponseValue() {
			return "";
		}
	}
}
