package com.tf.reader.library;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.auth.model.TnfUser;
import com.tf.reader.auth.model.UserType;
import com.tf.reader.auth.token.TokenService;
import com.tf.reader.library.api.ChangeLog;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.api.ChangeRecord;
import com.tf.reader.library.repository.ChangeLogRepository;
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both library endpoints through the whole application: the real filter chain, a real token from the
 * real {@code TokenService}, the real published seams, real MongoDB.
 *
 * <p><b>Why this exists when the module is already well covered.</b> Every other test here mocks
 * something structural — {@code LibraryAssemblerTest} mocks both seams, {@code
 * LibraryEndpointsWebTest} mocks the assembler itself. So the wiring between them is the one thing
 * nothing asserts, and it is exactly what breaks when another lane renames a field or changes a
 * signature. Two of those landed in a single week.
 *
 * <p><b>Fixtures are written here, not seeded.</b> {@code flambeau-seed.json} is shared and has been
 * edited twice this week; a test asserting on it would fail for reasons that have nothing to do with
 * this module. Every row below is created by this class and scoped to reader ids nothing else uses.
 *
 * <p><b>Holds are deliberately absent.</b> {@code QueueService} computes position and queueLength
 * from Redis, so seeding a hold that renders correctly means reproducing another lane's key layout —
 * a test that would fail when they change it, telling us nothing about the library module. Hold
 * mapping is covered against the published {@code HoldSnapshot} shape in
 * {@code LibraryAssemblerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LibraryEndpointsIT extends ContainerisedInfrastructure {

	/** Ids nothing else in the suite or the seed files uses, so this test owns its own data. */
	private static final String READER = "usr_it_library";
	private static final String OTHER_READER = "usr_it_intruder";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private LoanRepository loans;

	@Autowired
	private ChangeLogRepository changeLog;

	@Autowired
	private ChangeLog feed;

	@Autowired
	private Clock clock;

	@Autowired
	private MongoTemplate mongo;

	/**
	 * Clears both halves of the feed's state, not just the visible one.
	 *
	 * <p>Dropping {@code changeLog} without {@code changeSeq} leaves the counters where the previous
	 * test left them, so the next reader's first entry arrives at sequence 8 rather than 1 — the
	 * entries look right and every absolute assertion about ordering is quietly wrong. The counter
	 * collection has no repository because nothing in production reads it, so it is dropped by name.
	 */
	@BeforeEach
	void clearOwnData() {
		loans.findByUserIdAndStatus(READER, LoanStatus.ACTIVE).forEach(loans::delete);
		loans.findByUserIdAndStatus(OTHER_READER, LoanStatus.ACTIVE).forEach(loans::delete);
		changeLog.deleteAll();
		mongo.dropCollection("changeSeq");
	}

	@Test
	@DisplayName("a real loan reaches the shelf through the published seam")
	void aRealLoanReachesTheShelf() throws Exception {
		givenAnActiveLoan(READER, "loan_it_1", "item_42", LicenceModel.ELITE, false);

		mvc.perform(get("/api/v1/library").header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loans[0].loanId").value("loan_it_1"))
				.andExpect(jsonPath("$.loans[0].itemId").value("item_42"))
				.andExpect(jsonPath("$.loans[0].licenceModel").value("ELITE"))
				.andExpect(jsonPath("$.loans[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.loans[0].canPersist").value(false))
				.andExpect(jsonPath("$.loans[0].borrowedAt").exists())
				.andExpect(jsonPath("$.loans[0].dueAt").exists())
				.andExpect(jsonPath("$.holds").isArray())
				.andExpect(jsonPath("$.cursor").exists())
				.andExpect(jsonPath("$.serverTime").exists());
	}

	@Test
	@DisplayName("an open-access loan omits dueAt rather than sending null")
	void openAccessOmitsDueAt() throws Exception {
		Loan openEnded = activeLoan(READER, "loan_it_oa", "item_oa9", LicenceModel.OPEN_ACCESS, true);
		openEnded.setDueAt(null);
		loans.save(openEnded);

		mvc.perform(get("/api/v1/library").header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loans[0].dueAt").doesNotExist())
				.andExpect(jsonPath("$.loans[0].canPersist").value(true));
	}

	@Test
	@DisplayName("one reader never sees another's shelf, and no parameter can change that")
	void readersAreIsolated() throws Exception {
		givenAnActiveLoan(READER, "loan_it_mine", "item_42", LicenceModel.ELITE, false);
		givenAnActiveLoan(OTHER_READER, "loan_it_theirs", "item_env", LicenceModel.SUBSCRIPTION, true);

		// The query parameters are the attack: both are ignored, because the shelf comes from the
		// token and the endpoint has no input that could redirect it.
		mvc.perform(get("/api/v1/library")
						.param("userId", OTHER_READER)
						.param("institutionId", "inst_elsewhere")
						.header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loans.length()").value(1))
				.andExpect(jsonPath("$.loans[0].loanId").value("loan_it_mine"));
	}

	@Test
	@DisplayName("a loan past its due date is not on the shelf, sweep or no sweep")
	void aLapsedLoanIsNotLive() throws Exception {
		Loan lapsed = activeLoan(READER, "loan_it_lapsed", "item_42", LicenceModel.ELITE, false);
		lapsed.setDueAt(clock.instant().minus(1, ChronoUnit.HOURS));
		loans.save(lapsed);

		// Still ACTIVE in Mongo — the expiry sweep runs on a tick. D-006 re-derives liveness from
		// dueAt, so the shelf is right in the window where the stored status is stale.
		mvc.perform(get("/api/v1/library").header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loans").isEmpty());
	}

	@Test
	@DisplayName("the feed returns what the port wrote, oldest first")
	void theFeedReturnsWhatThePortWrote() throws Exception {
		Instant at = clock.instant();
		feed.record(ChangeRecord.forLoan(READER, ChangeReason.LOAN_CREATED, "item_42", "loan_it_1", at));
		feed.record(ChangeRecord.forHold(READER, ChangeReason.HOLD_PROMOTED, "item_q7", "hold_it_1", at));
		feed.record(ChangeRecord.forRevocation(READER, "item_env", "loan_it_2", at));

		mvc.perform(get("/api/v1/loans/changes").header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.changes.length()").value(3))
				.andExpect(jsonPath("$.changes[0].reason").value("LOAN_CREATED"))
				.andExpect(jsonPath("$.changes[1].reason").value("HOLD_PROMOTED"))
				.andExpect(jsonPath("$.changes[1].holdId").value("hold_it_1"))
				.andExpect(jsonPath("$.changes[1].loanId").doesNotExist())
				.andExpect(jsonPath("$.changes[2].reason").value("ENTITLEMENT_REVOKED"))
				.andExpect(jsonPath("$.hasMore").value(false));
	}

	@Test
	@DisplayName("the cursor handed out beside the shelf resumes the feed exactly")
	void theCursorHandoffLosesNothing() throws Exception {
		Instant at = clock.instant();
		feed.record(ChangeRecord.forLoan(READER, ChangeReason.LOAN_CREATED, "item_42", "loan_it_1", at));

		// This is the guarantee the whole design rests on, and until now nothing proved it end to
		// end: take the cursor from the library response, ask the feed for everything after it, and
		// there must be nothing left over and no rewind.
		String cursor = readCursor();

		mvc.perform(get("/api/v1/loans/changes").param("since", cursor)
						.header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.changes").isEmpty())
				.andExpect(jsonPath("$.nextCursor").value(cursor))
				.andExpect(jsonPath("$.hasMore").value(false));
	}

	@Test
	@DisplayName("a page break carries hasMore, and the next cursor picks up exactly there")
	void pagingResumesWithoutGaps() throws Exception {
		Instant at = clock.instant();
		for (int i = 1; i <= 3; i++) {
			feed.record(ChangeRecord.forLoan(READER, ChangeReason.LOAN_CREATED, "item_" + i,
					"loan_it_" + i, at));
		}

		String nextCursor = mvc.perform(get("/api/v1/loans/changes").param("size", "2")
						.header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.changes.length()").value(2))
				.andExpect(jsonPath("$.hasMore").value(true))
				.andReturn().getResponse().getContentAsString()
				.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

		mvc.perform(get("/api/v1/loans/changes").param("since", nextCursor)
						.header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.changes.length()").value(1))
				.andExpect(jsonPath("$.changes[0].itemId").value("item_3"))
				.andExpect(jsonPath("$.hasMore").value(false));
	}

	@Test
	@DisplayName("one reader's feed never contains another's changes")
	void feedsAreIsolated() throws Exception {
		Instant at = clock.instant();
		feed.record(ChangeRecord.forLoan(READER, ChangeReason.LOAN_CREATED, "item_42", "loan_mine", at));
		feed.record(ChangeRecord.forLoan(OTHER_READER, ChangeReason.LOAN_CREATED, "item_env",
				"loan_theirs", at));

		mvc.perform(get("/api/v1/loans/changes").header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.changes.length()").value(1))
				.andExpect(jsonPath("$.changes[0].loanId").value("loan_mine"));
	}

	@Test
	@DisplayName("sequences are per reader, so one busy reader cannot skip another's entries")
	void sequencesAreScopedToTheReader() throws Exception {
		Instant at = clock.instant();
		for (int i = 0; i < 4; i++) {
			feed.record(ChangeRecord.forLoan(OTHER_READER, ChangeReason.LOAN_CREATED, "item_env",
					"loan_theirs_" + i, at));
		}
		feed.record(ChangeRecord.forLoan(READER, ChangeReason.LOAN_CREATED, "item_42", "loan_mine", at));

		// Their four entries must not push ours to sequence 5: a client resuming from a stored cursor
		// of 4 would then never see it.
		mvc.perform(get("/api/v1/loans/changes").header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.changes[0].sequence").value(1));
	}

	@Test
	@DisplayName("a cursor from the future is refused in the shared envelope, never an empty page")
	void aFutureCursorIsRefused() throws Exception {
		mvc.perform(get("/api/v1/loans/changes").param("since", "9999")
						.header("Authorization", bearer(READER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.path").value("/api/v1/loans/changes"))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	@DisplayName("a brand new reader gets an empty page, not a refusal")
	void aNewReaderIsNotFromTheFuture() throws Exception {
		mvc.perform(get("/api/v1/loans/changes").header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.changes").isEmpty())
				.andExpect(jsonPath("$.nextCursor").value("0"));
	}

	@Test
	@DisplayName("neither endpoint answers without a token")
	void bothEndpointsDenyWithoutAToken() throws Exception {
		mvc.perform(get("/api/v1/library")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/loans/changes")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("a garbled token is refused, not treated as anonymous")
	void aGarbledTokenIsRefused() throws Exception {
		mvc.perform(get("/api/v1/library").header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized());
	}

	/** The cursor the library response hands out, which the app is told to send straight back. */
	private String readCursor() throws Exception {
		return mvc.perform(get("/api/v1/library").header("Authorization", bearer(READER)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString()
				.replaceAll(".*\"cursor\":\"([^\"]+)\".*", "$1");
	}

	/** A real token from the real issuer, so the filter chain verifies what auth actually mints. */
	private String bearer(String userId) {
		TnfUser user = new TnfUser(userId, UserType.INSTITUTION, "inst_7f3",
				List.of("MEMBER"), List.of("col_law2024"));
		return "Bearer " + tokens.issue(user).token();
	}

	private void givenAnActiveLoan(String userId, String loanId, String itemId,
			LicenceModel licence, boolean canPersist) {
		loans.save(activeLoan(userId, loanId, itemId, licence, canPersist));
	}

	private Loan activeLoan(String userId, String loanId, String itemId, LicenceModel licence,
			boolean canPersist) {
		Instant now = clock.instant();
		return Loan.builder()
				.loanId(loanId)
				.itemId(itemId)
				.userId(userId)
				.institutionId("inst_7f3")
				.licenceModel(licence)
				.status(LoanStatus.ACTIVE)
				.canPersist(canPersist)
				.borrowedAt(now.minus(1, ChronoUnit.DAYS))
				.dueAt(now.plus(13, ChronoUnit.DAYS))
				.build();
	}

}
