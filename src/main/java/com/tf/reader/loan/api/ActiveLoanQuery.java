package com.tf.reader.loan.api;

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
}
