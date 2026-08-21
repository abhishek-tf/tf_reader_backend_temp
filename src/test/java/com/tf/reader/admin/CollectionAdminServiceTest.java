package com.tf.reader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import com.tf.reader.admin.dto.CollectionItemsResult;
import com.tf.reader.admin.dto.CollectionItemsWrite;
import com.tf.reader.admin.dto.CollectionView;
import com.tf.reader.admin.dto.CollectionWrite;
import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.admin.service.CollectionAdminService;
import com.tf.reader.catalogue.entity.BookCollection;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.repository.BookCollectionRepository;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.catalogue.service.CatalogueVersionBumper;
import com.tf.reader.common.audit.AdminAuditWriter;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.security.TokenClaims;

/** Business rules for collections, tested without a servlet or a database. */
class CollectionAdminServiceTest {

	private final BookCollectionRepository bookCollectionRepository = mock(BookCollectionRepository.class);
	private final CatalogueItemRepository catalogueItemRepository = mock(CatalogueItemRepository.class);
	private final PublisherRepository publisherRepository = mock(PublisherRepository.class);
	private final CatalogueVersionBumper versionBumper = mock(CatalogueVersionBumper.class);
	private final AdminAuditWriter auditWriter = mock(AdminAuditWriter.class);

	private final CollectionAdminService service = new CollectionAdminService(bookCollectionRepository,
			catalogueItemRepository, publisherRepository, versionBumper, auditWriter, new AdminScopeAuthorizer());

	@BeforeEach
	void actAsSuperAdmin() {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(900)).claim(TokenClaims.ROLE, AdminRole.SUPER_ADMIN.name())
				.build();
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null, "ROLE_ADMIN"));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void movesBooksInAndOutOfCollection() {
		BookCollection collection = collection("col_law2024", "pub_rtlg");
		when(bookCollectionRepository.findById("col_law2024")).thenReturn(Optional.of(collection));

		CatalogueItem keep = item("item_1", List.of("col_law2024"));
		CatalogueItem toAdd = item("item_2", List.of());
		CatalogueItem toRemove = item("item_3", List.of("col_law2024"));

		when(catalogueItemRepository.findAllById(anyIterable())).thenReturn(List.of(keep, toAdd));
		when(catalogueItemRepository.findByCollectionIds("col_law2024")).thenReturn(List.of(keep, toRemove));
		when(catalogueItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(versionBumper.bump(CatalogueVersionBumper.Scope.COLLECTION, "col_law2024"))
				.thenReturn(List.of("inst_7f3"));

		CollectionItemsResult result = service.setItems("col_law2024", new CollectionItemsWrite(List.of("item_1", "item_2")));

		assertThat(result.itemCount()).isEqualTo(2);
		assertThat(result.affectedInstitutions()).containsExactly("inst_7f3");
		assertThat(toAdd.getCollectionIds()).containsExactly("col_law2024");
		assertThat(toRemove.getCollectionIds()).doesNotContain("col_law2024");
		assertThat(keep.getCollectionIds()).containsExactly("col_law2024");
	}

	@Test
	void rejectsAnUnknownItemId() {
		when(bookCollectionRepository.findById("col_law2024")).thenReturn(Optional.of(collection("col_law2024", "pub_rtlg")));
		when(catalogueItemRepository.findAllById(anyIterable())).thenReturn(List.of());

		assertThatThrownBy(() -> service.setItems("col_law2024", new CollectionItemsWrite(List.of("item_missing"))))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
	}

	@Test
	void unknownCollectionThrowsNotFound() {
		when(bookCollectionRepository.findById("col_nope")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.setItems("col_nope", new CollectionItemsWrite(List.of())))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void createsACollectionWithADerivedItemCount() {
		when(publisherRepository.findById("pub_rtlg")).thenReturn(Optional.of(new Publisher()));
		when(bookCollectionRepository.findByPublisherIdAndCode("pub_rtlg", "law-2024")).thenReturn(Optional.empty());
		when(bookCollectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(catalogueItemRepository.countByCollectionIds(any())).thenReturn(3L);

		CollectionView created = service.create("pub_rtlg", new CollectionWrite("law-2024", "Law 2024", "84 titles"));

		assertThat(created.publisherId()).isEqualTo("pub_rtlg");
		assertThat(created.code()).isEqualTo("law-2024");
		assertThat(created.itemCount()).isEqualTo(3);
	}

	@Test
	void rejectsADuplicateCodeUnderTheSamePublisher() {
		when(publisherRepository.findById("pub_rtlg")).thenReturn(Optional.of(new Publisher()));
		when(bookCollectionRepository.findByPublisherIdAndCode("pub_rtlg", "law-2024"))
				.thenReturn(Optional.of(collection("col_existing", "pub_rtlg")));

		assertThatThrownBy(
				() -> service.create("pub_rtlg", new CollectionWrite("law-2024", "Law 2024", null)))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.CODE_TAKEN));
	}

	@Test
	void listReturnsAPageOfAPublishersCollections() {
		when(publisherRepository.findById("pub_rtlg")).thenReturn(Optional.of(new Publisher()));
		BookCollection collection = collection("col_law2024", "pub_rtlg");
		when(bookCollectionRepository.findByPublisherId(eq("pub_rtlg"), any()))
				.thenReturn(new PageImpl<>(List.of(collection)));
		when(catalogueItemRepository.countByCollectionIds("col_law2024")).thenReturn(5L);

		var result = service.list("pub_rtlg", new PageQuery(0, 20));

		assertThat(result.items()).hasSize(1);
		assertThat(result.items().get(0).itemCount()).isEqualTo(5);
		assertThat(result.total()).isEqualTo(1);
	}

	@Test
	void unknownPublisherThrowsNotFoundOnList() {
		when(publisherRepository.findById("pub_nope")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.list("pub_nope", new PageQuery(0, 20)))
				.isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	private static BookCollection collection(String id, String publisherId) {
		return new BookCollection(id, publisherId, "law2024", "Law 2024", null);
	}

	private static CatalogueItem item(String id, List<String> collectionIds) {
		CatalogueItem item = new CatalogueItem();
		item.setId(id);
		item.setCollectionIds(collectionIds);
		return item;
	}

}
