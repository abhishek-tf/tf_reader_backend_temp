package com.tf.reader.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/auth/token}: the one-time code from the deep-link callback. */
public record TokenExchangeRequest(

		@NotBlank(message = "is required")
		String code) {
}
