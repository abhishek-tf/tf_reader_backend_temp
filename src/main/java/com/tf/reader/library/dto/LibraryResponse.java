package com.tf.reader.library.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response body listing a reader's library: the screen the app opens on, in one call.
 *
 * <p><b>Item ids, not books.</b> We do not own catalogue metadata. Titles and covers are one
 * {@code POST /api/v1/catalogue/items:batch} away, capped at 100 ids — which is also why this does
 * not paginate. A shelf longer than that wants {@code GET /api/v1/loans}.
 *
 * <p><b>{@code loans} and {@code holds} are always arrays, never absent.</b> Both are empty today —
 * neither published port has an implementation to inject — and empty is also what a new reader gets
 * forever after. The screen is therefore built once against this shape rather than twice.
 *
 * @param cursor     the change-feed cursor as at this response. Call
 *                   {@code GET /api/v1/loans/changes?since=} with it and miss nothing in between
 * @param serverTime the one clock on this screen. Every countdown is a difference against this
 */
public record LibraryResponse(
		List<LibraryLoan> loans,
		List<LibraryHold> holds,
		String cursor,
		Instant serverTime) {
}
