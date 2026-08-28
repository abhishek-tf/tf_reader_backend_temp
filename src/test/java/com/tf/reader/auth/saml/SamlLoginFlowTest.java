package com.tf.reader.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

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
import com.tf.reader.auth.AuthTestInstitutions;
import com.tf.reader.auth.transaction.AuthTransaction;
import com.tf.reader.auth.transaction.AuthTransactionStore;
import com.tf.reader.auth.security.UserSecurityConfig;
import com.tf.reader.catalogue.repository.InstitutionRepository;

/**
 * Exercises the real Spring Security SAML filter chain against the real configuration.
 *
 * <p>Runs entirely locally: it never calls samlmock.dev. The outbound half can be checked by
 * reading the redirect we issue, and the inbound half by feeding the ACS a response the IdP did
 * not sign. Driving the actual mock IdP needs a human to click its Send button, so that part is
 * a manual procedure documented in CLAUDE.md rather than a test that would fail whenever a
 * third-party website is down.
 */
// The application refuses to start without a signing secret, so the test context supplies a
// throwaway one. No secret is committed for any real environment.
//
// spring.profiles.active is forced empty because application.yml defaults it to "local", and a
// developer's own gitignored application-local.yml (never committed - see CLAUDE.md) points the
// mock registration at their own machine instead of samlmock.dev. Without this, whether these
// assertions hold depends on files nobody else on the team has.
@SpringBootTest(properties = {
		"tf.security.jwt.secret=a-test-only-signing-secret-of-sufficient-length-0123456789",
		"spring.profiles.active=" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SamlLoginFlowTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AuthTransactionStore transactions;

	@Autowired
	private InstitutionRepository institutions;

	@BeforeEach
	void seedInstitutions() {
		AuthTestInstitutions.seed(institutions);
	}

	@Test
	void theEntryPointRedirectsToTheOneMockIdpCarryingOurTransactionAsRelayState() throws Exception {
		AuthTransaction transaction = transactions.open("inst_7f3");

		MvcResult result = mockMvc.perform(get("/saml2/authenticate")
						.queryParam("registrationId", "tf-reader")
						.queryParam(UserSecurityConfig.AUTH_TRANSACTION_PARAM, transaction.id()))
				.andExpect(status().is3xxRedirection())
				.andReturn();

		String location = URLDecoder.decode(result.getResponse().getRedirectedUrl(),
				StandardCharsets.UTF_8);

		assertThat(location).startsWith("https://samlmock.dev/idp");
		// The audience and ACS pre-fill the mock IdP's form.
		assertThat(location).contains("aud=tf-reader-sp");
		assertThat(location).contains("acs_url=http://localhost:8080/login/saml2/sso/tf-reader");
		// A real AuthnRequest, so the IdP can echo InResponseTo.
		assertThat(location).contains("SAMLRequest=");
		// And our opaque transaction id, which is how the institution survives the round trip.
		assertThat(location).contains("RelayState=" + transaction.id());
	}

	@Test
	void everyInstitutionIsSentToTheSameIdp() throws Exception {
		// One integration, many institutions. If these ever differ, the architecture has
		// regressed to one IdP per institution.
		String imperial = idpUrlFor("inst_7f3");
		String dsu = idpUrlFor("inst_ucl");
		String xyz = idpUrlFor("inst_leeds");

		assertThat(imperial).isEqualTo(dsu).isEqualTo(xyz);
		assertThat(imperial).isEqualTo("https://samlmock.dev/idp");
	}

	@Test
	void theAcsRejectsAResponseTheIdpDidNotSign() throws Exception {
		// The assertion is neither signed by the configured certificate nor well formed, so it
		// must be refused - and refused by sending the browser back to the app with an error,
		// which is the only thing that can act on a refusal reached by IdP redirect.
		String forged = java.util.Base64.getEncoder().encodeToString(
				"<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\"/>"
						.getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(post("/login/saml2/sso/tf-reader")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("SAMLResponse", forged))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location",
						"tfreader://auth/callback?error=SAML_AUTHENTICATION_FAILED"));
	}

	@Test
	void theAcsRejectsRubbishRatherThanAcceptingIt() throws Exception {
		mockMvc.perform(post("/login/saml2/sso/tf-reader")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("SAMLResponse", "not-even-base64")
						.param("RelayState", transactions.open("inst_7f3").id()))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location",
						"tfreader://auth/callback?error=SAML_AUTHENTICATION_FAILED"));
	}

	@Test
	void theStartEndpointNeedsNoTokenButEverythingElseDoes() throws Exception {
		mockMvc.perform(post("/api/v1/auth/saml/start").param("institutionId", "inst_7f3"))
				.andExpect(status().isOk());

		// Deny by default, and refused as JSON the app can read rather than a redirect to the
		// IdP, which is what saml2Login would do on its own. /api/v1/loans sits under
		// common.security.SecurityConfig's own app-api chain, not this module's, hence
		// UNAUTHENTICATED rather than this module's finer-grained TOKEN_MISSING.
		mockMvc.perform(get("/api/v1/loans"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void theEntryPointIsUnusableWithoutAKnownRegistration() throws Exception {
		mockMvc.perform(get("/saml2/authenticate").queryParam("registrationId", "inst_7f3"))
				.andExpect(status().is4xxClientError())
				.andExpect(header().doesNotExist("Location"));
	}

	private String idpUrlFor(String institutionId) throws Exception {
		AuthTransaction transaction = transactions.open(institutionId);
		String redirect = mockMvc.perform(get("/saml2/authenticate")
						.queryParam("registrationId", "tf-reader")
						.queryParam(UserSecurityConfig.AUTH_TRANSACTION_PARAM, transaction.id()))
				.andReturn()
				.getResponse()
				.getRedirectedUrl();
		return redirect.substring(0, redirect.indexOf('?'));
	}
}
