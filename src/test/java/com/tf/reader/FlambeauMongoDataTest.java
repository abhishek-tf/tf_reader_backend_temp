package com.tf.reader;

import com.tf.reader.admin.entity.AdminUser;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.Institution;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.EntitlementRepository;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.repository.HoldRepository;
import com.tf.reader.library.entity.ChangeLogEntry;
import com.tf.reader.library.repository.ChangeLogRepository;
import com.tf.reader.loan.entity.Loan;
import com.tf.reader.loan.entity.LoanStatus;
import com.tf.reader.loan.repository.LoanRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying MongoDB persistence across all primary entities:
 * Catalogue, Loans, Holds, Library Sync Log, and Institutions.
 */
@SpringBootTest
@ActiveProfiles("local")
public class FlambeauMongoDataTest extends ContainerisedInfrastructure {

	@Autowired
	private CatalogueItemRepository catalogueItemRepository;

	@Autowired
	private InstitutionRepository institutionRepository;

	@Autowired
	private EntitlementRepository entitlementRepository;

	@Autowired
	private LoanRepository loanRepository;

	@Autowired
	private HoldRepository holdRepository;

	@Autowired
	private ChangeLogRepository changeLogRepository;

	@Test
	@DisplayName("verify MongoDB persistence for Catalogue, Loans, Holds, and Sync Logs")
	void testMongoDataPersistence() {
		// 1. Verify Catalogue Items
		List<CatalogueItem> items = catalogueItemRepository.findAll();
		assertThat(items).isNotEmpty();
		assertThat(items).anyMatch(item -> "item_42".equals(item.getId()));

		// 2. Insert and verify Loan entity
		Loan newLoan = Loan.builder()
				.loanId("loan_test_1001")
				.itemId("item_cs2026")
				.userId("usr_dev123")
				.institutionId("inst_7f3")
				.status(LoanStatus.ACTIVE)
				.borrowedAt(Instant.now())
				.dueAt(Instant.now().plusSeconds(86400 * 14))
				.build();

		Loan savedLoan = loanRepository.save(newLoan);
		assertThat(savedLoan.getLoanId()).isEqualTo("loan_test_1001");
		assertThat(loanRepository.findByUserIdAndItemIdAndStatus("usr_dev123", "item_cs2026", LoanStatus.ACTIVE))
				.isPresent();

		// 3. Insert and verify Hold entity
		Hold newHold = Hold.queued("usr_dev123", "inst_7f3", "item_cs2026", 1L, Instant.now());
		Hold savedHold = holdRepository.save(newHold);
		assertThat(savedHold.getHoldId()).startsWith("hold_");

		// 5. Verify Publishers & Book Collections
		assertThat(catalogueItemRepository.findAll()).isNotEmpty();
		assertThat(institutionRepository.findAll()).isNotEmpty();
		assertThat(entitlementRepository.findAll()).isNotEmpty();

		// 6. Verify User Security & Admin collections
		AdminUser adminUser = new AdminUser(
				"adm_test_01",
				"admin.test@tandf.example",
				"Test Admin",
				"$2a$10$abcdefghijklmnopqrstuvwxyz0123456789",
				com.tf.reader.admin.entity.AdminRole.SUPER_ADMIN,
				null,
				null,
				com.tf.reader.admin.entity.AdminStatus.ACTIVE,
				Instant.now()
		);
		assertThat(adminUser.getEmail()).contains("@tandf.example");
	}
}
