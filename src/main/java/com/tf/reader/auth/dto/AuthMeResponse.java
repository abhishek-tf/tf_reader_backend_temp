package com.tf.reader.auth.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tf.reader.auth.model.UserType;

/**
 * Response body for {@code GET /api/v1/auth/me} - "who am I, and how long have I got".
 *
 * <p>Every field is copied from the presented token's own claims. Nothing is minted here: a real
 * refresh token now exists ({@code POST /auth/refresh}), so this endpoint no longer re-issues a
 * fresh access token on every call - it is a plain read, and {@code expiresAt} describes the
 * token the caller already has, not a new one.
 *
 * <p><b>{@code institutionId} is omitted, not null, for an individual subscriber.</b> The
 * contract shows it absent, and Jackson would otherwise emit {@code "institutionId": null} -
 * which reads to a consumer as "belongs to an institution whose id we lost" rather than "has no
 * institution".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthMeResponse(
		String userId,
		UserType type,
		String institutionId,
		List<String> roles,
		List<String> collections,
		Instant expiresAt,
		Instant serverTime) {
}
