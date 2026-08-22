package com.tf.reader.library.repository;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tf.reader.library.dto.LibraryHold;
import com.tf.reader.library.dto.LibraryLoan;
import com.tf.reader.library.dto.LibraryOffer;

/**
 * Seeded shelves, so the library screen has something to render before the loan and hold seams
 * publish a list.
 *
 * <p><b>This is a fixture, not a read model.</b> Nothing here reaches Mongo or Redis, so a hold
 * placed through {@code POST /api/v1/holds} does not appear on these shelves and a loan returned
 * through the loan module does not leave them. The shapes are real; the data is not.
 *
 * <p><b>It lives in {@code library/} on purpose.</b> The alternative — a local {@code @Service}
 * implementing {@code hold.api.HoldSnapshotQuery} — puts a second candidate in the context the
 * moment the hold lane annotates theirs, and the application then fails to start for whoever merges
 * second. A plain component nobody else declares cannot collide.
 *
 * <p>Deleting it is one commit: drop this file and have {@code LibraryAssembler} call the published
 * ports instead.
 *
 * <p>Item ids are the ones in {@code seed/demo-dataset.json}, so
 * {@code POST /api/v1/catalogue/items:batch} resolves them to real titles and covers rather than
 * 404ing on an id invented here.
 */
@Component
public class MockLibraryRepository {

	/** The default {@code POST /api/v1/auth/dev-token} mints, so a token with no parameters lands on a full shelf. */
	private static final String DEV_READER = "usr_dev123";

	/** {@code john.doe@example.com} at Imperial, from {@code auth.repository.MockUserRepository}. */
	private static final String IMPERIAL_READER = "usr_6712ab";

	private final Clock clock;

	public MockLibraryRepository(Clock clock) {
		this.clock = clock;
	}

	/**
	 * Between them the two seeded loans cover both wire shapes the app has to render: a dated loan
	 * that counts down, and an open-ended one where {@code dueAt} is absent from the JSON entirely
	 * rather than null.
	 */
	public List<LibraryLoan> loansFor(String userId) {
		Instant now = now();
		return switch (userId) {
			case DEV_READER -> List.of(
					// ELITE, so canPersist is false and the download button stays off.
					new LibraryLoan("loan_mock_42", "item_42", "ELITE", "ACTIVE",
							now.minus(1, ChronoUnit.DAYS), now.plus(13, ChronoUnit.DAYS), false),
					// Open access never expires, so dueAt is null and @JsonInclude drops the field.
					new LibraryLoan("loan_mock_oa9", "item_oa9", "OPEN_ACCESS", "ACTIVE",
							now.minus(9, ChronoUnit.DAYS), null, true));
			case IMPERIAL_READER -> List.of(
					new LibraryLoan("loan_mock_f3", "item_f3", "SUBSCRIPTION", "ACTIVE",
							now.minus(3, ChronoUnit.DAYS), now.plus(20, ChronoUnit.DAYS), true));
			default -> List.of();
		};
	}

	/**
	 * One WAITING hold and one OFFERED hold, because the card is a different card in each state:
	 * WAITING shows a queue position and a guess, OFFERED shows a real deadline and no guess.
	 */
	public List<LibraryHold> holdsFor(String userId) {
		Instant now = now();
		return switch (userId) {
			case DEV_READER -> List.of(
					new LibraryHold("hold_mock_q7", "item_q7", "WAITING", 3, 7, 12,
							now.minus(5, ChronoUnit.DAYS), null),
					// estimatedWaitDays is null once OFFERED — there is a real deadline instead, and
					// showing a guess beside a fact on one card is what confuses a reader into
					// abandoning a copy that is still theirs.
					new LibraryHold("hold_mock_f3", "item_f3", "OFFERED", 0, 4, null,
							now.minus(11, ChronoUnit.DAYS),
							new LibraryOffer("offer_mock_f3", now.plus(36, ChronoUnit.HOURS))));
			default -> List.of();
		};
	}

	/**
	 * Whole seconds, and from the injected clock rather than {@code Instant.now()}, so these
	 * timestamps sit on the same clock as the response's {@code serverTime} and a test can move both
	 * together. Relative to now rather than written down as literals: a fixed {@code dueAt} is in the
	 * past by next week and every seeded loan renders as expired.
	 */
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.SECONDS);
	}

}
