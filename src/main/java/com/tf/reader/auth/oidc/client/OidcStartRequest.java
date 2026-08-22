package com.tf.reader.auth.oidc.client;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/oidc/start}. See
 * {@code api-docs/flambeau-api.yaml}, {@code OidcStartRequest}.
 *
 * <p>One field, and the contract defines no {@code idpHint} counterpart here: we run one OIDC
 * integration - one B2C tenant, one user flow - for every institution, so nothing about the
 * request selects an identity provider.
 *
 * <p><b>{@code institutionId} is the only thing a client gets to say, and it is not trusted after
 * this call.</b> It is resolved against the institution repository, recorded server-side under an
 * opaque transaction id, and read back from there at the callback. Nothing the client sends on the
 * way back can change which institution the sign-in was for.
 */
public record OidcStartRequest(

		@NotBlank(message = "is required")
		String institutionId) {
}
