package com.tf.reader.library.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tf.reader.hold.api.HoldSnapshot;
import com.tf.reader.hold.api.HoldSnapshotQuery;
import com.tf.reader.library.dto.LibraryHold;
import com.tf.reader.library.dto.LibraryLoan;
import com.tf.reader.library.dto.LibraryOffer;
import com.tf.reader.library.dto.LibraryResponse;
import com.tf.reader.library.support.ReaderIdentity;
import com.tf.reader.loan.api.ActiveLoanQuery;
import com.tf.reader.loan.api.ActiveLoanView;

import lombok.extern.slf4j.Slf4j;

/**
 * Builds the library response from loans, holds and the change feed.
 *
 * <p><b>A read model over three sources, owning none of them.</b> Loans come from
 * {@code loan.api.ActiveLoanQuery}, holds from {@code hold.api.HoldSnapshotQuery}, and the cursor
 * from this module's own change feed. Nothing here re-derives what another lane already computes.
 *
 * <p>The response shape never changed while those two seams landed weeks apart, which is what let
 * the screen be built once rather than twice.
 */
@Slf4j
@Service
public class LibraryAssembler {

	/** The only loan status a shelf may show. Anything else means the loan seam broke its contract. */
	private static final String ACTIVE = "ACTIVE";

	private final ChangeFeedService changeFeed;
	private final ActiveLoanQuery activeLoans;
	private final HoldSnapshotQuery holdSnapshots;
	private final Clock clock;

	public LibraryAssembler(ChangeFeedService changeFeed, ActiveLoanQuery activeLoans,
			HoldSnapshotQuery holdSnapshots, Clock clock) {
		this.changeFeed = changeFeed;
		this.activeLoans = activeLoans;
		this.holdSnapshots = holdSnapshots;
		this.clock = clock;
	}

	public LibraryResponse assemble(ReaderIdentity reader) {
		// Cursor first, loans and holds second, and the order is the point. Read the cursor last and
		// a change landing in between is behind the cursor but missing from the snapshot, so the app
		// never learns about it — a revocation lost permanently, with the device keeping the key.
		// Read it first and that same change is merely replayed on the next poll, which is harmless:
		// applying these transitions twice converges on the same shelf.
		//
		// Week-2 task 9 says the opposite. §11's own definition of done — "the cursor is never ahead
		// of the data beside it" — agrees with this ordering. Raised at the gate.
		ChangeCursor cursor = changeFeed.currentCursor(reader.userId());

		List<LibraryLoan> loans = loansFor(reader);
		List<LibraryHold> holds = holdsFor(reader);

		return new LibraryResponse(loans, holds, cursor.value(), serverTime());
	}

	/**
	 * The reader's active loans, from the published loan seam.
	 *
	 * <p>{@code findAllFor} applies the D-006 liveness rule — an {@code ACTIVE} row already past its
	 * {@code dueAt} is excluded — so the shelf never shows a loan the reader has effectively lost,
	 * even in the window before the expiry sweep runs.
	 *
	 * <p>{@code status} and {@code borrowedAt} are both read from the view since D-026. Before that
	 * the view published neither: status was hard-coded here and {@code borrowedAt} was omitted
	 * entirely, which left the frozen response shape advertising a field nothing could populate.
	 */
	private List<LibraryLoan> loansFor(ReaderIdentity reader) {
		return activeLoans.findAllFor(reader.userId()).stream()
				.filter(loan -> live(loan, reader))
				.map(loan -> new LibraryLoan(
						loan.loanId(),
						loan.itemId(),
						loan.licenceModel(),
						loan.status(),
						loan.borrowedAt(),
						loan.dueAt(),
						loan.canPersist()))
				.toList();
	}

	/**
	 * Drops anything the loan seam returns that is not actually live, and says so loudly.
	 *
	 * <p><b>Why this guard exists at all.</b> Until D-026 the wire status was hard-coded here, so a
	 * closed loan could not reach the shelf by construction. Reading {@code status} from the view is
	 * better — one source of truth — but it trades that guarantee for trust: if {@code findAllFor}
	 * ever stops filtering, whatever it returns is what the reader sees.
	 *
	 * <p><b>Why it drops rather than throws.</b> A returned or expired loan rendered as one the
	 * reader still holds is the actively harmful case — they tap it and get a refusal from the read
	 * broker instead of a book. Failing the whole request would replace one wrong row with a blank
	 * screen, which is worse for a reader who has nine other books.
	 *
	 * <p>Logged at error rather than warn because this can only happen if the seam broke its own
	 * contract, and a silently shorter shelf reads to the reader as "I have fewer books than I
	 * thought" — the kind of thing nobody reports and nobody finds.
	 */
	private static boolean live(ActiveLoanView loan, ReaderIdentity reader) {
		if (ACTIVE.equals(loan.status())) {
			return true;
		}
		log.error("ActiveLoanQuery returned a non-live loan; dropped from the shelf. "
				+ "reader={} loan={} item={} status={}",
				reader.userId(), loan.loanId(), loan.itemId(), loan.status());
		return false;
	}

	/**
	 * The reader's holds, offered ones included, from the published hold seam.
	 *
	 * <p><b>{@code position} and {@code queueLength} are read live and never cached here.</b> They are
	 * computed on every read by the queue, because a hold ahead of this one cancelling changes both —
	 * a stored position is wrong the moment anybody in front gives up.
	 *
	 * <p>{@code OfferView} also carries {@code offeredAt}, which is dropped: the screen renders a
	 * countdown to the deadline, and the moment the offer started is not something the reader is
	 * racing.
	 */
	private List<LibraryHold> holdsFor(ReaderIdentity reader) {
		return holdSnapshots.holdsFor(reader.userId()).stream()
				.map(hold -> new LibraryHold(
						hold.holdId(),
						hold.itemId(),
						hold.status(),
						hold.position(),
						hold.queueLength(),
						hold.estimatedWaitDays(),
						hold.placedAt(),
						offerOf(hold)))
				.toList();
	}

	/** Present only while a hold is OFFERED; absent from the JSON otherwise. */
	private static LibraryOffer offerOf(HoldSnapshot hold) {
		return hold.offer() == null
				? null
				: new LibraryOffer(hold.offer().offerId(), hold.offer().expiresAt());
	}

	/**
	 * The one clock on this screen.
	 *
	 * <p>Whole seconds per the wire convention, and from the injected clock rather than
	 * {@code Instant.now()} — every countdown the app renders is a difference against this value, so
	 * a test has to be able to move it.
	 */
	private Instant serverTime() {
		return clock.instant().truncatedTo(ChronoUnit.SECONDS);
	}

}
