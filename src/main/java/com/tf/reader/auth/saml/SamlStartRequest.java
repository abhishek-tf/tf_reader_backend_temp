package com.tf.reader.auth.saml;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/saml/start}. API Reference section 3.
 *
 * <p>{@code idpHint} is accepted because the contract defines it, and is deliberately unused:
 * we run one SAML integration for every institution, so nothing about the request selects an
 * IdP. Keeping the field means the app does not change when a real multi-IdP federation
 * arrives, and dropping it would be a breaking contract change for a caller that already
 * sends it.
 */
public record SamlStartRequest(

		@NotBlank(message = "is required")
		String institutionId,

		String idpHint) {
}
