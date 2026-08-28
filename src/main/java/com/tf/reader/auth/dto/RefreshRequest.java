package com.tf.reader.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/auth/refresh}. */
public record RefreshRequest(

		@NotBlank(message = "is required")
		String refreshToken) {
}
