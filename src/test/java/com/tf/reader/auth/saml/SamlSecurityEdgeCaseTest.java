package com.tf.reader.auth.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.auth.transaction.AuthTransaction;
import com.tf.reader.auth.transaction.AuthTransactionStore;

/**
 * Hostile input at the SAML ACS, over real HTTP.
 *
 * <p>Every request below is one somebody could actually send: a replayed response, a forged
 * RelayState, an oversized body, an attempt to name an institution in the callback. The bar for
 * all of them is the same and is deliberately blunt:
 *
 * <ul>
 * <li><b>never 5xx</b> - a controlled refusal, not a stack trace. A 500 on the ACS turns a bad
 * client into an error in our logs and leaks internals into a response body</li>
 * <li><b>the canonical error shape</b>, {@code {code, message, traceId}}</li>
 * <li><b>one error code</b>, {@code SAML_AUTHENTICATION_FAILED}, whatever failed. Telling a
 * caller <em>which</em> check rejected them is useful to somebody probing our configuration and
 * to nobody else</li>
 * </ul>
 *
 * <p><b>What this test honestly cannot do.</b> A response that is validly <em>signed</em> but
 * wrong in one field - wrong audience, wrong issuer, expired assertion - cannot be constructed
 * here, because signing one needs samlmock.dev's private key and we hold only their public
 * certificate. Those four cases are exercised below as unsigned responses, so what is really
 * proven is "refused", not "refused specifically by the audience check". Spring Security owns
 * those comparisons and {@code SamlRelyingPartyRegistrationTest} pins that they are configured
 * and enabled; short of a private key, that is the honest limit and it is better stated than
 * papered over with a test that looks stronger than it is.
 */
@SpringBootTest(properties = "tnf.auth.jwt.secret=" + ContainerisedInfrastructure.JWT_SECRET)
@AutoConfigureMockMvc
class SamlSecurityEdgeCaseTest extends ContainerisedInfrastructure {

	private static final String ACS = "/login/saml2/sso/tf-reader";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AuthTransactionStore transactions;

	// ───────────────────────── replay ─────────────────────────

	@Test
	void thePreciseSameCallbackTwiceIsRefusedBothTimes() throws Exception {
		// A captured ACS POST, resubmitted. The response is not signed by the IdP so it never gets
		// as far as the transaction, but the shape of the refusal must not change between attempts
		// - an endpoint that answered differently the second time would be leaking state.
		AuthTransaction transaction = transactions.open("inst_7f3");
		MockHttpServletRequestBuilder replay = acs(unsignedResponse(), transaction.id());

		assertRefused(replay);
		assertRefused(acs(unsignedResponse(), transaction.id()));
	}

	@Test
	void aRelayStateAlreadySpentCannotBeUsedAgain() throws Exception {
		// Spend it through the store, exactly as a completed sign-in would, then try to present it.
		AuthTransaction transaction = transactions.open("inst_7f3");
		assertThat(transactions.consume(transaction.id())).isPresent();

		assertRefused(acs(unsignedResponse(), transaction.id()));
	}

	// ───────────────────── RelayState the client controls ─────────────────────

	@Test
	void everyShapeOfBadRelayStateIsRefusedWithoutAServerError() throws Exception {
		for (String relayState : List.of(
				"",                                   // empty
				"   ",                                // blank
				"authTxn_never-issued",               // well-formed but invented
				"../../etc/passwd",                   // path traversal shaped
				"<script>alert(1)</script>",          // markup
				"%00null",                            // encoded null
				"a".repeat(64_000))) {                // very large

			assertRefused(acs(unsignedResponse(), relayState));
		}
	}

	@Test
	void aCallbackWithNoRelayStateAtAllIsRefused() throws Exception {
		assertRefused(post(ACS)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("SAMLResponse", unsignedResponse()));
	}

	// ───────────────────── the SAMLResponse itself ─────────────────────

	@Test
	void everyShapeOfBadSamlResponseIsRefusedWithoutAServerError() throws Exception {
		String relayState = transactions.open("inst_7f3").id();

		for (String response : List.of(
				"",                                              // empty
				"not-even-base64",                               // not base64
				base64("<samlp:Response/>"),                     // base64 but not SAML
				base64("<?xml version=\"1.0\"?><nonsense/>"),    // valid xml, wrong document
				base64("{\"json\":\"not xml\"}"),                // wrong format entirely
				unsignedResponse())) {                           // shaped right, unsigned

			assertRefused(acs(response, relayState));
		}
	}

	@Test
	void aCallbackWithNoSamlResponseAtAllIsRefused() throws Exception {
		assertRefused(post(ACS)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("RelayState", transactions.open("inst_7f3").id()));
	}

	@Test
	void anOversizedSamlResponseIsRefusedRatherThanCrashingTheParser() throws Exception {
		// 512KB of base64. An XML parser handed something this size must refuse it as invalid,
		// not exhaust a heap or surface a parser exception as a 500.
		String huge = Base64.getEncoder().encodeToString("A".repeat(512 * 1024)
				.getBytes(StandardCharsets.UTF_8));

		assertRefused(acs(huge, transactions.open("inst_7f3").id()));
	}

	@Test
	void aTamperedResponseIsRefused() throws Exception {
		// The unsigned response with its subject edited. Against a real signed assertion this is
		// what a signature check catches; here it is refused earlier, for want of a signature at
		// all. Either way it must not authenticate anybody.
		String tampered = base64(samlResponseXml().replace("john.doe@example.com", "jane.roe@example.com"));

		assertRefused(acs(tampered, transactions.open("inst_7f3").id()));
	}

	@Test
	void aResponseNamingTheWrongIssuerOrAudienceIsRefused() throws Exception {
		// See the class note: unsigned, so what is proven is "refused", not "refused BY the issuer
		// check". Kept because a regression that started accepting unsigned responses would show
		// up here as loudly as anywhere.
		String wrongIssuer = base64(samlResponseXml().replace("saml-mock", "attacker.example.com"));
		String wrongAudience = base64(samlResponseXml().replace("tf-reader-sp", "some-other-app"));

		assertRefused(acs(wrongIssuer, transactions.open("inst_7f3").id()));
		assertRefused(acs(wrongAudience, transactions.open("inst_7f3").id()));
	}

	// ───────────── the institution is never the client's to choose ─────────────

	@Test
	void noInstitutionSuppliedAtTheCallbackIsEverConsulted() throws Exception {
		// The headline B2B property. The institution is recovered from the server-side transaction
		// and from nothing else, so an attacker who knows a RelayState still cannot redirect the
		// sign-in at another tenant by naming one here.
		//
		// This asserts the refusal is unchanged by any of it; the positive half - that a DSU
		// transaction can only ever produce the DSU membership - is proven with real beans in
		// SamlAuthenticationServiceTest and EndToEndAuthFlowTest.
		AuthTransaction transaction = transactions.open("inst_ucl");

		assertRefused(post(ACS)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("SAMLResponse", unsignedResponse())
				.param("RelayState", transaction.id())
				// body
				.param("institutionId", "inst_7f3")
				.param("institution", "inst_7f3")
				// query string
				.queryParam("institutionId", "inst_7f3")
				// headers
				.header("X-Institution-Id", "inst_7f3")
				.header("X-Tenant", "inst_7f3"));
	}

	@Test
	void theSamlPackageNeverReadsAnInstitutionOffTheRequest() throws Exception {
		// The test above can only show that a refusal stays a refusal. This one shows why: no code
		// in the SAML package reads an institution from the request at all. RelayState is the one
		// parameter it is allowed to read, and that is an opaque id, not an institution.
		List<String> offenders = new java.util.ArrayList<>();

		try (var walk = java.nio.file.Files.walk(
				java.nio.file.Path.of("src/main/java/com/tf/reader/auth/saml"))) {

			for (java.nio.file.Path file : walk.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("/mock/")).toList()) {
				for (String line : java.nio.file.Files.readAllLines(file)) {
					String code = line.strip();
					if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
						continue;
					}
					boolean readsRequest = code.contains("getParameter(") || code.contains("getHeader(");
					boolean readsRelayState = code.contains("RELAY_STATE") || code.contains("RelayState");

					if (readsRequest && !readsRelayState) {
						offenders.add(file.getFileName() + " → " + code);
					}
				}
			}
		}

		assertThat(offenders)
				.describedAs("""
						SAML reads exactly one thing off the callback request: RelayState. \
						Anything else - an institution, a user, a role - would be a value the \
						client chose, arriving after authentication, which is the whole mistake \
						the transaction store exists to prevent.""")
				.isEmpty();
	}

	// ───────────────────── the entry point, before the IdP ─────────────────────

	@Test
	void theEntryPointRefusesAnUnknownRegistrationWithoutRedirecting() throws Exception {
		for (String registration : List.of("inst_7f3", "tf-reader-sp", "../tf-reader", "")) {
			var result = mockMvc.perform(get("/saml2/authenticate")
					.queryParam("registrationId", registration)).andReturn();

			assertThat(result.getResponse().getStatus()).isBetween(400, 499);
			assertThat(result.getResponse().getHeader("Location")).isNull();
		}
	}

	@Test
	void aSignInSessionIsNeverLeftBehindAsASecondCredential() throws Exception {
		// The ACS needs a session for InResponseTo. Whatever happens next, that session must not
		// survive to authenticate /api/** - see StatelessApiTest for the bug this guards.
		MockHttpSession session = new MockHttpSession();
		mockMvc.perform(acs(unsignedResponse(), transactions.open("inst_7f3").id())
				.session(session));

		mockMvc.perform(get("/api/v1/auth/me").session(session))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_MISSING"));
	}

	// ───────────────────────────── helpers ─────────────────────────────

	/**
	 * Every refusal looks the same: a redirect back to the app carrying one error code and
	 * nothing else - no identity, no institution, no token, because the browser here is
	 * mid-redirect from the IdP and a JSON body would go unread.
	 */
	private void assertRefused(MockHttpServletRequestBuilder request) throws Exception {
		mockMvc.perform(request)
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string("Location",
						"tfreader://auth/callback?error=SAML_AUTHENTICATION_FAILED"));
	}

	private MockHttpServletRequestBuilder acs(String samlResponse, String relayState) {
		return post(ACS)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("SAMLResponse", samlResponse)
				.param("RelayState", relayState);
	}

	private static String unsignedResponse() {
		return base64(samlResponseXml());
	}

	/**
	 * A structurally plausible SAML response with no signature.
	 *
	 * <p>Shaped like the real thing so the parser gets far enough to reach the checks that matter,
	 * rather than bailing out on malformed XML and proving nothing.
	 */
	private static String samlResponseXml() {
		return """
				<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
				                xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
				                ID="_edge-case" Version="2.0" IssueInstant="2026-01-01T00:00:00Z"
				                Destination="http://localhost:8080/login/saml2/sso/tf-reader">
				  <saml:Issuer>saml-mock</saml:Issuer>
				  <samlp:Status>
				    <samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
				  </samlp:Status>
				  <saml:Assertion ID="_assertion" Version="2.0" IssueInstant="2026-01-01T00:00:00Z">
				    <saml:Issuer>saml-mock</saml:Issuer>
				    <saml:Subject>
				      <saml:NameID>john.doe@example.com</saml:NameID>
				    </saml:Subject>
				    <saml:Conditions NotBefore="2026-01-01T00:00:00Z" NotOnOrAfter="2026-01-01T00:05:00Z">
				      <saml:AudienceRestriction>
				        <saml:Audience>tf-reader-sp</saml:Audience>
				      </saml:AudienceRestriction>
				    </saml:Conditions>
				  </saml:Assertion>
				</samlp:Response>
				""";
	}

	private static String base64(String xml) {
		return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
	}
}
