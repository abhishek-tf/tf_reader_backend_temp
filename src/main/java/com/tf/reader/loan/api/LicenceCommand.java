package com.tf.reader.loan.api;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.SubjectRef;

/**
 * Published contract: create the licence for a read, carrying its already-claimed lease ID.
 */
public interface LicenceCommand {

	/**
	 * Create the licence for a read that has already passed every check AND, for elite, already holds a copy.
	 *
	 * <p>{@code leaseId} is non-null for {@code ENTITLED_CONCURRENT} (ELITE) and null for the other two tiers,
	 * so it means exactly one thing on the row: "this tier has no copy limit".
	 *
	 * <p>Idempotent on {@code (userId, itemId)} while a licence is live.
	 */
	LicenceView create(SubjectRef subject, String itemId, AccessLevel accessLevel, int loanPeriodDays, String leaseId);

	/**
	 * True if a loan for this (userId, itemId) exists with status EXPIRED — meaning the system
	 * reclaimed the seat rather than the user returning it. Used by the read broker to reject
	 * silent re-mints: a STREAM re-check is not the same as a fresh borrow.
	 */
	boolean hasExpiredLoan(String userId, String itemId);

	/**
	 * True if a loan for this (userId, itemId) exists with status ACTIVE — this reader already
	 * has the title on loan right now. Used by the hold queue to refuse joining a wait list for
	 * something already held: without this check, a reader whose read-broker copy CLAIM failed
	 * (every concurrent-reading slot momentarily taken by someone else, or themselves, elsewhere)
	 * got queued behind their own active loan, and the library screen showed the same title as
	 * both "yours" and "waiting for access" at once — confusing and wrong, since a loan is proof
	 * they never needed to wait in the first place.
	 */
	boolean hasActiveLoan(String userId, String itemId);

	/**
	 * The copy-lease token recorded on this (userId, itemId)'s ACTIVE loan, or null if there is
	 * no active loan or it is not copy-limited (leaseId is only ever set for ENTITLED_CONCURRENT).
	 *
	 * <p>Lets the read broker EXTEND the lease this loan was originally granted with, instead of
	 * claiming a brand-new one on every open. Without this, {@code ReadBrokerService.open()}
	 * unconditionally called {@code CopyLease.claim()} before ever reaching {@link #create}'s own
	 * idempotency check — so borrowing a 2-copy title, then immediately opening it, consumed BOTH
	 * copies for the same single reader (one lease from the borrow, an entirely separate one from
	 * the first open), leaving zero slots for anyone else and refusing even THIS reader's own next
	 * open. See queue audit, 2026-09-20.
	 */
	String activeLoanLeaseId(String userId, String itemId);
}
