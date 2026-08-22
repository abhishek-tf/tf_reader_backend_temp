package com.tf.reader.library.service;

/**
 * A position in one reader's change feed.
 *
 * <p><b>Opaque to the client, one type in here.</b> The wire form happens to be the decimal sequence
 * today, and the app is told not to parse it or construct one — so this record is the only place
 * that knows the encoding, and changing it later touches nothing else.
 *
 * <p><b>Never a timestamp.</b> A cursor the client could compute from its own clock is a cursor a
 * fast device sets into the future, and asking for changes since the future would return an empty
 * page forever with no error to notice. Every cursor we hand out came from a sequence we allocated,
 * which is also what makes the high-water-mark refusal possible.
 */
public record ChangeCursor(long sequence) {

	/** Before the reader's first entry. What a client that has stored nothing gets. */
	public static final ChangeCursor BEGINNING = new ChangeCursor(0L);

	public ChangeCursor {
		if (sequence < 0) {
			throw new IllegalArgumentException("cursor cannot be negative");
		}
	}

	public static ChangeCursor of(long sequence) {
		return new ChangeCursor(sequence);
	}

	/**
	 * Reads a cursor the client sent back.
	 *
	 * @param raw the {@code since} parameter; null or blank means from the beginning
	 * @throws IllegalArgumentException if it is not something this server could have issued
	 */
	public static ChangeCursor parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return BEGINNING;
		}
		String trimmed = raw.trim();
		try {
			return of(Long.parseLong(trimmed));
		}
		catch (NumberFormatException notOurs) {
			// Rejected rather than treated as the beginning. Silently replaying the whole feed hides
			// a client bug behind a screen that merely looks slow.
			throw new IllegalArgumentException("not a cursor this server issued: " + trimmed);
		}
	}

	/** The wire form. */
	public String value() {
		return Long.toString(sequence);
	}

}
