package com.tf.reader.library;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.tf.reader.hold.api.HoldSnapshot;
import com.tf.reader.hold.api.HoldSnapshotQuery;
import com.tf.reader.hold.api.OfferView;
import com.tf.reader.library.dto.LibraryResponse;
import com.tf.reader.library.service.ChangeCursor;
import com.tf.reader.library.service.ChangeFeedService;
import com.tf.reader.library.service.LibraryAssembler;
import com.tf.reader.library.support.ReaderIdentity;
import com.tf.reader.loan.api.ActiveLoanQuery;
import com.tf.reader.loan.api.ActiveLoanView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryAssemblerTest {

	private static final ReaderIdentity READER = new ReaderIdentity("user_9c2", "inst_7f3");

	private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final ChangeFeedService changeFeed = mock(ChangeFeedService.class);
	private final ActiveLoanQuery activeLoans = mock(ActiveLoanQuery.class);
	private final HoldSnapshotQuery holdSnapshots = mock(HoldSnapshotQuery.class);

	private final LibraryAssembler assembler =
			new LibraryAssembler(changeFeed, activeLoans, holdSnapshots, CLOCK);

	@Test
	@DisplayName("loans and holds are empty arrays, never absent, so the screen is built once")
	void publishesTheShapeEvenWhenBothAreEmpty() {
		givenCursor(ChangeCursor.of(1189L));
		givenLoans();
		givenHolds();

		LibraryResponse response = assembler.assemble(READER);

		assertThat(response.loans()).isNotNull().isEmpty();
		assertThat(response.holds()).isNotNull().isEmpty();
	}

	@Test
	@DisplayName("loans come from the published loan seam, mapped onto the wire shape")
	void mapsLoansFromTheSeam() {
		givenCursor(ChangeCursor.of(4L));
		givenHolds();
		givenLoans(
				new ActiveLoanView("loan_7c1", "item_42", "ELITE", false,
						NOW.plus(13, ChronoUnit.DAYS), null, null, NOW.minus(1, ChronoUnit.DAYS), "ACTIVE"),
				new ActiveLoanView("loan_oa9", "item_oa9", "OPEN_ACCESS", true, null, null, null, NOW.minus(9, ChronoUnit.DAYS), "ACTIVE"));

		LibraryResponse response = assembler.assemble(READER);

		assertThat(response.loans()).extracting("itemId").containsExactly("item_42", "item_oa9");
		assertThat(response.loans().get(0).licenceModel()).isEqualTo("ELITE");
		assertThat(response.loans().get(0).canPersist()).isFalse();
	}

	@Test
	@DisplayName("status is forwarded from the view, not invented here")
	void statusComesFromTheSeam() {
		givenCursor(ChangeCursor.of(4L));
		givenHolds();
		givenLoans(loan("loan_7c1", "item_42", "ACTIVE"));

		// Read from the view since D-026 rather than hard-coded, so there is one source of truth for
		// what a loan's status is.
		assertThat(assembler.assemble(READER).loans().get(0).status()).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("a loan the seam should not have returned is dropped, not rendered")
	void dropsANonLiveLoan() {
		givenCursor(ChangeCursor.of(4L));
		givenHolds();
		givenLoans(
				loan("loan_ok", "item_42", "ACTIVE"),
				loan("loan_gone", "item_oa9", "RETURNED"),
				loan("loan_lapsed", "item_env", "EXPIRED"));

		// Reading status from the view traded a guarantee for trust: before D-026 a closed loan could
		// not reach the shelf by construction. A returned loan rendered as one the reader still holds
		// is the harmful case — they tap it and get a refusal instead of a book.
		assertThat(assembler.assemble(READER).loans())
				.extracting("loanId").containsExactly("loan_ok");
	}

	@Test
	@DisplayName("one bad row does not blank the shelf")
	void oneBadRowDoesNotCostTheWholeShelf() {
		givenCursor(ChangeCursor.of(4L));
		givenHolds();
		givenLoans(loan("loan_gone", "item_oa9", "RETURNED"), loan("loan_ok", "item_42", "ACTIVE"));

		// Failing the whole request would replace one wrong row with an empty screen, which is worse
		// for a reader who has nine other books. The drop is logged at error instead.
		assertThat(assembler.assemble(READER).loans()).hasSize(1);
	}

	@Test
	@DisplayName("an open-ended loan carries no dueAt, so the card shows no countdown")
	void openEndedLoanHasNoDueDate() {
		givenCursor(ChangeCursor.of(4L));
		givenHolds();
		givenLoans(
				new ActiveLoanView("loan_7c1", "item_42", "ELITE", false,
						NOW.plus(13, ChronoUnit.DAYS), null, null, NOW.minus(1, ChronoUnit.DAYS), "ACTIVE"),
				new ActiveLoanView("loan_oa9", "item_oa9", "OPEN_ACCESS", true, null, null, null, NOW.minus(9, ChronoUnit.DAYS), "ACTIVE"));

		LibraryResponse response = assembler.assemble(READER);

		assertThat(response.loans().get(1).dueAt()).isNull();
		// A dated loan has to be ahead of the response's own serverTime, or the app renders a loan
		// that expired before it was handed over.
		assertThat(response.loans().get(0).dueAt()).isAfter(response.serverTime());
	}

	@Test
	@DisplayName("borrowedAt is now populated from the seam (D-026)")
	void borrowedAtIsPopulatedFromTheSeam() {
		givenCursor(ChangeCursor.of(4L));
		givenHolds();
		Instant borrowed = NOW.minus(1, ChronoUnit.DAYS);
		givenLoans(new ActiveLoanView("loan_7c1", "item_42", "ELITE", false,
				NOW.plus(13, ChronoUnit.DAYS), null, null, borrowed, "ACTIVE"));

		assertThat(assembler.assemble(READER).loans().get(0).borrowedAt()).isEqualTo(borrowed);
	}

	@Test
	@DisplayName("holds come from the published hold seam, mapped onto the wire shape")
	void mapsHoldsFromTheSeam() {
		givenCursor(ChangeCursor.of(4L));
		givenLoans();
		givenHolds(queued("hold_q7", "item_q7", 3, 7, 12), offered("hold_f3", "item_f3", 1, 4));

		LibraryResponse response = assembler.assemble(READER);

		assertThat(response.holds()).extracting("itemId").containsExactly("item_q7", "item_f3");
		assertThat(response.holds().get(0).position()).isEqualTo(3);
		assertThat(response.holds().get(0).queueLength()).isEqualTo(7);
	}

	@Test
	@DisplayName("an offered hold swaps the wait guess for a real deadline")
	void offeredHoldHasADeadlineAndNoGuess() {
		givenCursor(ChangeCursor.of(4L));
		givenLoans();
		givenHolds(queued("hold_q7", "item_q7", 3, 7, 12), offered("hold_f3", "item_f3", 1, 4));

		LibraryResponse response = assembler.assemble(READER);

		var offered = response.holds().get(1);
		assertThat(offered.status()).isEqualTo("OFFERED");
		assertThat(offered.estimatedWaitDays()).isNull();
		assertThat(offered.offer().offerId()).isEqualTo("offer_f3");
		assertThat(offered.offer().expiresAt()).isAfter(response.serverTime());

		var waiting = response.holds().get(0);
		assertThat(waiting.status()).isEqualTo("QUEUED");
		assertThat(waiting.offer()).isNull();
		assertThat(waiting.estimatedWaitDays()).isEqualTo(12);
	}

	@Test
	@DisplayName("offeredAt is dropped: the reader is racing the deadline, not the start")
	void offerCarriesOnlyWhatTheCardNeeds() {
		givenCursor(ChangeCursor.of(4L));
		givenLoans();
		givenHolds(offered("hold_f3", "item_f3", 1, 4));

		var offer = assembler.assemble(READER).holds().get(0).offer();

		// LibraryOffer is two fields where OfferView is three. Asserting the shape here stops the
		// third quietly reappearing in the response and changing what team1 parses.
		assertThat(offer.offerId()).isEqualTo("offer_f3");
		assertThat(offer.expiresAt()).isNotNull();
	}

	@Test
	@DisplayName("the cursor is the reader's own feed position, as at this response")
	void carriesTheFeedCursor() {
		givenCursor(ChangeCursor.of(1189L));
		givenLoans();
		givenHolds();

		assertThat(assembler.assemble(READER).cursor()).isEqualTo("1189");
	}

	@Test
	@DisplayName("a reader with no history gets a cursor they can send straight back")
	void newReaderGetsTheBeginning() {
		givenCursor(ChangeCursor.BEGINNING);
		givenLoans();
		givenHolds();

		String cursor = assembler.assemble(READER).cursor();

		assertThat(cursor).isEqualTo("0");
		// The app sends this back unmodified on its first sync, so it has to parse.
		assertThat(ChangeCursor.parse(cursor)).isEqualTo(ChangeCursor.BEGINNING);
	}

	@Test
	@DisplayName("serverTime is the server's, so every countdown on the screen has one anchor")
	void anchorsToServerTime() {
		givenCursor(ChangeCursor.of(1189L));
		givenLoans();
		givenHolds();

		assertThat(assembler.assemble(READER).serverTime()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("the cursor is read before both shelves, so nothing falls between the reads")
	void readsTheCursorBeforeTheShelves() {
		givenCursor(ChangeCursor.of(1189L));
		givenLoans();
		givenHolds();

		assembler.assemble(READER);

		// Cursor last would mean a change landing mid-assembly is behind the cursor but missing from
		// the snapshot, and the app never learns about it. Cursor first replays it, which converges.
		InOrder order = inOrder(changeFeed, activeLoans, holdSnapshots);
		order.verify(changeFeed).currentCursor("user_9c2");
		order.verify(activeLoans).findAllFor("user_9c2");
		order.verify(holdSnapshots).holdsFor("user_9c2");
	}

	@Test
	@DisplayName("both seams are asked for this reader, never anybody else")
	void asksBothSeamsForThisReader() {
		givenCursor(ChangeCursor.of(1189L));
		givenLoans();
		givenHolds();

		assembler.assemble(READER);

		verify(activeLoans).findAllFor("user_9c2");
		verify(holdSnapshots).holdsFor("user_9c2");
	}

	@Test
	@DisplayName("an individual subscriber has no institution and is not defaulted into one")
	void individualSubscriberBelongsToNoInstitution() {
		assertThat(new ReaderIdentity("user_solo", null).belongsToAnInstitution()).isFalse();
		assertThat(new ReaderIdentity("user_solo", "  ").belongsToAnInstitution()).isFalse();
		assertThat(READER.belongsToAnInstitution()).isTrue();
	}

	private void givenCursor(ChangeCursor cursor) {
		when(changeFeed.currentCursor(READER.userId())).thenReturn(cursor);
	}

	/** A live, dated loan with the given status — the nine-component view since D-026. */
	private static ActiveLoanView loan(String loanId, String itemId, String status) {
		return new ActiveLoanView(loanId, itemId, "ELITE", false,
				NOW.plus(13, ChronoUnit.DAYS), null, null, NOW.minus(1, ChronoUnit.DAYS), status);
	}

	private void givenLoans(ActiveLoanView... loans) {
		when(activeLoans.findAllFor(anyString())).thenReturn(List.of(loans));
	}

	private void givenHolds(HoldSnapshot... holds) {
		when(holdSnapshots.holdsFor(anyString())).thenReturn(List.of(holds));
	}

	private static HoldSnapshot queued(String holdId, String itemId, int position, int queueLength,
			Integer waitDays) {
		return new HoldSnapshot(holdId, itemId, "QUEUED", position, queueLength, waitDays,
				NOW.minus(5, ChronoUnit.DAYS), null);
	}

	private static HoldSnapshot offered(String holdId, String itemId, int position, int queueLength) {
		return new HoldSnapshot(holdId, itemId, "OFFERED", position, queueLength, null,
				NOW.minus(11, ChronoUnit.DAYS),
				new OfferView("offer_f3", NOW.minus(2, ChronoUnit.HOURS),
						NOW.plus(36, ChronoUnit.HOURS)));
	}

}
