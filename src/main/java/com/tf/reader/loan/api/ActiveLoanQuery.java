package com.tf.reader.loan.api;

import java.util.List;
import java.util.Optional;

/**
 * Published contract: does this person currently hold this title, live right now?
 *
 * <p>The one seam other capabilities use to ask about a reader's hold on an item. They never touch
 * the {@code loans} collection directly. "Live right now" is re-derived from {@code dueAt} against
 * the server clock, not read off the stored status (D-006), so a lapsed-but-not-yet-swept loan
 * reads as inactive.
 */
public interface ActiveLoanQuery {

	/**
	 * @return the active loan for {@code (userId, itemId)}, or empty if there is none or it has
	 *         already passed its due date.
	 */
	Optional<ActiveLoanView> findActive(String userId, String itemId);

	/**
	 * Every loan this reader holds that is still live right now — for building the library shelf
	 * (Module E, D-025). Applies the same D-006 liveness rule as {@link #findActive}: a lapsed-but-
	 * not-yet-swept row is excluded. Returned newest-first ({@code borrowedAt} descending).
	 */
	List<ActiveLoanView> findAllFor(String userId);

	/**
	 * Every live ELITE loan, for any reader, in any institution — the reconciler's rebuild read.
	 * Applies the same D-006 liveness rule: a lapsed-but-not-yet-swept row is excluded.
	 */
	List<ActiveLoanView> findAllActiveElite();
}
