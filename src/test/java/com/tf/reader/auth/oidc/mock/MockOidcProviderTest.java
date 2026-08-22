package com.tf.reader.auth.oidc.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.springframework.http.MediaType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.MockOidcTestProfile;

/**
 * The mock provider, tested as what it is: somebody else's OpenID Connect server.
 *
 * <p>Not one assertion here is about the Reader. These are the checks a real provider performs
 * and the responses a real provider gives, and they matter because a mock that accepted anything
 * would hide exactly the class of bug this whole exercise exists to surface early - a redirect
 * uri that does not match what is registered, a client id typo, a missing {@code openid} scope
 * that silently produces no ID token, a code that can be spent twice.
 *
 * <p>Runs on the application's own port: {@code mock-oidc.port} is deliberately unset in tests,
 * so the suite needs no fixed port to be free and can run in parallel with anything else.
 */
@SpringBootTest(properties = { "tnf.auth.jwt.secret=" + ContainerisedInfrastructure.JWT_SECRET })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MockOidcProviderTest extends MockOidcTestProfile {

	@Autowired
	private MockMvc mockMvc;

	// ───────────────────────────── discovery ─────────────────────────────

	@Test
	void theDiscoveryDocumentDescribesTheProvider() throws Exception {
		mockMvc.perform(get("/.well-known/openid-configuration"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuer").value(ISSUER))
				.andExpect(jsonPath("$.authorization_endpoint").value(ISSUER + "/oauth2/authorize"))
				.andExpect(jsonPath("$.token_endpoint").value(ISSUER + "/oauth2/token"))
				.andExpect(jsonPath("$.jwks_uri").value(ISSUER + "/oauth2/jwks"))
				.andExpect(jsonPath("$.response_types_supported[0]").value("code"))
				.andExpect(jsonPath("$.subject_types_supported[0]").value("public"))
				.andExpect(jsonPath("$.id_token_signing_alg_values_supported[0]").value("RS256"))
				.andExpect(jsonPath("$.scopes_supported",
						org.hamcrest.Matchers.hasItems("openid", "profile", "email")))
				.andExpect(jsonPath("$.claims_supported", org.hamcrest.Matchers.hasItems(
						"sub", "iss", "aud", "exp", "iat", "nonce", "email", "name")));
	}

	@Test
	void theDiscoveryDocumentAgreesWithTheEndpointsThatActuallyExist() throws Exception {
		// A discovery document that described endpoints the provider does not serve would be worse
		// than none: a client library configures itself from it and then fails somewhere else.
		Map<?, ?> document = json(mockMvc.perform(get("/.well-known/openid-configuration")));

		String jwks = ((String) document.get("jwks_uri")).substring(ISSUER.length());
		mockMvc.perform(get(jwks)).andExpect(status().isOk());
	}

	// ───────────────────────────── JWKS ─────────────────────────────

	@Test
	void theJwksEndpointPublishesAnRsaPublicKeyWithAKeyId() throws Exception {
		mockMvc.perform(get("/oauth2/jwks"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.keys[0].kty").value("RSA"))
				.andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
				// The modulus and exponent - the public key itself.
				.andExpect(jsonPath("$.keys[0].n").isNotEmpty())
				.andExpect(jsonPath("$.keys[0].e").isNotEmpty());
	}

	@Test
	void theJwksEndpointNeverPublishesThePrivateKey() throws Exception {
		// RSAKey holds both halves and serialising the whole thing would put the private key at a
		// public endpoint. toPublicJWK() is what prevents it, and this is the test that says so.
		String body = mockMvc.perform(get("/oauth2/jwks"))
				.andReturn().getResponse().getContentAsString();

		// d is the private exponent; p, q, dp, dq and qi are the CRT parameters. Any one of them
		// is enough to sign tokens as this provider.
		assertThat(body).doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"")
				.doesNotContain("\"dp\"").doesNotContain("\"dq\"").doesNotContain("\"qi\"");
	}

	// ───────────────────────── the authorization endpoint ─────────────────────────

	@Test
	void theAuthorizationEndpointShowsASignInPage() throws Exception {
		mockMvc.perform(authorizeRequest())
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Local Mock OIDC")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("john.doe@example.com")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Login &amp; Authorize")));
	}

	@Test
	void theSignInPageCarriesTheRequestForwardButNoTokenOrCode() throws Exception {
		// The page must not shortcut the flow. If a code or a token appeared here, the redirect
		// and the back-channel exchange would be untested theatre.
		String page = mockMvc.perform(authorizeRequest())
				.andReturn().getResponse().getContentAsString();

		assertThat(page).contains("name=\"state\" value=\"" + STATE + "\"");
		assertThat(page).contains("name=\"nonce\" value=\"" + NONCE + "\"");
		assertThat(page).doesNotContain("code=").doesNotContain("id_token").doesNotContain("eyJ");
	}

	@Test
	void theAuthorizationEndpointRejectsAnUnknownClientId() throws Exception {
		mockMvc.perform(get("/oauth2/authorize")
						.queryParam("client_id", "somebody-elses-app")
						.queryParam("redirect_uri", REDIRECT_URI)
						.queryParam("response_type", "code")
						.queryParam("scope", "openid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("unauthorized_client"));
	}

	@Test
	void theAuthorizationEndpointRejectsARedirectUriThatIsNotRegistered() throws Exception {
		// The most important check in the file. An open redirect here means an attacker can have
		// the provider deliver a legitimately issued authorization code to a url they control,
		// which is the classic route from "OAuth integration" to "account takeover".
		for (String hostile : new String[] {
				"https://attacker.example.com/callback",
				// A prefix of the real one - what a "startsWith" check would wave through.
				REDIRECT_URI + ".attacker.example.com",
				// Our own host, somebody else's path.
				"http://localhost:8080/some/other/path",
				"" }) {

			mockMvc.perform(get("/oauth2/authorize")
							.queryParam("client_id", CLIENT_ID)
							.queryParam("redirect_uri", hostile)
							.queryParam("response_type", "code")
							.queryParam("scope", "openid"))
					.andExpect(status().isBadRequest())
					// And crucially NOT a redirect: reporting the error to an unvalidated uri
					// would be the same hole wearing a different hat.
					.andExpect(header().doesNotExist("Location"))
					.andExpect(jsonPath("$.error").value("invalid_request"));
		}
	}

	@Test
	void theAuthorizationEndpointRejectsAnythingButTheCodeFlow() throws Exception {
		// The implicit flow returns tokens in the url fragment. Not supported, on purpose.
		mockMvc.perform(get("/oauth2/authorize")
						.queryParam("client_id", CLIENT_ID)
						.queryParam("redirect_uri", REDIRECT_URI)
						.queryParam("response_type", "token id_token")
						.queryParam("scope", "openid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("unsupported_response_type"));
	}

	@Test
	void theAuthorizationEndpointRequiresTheOpenidScope() throws Exception {
		// Without it there is no ID token, so the sign-in is plain OAuth 2.0 and carries no
		// assertion about who anybody is. Refused early rather than discovered at the callback.
		mockMvc.perform(get("/oauth2/authorize")
						.queryParam("client_id", CLIENT_ID)
						.queryParam("redirect_uri", REDIRECT_URI)
						.queryParam("response_type", "code")
						.queryParam("scope", "profile email"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_scope"));
	}

	@Test
	void authorizingRedirectsBackWithACodeAndTheStateUntouched() throws Exception {
		MvcResult result = mockMvc.perform(consentPost())
				.andExpect(status().isFound())
				.andReturn();

		String location = result.getResponse().getRedirectedUrl();

		assertThat(location).startsWith(REDIRECT_URI);
		assertThat(location).contains("code=");
		// Echoed back exactly. It is the relying party's value and the provider's only job with it
		// is to return it unchanged.
		assertThat(location).contains("state=" + STATE);
		// And no token in the url, ever. This is the whole reason the code flow exists.
		assertThat(location).doesNotContain("id_token").doesNotContain("access_token");
	}

	@Test
	void theConsentPostIsValidatedAgainAndNotTrusted() throws Exception {
		// The form posts back the parameters it was rendered with, and a hand-written POST is free
		// to change them. Validating only at render time would be a check an attacker simply skips.
		mockMvc.perform(post("/oauth2/authorize")
						.param("client_id", CLIENT_ID)
						.param("redirect_uri", "https://attacker.example.com/callback")
						.param("response_type", "code")
						.param("scope", "openid")
						.param("state", STATE))
				.andExpect(status().isBadRequest())
				.andExpect(header().doesNotExist("Location"));
	}

	// ───────────────────────── the token endpoint ─────────────────────────

	@Test
	void theTokenEndpointExchangesAValidCode() throws Exception {
		String code = authorizationCode();

		mockMvc.perform(tokenRequest(code).param("client_secret", CLIENT_SECRET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token_type").value("Bearer"))
				.andExpect(jsonPath("$.expires_in").isNumber())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.scope").value("openid profile email"))
				// Three segments: a real signed JWT, not a stand-in.
				.andExpect(jsonPath("$.id_token").value(org.hamcrest.Matchers.matchesRegex(
						"^[\\w-]+\\.[\\w-]+\\.[\\w-]+$")));
	}

	@Test
	void anAuthorizationCodeIsSingleUse() throws Exception {
		String code = authorizationCode();

		mockMvc.perform(tokenRequest(code).param("client_secret", CLIENT_SECRET))
				.andExpect(status().isOk());

		// The second attempt is the one that matters: a code that could be replayed would let
		// anyone who saw it in a browser history or a proxy log mint themselves a session.
		mockMvc.perform(tokenRequest(code).param("client_secret", CLIENT_SECRET))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_grant"));
	}

	@Test
	void anUnknownCodeIsRefusedTheSameWayAReusedOneIs() throws Exception {
		// Same error for unknown, expired and already-redeemed, as RFC 6749 §5.2 requires:
		// distinguishing them tells an attacker whether a code they guessed ever existed.
		mockMvc.perform(tokenRequest("a-code-that-was-never-issued")
						.param("client_secret", CLIENT_SECRET))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_grant"));
	}

	@Test
	void theTokenEndpointRejectsAWrongClientSecret() throws Exception {
		String code = authorizationCode();

		mockMvc.perform(tokenRequest(code).param("client_secret", "not-the-secret"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_client"));
	}

	@Test
	void theTokenEndpointRejectsAnUnknownClientId() throws Exception {
		String code = authorizationCode();

		mockMvc.perform(post("/oauth2/token")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("grant_type", "authorization_code")
						.param("code", code)
						.param("client_id", "somebody-elses-app")
						.param("client_secret", CLIENT_SECRET)
						.param("redirect_uri", REDIRECT_URI))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_client"));
	}

	@Test
	void theTokenEndpointRejectsAMismatchedRedirectUri() throws Exception {
		// RFC 6749 §4.1.3. The redirect uri is repeated in the exchange precisely so a code
		// delivered to one uri cannot be redeemed towards another.
		String code = authorizationCode();

		mockMvc.perform(post("/oauth2/token")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("grant_type", "authorization_code")
						.param("code", code)
						.param("client_id", CLIENT_ID)
						.param("client_secret", CLIENT_SECRET)
						.param("redirect_uri", "https://attacker.example.com/callback"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_grant"));
	}

	@Test
	void theTokenEndpointRejectsAnyOtherGrantType() throws Exception {
		mockMvc.perform(post("/oauth2/token")
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.param("grant_type", "password")
						.param("code", authorizationCode())
						.param("client_id", CLIENT_ID)
						.param("client_secret", CLIENT_SECRET)
						.param("redirect_uri", REDIRECT_URI))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("unsupported_grant_type"));
	}

	@Test
	void aRefusedExchangeStillSpendsNothingItShouldNot() throws Exception {
		// The client check runs BEFORE the code is looked up, so a caller who has not proved they
		// are the client cannot burn somebody else's code by guessing at it.
		String code = authorizationCode();

		mockMvc.perform(tokenRequest(code).param("client_secret", "not-the-secret"))
				.andExpect(status().isBadRequest());

		// Still redeemable by the real client.
		mockMvc.perform(tokenRequest(code).param("client_secret", CLIENT_SECRET))
				.andExpect(status().isOk());
	}

	// ───────────────────────── logging hygiene ─────────────────────────

	@Test
	void noCodeSecretOrTokenIsWrittenToTheLog(CapturedOutput output) throws Exception {
		String code = authorizationCode();
		String body = mockMvc.perform(tokenRequest(code).param("client_secret", CLIENT_SECRET))
				.andReturn().getResponse().getContentAsString();

		String idToken = (String) json(body).get("id_token");
		String accessToken = (String) json(body).get("access_token");

		assertThat(output).doesNotContain(code);
		assertThat(output).doesNotContain(CLIENT_SECRET);
		assertThat(output).doesNotContain(idToken);
		assertThat(output).doesNotContain(accessToken);
	}

	// ───────────────────────────── helpers ─────────────────────────────

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizeRequest() {
		return get("/oauth2/authorize")
				.queryParam("client_id", CLIENT_ID)
				.queryParam("redirect_uri", REDIRECT_URI)
				.queryParam("response_type", "code")
				.queryParam("scope", "openid profile email")
				.queryParam("state", STATE)
				.queryParam("nonce", NONCE);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder consentPost() {
		return post("/oauth2/authorize")
				.param("client_id", CLIENT_ID)
				.param("redirect_uri", REDIRECT_URI)
				.param("response_type", "code")
				.param("scope", "openid profile email")
				.param("state", STATE)
				.param("nonce", NONCE);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(
			String code) {
		return post("/oauth2/token")
				// The token endpoint declares consumes=application/x-www-form-urlencoded, as RFC 6749
				// requires. MockMvc's .param() does not imply a content type, so without this the
				// endpoint answers 415 and every assertion below would be testing the wrong thing.
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.param("grant_type", "authorization_code")
				.param("code", code)
				.param("client_id", CLIENT_ID)
				.param("redirect_uri", REDIRECT_URI);
	}

	/** Drives the authorization endpoint and pulls the code out of the redirect. */
	private String authorizationCode() throws Exception {
		String location = mockMvc.perform(consentPost())
				.andExpect(status().isFound())
				.andReturn().getResponse().getRedirectedUrl();

		return org.springframework.web.util.UriComponentsBuilder.fromUriString(location)
				.build().getQueryParams().getFirst("code");
	}

	private static Map<?, ?> json(org.springframework.test.web.servlet.ResultActions result)
			throws Exception {
		return json(result.andReturn().getResponse().getContentAsString());
	}

	private static Map<?, ?> json(String body) {
		return new tools.jackson.databind.json.JsonMapper().readValue(body, Map.class);
	}
}
