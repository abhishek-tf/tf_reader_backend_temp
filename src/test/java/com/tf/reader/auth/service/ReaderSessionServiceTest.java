package com.tf.reader.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.entity.ReaderSession;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.repository.ReaderSessionRepository;
import com.tf.reader.auth.service.ReaderSessionService.IssuedRefreshToken;

/**
 * The service surface {@code AuthController} drives: create a session at sign-in, rotate it at
 * refresh. Persistence-level guarantees (the atomic claim, the two indexes) are
 * {@code ReaderSessionRepositoryTest}'s job; this is about what the service does with them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReaderSessionServiceTest {

	private static final TnfUser MEMBER = new TnfUser("usr_6712ab", UserType.INSTITUTION,
			"inst_7f3", List.of("MEMBER"), List.of("col_medicine"));

	@Autowired
	private ReaderSessionService readerSessions;

	@Autowired
	private ReaderSessionRepository readerSessionRepository;

	@BeforeEach
	void clearSessions() {
		this.readerSessionRepository.deleteAll();
	}

	@Test
	void createsASessionThatCanLaterBeRotated() {
		IssuedRefreshToken issued = readerSessions.createSession(MEMBER);

		assertThat(issued.value()).isNotBlank();
		assertThat(issued.session().getUserId()).isEqualTo("usr_6712ab");
		assertThat(issued.session().getRoles()).containsExactly("MEMBER");
		assertThat(issued.session().getExpiresAt()).isAfter(issued.session().getIssuedAt());

		Optional<ReaderSession> rotated = readerSessions.revokeForExchange(issued.value());

		assertThat(rotated).isPresent();
		assertThat(rotated.get().getId()).isEqualTo(issued.session().getId());
		assertThat(rotated.get().getInstitutionId()).isEqualTo("inst_7f3");
		assertThat(rotated.get().getCollections()).containsExactly("col_medicine");
	}

	@Test
	void aRotatedTokenCannotBeExchangedASecondTime() {
		IssuedRefreshToken issued = readerSessions.createSession(MEMBER);
		readerSessions.revokeForExchange(issued.value());

		assertThat(readerSessions.revokeForExchange(issued.value()))
				.describedAs("the same refresh token presented twice must not yield two sessions")
				.isEmpty();
	}

	@Test
	void anUnknownRefreshTokenIsRefused() {
		assertThat(readerSessions.revokeForExchange("never-issued")).isEmpty();
	}

	@Test
	void twoSessionsForTheSameUserGetIndependentRefreshTokens() {
		IssuedRefreshToken first = readerSessions.createSession(MEMBER);
		IssuedRefreshToken second = readerSessions.createSession(MEMBER);

		assertThat(first.value()).isNotEqualTo(second.value());
		assertThat(readerSessions.revokeForExchange(first.value())).isPresent();
		// Rotating the first session must not have touched the second.
		assertThat(readerSessions.revokeForExchange(second.value())).isPresent();
	}
}
