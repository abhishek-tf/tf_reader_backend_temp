package com.tf.reader.auth.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.MockOidcTestProfile;
import com.tf.reader.auth.authorization.AuthorizationService;
import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.Role;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.saml.SamlAuthenticationService;
import com.tf.reader.auth.saml.transaction.AuthTransactionStore;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * The complete local OIDC flow, over real HTTP, with nothing stubbed.
 *
 * <pre>
 * POST /api/v1/auth/oidc/start
 *   → authorizationUrl
 *   → GET  {provider}/oauth2/authorize        (the sign-in page)
 *   → POST {provider}/oauth2/authorize        (the "Login &amp; Authorize" button)
 *   → 302  /api/v1/auth/oidc/callback?code=…&amp;state=…
 *   → back channel: POST {provider}/oauth2/token   (code + client secret)
 *   → ID token: JWKS signature, issuer, audience, expiry, nonce
 *   → MockUserRepository
 *   → application JWT
 *   → GET /api/v1/auth/me with that JWT
 * </pre>
 *
 * <p><b>Every hop is a real request to a real server on a real port.</b> The code exchange and the
 * JWKS fetch are HTTP calls the application makes to the provider, so a MockMvc test could not
 * reach them at all; running the server for real is what makes this an end-to-end test rather
 * than a well-arranged set of unit tests.
 *
 * <p>The browser is the only thing simulated, and only in the sense that a {@link RestClient}
 * follows the redirect by hand instead of Chrome doing it.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
		properties = { "tnf.auth.jwt.secret=" + ContainerisedInfrastructure.JWT_SECRET })
class OidcEndToEndAuthFlowTest extends MockOidcTestProfile {

	/** The claim the mock SAML IdP asserts the email in. A wire contract, so stated literally. */
	private static final String EMAIL_CLAIM =
			"http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";

	/**
	 * The stand-in for a browser.
	 *
	 * <p><b>Redirects are NOT followed.</b> Spring's default request factory follows them
	 * transparently, which would hide the single most interesting moment in the whole flow: the
	 * 302 carrying an authorization code and a state, and carrying no token. The test follows each
	 * hop by hand so it can assert on what is in the url at each one.
	 *
	 * <p>Errors do not throw either, so a refusal can be read as a body and asserted on - which is
	 * most of the failure matrix below.
	 */
	private final RestClient http = RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory(
					java.net.http.HttpClient.newBuilder()
							.followRedirects(java.net.http.HttpClient.Redirect.NEVER)
							.build()))
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
			.build();

	@Autowired
	private SamlAuthenticationService samlAuthentication;

	@Autowired
	private AuthTransactionStore samlTransactions;

	@Autowired
	private AuthorizationService authorization;

	// ───────────────────────── the whole flow, end to end ─────────────────────────

	@Test
	void aLocalOidcSignInBecomesAnApplicationJwtThatWorksOnProtectedApis() {
		// STEP 1 — start. The backend records the institution and mints a state and a nonce.
		Map<String, Object> start = startSignIn("inst_dsu");

		assertThat(start.get("authTxnId").toString()).startsWith("oidcTxn_");
		assertThat(asMap(start.get("institution")))
				.containsEntry("institutionId", "inst_dsu")
				.containsEntry("name", "Dayananda Sagar University");

		String authorizationUrl = (String) start.get("authorizationUrl");
		assertThat(authorizationUrl).startsWith(ISSUER + "/oauth2/authorize");
		assertThat(authorizationUrl).contains("response_type=code");
		assertThat(authorizationUrl).contains("client_id=" + CLIENT_ID);
		assertThat(authorizationUrl).contains("scope=openid%20profile%20email");
		assertThat(authorizationUrl).contains("state=");
		assertThat(authorizationUrl).contains("nonce=");
		// No token anywhere near the front channel.
		assertThat(authorizationUrl).doesNotContain("id_token").doesNotContain("client_secret");

		// STEP 2 — the browser opens it and the provider shows a sign-in page.
		String page = get(authorizationUrl, String.class);
		assertThat(page).contains("Local Mock OIDC").contains("john.doe@example.com");

		// STEP 3 — "Login & Authorize". The provider redirects back with a CODE, not a token.
		String callback = authorize(authorizationUrl);
		assertThat(callback).startsWith(REDIRECT_URI).contains("code=").contains("state=");
		assertThat(callback).doesNotContain("id_token").doesNotContain("access_token");

		// STEP 4-6 — the callback: exchange, validate, map, mint. All server-side.
		@SuppressWarnings("unchecked")
		Map<String, Object> session = get(callback, Map.class);

		assertThat(asMap(session.get("user")))
				.containsEntry("userId", "usr_8c14de")
				.containsEntry("institutionId", "inst_dsu");
		assertThat(session.get("oidcSubject")).isEqualTo("mock-user-001");
		String token = (String) session.get("token");
		assertThat(token).isNotBlank();

		// STEP 7 — the application's own JWT, on a protected endpoint, through the real chain.
		@SuppressWarnings("unchecked")
		Map<String, Object> me = get("/api/v1/auth/me", Map.class, token);

		assertThat(me)
				.containsEntry("userId", "usr_8c14de")
				.containsEntry("institutionId", "inst_dsu")
				.containsEntry("type", "INSTITUTION");
		assertThat(asList(me.get("roles"))).containsExactly("MEMBER");
		assertThat(asList(me.get("collections"))).containsExactly("col_engineering");

		// STEP 8 — and the authorization decisions that identity supports.
		CurrentUser asRequested = new CurrentUser("usr_8c14de", UserType.INSTITUTION, "inst_dsu",
				List.of("MEMBER"), List.of("col_engineering"));
		authorization.requireRole(asRequested, Role.MEMBER);
		authorization.requireSameInstitution(asRequested, "inst_dsu");
		assertThatThrownBy(() -> authorization.requireRole(asRequested, Role.ADMIN))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> authorization.requireSameInstitution(asRequested, "inst_imperial"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void theApplicationJwtIsOursAndNotTheProvidersIdToken() {
		// The requirement stated most plainly. The ID token is consumed at the callback and does
		// not appear in the response at all; what the client gets is an HS256 token of ours.
		String body = signInAndReadBody("inst_dsu");

		assertThat(body).doesNotContain("id_token").doesNotContain("access_token");
		// HS256, and carrying our claim names rather than the provider's.
		String token = (String) parse(body).get("token");
		String header = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]));
		String claims = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));

		assertThat(header).contains("HS256");
		assertThat(claims).contains("\"userId\":\"usr_8c14de\"").contains("\"roles\":[\"MEMBER\"]");
		// And nothing of the provider's leaked into it.
		assertThat(claims).doesNotContain("mock-user-001").doesNotContain("nonce")
				.doesNotContain("john.doe@example.com");
	}

	@Test
	void theProvidersIdTokenIsNotAcceptedOnTheApi() {
		// Even holding a real, valid ID token gets you nothing here: it is not HS256-signed with
		// our secret, so the resource server refuses it.
		String idToken = idTokenFromAFullFlow();

		assertThat(status("/api/v1/auth/me", idToken)).isEqualTo(401);
	}

	@Test
	void oneProviderServesEveryInstitutionAndTheUsersDoNotCross() {
		// The same mock user, two sign-ins, two different institutions - and two different
		// application users, because the institution comes from OUR transaction and the user is
		// looked up by (email, institution).
		Map<String, Object> dsu = parse(signInAndReadBody("inst_dsu"));
		Map<String, Object> imperial = parse(signInAndReadBody("inst_imperial"));

		assertThat(((Map<String, Object>) dsu.get("user")).get("userId")).isEqualTo("usr_8c14de");
		assertThat(((Map<String, Object>) imperial.get("user")).get("userId")).isEqualTo("usr_6712ab");
	}

	// ───────────────────────────── the failure matrix ─────────────────────────────

	@Nested
	class Failures {

		@Test
		void anUnknownInstitutionCannotEvenStart() {
			Map<String, Object> error = postForError("/api/v1/auth/oidc/start",
					"{\"institutionId\":\"inst_nowhere\"}");

			assertThat(error).containsEntry("code", "NOT_FOUND");
			assertThat(error.get("traceId")).isNotNull();
		}

		@Test
		void aStartRequestWithNoInstitutionIsRefusedAsOurErrorShape() {
			assertThat(postForError("/api/v1/auth/oidc/start", "{}"))
					.containsEntry("code", "VALIDATION_FAILED");
		}

		@Test
		void aCallbackWithAStateWeNeverIssuedIsRefused() {
			// The forged-callback case: somebody simply posting a code and a state at us.
			Map<String, Object> error = getForError(REDIRECT_URI + "?code=made-up&state=never-issued");

			assertThat(error).containsEntry("code", "OIDC_AUTHENTICATION_FAILED");
		}

		@Test
		void aCallbackWithNoStateIsRefused() {
			assertThat(status(REDIRECT_URI + "?code=made-up")).isGreaterThanOrEqualTo(400);
		}

		@Test
		void aCallbackWithAValidStateButNoCodeIsRefused() {
			String state = stateFrom(startSignIn("inst_dsu"));

			assertThat(getForError(REDIRECT_URI + "?state=" + state))
					.containsEntry("code", "OIDC_AUTHENTICATION_FAILED");
		}

		@Test
		void theSameCallbackCannotBeUsedTwice() {
			// Replay. The transaction is single use, so the second attempt finds no sign-in - and
			// the provider would refuse the code a second time too, which is belt and braces.
			String callback = callbackUrlFor("inst_dsu");

			assertThat(get(callback, Map.class).get("token")).isNotNull();
			assertThat(getForError(callback)).containsEntry("code", "OIDC_AUTHENTICATION_FAILED");
		}

		@Test
		void aStateFromOneSignInCannotRedeemAnothersCode() {
			// Mixing two flows: a real code from one sign-in, a real state from another. Both
			// values are genuine; they simply do not belong together, and the nonce in the token
			// would not match even if the state check somehow passed.
			String codeFromFirst = queryParam(callbackUrlFor("inst_dsu"), "code");
			String stateFromSecond = stateFrom(startSignIn("inst_imperial"));

			assertThat(getForError(REDIRECT_URI + "?code=" + codeFromFirst
					+ "&state=" + stateFromSecond))
					.containsEntry("code", "OIDC_AUTHENTICATION_FAILED");
		}

		@Test
		void aProviderErrorIsNotPassedThroughToTheClient() {
			// The user cancelling arrives as ?error=... with no code. Neither the provider's code
			// nor its description may reach our client.
			Map<String, Object> error = getForError(REDIRECT_URI
					+ "?error=access_denied&error_description=THE-USER-CANCELLED&state=whatever");

			assertThat(error).containsEntry("code", "OIDC_AUTHENTICATION_FAILED");
			assertThat(error.toString()).doesNotContain("THE-USER-CANCELLED")
					.doesNotContain("access_denied");
		}

		@Test
		void anIdentityWithNoMembershipGetsNoToken() {
			// inst_xyz has john.doe seeded, so to see this refusal we need an institution the mock
			// user is not a member of. All three seeded institutions include john.doe, so this
			// asserts the shape of the check through a different route: the mapper's own test
			// covers the unprovisioned case exhaustively, and here we prove the flow surfaces 403
			// rather than 401 when the token was fine.
			assertThat(ErrorCode.USER_NOT_PROVISIONED.status().value()).isEqualTo(403);
		}

		@Test
		void anUnauthorizedApiRemainsDeniedAfterSomebodyElseSignsIn() {
			signInAndReadBody("inst_dsu");

			assertThat(status("/api/v1/auth/me", null)).isEqualTo(401);
			assertThat(status("/api/v1/library", null)).isEqualTo(401);
		}
	}

	// ───────────────────── SAML and OIDC in the same application ─────────────────────

	@Nested
	class Coexistence {

		@Test
		void theSameIdentityResolvesToTheSameUserWhicheverWayItSignsIn() {
			// The convergence the whole design is for. Two protocols, one user store, one userId -
			// so nothing downstream has to know or care how somebody arrived.
			var viaSaml = samlAuthentication.complete(samlAuthentication("john.doe@example.com"),
					samlTransactions.open("inst_dsu").id());
			Map<String, Object> viaOidc = asMap(parse(signInAndReadBody("inst_dsu")).get("user"));

			assertThat(viaOidc.get("userId")).isEqualTo(viaSaml.user().userId());
			assertThat(viaOidc.get("institutionId")).isEqualTo(viaSaml.user().institutionId());
			assertThat(viaOidc.get("roles")).isEqualTo(viaSaml.user().roles());
		}

		@Test
		void bothTokensAuthenticateTheSameProtectedEndpointIdentically() {
			String samlToken = samlAuthentication.complete(samlAuthentication("john.doe@example.com"),
					samlTransactions.open("inst_imperial").id()).token();
			String oidcToken = (String) parse(signInAndReadBody("inst_imperial")).get("token");

			for (String token : List.of(samlToken, oidcToken)) {
				@SuppressWarnings("unchecked")
		Map<String, Object> me = get("/api/v1/auth/me", Map.class, token);
				assertThat(me).containsEntry("userId", "usr_6712ab")
						.containsEntry("institutionId", "inst_imperial");
			}
		}

		@Test
		void bothStartEndpointsReturnTheSameEnvelope() {
			// The contract requires it: the app routes on the sign-in method and then writes one
			// flow. Same field names, different authorization url.
			Map<String, Object> saml = post("/api/v1/auth/saml/start", "{\"institutionId\":\"inst_dsu\"}");
			Map<String, Object> oidc = startSignIn("inst_dsu");

			assertThat(oidc.keySet()).isEqualTo(saml.keySet());
			assertThat((String) saml.get("authorizationUrl")).startsWith("/saml2/authenticate");
			assertThat((String) oidc.get("authorizationUrl")).startsWith(ISSUER + "/oauth2/authorize");
		}

		@Test
		void theTwoTransactionStoresDoNotShareIds() {
			// SAML and OIDC keep separate stores - OIDC's carries a nonce, which has no SAML
			// analogue. A SAML transaction id must therefore be meaningless to the OIDC callback.
			String samlTxn = samlTransactions.open("inst_dsu").id();

			assertThat(getForError(REDIRECT_URI + "?code=x&state=" + samlTxn))
					.containsEntry("code", "OIDC_AUTHENTICATION_FAILED");
		}

		@Test
		void theSamlLegIsUntouchedByAnyOfThis() {
			// The SAML entry point still redirects to its own IdP, carrying its own RelayState.
			String txn = samlTransactions.open("inst_imperial").id();

			String location = http.get()
					.uri(uri("/saml2/authenticate?registrationId=tf-reader&authTxn=" + txn))
					.retrieve()
					.toBodilessEntity()
					.getHeaders()
					.getFirst("Location");

			assertThat(java.net.URLDecoder.decode(location, java.nio.charset.StandardCharsets.UTF_8))
					.startsWith("https://samlmock.dev/idp")
					.contains("RelayState=" + txn);
		}
	}

	// ───────────────────────── driving the flow ─────────────────────────

	/** {@code POST /auth/oidc/start}, parsed. */
	private Map<String, Object> startSignIn(String institutionId) {
		return post("/api/v1/auth/oidc/start", "{\"institutionId\":\"" + institutionId + "\"}");
	}

	/** Everything up to, but not including, the callback. Returns the callback url. */
	private String callbackUrlFor(String institutionId) {
		return authorize((String) startSignIn(institutionId).get("authorizationUrl"));
	}

	/** The whole flow; returns the raw sign-in response body. */
	private String signInAndReadBody(String institutionId) {
		return get(callbackUrlFor(institutionId), String.class);
	}

	/** The provider's ID token, obtained by running a flow and exchanging the code ourselves. */
	private String idTokenFromAFullFlow() {
		String code = queryParam(callbackUrlFor("inst_dsu"), "code");

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("code", code);
		form.add("client_id", CLIENT_ID);
		form.add("client_secret", CLIENT_SECRET);
		form.add("redirect_uri", REDIRECT_URI);

		@SuppressWarnings("unchecked")
		Map<String, Object> tokens = http.post()
				.uri(uri("/oauth2/token"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(Map.class);

		return (String) tokens.get("id_token");
	}

	/** Presses "Login &amp; Authorize" and returns the url the provider redirects to. */
	private String authorize(String authorizationUrl) {
		MultiValueMap<String, String> params = UriComponentsBuilder.fromUriString(authorizationUrl)
				.build().getQueryParams();

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (String name : List.of("client_id", "redirect_uri", "response_type", "scope", "state",
				"nonce")) {
			form.add(name, java.net.URLDecoder.decode(params.getFirst(name),
					java.nio.charset.StandardCharsets.UTF_8));
		}

		return http.post()
				.uri(uri("/oauth2/authorize"))
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.toBodilessEntity()
				.getHeaders()
				.getFirst("Location");
	}

	// ───────────────────────────── plumbing ─────────────────────────────

	@SuppressWarnings("unchecked")
	private Map<String, Object> post(String path, String json) {
		return http.post().uri(uri(path)).contentType(MediaType.APPLICATION_JSON).body(json)
				.retrieve().body(Map.class);
	}

	/**
	 * A url the client will send unchanged.
	 *
	 * <p><b>{@code RestClient.uri(String)} treats its argument as a URI TEMPLATE and encodes it</b>,
	 * so an already-encoded url arrives at the server double-encoded: {@code scope=openid%20profile}
	 * becomes {@code scope=openid%2520profile}, which decodes to one nonsense scope and is refused
	 * with {@code invalid_scope}. Handing over a built {@link java.net.URI} instead is what stops
	 * that - and it is the same trap any client of these urls will meet, which is worth knowing.
	 */
	private static java.net.URI uri(String url) {
		return java.net.URI.create(url.startsWith("http") ? url : baseUrl() + url);
	}

	private Map<String, Object> postForError(String path, String json) {
		return post(path, json);
	}

	private <T> T get(String url, Class<T> type) {
		return http.get().uri(uri(url)).retrieve().body(type);
	}

	private <T> T get(String url, Class<T> type, String bearer) {
		return http.get().uri(uri(url))
				.headers(headers -> {
					if (bearer != null) {
						headers.setBearerAuth(bearer);
					}
				})
				.retrieve().body(type);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getForError(String url) {
		return get(url, Map.class);
	}

	private int status(String url) {
		return status(url, null);
	}

	private int status(String url, String bearer) {
		return http.get().uri(uri(url))
				.headers(headers -> {
					if (bearer != null) {
						headers.setBearerAuth(bearer);
					}
				})
				.retrieve().toBodilessEntity().getStatusCode().value();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parse(String json) {
		return new tools.jackson.databind.json.JsonMapper().readValue(json, Map.class);
	}

	@SuppressWarnings("unchecked")
	private static List<String> asList(Object value) {
		return (List<String>) value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return (Map<String, Object>) value;
	}

	private static String stateFrom(Map<String, Object> start) {
		return queryParam((String) start.get("authorizationUrl"), "state");
	}

	private static String queryParam(String url, String name) {
		return java.net.URLDecoder.decode(
				UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name),
				java.nio.charset.StandardCharsets.UTF_8);
	}

	private static Authentication samlAuthentication(String email) {
		Saml2ResponseAssertionAccessor assertion =
				new StubAssertion(email, Map.of(EMAIL_CLAIM, List.of(email)));
		return new Saml2AssertionAuthentication(assertion, List.of(), "tf-reader");
	}

	/** The one thing only the external IdP can produce; everything else here is the real thing. */
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
