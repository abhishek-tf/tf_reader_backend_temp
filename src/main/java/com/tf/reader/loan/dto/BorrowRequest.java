package com.tf.reader.loan.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/loans}.
 *
 * <p>The client sends only the item it wants. The licence model, copy limit and loan period are
 * decided by the entitlement check, never by the client (D-009); {@code userId} comes from the token.
 */
public record BorrowRequest(@NotBlank String itemId) {
}
