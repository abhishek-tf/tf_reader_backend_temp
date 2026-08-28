package com.tf.reader.auth.controller;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.tf.reader.auth.dto.AuthMeResponse;
import com.tf.reader.auth.dto.RefreshRequest;
import com.tf.reader.auth.dto.TokenExchangeRequest;
import com.tf.reader.auth.dto.TokenResponse;
import com.tf.reader.auth.entity.ReaderSession;
import com.tf.reader.auth.saml.SamlStartResponse;
import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.Institution;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.security.CurrentUserAuthenticationToken;
import com.tf.reader.auth.security.UserSecurityConfig;
import com.tf.reader.auth.service.ReaderSessionService;
import com.tf.reader.auth.service.ReaderSessionService.IssuedRefreshToken;
import com.tf.reader.catalogue.api.InstitutionLookup;
import com.tf.reader.catalogue.api.InstitutionRef;
import com.tf.reader.auth.token.AuthorizationCodeStore;
import com.tf.reader.auth.token.IssuedToken;
import com.tf.reader.auth.token.TokenService;
import com.tf.reader.auth.transaction.AuthTransaction;
import com.tf.reader.auth.transaction.AuthTransactionStore;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * The auth group: starting institutional sign-in, reporting who is signed in, and exchanging a
 * sign-in for a token pair.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	/** The one relying party registration. Every institution authenticates through it. */
	static final String REGISTRATION_ID = "tf-reader";

	private final AuthTransactionStore transactions;
	private final InstitutionLookup institutions;
	private final TokenService tokenService;
	private final ReaderSessionService readerSessions;
	private final AuthorizationCodeStore authorizationCodes;
	private final Clock clock;

	public AuthController(AuthTransactionStore transactions, InstitutionLookup institutions,
			TokenService tokenService, ReaderSessionService readerSessions,
			AuthorizationCodeStore authorizationCodes, Clock clock) {
		this.transactions = transactions;
		this.institutions = institutions;
		this.tokenService = tokenService;
		this.readerSessions = readerSessions;
		this.authorizationCodes = authorizationCodes;
		this.clock = clock;
	}

	/**
	 * Who am I, and how long have I got. Called by the app on resume.
	 *
	 * <p>No body, no query parameters, no path variables - there is nothing here for a caller to
	 * assert about itself. The identity, and {@code expiresAt}, are both read from the token that
	 * was already presented; nothing is minted here. Renewal is {@code POST /auth/refresh} now,
	 * not this endpoint.
	 */
	@GetMapping("/me")
	public AuthMeResponse me(@AuthenticationPrincipal CurrentUser currentUser,
			Authentication authentication) {
		// Defence in depth, and the reason it is not unreachable code: @AuthenticationPrincipal
		// resolves to null whenever the request was authenticated by something that is not our
		// bearer token, because the principal is then not a CurrentUser. The API chain is
		// stateless precisely so that cannot happen - but if a future chain, filter or test slice
		// ever authenticates another way, this endpoint must refuse rather than dereference null
		// and answer 500 with a stack trace where an identity should have been.
		if (currentUser == null || !(authentication instanceof CurrentUserAuthenticationToken token)) {
			throw new ApiException(ErrorCode.TOKEN_MISSING,
					"This endpoint requires authentication.");
		}

		Jwt presentedToken = token.getCredentials();

		return new AuthMeResponse(
				currentUser.userId(),
				currentUser.type(),
				// Null for an individual, and omitted from the JSON rather than sent as null.
				currentUser.belongsToAnInstitution() ? currentUser.institutionId() : null,
				currentUser.roles(),
				currentUser.collections(),
				presentedToken.getExpiresAt(),
				clock.instant().truncatedTo(ChronoUnit.SECONDS));
	}

	/**
	 * Begins institutional sign-in over SAML.
	 *
	 * <p>{@code institutionId} travels as a query parameter, not a request body, per the RN
	 * client's integration shape. {@code idpHint} is accepted and deliberately unused: we run one
	 * SAML integration for every institution, so nothing about the request selects an IdP.
	 */
	@PostMapping("/saml/start")
	public SamlStartResponse samlStart(
			@RequestParam(required = false) String institutionId,
			@RequestParam(required = false) String idpHint) {
		if (institutionId == null || institutionId.isBlank()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "institutionId is required");
		}

		InstitutionRef institutionRef = institutions.find(institutionId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
						"No institution is registered with id '" + institutionId + "'."));
		Institution institution = new Institution(institutionRef.institutionId(), institutionRef.name());

		AuthTransaction transaction = transactions.open(institution.institutionId());

		return new SamlStartResponse(
				transaction.id(),
				authorizationUrl(transaction),
				institution,
				transaction.expiresAt().truncatedTo(ChronoUnit.SECONDS),
				transaction.createdAt().truncatedTo(ChronoUnit.SECONDS));
	}

	/**
	 * Exchanges the one-time code from the SAML deep-link callback for an access and refresh
	 * token. The code was minted once, at the ACS, alongside the tokens it stands for - this call
	 * never mints anything new.
	 */
	@PostMapping("/token")
	public TokenResponse exchangeCode(@Valid @RequestBody TokenExchangeRequest request) {
		return authorizationCodes.consume(request.code())
				.orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID,
						"This code is unknown, already used, or expired."));
	}

	/**
	 * Rotates a refresh token: the session it belongs to is revoked and replaced, and a fresh
	 * access token is minted from the identity it had snapshotted.
	 */
	@PostMapping("/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
		ReaderSession claimed = readerSessions.revokeForExchange(request.refreshToken())
				.orElseThrow(() -> new ApiException(ErrorCode.TOKEN_EXPIRED,
						"This refresh token is unknown, already used, or expired."));

		TnfUser user = new TnfUser(claimed.getUserId(), claimed.getType(), claimed.getInstitutionId(),
				claimed.getRoles(), claimed.getCollections());

		IssuedToken accessToken = tokenService.issue(user);
		IssuedRefreshToken refreshToken = readerSessions.createSession(user);

		// From issuedAt, not clock.instant(): the token's own issuedAt is already truncated to
		// whole seconds, so subtracting the current instant (which carries sub-second precision)
		// would round the reported lifetime down by up to a second for no reason.
		return new TokenResponse(accessToken.token(), refreshToken.value(),
				Duration.between(accessToken.issuedAt(), accessToken.expiresAt()).getSeconds());
	}

	@PostMapping("/dev-token")
	public IssuedToken generateDevToken(
			@RequestParam(defaultValue = "usr_dev123") String userId,
			@RequestParam(defaultValue = "inst_7f3") String institutionId) {
		TnfUser user = new TnfUser(userId, UserType.INSTITUTION, institutionId, List.of("MEMBER"), List.of("col_law2024"));
		return tokenService.issue(user);
	}

	/**
	 * The Spring Security entry point that builds the AuthnRequest, carrying our transaction id
	 * so it becomes the RelayState.
	 */
	private String authorizationUrl(AuthTransaction transaction) {
		return UriComponentsBuilder.fromPath("/saml2/authenticate")
				.queryParam("registrationId", REGISTRATION_ID)
				.queryParam(UserSecurityConfig.AUTH_TRANSACTION_PARAM, transaction.id())
				.build()
				.toUriString();
	}
}
