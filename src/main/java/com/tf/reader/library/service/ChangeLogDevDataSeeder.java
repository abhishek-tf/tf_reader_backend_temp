package com.tf.reader.library.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tf.reader.library.api.ChangeReason;
import com.tf.reader.library.entity.ChangeLogEntry;
import com.tf.reader.library.repository.ChangeLogRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev-only change-feed fixtures for team flambeau, reading the {@code changelog} array out of the
 * shared {@code flambeau-seed.json}. Same rails as {@link
 * com.tf.reader.loan.service.LoanDevDataSeeder}: local profile, {@code tnf.seed.enabled}.
 *
 * <p>Skipped per reader, not per entry: a reader who already has any change-feed history is left
 * alone entirely, since {@link ReaderSequenceAllocator} — reused here, not reimplemented — hands
 * out the real next sequence, and inserting seed rows into the middle of an existing history would
 * make the unique {@code reader_sequence} index the thing that catches our own mistake instead of
 * a genuine race.
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "tnf.seed", name = "enabled", havingValue = "true")
public class ChangeLogDevDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ChangeLogDevDataSeeder.class);
	private static final String DATASET_PATH = "seed/flambeau-seed.json";

	private final ChangeLogRepository changeLog;
	private final ReaderSequenceAllocator sequences;
	private final ObjectMapper mapper;

	public ChangeLogDevDataSeeder(ChangeLogRepository changeLog, ReaderSequenceAllocator sequences,
			ObjectMapper mapper) {
		this.changeLog = changeLog;
		this.sequences = sequences;
		this.mapper = mapper;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		List<SeedReaderHistory> seeds;
		try (InputStream in = new ClassPathResource(DATASET_PATH).getInputStream()) {
			JsonNode root = mapper.readTree(in);
			seeds = mapper.convertValue(root.get("changelog"),
					mapper.getTypeFactory().constructCollectionType(List.class, SeedReaderHistory.class));
		}

		int insertedReaders = 0;
		for (SeedReaderHistory reader : seeds) {
			if (changeLog.findFirstByUserIdOrderBySequenceDesc(reader.userId()).isPresent()) {
				continue;
			}
			for (SeedEntry entry : reader.entries()) {
				long sequence = sequences.next(reader.userId());
				changeLog.save(entry.toEntity(reader.userId(), sequence));
			}
			insertedReaders++;
		}
		log.info("flambeau change-log seed: {} readers seeded, {} already had history",
				insertedReaders, seeds.size() - insertedReaders);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SeedReaderHistory(String userId, List<SeedEntry> entries) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SeedEntry(ChangeReason reason, String itemId, String loanId, String holdId, Instant occurredAt) {

		ChangeLogEntry toEntity(String userId, long sequence) {
			return ChangeLogEntry.builder()
					.userId(userId).sequence(sequence).reason(reason)
					.itemId(itemId).loanId(loanId).holdId(holdId).occurredAt(occurredAt)
					.build();
		}
	}
}
