package com.tf.reader.auth;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.tf.reader.auth.security.TnfJwtValidator;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.error.ErrorResponseWriter;

/**
 * How an unauthenticated call to the JSON API is refused.
 *
 * <p>Two reasons this exists rather than Spring's defaults. {@code saml2Login} would redirect
 * an unauthenticated request to the IdP, and {@code oauth2ResourceServer} would answer 401 with
 * an empty body and a {@code WWW-Authenticate} header - a React Native client can act on
 * neither. This answers our error shape in both cases.
 *
 * <p>It distinguishes three refusals, because the app does different things with them:
 * <ul>
 * <li>{@code TOKEN_MISSING} - nothing was presented. Sign in.</li>
 * <li>{@code TOKEN_EXPIRED} - a token we issued has run out. Clear the keychain and sign in;
 * this is the ordinary case, since tokens live an hour and there is no refresh.</li>
 * <li>{@code TOKEN_INVALID} - a token was presented and is not usable. Also sign in, but this
 * one is worth a log line.</li>
 * </ul>
 */
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ErrorResponseWriter errorResponseWriter;

	public ApiAuthenticationEntryPoint(ErrorResponseWriter errorResponseWriter) {
		this.errorResponseWriter = errorResponseWriter;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		ErrorCode code = classify(authException);
		this.errorResponseWriter.write(request, response, code, messageFor(code));
	}

	/**
	 * Reads our own validator's error codes rather than matching on Spring's message text, so
	 * an upstream change to that wording cannot silently turn every expiry into "invalid".
	 */
	private ErrorCode classify(AuthenticationException authException) {
		if (!(authException instanceof OAuth2AuthenticationException)) {
			// No bearer token was presented at all - the resource server never ran.
			return ErrorCode.TOKEN_MISSING;
		}
		Throwable cause = authException.getCause();
		if (cause instanceof JwtValidationException validation) {
			boolean expired = validation.getErrors().stream()
					.anyMatch(error -> TnfJwtValidator.EXPIRED.equals(error.getErrorCode()));
			return expired ? ErrorCode.TOKEN_EXPIRED : ErrorCode.TOKEN_INVALID;
		}
		return ErrorCode.TOKEN_INVALID;
	}

	private String messageFor(ErrorCode code) {
		return switch (code) {
			case TOKEN_EXPIRED -> "Your session has expired. Please sign in again.";
			case TOKEN_INVALID -> "This access token is not valid.";
			default -> "This endpoint requires authentication.";
		};
	}
}
