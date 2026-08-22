package com.tf.reader.auth.controller;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.tf.reader.auth.dto.AuthMeResponse;
import com.tf.reader.auth.saml.SamlStartRequest;
import com.tf.reader.auth.saml.SamlStartResponse;
import com.tf.reader.auth.security.UserSecurityConfig;
import com.tf.reader.auth.model.CurrentUser;
import com.tf.reader.auth.model.Institution;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.repository.MockInstitutionRepository;
import com.tf.reader.auth.security.UserSecurityConfig;
import com.tf.reader.auth.token.IssuedToken;
import com.tf.reader.auth.token.TokenService;
import com.tf.reader.auth.saml.transaction.AuthTransaction;
import com.tf.reader.auth.saml.transaction.AuthTransactionStore;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;

/**
 * The auth group: starting institutional sign-in, and reporting who is signed in.
 *
 * <p>Neither method authenticates anybody. {@code /saml/start} records which institution was
 * chosen and hands back the URL that begins the SAML flow; {@code /me} reads an identity Spring
 * Security has already established. No token is parsed here.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	/** The one relying party registration. Every institution authenticates through it. */
	static final String REGISTRATION_ID = "tf-reader";

	private final AuthTransactionStore transactions;
	private final MockInstitutionRepository institutions;
	private final TokenService tokenService;
	private final Clock clock;

	public AuthController(AuthTransactionStore transactions, MockInstitutionRepository institutions,
			TokenService tokenService, Clock clock) {
		this.transactions = transactions;
		this.institutions = institutions;
		this.tokenService = tokenService;
		this.clock = clock;
	}

	/**
	 * Who am I, and how long have I got. Called by the app on resume.
	 *
	 * <p>No body, no query parameters, no path variables - there is nothing here for a caller to
	 * assert about itself. The identity arrives as the authenticated principal, which exists only
	 * because a token passed signature and claim validation before this method was reached.
	 *
	 * <p><b>This is also how a session slides.</b> There is no refresh token: an expired token is
	 * never exchangeable, because an expired token never gets here - the filter chain refuses it
	 * with 401 and this method does not run. A still-valid token is exchanged for a fresh one, so
	 * an active user continues indefinitely and an idle one is signed out. The one-hour lifetime
	 * is therefore an idle timeout rather than a session length.
	 */
	@GetMapping("/me")
	public AuthMeResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
		// Defence in depth, and the reason it is not unreachable code: @AuthenticationPrincipal
		// resolves to null whenever the request was authenticated by something that is not our
		// bearer token, because the principal is then not a CurrentUser. The API chain is
		// stateless precisely so that cannot happen - but if a future chain, filter or test slice
		// ever authenticates another way, this endpoint must refuse rather than dereference null
		// and answer 500 with a stack trace where an identity should have been.
		if (currentUser == null) {
			throw new ApiException(ErrorCode.TOKEN_MISSING,
					"This endpoint requires authentication.");
		}

		IssuedToken reissued = tokenService.issue(asTnfUser(currentUser));

		return new AuthMeResponse(
				currentUser.userId(),
				currentUser.type(),
				// Null for an individual, and omitted from the JSON rather than sent as null.
				currentUser.belongsToAnInstitution() ? currentUser.institutionId() : null,
				currentUser.roles(),
				currentUser.collections(),
				// The expiry of the token just minted, not of the one presented. The lifetime
				// comes from JwtProperties; no duration is written down twice.
				reissued.expiresAt(),
				clock.instant().truncatedTo(ChronoUnit.SECONDS),
				reissued.token());
	}

	/**
	 * The request-time identity expressed as the login-time model TokenService takes.
	 *
	 * <p>Copied field by field on purpose. The two models are identical in shape today and are
	 * deliberately separate types; if either ever gains a field, this line stops compiling and
	 * somebody decides what should happen, which is the point.
	 */
	private static TnfUser asTnfUser(CurrentUser currentUser) {
		return new TnfUser(currentUser.userId(), currentUser.type(), currentUser.institutionId(),
				currentUser.roles(), currentUser.collections());
	}

	@PostMapping("/saml/start")
	public SamlStartResponse samlStart(@Valid @RequestBody SamlStartRequest request) {
		Institution institution = institutions.find(request.institutionId())
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
						"No institution is registered with id '" + request.institutionId() + "'."));

		AuthTransaction transaction = transactions.open(institution.institutionId());

		return new SamlStartResponse(
				transaction.id(),
				authorizationUrl(transaction),
				institution,
				transaction.expiresAt().truncatedTo(ChronoUnit.SECONDS),
				transaction.createdAt().truncatedTo(ChronoUnit.SECONDS));
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
