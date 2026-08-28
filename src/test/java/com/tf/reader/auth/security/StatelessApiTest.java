package com.tf.reader.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.AuthTestInstitutions;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.token.JwtProperties;
import com.tf.reader.auth.token.JwtTokenService;
import com.tf.reader.catalogue.repository.InstitutionRepository;

/**
 * The API authenticates from a bearer token and from nothing else.
 *
 * <p><b>The bug this exists to keep fixed.</b> The SAML leg needs an HTTP session, because
 * {@code InResponseTo} validation checks the returning response against the AuthnRequest held in
 * it. Spring Security persists the authentication it just created into that same session at the
 * ACS - so while the SAML leg and the API shared one filter chain, the JSESSIONID left behind by
 * sign-in authenticated every {@code /api/**} route <b>with no bearer token at all</b>: a second
 * credential this application cannot expire, cannot revoke, and never ran through
 * {@link TnfJwtValidator}. {@code /auth/me} answered 500 on a NullPointerException, because
 * {@code @AuthenticationPrincipal CurrentUser} resolves to null when the principal is a SAML
 * assertion; any endpoint the other modules add would have done the same.
 *
 * <p>The API chain is stateless, so these requests are anonymous no matter what the session says.
 */
@SpringBootTest(properties = {"tf.security.jwt.secret=" + StatelessApiTest.SECRET, "tf.security.jwt.access-token-ttl=1h"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatelessApiTest {

	static final String SECRET = "a-test-only-signing-secret-of-sufficient-length-0123456789";

	private static final TnfUser MEMBER = new TnfUser("usr_6712ab", UserType.INSTITUTION,
			"inst_7f3", List.of("MEMBER"), List.of("col_medicine"));

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InstitutionRepository institutions;

	@BeforeEach
	void seedInstitutions() {
		AuthTestInstitutions.seed(institutions);
	}

	@Test
	void aSamlSessionCannotStandInForABearerTokenOnAuthMe() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me").session(sessionAuthenticatedBySaml()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_MISSING"))
				// Not a 500, and not an identity: the controller must not have run at all.
				.andExpect(jsonPath("$.userId").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void aSamlSessionCannotReachAnyOtherProtectedRouteEither() throws Exception {
		// The rule has to hold for routes nobody has written yet. These three sit under
		// common.security.SecurityConfig's own app-api chain, not this module's, so the refusal
		// carries that chain's code (UNAUTHENTICATED) rather than this one's finer-grained
		// TOKEN_MISSING - the property under test is that the session cannot substitute for a
		// bearer token anywhere, not which chain's vocabulary the 401 uses.
		for (String path : List.of("/api/v1/loans", "/api/v1/library", "/api/v1/admin/reconcile")) {
			mockMvc.perform(get(path).session(sessionAuthenticatedBySaml()))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
	}

	@Test
	void anAuthenticatedApiCallCreatesNoSessionToStealLater() throws Exception {
		// Statelessness in the other direction: presenting a valid token must not mint a session
		// that would then be a longer-lived credential than the one-hour token it came from.
		MvcResult result = mockMvc.perform(get("/api/v1/auth/me")
						.header("Authorization", "Bearer " + tokenFor(MEMBER)))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(result.getRequest().getSession(false))
				.describedAs("the API chain must not create an HTTP session")
				.isNull();
	}

	@Test
	void theOpenSignInRouteCreatesNoSessionEither() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/saml/start")
						.param("institutionId", "inst_7f3"))
				.andExpect(status().isOk())
				.andReturn();

		assertThat(result.getRequest().getSession(false)).isNull();
	}

	/** A session holding exactly what Spring Security writes into one at the ACS. */
	private MockHttpSession sessionAuthenticatedBySaml() {
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new Saml2AssertionAuthentication(
				new StubAssertion("john.doe@example.com"),
				List.of(new SimpleGrantedAuthority("ROLE_MEMBER")), "tf-reader"));

		MockHttpSession session = new MockHttpSession();
		// The attribute name HttpSessionSecurityContextRepository uses.
		session.setAttribute("SPRING_SECURITY_CONTEXT", context);
		return session;
	}

	private String tokenFor(TnfUser user) {
		return JwtTokenService.forTest(SECRET, Duration.ofHours(1), Clock.systemUTC())
				.issue(user).token();
	}

	private record StubAssertion(String nameId) implements Saml2ResponseAssertionAccessor {

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
			return Map.of();
		}

		@Override
		public String getResponseValue() {
			return "";
		}
	}
}
