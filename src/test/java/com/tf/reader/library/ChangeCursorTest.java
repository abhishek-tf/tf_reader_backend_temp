package com.tf.reader.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tf.reader.library.service.ChangeCursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cursor is the whole sync contract in one value, so the edges matter more than the middle.
 */
class ChangeCursorTest {

	@Test
	@DisplayName("a cursor survives the round trip through the wire form")
	void roundTrips() {
		assertThat(ChangeCursor.parse(ChangeCursor.of(1189L).value()))
				.isEqualTo(ChangeCursor.of(1189L));
	}

	@Test
	@DisplayName("no cursor means from the beginning, so a client storing nothing gets everything")
	void absentMeansBeginning() {
		assertThat(ChangeCursor.parse(null)).isEqualTo(ChangeCursor.BEGINNING);
		assertThat(ChangeCursor.parse("")).isEqualTo(ChangeCursor.BEGINNING);
		assertThat(ChangeCursor.parse("   ")).isEqualTo(ChangeCursor.BEGINNING);
	}

	@Test
	@DisplayName("the beginning is zero, which no allocated sequence ever is")
	void beginningIsBeforeEverything() {
		assertThat(ChangeCursor.BEGINNING.sequence()).isZero();
		assertThat(ChangeCursor.BEGINNING.value()).isEqualTo("0");
	}

	@Test
	@DisplayName("a timestamp is not a cursor, and is refused rather than read as the beginning")
	void refusesATimestamp() {
		// The failure this prevents: a device deriving a cursor from its own clock, then silently
		// receiving an empty feed forever.
		assertThatThrownBy(() -> ChangeCursor.parse("2026-08-20T10:00:00Z"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("anything we could not have issued is rejected, not replayed from scratch")
	void rejectsWhatWeDidNotIssue() {
		// Silently replaying the whole feed would hide a client bug behind a slow-looking screen.
		assertThatThrownBy(() -> ChangeCursor.parse("abc"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ChangeCursor.parse("11.89"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("a negative cursor is refused at construction, not carried around")
	void refusesNegative() {
		assertThatThrownBy(() -> ChangeCursor.of(-1L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ChangeCursor.parse("-5"))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
