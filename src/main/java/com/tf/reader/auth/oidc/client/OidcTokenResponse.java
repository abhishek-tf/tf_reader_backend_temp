package com.tf.reader.auth.oidc.client;

import java.util.Map;

/**
 * The token endpoint's successful response, RFC 6749 §5.1 plus OIDC's {@code id_token}.
 *
 * <p>Only the fields we use are modelled; a provider is free to send more and they are ignored.
 * Notably absent is {@code refresh_token}: the project has no refresh flow, so there is nowhere
 * for one to go and no field for it to be accidentally stored in.
 *
 * <p><b>Read from a map rather than bound by annotations.</b> Boot 4 uses Jackson 3, and the
 * wire names here are snake_case while the record's are not; picking the four keys out by hand
 * is three lines, needs no annotation, and cannot be broken by a global naming strategy somebody
 * configures later - which CLAUDE.md already flags as a thing that would silently break other
 * modules' payloads.
 *
 * <p><b>The {@code toString()} is redacted.</b> A record's generated one prints every component,
 * which would put an ID token - a credential naming a real person - into any log line, binding
 * error or exception that happened to carry this object. Same reasoning as
 * {@code JwtProperties.toString()}, and {@code SensitiveDataLoggingTest} asserts it.
 */
public record OidcTokenResponse(
		String accessToken,
		String tokenType,
		Long expiresIn,
		String idToken,
		String scope) {

	/** Builds the response from the parsed JSON body, tolerating anything else the provider sent. */
	public static OidcTokenResponse from(Map<String, Object> body) {
		return new OidcTokenResponse(
				stringOrNull(body.get("access_token")),
				stringOrNull(body.get("token_type")),
				(body.get("expires_in") instanceof Number seconds) ? seconds.longValue() : null,
				stringOrNull(body.get("id_token")),
				stringOrNull(body.get("scope")));
	}

	private static String stringOrNull(Object value) {
		return (value instanceof String text) ? text : null;
	}

	@Override
	public String toString() {
		return "OidcTokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn
				+ ", scope=" + scope + ", accessToken=<redacted>, idToken=<redacted>]";
	}
}
