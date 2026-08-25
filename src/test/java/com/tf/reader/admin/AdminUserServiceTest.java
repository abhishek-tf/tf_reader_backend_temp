package com.tf.reader.admin;

import com.tf.reader.admin.dto.AdminProfileResponse;
import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.entity.AdminStatus;
import com.tf.reader.admin.entity.AdminUser;
import com.tf.reader.admin.repository.AdminUserRepository;
import com.tf.reader.admin.security.AdminScopeAuthorizer;
import com.tf.reader.admin.service.AdminUserService;
import com.tf.reader.common.audit.AdminAuditWriter;
import com.tf.reader.common.audit.AuditLog;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;
import com.tf.reader.common.page.PageResponse;
import com.tf.reader.common.security.TokenClaims;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** List and deactivation rules, tested without a servlet or a database. */
class AdminUserServiceTest {

	private AdminUserRepository adminUserRepository;
	private PasswordEncoder passwordEncoder;
	private AdminAuditWriter auditWriter;
	private MongoTemplate mongo;

	private AdminUserService service;

	@BeforeEach
	void setUp() {
		adminUserRepository = mock(AdminUserRepository.class);
		passwordEncoder = mock(PasswordEncoder.class);
		auditWriter = mock(AdminAuditWriter.class);
		mongo = mock(MongoTemplate.class);

		service = new AdminUserService(adminUserRepository, passwordEncoder, auditWriter, new AdminScopeAuthorizer(),
				mongo);

		actingAs(AdminRole.SUPER_ADMIN, null, null);
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static void actingAs(AdminRole role, String publisherScope, String institutionScope) {
		Jwt.Builder tokenBuilder = Jwt.withTokenValue("token")
				.header("alg", "none")
				.subject("adm_actor")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(3600));
		if (role != null) {
			tokenBuilder.claim(TokenClaims.ROLE, role.name());
		}
		if (publisherScope != null) {
			tokenBuilder.claim(TokenClaims.SCOPE_PUBLISHER_ID, publisherScope);
		}
		if (institutionScope != null) {
			tokenBuilder.claim(TokenClaims.SCOPE_INSTITUTION_ID, institutionScope);
		}
		SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(tokenBuilder.build(), null, "ROLE_ADMIN"));
	}

	private static AdminUser operator(String id, String email, String publisherId, String institutionId) {
		AdminUser adminUser = new AdminUser();
		adminUser.setId(id);
		adminUser.setEmail(email);
		adminUser.setName("Operator " + id);
		adminUser.setPasswordHash("$2a$10$hash");
		adminUser.setRole(publisherId != null ? AdminRole.PUBLISHER_ADMIN : AdminRole.INSTITUTION_ADMIN);
		adminUser.setPublisherId(publisherId);
		adminUser.setInstitutionId(institutionId);
		adminUser.setStatus(AdminStatus.ACTIVE);
		return adminUser;
	}

	private static AdminUser activeOperator() {
		AdminUser adminUser = operator("adm_9f1", "ops@tandf.example", "pub_r1", null);
		adminUser.setName("Catalogue Ops");
		return adminUser;
	}

	/** The query actually handed to Mongo, which is where every scope rule has to show up. */
	private Query executedQuery() {
		ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(captor.capture(), eq(AdminUser.class));
		return captor.getValue();
	}

	private void mongoReturns(List<AdminUser> found, long total) {
		when(mongo.count(any(Query.class), eq(AdminUser.class))).thenReturn(total);
		when(mongo.find(any(Query.class), eq(AdminUser.class))).thenReturn(found);
	}

	// ---------------------------------------------------------------- list

	@Test
	@DisplayName("list as SUPER_ADMIN applies no scope criterion at all")
	void superAdminListsEveryAdminUser() {
		mongoReturns(List.of(operator("adm_1", "a@tandf.example", "pub_r1", null),
				operator("adm_2", "b@tandf.example", null, "inst_7f3")), 2);

		PageResponse<AdminProfileResponse> result = service.list(new PageQuery(0, 20));

		assertThat(executedQuery().getQueryObject()).isEmpty();
		assertThat(result.items()).hasSize(2);
		assertThat(result.total()).isEqualTo(2);
	}

	@Test
	@DisplayName("list as PUBLISHER_ADMIN filters on publisherId and never on institutionId")
	void publisherAdminFiltersOnPublisherIdAndNeverOnInstitution() {
		actingAs(AdminRole.PUBLISHER_ADMIN, "pub_r1", null);
		mongoReturns(List.of(operator("adm_1", "a@tandf.example", "pub_r1", null)), 1);

		service.list(new PageQuery(0, 20));

		assertThat(executedQuery().getQueryObject())
				.containsEntry("publisherId", "pub_r1")
				.doesNotContainKey("institutionId");
	}

	@Test
	@DisplayName("list as INSTITUTION_ADMIN filters on institutionId and never on publisherId")
	void institutionAdminFiltersOnInstitutionIdAndNeverOnPublisher() {
		actingAs(AdminRole.INSTITUTION_ADMIN, null, "inst_7f3");
		mongoReturns(List.of(operator("adm_2", "b@tandf.example", null, "inst_7f3")), 1);

		service.list(new PageQuery(0, 20));

		assertThat(executedQuery().getQueryObject())
				.containsEntry("institutionId", "inst_7f3")
				.doesNotContainKey("publisherId");
	}

	@Test
	@DisplayName("list paginates with skip and limit rather than trimming in Java")
	void listPaginatesUsingSkipAndLimit() {
		mongoReturns(List.of(operator("adm_1", "a@tandf.example", "pub_r1", null)), 57);

		PageResponse<AdminProfileResponse> result = service.list(new PageQuery(2, 10));

		Query query = executedQuery();
		assertThat(query.getSkip()).isEqualTo(20);
		assertThat(query.getLimit()).isEqualTo(10);

		assertThat(result.page()).isEqualTo(2);
		assertThat(result.size()).isEqualTo(10);
		assertThat(result.total()).isEqualTo(57);
	}

	/**
	 * Mongo is made to return rows from two different publishers while the caller is scoped to one.
	 * A Java-side filter would drop the foreign row; returning all three proves there is none.
	 */
	@Test
	@DisplayName("list never filters after fetching")
	void listDoesNotFilterAfterFetching() {
		actingAs(AdminRole.PUBLISHER_ADMIN, "pub_r1", null);
		mongoReturns(List.of(operator("adm_1", "a@tandf.example", "pub_r1", null),
				operator("adm_2", "b@tandf.example", "pub_other", null),
				operator("adm_3", "c@tandf.example", null, "inst_7f3")), 3);

		PageResponse<AdminProfileResponse> result = service.list(new PageQuery(0, 20));

		assertThat(result.items()).hasSize(3);
	}

	@Test
	@DisplayName("list orders by email ascending")
	void listOrdersByEmailAscending() {
		mongoReturns(List.of(), 0);

		service.list(new PageQuery(0, 20));

		assertThat(executedQuery().getSortObject()).containsEntry("email", 1);
	}

	@Test
	@DisplayName("a publisher admin with no scope claim matches nothing rather than everything")
	void publisherAdminWithoutAScopeClaimMatchesNothing() {
		actingAs(AdminRole.PUBLISHER_ADMIN, null, null);
		mongoReturns(List.of(), 0);

		service.list(new PageQuery(0, 20));

		assertThat(executedQuery().getQueryObject()).containsEntry("publisherId", "no-publisher-claim");
	}

	@Test
	@DisplayName("list is refused when the token carries no recognised role")
	void listIsDeniedForAnUnknownRole() {
		actingAs(null, null, null);

		assertThatThrownBy(() -> service.list(new PageQuery(0, 20))).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

		verify(mongo, never()).find(any(Query.class), eq(AdminUser.class));
	}

	// ---------------------------------------------------------------- deactivate

	@Test
	@DisplayName("deactivate sets status to DISABLED and saves rather than deleting")
	void deactivateDisablesAndKeepsTheDocument() {
		AdminUser existing = activeOperator();
		when(adminUserRepository.findById("adm_9f1")).thenReturn(Optional.of(existing));
		when(adminUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.deactivate("adm_9f1");

		ArgumentCaptor<AdminUser> savedCaptor = ArgumentCaptor.forClass(AdminUser.class);
		verify(adminUserRepository).save(savedCaptor.capture());
		assertThat(savedCaptor.getValue().getStatus()).isEqualTo(AdminStatus.DISABLED);

		verify(adminUserRepository, never()).delete(any());
		verify(adminUserRepository, never()).deleteById(any());
	}

	@Test
	@DisplayName("deactivate emits a STATUS audit carrying the old and new status, never the hash")
	void deactivateAuditsTheStatusChange() {
		when(adminUserRepository.findById("adm_9f1")).thenReturn(Optional.of(activeOperator()));
		when(adminUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.deactivate("adm_9f1");

		ArgumentCaptor<Map<String, Object>> beforeCaptor = ArgumentCaptor.forClass(Map.class);
		ArgumentCaptor<Map<String, Object>> afterCaptor = ArgumentCaptor.forClass(Map.class);
		verify(auditWriter).record(eq("adm_actor"), eq(AuditLog.Action.STATUS), eq("ADMIN_USER"), eq("adm_9f1"),
				beforeCaptor.capture(), afterCaptor.capture());

		assertThat(beforeCaptor.getValue()).containsExactly(Map.entry("status", AdminStatus.ACTIVE));
		assertThat(afterCaptor.getValue()).containsExactly(Map.entry("status", AdminStatus.DISABLED));
	}

	@Test
	@DisplayName("deactivate on an unknown id throws NOT_FOUND and saves nothing")
	void deactivateUnknownThrowsNotFound() {
		when(adminUserRepository.findById("adm_nope")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deactivate("adm_nope")).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.NOT_FOUND));

		verify(adminUserRepository, never()).save(any());
	}

	@Test
	@DisplayName("deactivate is refused for a non-super admin before anything is read")
	void deactivateRequiresSuperAdmin() {
		actingAs(AdminRole.PUBLISHER_ADMIN, "pub_r1", null);

		assertThatThrownBy(() -> service.deactivate("adm_9f1")).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.FORBIDDEN_ROLE));

		verify(adminUserRepository, never()).findById(any());
		verify(adminUserRepository, never()).save(any());
	}

}
