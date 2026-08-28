package com.tf.reader.loan.service;

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
import com.tf.reader.loan.entity.LicenceModel;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev-only loan fixtures for team flambeau, sharing {@code flambeau-seed.json}'s {@code loans}
 * array with the sibling seeders in loan/hold/reading/library — one file, one array per module, so
 * neither team edits wokay's {@code demo-dataset.json}. Same safety rails as that seeder: local
 * profile, the shared {@code tnf.seed.enabled} flag, insert-missing-only, never a delete.
 *
 * <p>{@code loan_seed_b2}'s return is what frees the copy {@code hold_seed_c3}'s offer (in the
 * same file's {@code holds} array) now occupies, so a reconciler run at startup rebuilds a
 * consistent Redis lease state from both arrays together.
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "tnf.seed", name = "enabled", havingValue = "true")
public class LoanDevDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LoanDevDataSeeder.class);
	private static final String DATASET_PATH = "seed/flambeau-seed.json";

	private final LoanRepository loans;
	private final ObjectMapper mapper;

	public LoanDevDataSeeder(LoanRepository loans, ObjectMapper mapper) {
		this.loans = loans;
		this.mapper = mapper;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		List<SeedLoan> seeds;
		try (InputStream in = new ClassPathResource(DATASET_PATH).getInputStream()) {
			JsonNode root = mapper.readTree(in);
			seeds = mapper.convertValue(root.get("loans"),
					mapper.getTypeFactory().constructCollectionType(List.class, SeedLoan.class));
		}

		int inserted = 0;
		for (SeedLoan seed : seeds) {
			if (loans.existsById(seed.loanId())) {
				continue;
			}
			loans.save(seed.toLoan());
			inserted++;
		}
		log.info("flambeau loan seed: {} inserted, {} already present", inserted, seeds.size() - inserted);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SeedLoan(
			String loanId,
			String userId,
			String itemId,
			String institutionId,
			LicenceModel licenceModel,
			LoanStatus status,
			boolean canPersist,
			String leaseId,
			Instant borrowedAt,
			Instant dueAt,
			Instant returnedAt,
			Instant expiredAt) {

		Loan toLoan() {
			return Loan.builder()
					.loanId(loanId).userId(userId).itemId(itemId).institutionId(institutionId)
					.licenceModel(licenceModel).status(status).canPersist(canPersist).leaseId(leaseId)
					.borrowedAt(borrowedAt).dueAt(dueAt).returnedAt(returnedAt).expiredAt(expiredAt)
					.build();
		}
	}
}
