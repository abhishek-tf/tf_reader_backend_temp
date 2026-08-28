package com.tf.reader.auth.dto;

/**
 * Response body for {@code POST /api/v1/auth/token} and {@code POST /api/v1/auth/refresh}.
 *
 * @param expiresIn seconds until {@code accessToken} expires, not an absolute instant - matching
 *                   what a client needs to schedule its own silent refresh
 */
public record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
}
