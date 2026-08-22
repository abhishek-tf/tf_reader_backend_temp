package com.tf.reader.auth.oidc.client;

import com.tf.reader.auth.oidc.validation.OidcIdTokenValidator;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.tf.reader.auth.model.Institution;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.repository.MockInstitutionRepository;
import com.tf.reader.auth.token.IssuedToken;
import com.tf.reader.auth.token.TokenService;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * The two halves of an OIDC sign-in: starting one, and completing the one that comes back.
 *
 * <p>Knows nothing about HTTP - no servlet, no request, no response - which is what keeps the
 * whole flow unit-testable without a browser and without a provider. {@link OidcController} does
 * the HTTP and nothing else.
 *
 * <p><b>Where this converges with SAML.</b> {@link #complete} ends at
 * {@code TokenService.issue(TnfUser)}, the same call
 * {@link com.tf.reader.auth.saml.SamlAuthenticationService} ends at, producing the same HS256
 * token validated by the same decoder on every later request. Below that line the application
 * cannot tell the two protocols apart, and that is the design:
 *
 * <pre>
 * SAML assertion ─┐
 *                 ├─→ TnfUser → TokenService → application JWT → CurrentUser → AuthorizationService
 * OIDC ID token ──┘
 * </pre>
 *
 * <p><b>The provider's ID token never becomes the application's token.</b> It is consumed here,
 * at sign-in, and does not leave this class. Handing it to the client as a bearer credential
 * would make our API's authorization depend on somebody else's token lifetime, somebody else's
 * claim set and somebody else's idea of who an administrator is.
 */
@Service
public class OidcAuthenticationService {

	private static final org.slf4j.Logger log =
			org.slf4j.LoggerFactory.getLogger(OidcAuthenticationService.class);

	private final OidcTransactionStore transactions;
	private final MockInstitutionRepository institutions;
	private final OidcTokenClient tokenClient;
	private final OidcIdTokenValidator idTokenValidator;
	private final OidcUserMapper userMapper;
	private final TokenService tokenService;
	private final OidcProperties properties;
	private final Clock clock;

	public OidcAuthenticationService(OidcTransactionStore transactions,
			MockInstitutionRepository institutions, OidcTokenClient tokenClient,
			OidcIdTokenValidator idTokenValidator, OidcUserMapper userMapper,
			TokenService tokenService, OidcProperties properties, Clock clock) {
		this.transactions = transactions;
		this.institutions = institutions;
		this.tokenClient = tokenClient;
		this.idTokenValidator = idTokenValidator;
		this.userMapper = userMapper;
		this.tokenService = tokenService;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Opens a sign-in and returns the url the browser must be sent to.
	 *
	 * <p>Authenticates nobody and mints no token. The only input is an institution id anyone
	 * could type, and it is resolved against the institution repository before anything else
	 * happens - an unknown institution cannot even start.
	 *
	 * @throws ApiException 404 if no such institution exists
	 */
	public OidcStartResponse start(String institutionId) {
		Institution institution = institutions.find(institutionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
						"No institution is registered with id '" + institutionId + "'."));

		OidcTransaction transaction = transactions.open(institution.institutionId());
		log.info("OIDC transaction created: {} for institution {}",
				transaction.id(), institution.institutionId());

		return new OidcStartResponse(
				transaction.id(),
				authorizationUrl(transaction),
				institution,
				transaction.expiresAt().truncatedTo(ChronoUnit.SECONDS),
				clock.instant().truncatedTo(ChronoUnit.SECONDS));
	}

	/**
	 * Completes the sign-in a callback refers to.
	 *
	 * <p>The order of the steps is the security property, so it is worth reading as a list:
	 *
	 * <ol>
	 * <li><b>state</b> - the transaction is consumed by it. Unknown, expired or already-used
	 * state finds nothing and the flow stops here, before any network call is made</li>
	 * <li><b>code exchange</b> - server to server, with our client secret</li>
	 * <li><b>ID token</b> - signature against the provider's JWKS, issuer, audience, expiry,
	 * then the nonce against this transaction</li>
	 * <li><b>institution</b> - from the transaction, never from a claim</li>
	 * <li><b>user</b> - our own store, by email plus institution</li>
	 * <li><b>token</b> - ours, minted last</li>
	 * </ol>
	 *
	 * <p>Note what that ordering buys: a caller who did not start a sign-in never reaches the
	 * token endpoint, and a user we could not map never reaches {@code tokenService.issue}, so a
	 * failed sign-in cannot produce a token at any point.
	 *
	 * @param code  the authorization code from the callback
	 * @param state the state from the callback - the ONLY thing that decides the institution
	 * @throws ApiException 401 if the state is unknown/expired/used, the exchange fails, or the
	 *                      ID token does not validate; 403 if the identity holds no membership
	 */
	public OidcLoginResult complete(String code, String state) {
		if (code == null || code.isBlank()) {
			throw new ApiException(ErrorCode.OIDC_AUTHENTICATION_FAILED,
					"This callback carried no authorization code.");
		}

		// STEP 1 - state. Consuming is the check: see OidcTransactionStore.consume.
		OidcTransaction transaction = transactions.consume(state)
				.orElseThrow(() -> {
					log.warn("OIDC state validation FAILED - no in-flight sign-in matches this callback");
					return new ApiException(ErrorCode.OIDC_AUTHENTICATION_FAILED,
							"This sign-in could not be matched to a transaction we started.");
				});
		log.info("OIDC state validation succeeded for transaction {}", transaction.id());

		// STEP 2 - the code becomes tokens, on a connection the browser never sees.
		OidcTokenResponse tokens = tokenClient.exchangeAuthorizationCode(code);

		// STEP 3 - and the ID token becomes claims we are willing to believe.
		Jwt idToken = idTokenValidator.validate(tokens.idToken(), transaction);

		// STEP 4 - the institution is recovered from OUR transaction. One provider serves every
		// institution, so no claim can tell us which one this is - and if the client could, it
		// could pick any of them.
		Institution institution = institutions.find(transaction.institutionId())
				.orElseThrow(() -> new ApiException(ErrorCode.OIDC_AUTHENTICATION_FAILED,
						"The institution this sign-in was started for no longer exists."));

		// STEP 5 - who that is, here.
		TnfUser user = userMapper.map(idToken, institution.institutionId());

		// STEP 6 - our token, from the mapped user and nothing else.
		IssuedToken token = tokenService.issue(user);
		log.info("Application JWT issued for {} via OIDC, expires at {}",
				user.userId(), token.expiresAt());

		return new OidcLoginResult(token.token(), token.expiresAt(),
				clock.instant().truncatedTo(ChronoUnit.SECONDS), institution,
				userMapper.resolveSubject(idToken), user);
	}

	/**
	 * The provider's authorization endpoint, with our state and nonce.
	 *
	 * <p>Every part of this url is configuration. Nothing in it comes from the request - in
	 * particular {@code redirect_uri}, which is the one parameter an attacker would most like to
	 * influence, and which is therefore read from {@link OidcProperties} and never from a caller.
	 */
	private String authorizationUrl(OidcTransaction transaction) {
		return UriComponentsBuilder.fromUriString(properties.authorizationUri())
				.queryParam("response_type", "code")
				.queryParam("client_id", properties.clientId())
				.queryParam("redirect_uri", properties.redirectUri())
				.queryParam("scope", properties.scopeParameter())
				.queryParam("state", transaction.state())
				.queryParam("nonce", transaction.nonce())
				.build()
				.encode()
				.toUriString();
	}

	/**
	 * What a completed sign-in produced.
	 *
	 * <p>Field for field the same as {@code SamlAuthenticationService.SamlLoginResult}, because
	 * the API Reference requires one sign-in envelope: the app routes on the institution's
	 * sign-in method and must not need two response parsers. {@code oidcSubject} stands where
	 * {@code samlSubject} stands - prototype evidence that the right transaction and the right
	 * identity met, and the field that would come out before this is shown to a real client.
	 *
	 * <p><b>The provider's ID token is deliberately not a component of this record</b>, so there
	 * is no path by which it could be serialised to a client or written to a log.
	 */
	public record OidcLoginResult(String token, Instant expiresAt, Instant serverTime,
			Institution institution, String oidcSubject, TnfUser user) {
	}
}
