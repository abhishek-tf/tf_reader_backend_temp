package com.tf.reader.auth;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tf.reader.auth.model.UserType;

/**
 * Response body for {@code GET /api/v1/auth/me} - "who am I, and how long have I got".
 *
 * <p>Every identity field is copied from the validated token. The endpoint accepts no body and
 * no parameters, so there is nothing a caller could substitute.
 *
 * <p><b>{@code institutionId} is omitted, not null, for an individual subscriber.</b> The
 * contract shows it absent, and Jackson would otherwise emit {@code "institutionId": null} -
 * which reads to a consumer as "belongs to an institution whose id we lost" rather than "has no
 * institution".
 *
 * <p><b>{@code token} is an addition to the documented shape.</b> The reference states that this
 * endpoint re-issues while the current token is valid, and that the one-hour TTL is an idle
 * timeout rather than a session length - but its example response omits the token, which would
 * leave {@code expiresAt} describing something the client never receives and no way for a
 * session to slide at all. Flagged for the Contracts Gate; delete this component and the
 * argument that fills it to match the document exactly.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthMeResponse(
		String userId,
		UserType type,
		String institutionId,
		List<String> roles,
		List<String> collections,
		Instant expiresAt,
		Instant serverTime,
		String token) {
}
