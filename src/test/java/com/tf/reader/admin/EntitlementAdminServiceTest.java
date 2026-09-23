package com.tf.reader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.tf.reader.admin.dto.EntitlementCreate;
import com.tf.reader.admin.dto.EntitlementUpdate;
import com.tf.reader.admin.dto.EntitlementView;
import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.admin.service.EntitlementAdminService;
import com.tf.reader.catalogue.entity.BookCollection;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.catalogue.service.BookCollectionStore;
import com.tf.reader.catalogue.service.CatalogueItemStore;
import com.tf.reader.catalogue.repository.EntitlementRepository;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.catalogue.service.CatalogueVersionBumper;
import com.tf.reader.common.audit.AdminAuditWriter;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.security.TokenClaims;

/** Business rules for granting and managing entitlements, tested without a servlet or a database. */
class EntitlementAdminServiceTest {

	private final EntitlementRepository entitlementRepository = mock(EntitlementRepository.class);
	private final InstitutionRepository institutionRepository = mock(InstitutionRepository.class);
	private final PublisherRepository publisherRepository = mock(PublisherRepository.class);
	private final BookCollectionStore bookCollectionStore = mock(BookCollectionStore.class);
	private final CatalogueItemStore catalogueItemStore = mock(CatalogueItemStore.class);
	private final CatalogueVersionBumper versionBumper = mock(CatalogueVersionBumper.class);
	private final AdminAuditWriter auditWriter = mock(AdminAuditWriter.class);

	private final EntitlementAdminService service = new EntitlementAdminService(entitlementRepository,
			institutionRepository, publisherRepository, bookCollectionStore, catalogueItemStore,
			versionBumper, auditWriter, new AdminScopeAuthorizer());

	@BeforeEach
	void actAsSuperAdmin() {
		actingAs(AdminRole.SUPER_ADMIN, null);
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static void actingAs(AdminRole role, String institutionId) {
		Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(900)).claim(TokenClaims.ROLE, role.name());
		if (institutionId != null) {
			builder.claim(TokenClaims.SCOPE_INSTITUTION_ID, institutionId);
		}
		SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(builder.build(), null, "ROLE_ADMIN"));
	}

	// ---------------------------------------------------------------- create

	@Test
	void createsACollectionGrantWithAResolvedItemCount() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(bookCollectionStore.existsById("col_law2024")).thenReturn(true);
		when(bookCollectionStore.findById("col_law2024"))
				.thenReturn(Optional.of(new BookCollection("col_law2024", "pub_rtlg", "law2024", "Law 2024", null)));
		when(catalogueItemStore.countByCollectionIds("col_law2024")).thenReturn(2L);
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.COLLECTION, "col_law2024", 2, null,
				LocalDate.parse("2026-08-01"), LocalDate.parse("2026-12-31"), null);

		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.institutionId()).isEqualTo("inst_7f3");
		assertThat(created.scopeType()).isEqualTo(ScopeType.COLLECTION);
		assertThat(created.copyLimited()).isTrue();
		assertThat(created.loanPeriodDays()).isEqualTo(14);
		assertThat(created.status()).isEqualTo(EntitlementStatus.ACTIVE);
		assertThat(created.version()).isZero();
		assertThat(created.resolvedItemCount()).isEqualTo(2);
		assertThat(created.scopeLabel()).isEqualTo("Collection - Law 2024");

		verify(versionBumper).bump(CatalogueVersionBumper.Scope.INSTITUTION, "inst_7f3");

		ArgumentCaptor<Map<String, Object>> afterCaptor = ArgumentCaptor.forClass(Map.class);
		verify(auditWriter).record(any(), eq(com.tf.reader.common.audit.AuditLog.Action.CREATE), eq("ENTITLEMENT"),
				any(), eq(null), afterCaptor.capture());
		// The create audit entry names what was granted and to whom, since there is no "before"
		// to make institutionId/scopeType/scopeId redundant the way they would be on an update.
		assertThat(afterCaptor.getValue()).containsEntry("institutionId", "inst_7f3")
				.containsEntry("scopeType", "COLLECTION").containsEntry("scopeId", "col_law2024");
	}

	@Test
	void createWithAnUnknownScopeIdIsValidationFailed() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(bookCollectionStore.existsById("col_ghost")).thenReturn(false);

		EntitlementCreate write = new EntitlementCreate(ScopeType.COLLECTION, "col_ghost", null, null, null, null,
				null);

		assertThatThrownBy(() -> service.create("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

		verify(entitlementRepository, never()).save(any());
		verify(versionBumper, never()).bump(any(), any());
	}

	@Test
	void createOnAnUnknownInstitutionIsNotFound() {
		when(institutionRepository.existsById("inst_ghost")).thenReturn(false);

		EntitlementCreate write = new EntitlementCreate(ScopeType.PUBLISHER, "pub_rtlg", null, null, null, null, null);

		assertThatThrownBy(() -> service.create("inst_ghost", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void createByAnAdminScopedToAnotherInstitutionIsForbiddenScope() {
		actingAs(AdminRole.INSTITUTION_ADMIN, "inst_other");

		EntitlementCreate write = new EntitlementCreate(ScopeType.PUBLISHER, "pub_rtlg", null, null, null, null, null);

		assertThatThrownBy(() -> service.create("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN_SCOPE));

		verify(entitlementRepository, never()).save(any());
	}

	@Test
	void createByInstitutionAdminForcesPendingRegardlessOfRequestedStatus() {
		actingAs(AdminRole.INSTITUTION_ADMIN, "inst_7f3");
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(publisherRepository.existsById("pub_rtlg")).thenReturn(true);
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.PUBLISHER, "pub_rtlg", null, null, null, null,
				EntitlementStatus.ACTIVE);

		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.status()).isEqualTo(EntitlementStatus.PENDING);

		ArgumentCaptor<Map<String, Object>> afterCaptor = ArgumentCaptor.forClass(Map.class);
		verify(auditWriter).record(any(), eq(com.tf.reader.common.audit.AuditLog.Action.CREATE), eq("ENTITLEMENT"),
				any(), eq(null), afterCaptor.capture());
		assertThat(afterCaptor.getValue()).containsEntry("status", "PENDING");
	}

	@Test
	void createBySuperAdminHonorsExplicitStatus() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(publisherRepository.existsById("pub_rtlg")).thenReturn(true);
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.PUBLISHER, "pub_rtlg", null, null, null, null,
				EntitlementStatus.ACTIVE);

		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.status()).isEqualTo(EntitlementStatus.ACTIVE);
	}

	@Test
	void createBySuperAdminDefaultsToActiveWhenStatusOmitted() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(publisherRepository.existsById("pub_rtlg")).thenReturn(true);
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.PUBLISHER, "pub_rtlg", null, null, null, null, null);

		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.status()).isEqualTo(EntitlementStatus.ACTIVE);
	}

	@Test
	void createSupersedesARevokedEntitlementForTheSameScope() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(publisherRepository.existsById("pub_rtlg")).thenReturn(true);

		Entitlement revoked = entitlement("ent_5a1", "inst_7f3", ScopeType.PUBLISHER, "pub_rtlg", 3, 0);
		revoked.setStatus(EntitlementStatus.REVOKED);
		when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId("inst_7f3", ScopeType.PUBLISHER,
				"pub_rtlg")).thenReturn(Optional.of(revoked));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.PUBLISHER, "pub_rtlg", null, null, null, null, null);

		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.id()).isEqualTo("ent_5a1");
		assertThat(created.status()).isEqualTo(EntitlementStatus.ACTIVE);
		assertThat(created.version()).isEqualTo(1);
		verify(versionBumper).bump(CatalogueVersionBumper.Scope.INSTITUTION, "inst_7f3");

		ArgumentCaptor<Map<String, Object>> beforeCaptor = ArgumentCaptor.forClass(Map.class);
		verify(auditWriter).record(any(), eq(com.tf.reader.common.audit.AuditLog.Action.CREATE), eq("ENTITLEMENT"),
				eq("ent_5a1"), beforeCaptor.capture(), any());
		// The revoked row's own terms, not just its status - a re-request silently overwrites
		// them, and the audit trail must show what was replaced.
		assertThat(beforeCaptor.getValue()).containsEntry("status", "REVOKED").containsEntry("copies", "3");
	}

	@Test
	void createByInstitutionAdminSupersedesTheirOwnRevokedEntitlementAsPending() {
		actingAs(AdminRole.INSTITUTION_ADMIN, "inst_7f3");
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(publisherRepository.existsById("pub_rtlg")).thenReturn(true);

		Entitlement revoked = entitlement("ent_5a1", "inst_7f3", ScopeType.PUBLISHER, "pub_rtlg", null, 3);
		revoked.setStatus(EntitlementStatus.REVOKED);
		when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId("inst_7f3", ScopeType.PUBLISHER,
				"pub_rtlg")).thenReturn(Optional.of(revoked));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.PUBLISHER, "pub_rtlg", null, null, null, null,
				EntitlementStatus.ACTIVE);

		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.id()).isEqualTo("ent_5a1");
		assertThat(created.status()).isEqualTo(EntitlementStatus.PENDING);
		assertThat(created.version()).isEqualTo(4);
	}

	// ---------------------------------------------------------------- update

	@Test
	void updateWithTheCorrectVersionIncrementsIt() {
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.COLLECTION, "col_law2024", 2, 3);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(existing));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(bookCollectionStore.findById("col_law2024")).thenReturn(Optional.empty());
		when(catalogueItemStore.countByCollectionIds("col_law2024")).thenReturn(0L);

		EntitlementUpdate write = new EntitlementUpdate(5, 30, LocalDate.parse("2026-09-01"),
				LocalDate.parse("2027-01-31"), 3L);

		EntitlementView updated = service.update("ent_5a1", write);

		assertThat(updated.copies()).isEqualTo(5);
		assertThat(updated.loanPeriodDays()).isEqualTo(30);
		assertThat(updated.version()).isEqualTo(4);
		verify(versionBumper).bump(CatalogueVersionBumper.Scope.INSTITUTION, "inst_7f3");
	}

	@Test
	@DisplayName("an institution admin may not amend an ACTIVE grant - only a super admin can")
	void updateByAnInstitutionAdminOnAnActiveGrantIsForbidden() {
		actingAs(AdminRole.INSTITUTION_ADMIN, "inst_7f3");
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.COLLECTION, "col_law2024", 2, 3);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(existing));

		EntitlementUpdate write = new EntitlementUpdate(500000, 30, LocalDate.parse("2026-09-01"),
				LocalDate.parse("2036-01-31"), 3L);

		assertThatThrownBy(() -> service.update("ent_5a1", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));
		verify(entitlementRepository, never()).save(any());
	}

	@Test
	@DisplayName("an institution admin may still amend a still-PENDING grant, since nothing is live yet")
	void updateByAnInstitutionAdminOnAPendingGrantIsAllowed() {
		actingAs(AdminRole.INSTITUTION_ADMIN, "inst_7f3");
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.COLLECTION, "col_law2024", 2, 3,
				EntitlementStatus.PENDING);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(existing));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(bookCollectionStore.findById("col_law2024")).thenReturn(Optional.empty());
		when(catalogueItemStore.countByCollectionIds("col_law2024")).thenReturn(0L);

		EntitlementUpdate write = new EntitlementUpdate(5, 30, LocalDate.parse("2026-09-01"),
				LocalDate.parse("2027-01-31"), 3L);

		EntitlementView updated = service.update("ent_5a1", write);

		assertThat(updated.status()).isEqualTo(EntitlementStatus.PENDING);
		assertThat(updated.copies()).isEqualTo(5);
	}

	@Test
	@DisplayName("a super admin may amend an ACTIVE grant directly")
	void updateBySuperAdminOnAnActiveGrantIsAllowed() {
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.COLLECTION, "col_law2024", 2, 3);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(existing));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(bookCollectionStore.findById("col_law2024")).thenReturn(Optional.empty());
		when(catalogueItemStore.countByCollectionIds("col_law2024")).thenReturn(0L);

		EntitlementUpdate write = new EntitlementUpdate(5, 30, LocalDate.parse("2026-09-01"),
				LocalDate.parse("2027-01-31"), 3L);

		EntitlementView updated = service.update("ent_5a1", write);

		assertThat(updated.status()).isEqualTo(EntitlementStatus.ACTIVE);
	}

	@Test
	@DisplayName("update with an explicit null copies clears the copy limit to UNLIMITED")
	void updateWithNullCopiesClearsTheLimit() {
		// copies staying nullable (no @NotNull) is deliberate: null is UNLIMITED, a real value,
		// not an omission - see EntitlementUpdate. loanPeriodDays and validTo must still be
		// supplied since a full replace always resends them.
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.COLLECTION, "col_law2024", 2, 3);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(existing));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(bookCollectionStore.findById("col_law2024")).thenReturn(Optional.empty());
		when(catalogueItemStore.countByCollectionIds("col_law2024")).thenReturn(0L);

		EntitlementUpdate write = new EntitlementUpdate(null, 30, LocalDate.parse("2026-09-01"), null, 3L);

		EntitlementView updated = service.update("ent_5a1", write);

		assertThat(updated.copies()).isNull();
	}

	@Test
	void updateWithAStaleVersionIsConflict() {
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.COLLECTION, "col_law2024", 2, 3);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(existing));

		EntitlementUpdate write = new EntitlementUpdate(5, 30, null, null, 1L);

		assertThatThrownBy(() -> service.update("ent_5a1", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.STALE_VERSION));

		verify(entitlementRepository, never()).save(any());
	}

	@Test
	void updateOnAnUnknownEntitlementIsNotFound() {
		when(entitlementRepository.findById("ent_ghost")).thenReturn(Optional.empty());

		EntitlementUpdate write = new EntitlementUpdate(null, null, null, null, 0L);

		assertThatThrownBy(() -> service.update("ent_ghost", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	// ---------------------------------------------------------------- revoke

	@Test
	void revokeSoftDeletesAndBumpsTheCatalogueVersion() {
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.PUBLISHER, "pub_rtlg", null, 0);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(existing));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.revoke("ent_5a1");

		assertThat(existing.getStatus()).isEqualTo(EntitlementStatus.REVOKED);
		verify(versionBumper).bump(CatalogueVersionBumper.Scope.INSTITUTION, "inst_7f3");
		verify(auditWriter).record(any(), eq(com.tf.reader.common.audit.AuditLog.Action.STATUS), eq("ENTITLEMENT"),
				eq("ent_5a1"), any(), any());
	}

	@Test
	void revokeOnAnUnknownEntitlementIsNotFound() {
		when(entitlementRepository.findById("ent_ghost")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.revoke("ent_ghost")).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void revokeByANonSuperAdminIsForbiddenRole() {
		actingAs(AdminRole.INSTITUTION_ADMIN, "inst_7f3");

		assertThatThrownBy(() -> service.revoke("ent_5a1")).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

		verify(entitlementRepository, never()).save(any());
	}

	// ---------------------------------------------------------------- approve / reject

	@Test
	void changeStatusApprovesPendingToActiveAndBumpsVersion() {
		Entitlement pending = entitlement("ent_5a1", "inst_7f3", ScopeType.PUBLISHER, "pub_rtlg", null, 0);
		pending.setStatus(EntitlementStatus.PENDING);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(pending));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementView updated = service.changeStatus("ent_5a1",
				new com.tf.reader.admin.dto.EntitlementStatusChange(EntitlementStatus.ACTIVE, "looks legitimate"));

		assertThat(updated.status()).isEqualTo(EntitlementStatus.ACTIVE);
		verify(versionBumper).bump(CatalogueVersionBumper.Scope.INSTITUTION, "inst_7f3");
		verify(auditWriter).record(any(), eq(com.tf.reader.common.audit.AuditLog.Action.STATUS), eq("ENTITLEMENT"),
				eq("ent_5a1"), any(), any(), any());
	}

	@Test
	void changeStatusRejectsPendingToRevokedWithoutBumpingVersion() {
		Entitlement pending = entitlement("ent_5a1", "inst_7f3", ScopeType.PUBLISHER, "pub_rtlg", null, 0);
		pending.setStatus(EntitlementStatus.PENDING);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(pending));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementView updated = service.changeStatus("ent_5a1",
				new com.tf.reader.admin.dto.EntitlementStatusChange(EntitlementStatus.REVOKED, null));

		assertThat(updated.status()).isEqualTo(EntitlementStatus.REVOKED);
		verify(versionBumper, never()).bump(any(), any());
	}

	@Test
	void changeStatusRejectsInvalidTransitionAsValidationFailed() {
		Entitlement active = entitlement("ent_5a1", "inst_7f3", ScopeType.PUBLISHER, "pub_rtlg", null, 0);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(active));

		assertThatThrownBy(() -> service.changeStatus("ent_5a1",
				new com.tf.reader.admin.dto.EntitlementStatusChange(EntitlementStatus.ACTIVE, null)))
						.isInstanceOf(ApiException.class)
						.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

		verify(entitlementRepository, never()).save(any());
		verify(versionBumper, never()).bump(any(), any());
	}

	@Test
	void changeStatusByANonSuperAdminIsForbiddenRole() {
		actingAs(AdminRole.INSTITUTION_ADMIN, "inst_7f3");

		assertThatThrownBy(() -> service.changeStatus("ent_5a1",
				new com.tf.reader.admin.dto.EntitlementStatusChange(EntitlementStatus.ACTIVE, null)))
						.isInstanceOf(ApiException.class)
						.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

		verify(entitlementRepository, never()).save(any());
	}

	// ---------------------------------------------------------------- list

	@Test
	void listReturnsAPageOfAnInstitutionsEntitlements() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.PUBLISHER, "pub_rtlg", null, 0);
		Page<Entitlement> page = new PageImpl<>(List.of(existing));
		when(entitlementRepository.findByInstitutionId(eq("inst_7f3"), any())).thenReturn(page);
		when(publisherRepository.findById("pub_rtlg")).thenReturn(Optional.empty());
		when(catalogueItemStore.countByPublisherId("pub_rtlg")).thenReturn(7L);

		var result = service.list("inst_7f3", new PageQuery(0, 20));

		assertThat(result.items()).hasSize(1);
		assertThat(result.items().get(0).resolvedItemCount()).isEqualTo(7);
		assertThat(result.total()).isEqualTo(1);
	}

	@Test
	void listOnAnUnknownInstitutionIsNotFound() {
		when(institutionRepository.existsById("inst_ghost")).thenReturn(false);

		assertThatThrownBy(() -> service.list("inst_ghost", new PageQuery(0, 20))).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	// ---------------------------------------------------------------- resolvedItemCount, ITEM scope

	@Test
	void resolvedItemCountForAnItemScopeIsOneOnlyWhenPublished() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(catalogueItemStore.existsById("item_42")).thenReturn(true);
		CatalogueItem published = new CatalogueItem();
		published.setId("item_42");
		published.setTitle("Rights for Robots");
		published.setStatus(ItemStatus.PUBLISHED);
		when(catalogueItemStore.findById("item_42")).thenReturn(Optional.of(published));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.ITEM, "item_42", null, null, null, null, null);
		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.resolvedItemCount()).isEqualTo(1);
		assertThat(created.scopeLabel()).isEqualTo("Item - Rights for Robots");
	}

	@Test
	void resolvedItemCountForAnUnpublishedItemScopeIsZero() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(catalogueItemStore.existsById("item_42")).thenReturn(true);
		CatalogueItem draft = new CatalogueItem();
		draft.setId("item_42");
		draft.setTitle("Rights for Robots");
		draft.setStatus(ItemStatus.DRAFT);
		when(catalogueItemStore.findById("item_42")).thenReturn(Optional.of(draft));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.ITEM, "item_42", null, null, null, null, null);
		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.resolvedItemCount()).isZero();
	}

	// ---------------------------------------------------------------- Elite requires copies

	@Test
	@DisplayName("creating a grant for an Elite item with no copies is rejected — Elite is always copy-limited")
	void createForAnEliteItemWithNoCopiesIsValidationFailed() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(catalogueItemStore.existsById("item_elite")).thenReturn(true);
		when(catalogueItemStore.findById("item_elite")).thenReturn(Optional.of(eliteItem("item_elite")));

		EntitlementCreate write = new EntitlementCreate(ScopeType.ITEM, "item_elite", null, null, null, null, null);

		assertThatThrownBy(() -> service.create("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

		verify(entitlementRepository, never()).save(any());
		verify(versionBumper, never()).bump(any(), any());
	}

	@Test
	@DisplayName("creating a grant for an Elite item WITH copies succeeds")
	void createForAnEliteItemWithCopiesSucceeds() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(catalogueItemStore.existsById("item_elite")).thenReturn(true);
		when(catalogueItemStore.findById("item_elite")).thenReturn(Optional.of(eliteItem("item_elite")));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.ITEM, "item_elite", 3, null, null, null, null);

		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.copies()).isEqualTo(3);
		assertThat(created.copyLimited()).isTrue();
	}

	@Test
	@DisplayName("a publisher-scoped grant is rejected when ANY published item under that publisher is Elite")
	void createForAPublisherWithAnEliteItemAndNoCopiesIsValidationFailed() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(publisherRepository.existsById("pub_rtlg")).thenReturn(true);
		CatalogueItem open = new CatalogueItem();
		open.setId("item_open");
		open.setStatus(ItemStatus.PUBLISHED);
		open.setAccessTier(com.tf.reader.catalogue.entity.AccessTier.OPEN_ACCESS);
		when(catalogueItemStore.findByPublisherIdAndStatus("pub_rtlg", ItemStatus.PUBLISHED))
				.thenReturn(List.of(open, eliteItem("item_elite")));

		EntitlementCreate write = new EntitlementCreate(ScopeType.PUBLISHER, "pub_rtlg", null, null, null, null, null);

		assertThatThrownBy(() -> service.create("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	@DisplayName("a draft (unpublished) Elite item does not block a copies-less grant — it grants no real access yet")
	void createForAnUnpublishedEliteItemWithNoCopiesIsAllowed() {
		when(institutionRepository.existsById("inst_7f3")).thenReturn(true);
		when(catalogueItemStore.existsById("item_elite_draft")).thenReturn(true);
		CatalogueItem draftElite = eliteItem("item_elite_draft");
		draftElite.setStatus(ItemStatus.DRAFT);
		when(catalogueItemStore.findById("item_elite_draft")).thenReturn(Optional.of(draftElite));
		when(entitlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		EntitlementCreate write = new EntitlementCreate(ScopeType.ITEM, "item_elite_draft", null, null, null, null,
				null);

		EntitlementView created = service.create("inst_7f3", write);

		assertThat(created.copyLimited()).isFalse();
	}

	@Test
	@DisplayName("clearing copies to null on an update is rejected when the grant's scope is an Elite item")
	void updateClearingCopiesForAnEliteItemIsValidationFailed() {
		Entitlement existing = entitlement("ent_5a1", "inst_7f3", ScopeType.ITEM, "item_elite", 3, 0);
		when(entitlementRepository.findById("ent_5a1")).thenReturn(Optional.of(existing));
		when(catalogueItemStore.findById("item_elite")).thenReturn(Optional.of(eliteItem("item_elite")));

		EntitlementUpdate write = new EntitlementUpdate(null, 30, LocalDate.parse("2026-09-01"), null, 0L);

		assertThatThrownBy(() -> service.update("ent_5a1", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

		verify(entitlementRepository, never()).save(any());
	}

	private static CatalogueItem eliteItem(String id) {
		CatalogueItem item = new CatalogueItem();
		item.setId(id);
		item.setTitle("An Elite Book");
		item.setStatus(ItemStatus.PUBLISHED);
		item.setAccessTier(com.tf.reader.catalogue.entity.AccessTier.ELITE);
		return item;
	}

	// ---------------------------------------------------------------- fixtures

	private static Entitlement entitlement(String id, String institutionId, ScopeType scopeType, String scopeId,
			Integer copies, long version) {
		return entitlement(id, institutionId, scopeType, scopeId, copies, version, EntitlementStatus.ACTIVE);
	}

	private static Entitlement entitlement(String id, String institutionId, ScopeType scopeType, String scopeId,
			Integer copies, long version, EntitlementStatus status) {
		Instant now = Instant.parse("2026-08-14T00:00:00Z");
		return new Entitlement(id, institutionId, scopeType, scopeId, copies, 14, LocalDate.parse("2026-08-01"),
				LocalDate.parse("2026-12-31"), status, version, now, now);
	}

}
