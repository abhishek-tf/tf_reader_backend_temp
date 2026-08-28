package com.tf.reader.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.auth.entity.ReaderSession;
import com.tf.reader.auth.model.UserType;

/**
 * The guarded session update, tested directly - mirrors {@code AdminSessionRepositoryTest}.
 * Rows are addressed by refresh-token hash, because an opaque token carries no session id.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReaderSessionRepositoryTest {

	@Autowired
	private ReaderSessionRepository readerSessionRepository;

	@Autowired
	private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

	@BeforeEach
	void clearSessions() {
		this.readerSessionRepository.deleteAll();
	}

	@Test
	void claimsALiveRowByRevokingIt() {
		givenSession("rsess_1", "hash-1", Instant.now().plus(Duration.ofDays(1)));

		Optional<ReaderSession> claimed =
				this.readerSessionRepository.revokeForExchange("hash-1", "ROTATED", Instant.now());

		assertThat(claimed).isPresent();
		assertThat(claimed.get().getId()).isEqualTo("rsess_1");
		assertThat(claimed.get().getUserId()).isEqualTo("usr_6712ab");

		ReaderSession stored = this.readerSessionRepository.findById("rsess_1").orElseThrow();
		assertThat(stored.getRevokedAt()).isNotNull();
		assertThat(stored.getRevokedReason()).isEqualTo("ROTATED");
	}

	@Test
	void refusesARevokedOrExpiredRow() {
		givenSession("revoked", "hash-revoked", Instant.now().plus(Duration.ofDays(1)));
		this.readerSessionRepository.revokeForExchange("hash-revoked", "ROTATED", Instant.now());

		givenSession("expired", "hash-expired", Instant.now().minus(Duration.ofMinutes(1)));

		assertThat(this.readerSessionRepository.revokeForExchange("hash-revoked", "ROTATED", Instant.now()))
				.describedAs("an already-revoked row cannot be exchanged a second time")
				.isEmpty();
		assertThat(this.readerSessionRepository.revokeForExchange("hash-expired", "ROTATED", Instant.now()))
				.isEmpty();
	}

	/** One token can never belong to two rows, so a hash collision cannot widen access. */
	@Test
	void refusesTwoRowsWithTheSameRefreshTokenHash() {
		givenSession("rsess_1", "shared-hash", Instant.now().plus(Duration.ofDays(1)));

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> givenSession("rsess_2", "shared-hash", Instant.now().plus(Duration.ofDays(1))))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void createsTheTwoMandatoryIndexes() {
		givenSession("rsess_index", "hash-index", Instant.now().plus(Duration.ofDays(1)));

		List<org.springframework.data.mongodb.core.index.IndexInfo> indexes =
				this.mongoTemplate.indexOps("readerSessions").getIndexInfo();

		assertThat(indexes)
				.anySatisfy(index -> {
					assertThat(index.isUnique()).isTrue();
					assertThat(index.getIndexFields()).singleElement()
							.satisfies(field -> assertThat(field.getKey()).isEqualTo("refreshTokenHash"));
				})
				.anySatisfy(index -> {
					assertThat(index.getExpireAfter()).isPresent();
					assertThat(index.getIndexFields()).singleElement()
							.satisfies(field -> assertThat(field.getKey()).isEqualTo("expiresAt"));
				});
	}

	private void givenSession(String id, String refreshTokenHash, Instant expiresAt) {
		ReaderSession session = new ReaderSession();
		session.setId(id);
		session.setUserId("usr_6712ab");
		session.setType(UserType.INSTITUTION);
		session.setInstitutionId("inst_7f3");
		session.setRoles(List.of("MEMBER"));
		session.setCollections(List.of("col_medicine"));
		session.setRefreshTokenHash(refreshTokenHash);
		session.setIssuedAt(Instant.now());
		session.setExpiresAt(expiresAt);
		this.readerSessionRepository.save(session);
	}

}
