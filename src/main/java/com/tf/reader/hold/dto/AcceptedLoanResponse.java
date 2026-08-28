package com.tf.reader.hold.dto;

import java.time.Instant;

// Response body for POST /api/v1/holds/{holdId}/accept — "the loan, same
// shape as borrowing" per the contract. Mirrors loan.dto.BorrowResponse's
// fields exactly, since hold may not import loan's dto/ package.
public record AcceptedLoanResponse(
        String loanId, String userId, String institutionId, String itemId, String licenceModel, String status,
        boolean canPersist, Instant borrowedAt, Instant dueAt, Instant serverTime) {
}
