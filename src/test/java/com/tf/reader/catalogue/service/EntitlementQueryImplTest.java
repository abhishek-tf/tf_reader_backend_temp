package com.tf.reader.catalogue.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentState;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.catalogue.repository.CatalogueItemRepository;
import com.tf.reader.catalogue.repository.EntitlementRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntitlementQueryImplTest {

    private static final SubjectRef SUBJECT = new SubjectRef("u_88", "inst_7f3");

    private CatalogueItemRepository catalogueItemRepository;
    private EntitlementRepository entitlementRepository;
    private EntitlementQuery query;

    @BeforeEach
    void setUp() {
        catalogueItemRepository = mock(CatalogueItemRepository.class);
        entitlementRepository = mock(EntitlementRepository.class);
        query = new EntitlementQueryImpl(catalogueItemRepository, entitlementRepository);

        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void allowsAccessWhenAnActiveCollectionGrantCoversTheItem() {
        CatalogueItem item = readyItem("item_c25", List.of("col_1"));
        when(catalogueItemRepository.findById("item_c25")).thenReturn(Optional.of(item));

        Entitlement grant = entitlement("ent_1", ScopeType.COLLECTION, "col_1", 3, 21,
                LocalDate.now().plusDays(30));
        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(
                eq("inst_7f3"), eq(ScopeType.COLLECTION), eq("col_1")))
                .thenReturn(Optional.of(grant));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isTrue();
        assertThat(decision.entitlementId()).isEqualTo("ent_1");
        assertThat(decision.copies()).isEqualTo(3);
        assertThat(decision.loanPeriodDays()).isEqualTo(21);
        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.ENTITLED_CONCURRENT);
        assertThat(decision.reason()).isNull();
    }

    @Test
    void deniesWithNoEntitlementWhenNothingCoversTheItem() {
        CatalogueItem item = readyItem("item_c25", List.of());
        when(catalogueItemRepository.findById("item_c25")).thenReturn(Optional.of(item));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NO_ENTITLEMENT);
    }

    @Test
    void deniesWithNoEntitlementWhenTheOnlyGrantHasExpired() {
        CatalogueItem item = readyItem("item_c25", List.of("col_1"));
        when(catalogueItemRepository.findById("item_c25")).thenReturn(Optional.of(item));

        Entitlement expired = entitlement("ent_1", ScopeType.COLLECTION, "col_1", 3, 21,
                LocalDate.now().minusDays(1));
        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(
                eq("inst_7f3"), eq(ScopeType.COLLECTION), eq("col_1")))
                .thenReturn(Optional.of(expired));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NO_ENTITLEMENT);
    }

    @Test
    void deniesWithContentNotReadyWhenTheItemIsNotPublished() {
        CatalogueItem item = readyItem("item_c25", List.of());
        item.setStatus(ItemStatus.DRAFT);
        when(catalogueItemRepository.findById("item_c25")).thenReturn(Optional.of(item));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.CONTENT_NOT_READY);
    }

    @Test
    void rejectsAMissingItemId() {
        assertThatIllegalArgumentException().isThrownBy(() -> query.check(SUBJECT, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> query.check(SUBJECT, null));
    }

    private CatalogueItem readyItem(String id, List<String> collectionIds) {
        CatalogueItem item = new CatalogueItem();
        item.setId(id);
        item.setPublisherId("pub_1");
        item.setCollectionIds(collectionIds);
        item.setAccessTier(AccessTier.SUBSCRIPTION);
        item.setStatus(ItemStatus.PUBLISHED);
        item.setContentState(ContentState.READY);
        return item;
    }

    private Entitlement entitlement(String id, ScopeType scopeType, String scopeId, Integer copies,
            Integer loanPeriodDays, LocalDate validTo) {
        Entitlement entitlement = new Entitlement();
        entitlement.setId(id);
        entitlement.setInstitutionId("inst_7f3");
        entitlement.setScopeType(scopeType);
        entitlement.setScopeId(scopeId);
        entitlement.setCopies(copies);
        entitlement.setLoanPeriodDays(loanPeriodDays);
        entitlement.setValidFrom(LocalDate.now().minusDays(1));
        entitlement.setValidTo(validTo);
        entitlement.setStatus(EntitlementStatus.ACTIVE);
        return entitlement;
    }
}
