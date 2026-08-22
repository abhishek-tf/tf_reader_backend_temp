package com.tf.reader.library;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tf.reader.library.dto.LibraryResponse;
import com.tf.reader.library.repository.MockLibraryRepository;
import com.tf.reader.library.service.ChangeCursor;
import com.tf.reader.library.service.ChangeFeedService;
import com.tf.reader.library.service.LibraryAssembler;
import com.tf.reader.library.support.ReaderIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibraryAssemblerTest {

	private static final ReaderIdentity READER = new ReaderIdentity("user_9c2", "inst_7f3");

	/** The one identity {@code MockLibraryRepository} seeds a full shelf for. */
	private static final ReaderIdentity SEEDED_READER = new ReaderIdentity("usr_dev123", "inst_7f3");

	private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private final ChangeFeedService changeFeed = mock(ChangeFeedService.class);

	// The real fixture, not a Mockito mock: it holds no collaborator but the clock, so stubbing it
	// would only assert that the seed says what the seed says.
	private final LibraryAssembler assembler =
			new LibraryAssembler(changeFeed, new MockLibraryRepository(CLOCK), CLOCK);

	@Test
	@DisplayName("loans and holds are empty arrays, never absent, so the screen is built once")
	void publishesTheShapeBeforeTheContent() {
		givenCursor(ChangeCursor.of(1189L));

		LibraryResponse response = assembler.assemble(READER);

		// An unseeded reader is what a brand new reader looks like: empty, never null.
		assertThat(response.loans()).isNotNull().isEmpty();
		assertThat(response.holds()).isNotNull().isEmpty();
	}

	@Test
	@DisplayName("a seeded reader gets their shelf, so the screen has something to render")
	void seededReaderGetsAShelf() {
		when(changeFeed.currentCursor(SEEDED_READER.userId())).thenReturn(ChangeCursor.of(4L));

		LibraryResponse response = assembler.assemble(SEEDED_READER);

		assertThat(response.loans()).extracting("itemId").containsExactly("item_42", "item_oa9");
		assertThat(response.holds()).extracting("itemId").containsExactly("item_q7", "item_f3");
	}

	@Test
	@DisplayName("an open-ended loan carries no dueAt, so the card shows no countdown")
	void openEndedLoanHasNoDueDate() {
		when(changeFeed.currentCursor(SEEDED_READER.userId())).thenReturn(ChangeCursor.of(4L));

		LibraryResponse response = assembler.assemble(SEEDED_READER);

		assertThat(response.loans().get(1).licenceModel()).isEqualTo("OPEN_ACCESS");
		assertThat(response.loans().get(1).dueAt()).isNull();
		// Every other date is measured against the response's own serverTime, so a dated loan has to
		// be ahead of it or the app renders a loan that expired before it was handed over.
		assertThat(response.loans().get(0).dueAt()).isAfter(response.serverTime());
	}

	@Test
	@DisplayName("an offered hold swaps the wait guess for a real deadline")
	void offeredHoldHasADeadlineAndNoGuess() {
		when(changeFeed.currentCursor(SEEDED_READER.userId())).thenReturn(ChangeCursor.of(4L));

		LibraryResponse response = assembler.assemble(SEEDED_READER);

		var offered = response.holds().get(1);
		assertThat(offered.status()).isEqualTo("OFFERED");
		assertThat(offered.estimatedWaitDays()).isNull();
		assertThat(offered.offer().expiresAt()).isAfter(response.serverTime());

		var waiting = response.holds().get(0);
		assertThat(waiting.status()).isEqualTo("WAITING");
		assertThat(waiting.offer()).isNull();
		assertThat(waiting.estimatedWaitDays()).isNotNull();
	}

	@Test
	@DisplayName("the cursor is the reader's own feed position, as at this response")
	void carriesTheFeedCursor() {
		givenCursor(ChangeCursor.of(1189L));

		assertThat(assembler.assemble(READER).cursor()).isEqualTo("1189");
	}

	@Test
	@DisplayName("a reader with no history gets a cursor they can send straight back")
	void newReaderGetsTheBeginning() {
		givenCursor(ChangeCursor.BEGINNING);

		String cursor = assembler.assemble(READER).cursor();

		assertThat(cursor).isEqualTo("0");
		// The app sends this back unmodified on its first sync, so it has to parse.
		assertThat(ChangeCursor.parse(cursor)).isEqualTo(ChangeCursor.BEGINNING);
	}

	@Test
	@DisplayName("serverTime is the server's, so every countdown on the screen has one anchor")
	void anchorsToServerTime() {
		givenCursor(ChangeCursor.of(1189L));

		assertThat(assembler.assemble(READER).serverTime()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("the cursor is asked for by this reader, never for anybody else")
	void readsTheCursorForThisReader() {
		givenCursor(ChangeCursor.of(1189L));

		assembler.assemble(READER);

		// The read-order rule this defends — cursor before shelf, so a change landing mid-assembly
		// is replayed rather than skipped — cannot be asserted until the loan and hold queries exist
		// to be ordered against. Until then this pins only the reader the cursor is for.
		verify(changeFeed).currentCursor("user_9c2");
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

}
