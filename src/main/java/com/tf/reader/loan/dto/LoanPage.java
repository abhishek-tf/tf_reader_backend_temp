package com.tf.reader.loan.dto;

import java.time.Instant;
import java.util.List;

/**
 * One page of a reader's personal library.
 *
 * <p>Its own shape rather than {@code common/page/PageResponse} because every response we return
 * carries {@code serverTime} (invariant #4), and the page is the response here.
 */
public record LoanPage(
		List<LoanResponse> loans,
		int page,
		int size,
		long total,
		Instant serverTime) {
}
