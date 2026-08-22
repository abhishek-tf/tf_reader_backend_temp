package com.tf.reader.library.support;

/**
 * Who this request's library belongs to, read from the verified token and nowhere else.
 *
 * <p><b>There is no constructor here that takes a request parameter.</b> Both fields come from the
 * identity the auth module already established, which is what makes reading another institution's
 * shelf impossible rather than merely unlikely: neither library endpoint has a {@code userId} or
 * {@code institutionId} input to tamper with.
 *
 * <p>This module's own type rather than {@code CurrentUser} directly, so the one import of another
 * lane's internals lives in {@link CurrentReaderResolver} and nowhere else.
 *
 * @param institutionId null for an individual subscriber, who belongs to no institution. Absent
 *                      rather than defaulted, so an institution-scoped rule treats them as
 *                      belonging to none rather than to a default one
 */
public record ReaderIdentity(String userId, String institutionId) {

	public ReaderIdentity {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("userId is required");
		}
	}

	public boolean belongsToAnInstitution() {
		return institutionId != null && !institutionId.isBlank();
	}

}
