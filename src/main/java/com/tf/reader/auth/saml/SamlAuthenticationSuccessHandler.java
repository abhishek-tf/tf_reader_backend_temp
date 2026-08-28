package com.tf.reader.auth.saml;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.tf.reader.auth.dto.TokenResponse;
import com.tf.reader.auth.saml.SamlAuthenticationService.SamlLoginResult;
import com.tf.reader.auth.service.ReaderSessionService;
import com.tf.reader.auth.service.ReaderSessionService.IssuedRefreshToken;
import com.tf.reader.auth.token.AuthorizationCodeStore;
import com.tf.reader.common.error.ApiException;

/**
 * Runs at the ACS once Spring Security has validated the SAML response.
 *
 * <p>HTTP only in spirit, though it now does more than write a body: the browser here is
 * mid-redirect from the IdP, not a fetch call that could read a JSON response, so this handler's
 * only job on either path is to end up at {@link #DEEP_LINK_CALLBACK}. On success that means
 * minting a refresh token ({@link ReaderSessionService}) alongside the access token
 * {@link SamlAuthenticationService} already minted, stashing both behind a one-time code
 * ({@link AuthorizationCodeStore}), and redirecting with it. Neither token is ever placed in the
 * redirect itself.
 */
@Component
public class SamlAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

	/** Where the browser is sent once the ACS is done, success or failure. */
	public static final String DEEP_LINK_CALLBACK = "tfreader://auth/callback";

	/** The parameter the IdP echoes our transaction id back in. */
	private static final String RELAY_STATE = "RelayState";

	private final SamlAuthenticationService authenticationService;
	private final ReaderSessionService readerSessions;
	private final AuthorizationCodeStore authorizationCodes;
	private final Clock clock;

	public SamlAuthenticationSuccessHandler(SamlAuthenticationService authenticationService,
			ReaderSessionService readerSessions, AuthorizationCodeStore authorizationCodes,
			Clock clock) {
		this.authenticationService = authenticationService;
		this.readerSessions = readerSessions;
		this.authorizationCodes = authorizationCodes;
		this.clock = clock;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		try {
			SamlLoginResult result =
					authenticationService.complete(authentication, request.getParameter(RELAY_STATE));

			IssuedRefreshToken refreshToken = readerSessions.createSession(result.user());
			long expiresIn = Duration.between(clock.instant(), result.expiresAt()).getSeconds();
			String code = authorizationCodes.issue(
					new TokenResponse(result.token(), refreshToken.value(), expiresIn));

			response.sendRedirect(DEEP_LINK_CALLBACK + "?code=" + code);
		}
		catch (ApiException failure) {
			// A valid assertion can still fail to become a sign-in - an expired transaction, or
			// an identity with no membership at the institution it was started for. The browser
			// is mid-redirect either way, so the refusal travels the same path as success: back
			// to the app, which is the only thing that can read a query parameter here.
			response.sendRedirect(DEEP_LINK_CALLBACK + "?error=" + failure.getCode().name());
		}
		finally {
			discardTheSignInSession(request);
		}
	}

	/**
	 * Ends the session the SAML leg needed, now that it has done its one job.
	 *
	 * <p>The session exists so the ACS can check {@code InResponseTo} against the AuthnRequest we
	 * sent. By this line that check has happened - but Spring Security has also <b>persisted the
	 * SAML authentication into that session</b>, before this handler was called. Left alive, the
	 * JSESSIONID is a second credential for an identity that never passed through the JWT
	 * validator, and one this application cannot expire, revoke or reason about: the token design
	 * is a one-hour idle timeout on a bearer token, not a server-side session.
	 *
	 * <p>In a {@code finally} because the refusal path above is reached <em>after</em> that same
	 * persistence, so a sign-in we rejected must not leave an authenticated session behind either.
	 */
	private void discardTheSignInSession(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
	}
}
