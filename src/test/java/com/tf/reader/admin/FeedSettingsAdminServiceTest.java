package com.tf.reader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.tf.reader.admin.dto.FeedSettingsView;
import com.tf.reader.admin.dto.FeedSettingsWrite;
import com.tf.reader.admin.dto.ShelfWrite;
import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.admin.service.FeedSettingsAdminService;
import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.FeedSettings;
import com.tf.reader.catalogue.entity.Institution;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.FeedSettingsRepository;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.catalogue.service.CatalogueVersionBumper;
import com.tf.reader.common.audit.AdminAuditWriter;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.security.TokenClaims;

/**
 * Business rules for the institution feed-settings admin endpoints, tested without a servlet or
 * a database. The "is this item entitled" question is delegated to {@link EntitlementQuery}
 * (Abhishek's seam) rather than re-derived here, so these tests mock its answer directly instead
 * of building entitlement records.
 */
class FeedSettingsAdminServiceTest {

	private FeedSettingsRepository feedSettingsRepository;
	private InstitutionRepository institutionRepository;
	private CatalogueItemRepository catalogueItemRepository;
	private EntitlementQuery entitlementQuery;
	private CatalogueVersionBumper versionBumper;
	private AdminAuditWriter auditWriter;
	private FeedSettingsAdminService service;

	@BeforeEach
	void setUp() {
		feedSettingsRepository = mock(FeedSettingsRepository.class);
		institutionRepository = mock(InstitutionRepository.class);
		catalogueItemRepository = mock(CatalogueItemRepository.class);
		entitlementQuery = mock(EntitlementQuery.class);
		versionBumper = mock(CatalogueVersionBumper.class);
		auditWriter = mock(AdminAuditWriter.class);

		when(institutionRepository.findById(anyString())).thenReturn(Optional.of(institution("inst_7f3")));
		when(entitlementQuery.check(any(), anyString())).thenReturn(entitledDecision());
		when(feedSettingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service = new FeedSettingsAdminService(feedSettingsRepository, institutionRepository,
				catalogueItemRepository, entitlementQuery, versionBumper, auditWriter, new AdminScopeAuthorizer());

		actingAs(AdminRole.SUPER_ADMIN, null);
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static Institution institution(String id) {
		Institution institution = new Institution();
		institution.setId(id);
		institution.setCatalogueVersion(5L);
		return institution;
	}

	private static CatalogueItem item(String id, String publisherId, List<String> collectionIds) {
		CatalogueItem item = new CatalogueItem();
		item.setId(id);
		item.setPublisherId(publisherId);
		item.setCollectionIds(collectionIds);
		return item;
	}

	private static EntitlementDecision entitledDecision() {
		return new EntitlementDecision(true, AccessLevel.ENTITLED_UNLIMITED, "ent_1", null, 14, null, null);
	}

	private static EntitlementDecision deniedDecision(DenyReason reason) {
		return new EntitlementDecision(false, null, null, null, 0, null, reason);
	}

	private static List<ShelfWrite> validShelves(List<String> shelf1Items) {
		return List.of(
				new ShelfWrite("shelf_1", "New this month", 1, shelf1Items),
				new ShelfWrite("shelf_2", "Law essentials", 2, List.of()),
				new ShelfWrite("shelf_3", "Audio picks", 3, List.of()));
	}

	private static void actingAs(AdminRole role, String institutionId) {
		Jwt.Builder tokenBuilder = Jwt.withTokenValue("token").header("alg", "none").subject("adm_test")
				.claim(TokenClaims.ROLE, role.name()).issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600));
		if (institutionId != null) {
			tokenBuilder.claim(TokenClaims.SCOPE_INSTITUTION_ID, institutionId);
		}
		SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(tokenBuilder.build(), null, "ROLE_ADMIN"));
	}

	// ---------------------------------------------------------------------------------------- get

	@Test
	@DisplayName("get with no existing record returns 3 empty shelves, version 0")
	void getWithNoRecordReturnsDefault() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());

		FeedSettingsView view = service.get("inst_7f3");

		assertThat(view.shelves()).hasSize(3);
		assertThat(view.shelves()).extracting("id").containsExactly("shelf_1", "shelf_2", "shelf_3");
		assertThat(view.shelves()).allSatisfy(s -> assertThat(s.itemIds()).isEmpty());
		assertThat(view.version()).isZero();
		assertThat(view.catalogueVersion()).isEqualTo(5L);
	}

	@Test
	@DisplayName("get an unknown institution throws NOT_FOUND")
	void getUnknownInstitutionThrows() {
		when(institutionRepository.findById("inst_nope")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.get("inst_nope")).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	@DisplayName("get from an admin scoped to a different institution throws FORBIDDEN_ROLE")
	void getWrongInstitutionScopeThrows() {
		actingAs(AdminRole.INSTITUTION_ADMIN, "inst_other");

		assertThatThrownBy(() -> service.get("inst_7f3")).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));
	}

	// --------------------------------------------------------------------------------------- save

	@Test
	@DisplayName("save with 3 valid, entitled shelves bumps catalogueVersion and increments version")
	void happyPathSaves() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		when(catalogueItemRepository.findAllById(anyIterable()))
				.thenReturn(List.of(item("item_42", "pub_rtlg", List.of())));
		when(entitlementQuery.check(any(), any())).thenReturn(entitledDecision());

		FeedSettingsWrite write = new FeedSettingsWrite("Imperial College Library", 20, "publishedAt.desc",
				validShelves(List.of("item_42")), 0L);

		FeedSettingsView saved = service.save("inst_7f3", write);

		assertThat(saved.version()).isEqualTo(1L);
		org.mockito.Mockito.verify(versionBumper).bump(CatalogueVersionBumper.Scope.INSTITUTION, "inst_7f3");
	}

	@Test
	@DisplayName("save with a stale version returns STALE_VERSION")
	void staleVersionThrows() {
		FeedSettings existing = new FeedSettings();
		existing.setVersion(3L);
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.of(existing));

		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, validShelves(List.of()), 2L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.STALE_VERSION));
	}

	@Test
	@DisplayName("save against an unknown institution throws NOT_FOUND")
	void saveUnknownInstitutionThrows() {
		when(institutionRepository.findById("inst_nope")).thenReturn(Optional.empty());

		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, validShelves(List.of()), 0L);

		assertThatThrownBy(() -> service.save("inst_nope", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	@DisplayName("save with a duplicate shelf id throws VALIDATION_FAILED")
	void duplicateShelfIdThrows() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		List<ShelfWrite> shelves = List.of(new ShelfWrite("shelf_1", "A", 1, List.of()),
				new ShelfWrite("shelf_1", "B", 2, List.of()), new ShelfWrite("shelf_3", "C", 3, List.of()));
		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, shelves, 0L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	@DisplayName("save with a wrong order set throws VALIDATION_FAILED")
	void wrongOrderSetThrows() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		List<ShelfWrite> shelves = List.of(new ShelfWrite("shelf_1", "A", 1, List.of()),
				new ShelfWrite("shelf_2", "B", 1, List.of()), new ShelfWrite("shelf_3", "C", 3, List.of()));
		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, shelves, 0L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	@DisplayName("save with a blank shelf title throws VALIDATION_FAILED")
	void blankTitleThrows() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		List<ShelfWrite> shelves = List.of(new ShelfWrite("shelf_1", "  ", 1, List.of()),
				new ShelfWrite("shelf_2", "B", 2, List.of()), new ShelfWrite("shelf_3", "C", 3, List.of()));
		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, shelves, 0L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	@DisplayName("save with an unknown item id throws VALIDATION_FAILED naming the id, without asking EntitlementQuery")
	void unknownItemIdThrows() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		when(catalogueItemRepository.findAllById(anyIterable())).thenReturn(List.of());

		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, validShelves(List.of("item_bogus")), 0L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> {
					assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
					assertThat(e.getMessage()).contains("item_bogus");
				});
		org.mockito.Mockito.verifyNoInteractions(entitlementQuery);
	}

	@Test
	@DisplayName("save with an item EntitlementQuery denies as NO_ENTITLEMENT throws, naming the id")
	void notEntitledItemThrows() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		when(catalogueItemRepository.findAllById(anyIterable()))
				.thenReturn(List.of(item("item_42", "pub_rtlg", List.of())));
		when(entitlementQuery.check(any(), any())).thenReturn(deniedDecision(DenyReason.NO_ENTITLEMENT));

		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, validShelves(List.of("item_42")), 0L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> {
					assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
					assertThat(e.getMessage()).contains("item_42");
				});
	}

	@Test
	@DisplayName("save with an item EntitlementQuery denies as ENTITLEMENT_EXPIRED throws")
	void expiredEntitlementThrows() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		when(catalogueItemRepository.findAllById(anyIterable()))
				.thenReturn(List.of(item("item_42", "pub_rtlg", List.of())));
		when(entitlementQuery.check(any(), any())).thenReturn(deniedDecision(DenyReason.ENTITLEMENT_EXPIRED));

		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, validShelves(List.of("item_42")), 0L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	@DisplayName("save with an item EntitlementQuery denies as CONTENT_NOT_READY also throws - per Abhishek, blocked the same as a real deny")
	void contentNotReadyThrows() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		when(catalogueItemRepository.findAllById(anyIterable()))
				.thenReturn(List.of(item("item_42", "pub_rtlg", List.of())));
		when(entitlementQuery.check(any(), any())).thenReturn(deniedDecision(DenyReason.CONTENT_NOT_READY));

		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, validShelves(List.of("item_42")), 0L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> {
					assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
					assertThat(e.getMessage()).contains("item_42");
				});
	}

	@Test
	@DisplayName("save with a shelf over 50 items throws VALIDATION_FAILED")
	void tooManyItemsOnAShelfThrows() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());
		List<String> tooMany = new java.util.ArrayList<>();
		for (int i = 0; i < 51; i++) {
			tooMany.add("item_" + i);
		}
		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, validShelves(tooMany), 0L);

		assertThatThrownBy(() -> service.save("inst_7f3", write)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	@DisplayName("an empty shelf is allowed")
	void emptyShelfIsAllowed() {
		when(feedSettingsRepository.findByInstitutionId("inst_7f3")).thenReturn(Optional.empty());

		FeedSettingsWrite write = new FeedSettingsWrite("Title", 20, null, validShelves(List.of()), 0L);

		FeedSettingsView saved = service.save("inst_7f3", write);

		assertThat(saved.shelves().get(0).itemIds()).isEmpty();
	}

}
