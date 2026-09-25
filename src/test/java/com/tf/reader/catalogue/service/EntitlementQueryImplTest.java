package com.tf.reader.catalogue.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.InstitutionLookup;
import com.tf.reader.catalogue.api.InstitutionRef;
import com.tf.reader.catalogue.api.SubjectRef;
import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentState;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ItemStatus;
import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.catalogue.repository.EntitlementRepository;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.common.model.RecordStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntitlementQueryImplTest {

    private static final SubjectRef SUBJECT = new SubjectRef("u_88", "inst_7f3");

    private CatalogueItemStore catalogueItemStore;
    private EntitlementRepository entitlementRepository;
    private PublisherRepository publisherRepository;
    private InstitutionLookup institutionLookup;
    private EntitlementQuery query;

    @BeforeEach
    void setUp() {
        catalogueItemStore = mock(CatalogueItemStore.class);
        entitlementRepository = mock(EntitlementRepository.class);
        publisherRepository = mock(PublisherRepository.class);
        institutionLookup = mock(InstitutionLookup.class);
        query = new EntitlementQueryImpl(catalogueItemStore, entitlementRepository, publisherRepository,
                institutionLookup);

        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(institutionLookup.find("inst_7f3")).thenReturn(Optional.of(new InstitutionRef("inst_7f3", "Imperial")));
        when(publisherRepository.findById("pub_1")).thenReturn(Optional.of(activePublisher("pub_1")));
    }

    @Test
    void allowsAccessWhenAnActiveCollectionGrantCoversTheItem() {
        // ELITE, not the readyItem default of SUBSCRIPTION: this test is specifically about a
        // copy-limited grant, and only ELITE is copy limited by nature.
        CatalogueItem item = readyItem("item_c25", List.of("col_1"));
        item.setAccessTier(AccessTier.ELITE);
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));

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
    void aSubscriptionItemIsNeverCopyLimitedEvenWhenTheMatchedGrantHasCopies() {
        // The same grant can cover a whole publisher, ELITE and SUBSCRIPTION titles alike. A
        // SUBSCRIPTION book must not inherit a concurrency limit meant for the ELITE titles
        // sharing that grant - the tier decides, not the number on the row.
        CatalogueItem item = readyItem("item_c25", List.of("col_1"));
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));

        Entitlement grant = entitlement("ent_1", ScopeType.COLLECTION, "col_1", 3, 21,
                LocalDate.now().plusDays(30));
        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(
                eq("inst_7f3"), eq(ScopeType.COLLECTION), eq("col_1")))
                .thenReturn(Optional.of(grant));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isTrue();
        assertThat(decision.copies()).isNull();
        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.ENTITLED_UNLIMITED);
    }

    @Test
    void deniesWithNoEntitlementWhenNothingCoversTheItem() {
        CatalogueItem item = readyItem("item_c25", List.of());
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NO_ENTITLEMENT);
    }

    @Test
    void deniesWithNoEntitlementWhenTheOnlyGrantHasExpired() {
        CatalogueItem item = readyItem("item_c25", List.of("col_1"));
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));

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
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.CONTENT_NOT_READY);
    }

    @Test
    void deniesWithNoEntitlementWhenThePublisherIsSuspended() {
        // A suspended publisher's whole catalogue disappears, not just books an institution
        // would otherwise need a grant for.
        CatalogueItem item = readyItem("item_c25", List.of("col_1"));
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));
        when(publisherRepository.findById("pub_1")).thenReturn(Optional.of(suspendedPublisher("pub_1")));

        Entitlement grant = entitlement("ent_1", ScopeType.COLLECTION, "col_1", 3, 21,
                LocalDate.now().plusDays(30));
        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(
                eq("inst_7f3"), eq(ScopeType.COLLECTION), eq("col_1")))
                .thenReturn(Optional.of(grant));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NO_ENTITLEMENT);
    }

    @Test
    void deniesAnOpenAccessItemWhenThePublisherIsSuspended() {
        // The OPEN_ACCESS bypass must not run before the publisher check, or a suspended
        // publisher's open access book keeps granting itself regardless of status.
        CatalogueItem item = readyItem("item_c25", List.of());
        item.setAccessTier(AccessTier.OPEN_ACCESS);
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));
        when(publisherRepository.findById("pub_1")).thenReturn(Optional.of(suspendedPublisher("pub_1")));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NO_ENTITLEMENT);
    }

    @Test
    void deniesWithNoEntitlementWhenThePublisherNoLongerExists() {
        CatalogueItem item = readyItem("item_c25", List.of());
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));
        when(publisherRepository.findById("pub_1")).thenReturn(Optional.empty());

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NO_ENTITLEMENT);
    }

    @Test
    void rejectsAMissingItemId() {
        assertThatIllegalArgumentException().isThrownBy(() -> query.check(SUBJECT, " "));
        assertThatIllegalArgumentException().isThrownBy(() -> query.check(SUBJECT, null));
    }

    @Test
    void deniesWithNotFoundWhenTheInstitutionIsSuspended() {
        // InstitutionLookup itself collapses "suspended" and "unknown" into an empty Optional -
        // check() never sees the difference, so this test only needs to stub the empty case.
        // The item IS still looked up (it has to be, to know whether this item even needs an
        // institution at all) - left unstubbed here, so it resolves to Optional.empty() and the
        // item-not-found branch is what actually denies this, but the result is the same NOT_FOUND
        // either way.
        when(institutionLookup.find("inst_7f3")).thenReturn(Optional.empty());

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NOT_FOUND);
    }

    @Test
    void deniesWithNotFoundWhenTheInstitutionIsUnknownAndTheItemIsNotOpenAccess() {
        // A real (non-open-access) item, unlike the two tests above: this pins that an unknown
        // institution still denies a REAL item, not just a missing one.
        CatalogueItem item = readyItem("item_c25", List.of());
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));
        when(institutionLookup.find("inst_7f3")).thenReturn(Optional.empty());

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NOT_FOUND);
        verify(entitlementRepository, never()).findByInstitutionIdAndScopeTypeAndScopeId(any(), any(), any());
    }

    @Test
    void grantsOpenAccessToASignedOutReaderWithNoInstitutionAtAll() {
        // The actual bug this pins: a signed-out reader's SubjectRef carries institutionId=null,
        // not merely an unknown one - open access must not require a real institution AT ALL.
        CatalogueItem item = readyItem("item_c25", List.of());
        item.setAccessTier(AccessTier.OPEN_ACCESS);
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));
        SubjectRef signedOut = new SubjectRef(null, null);

        EntitlementDecision decision = query.check(signedOut, "item_c25");

        assertThat(decision.entitled()).isTrue();
        assertThat(decision.accessLevel()).isEqualTo(AccessLevel.OPEN_ACCESS);
        // A null institutionId must never reach the repository - Spring Data's findById(null) throws.
        verify(institutionLookup, never()).find(any());
    }

    @Test
    void deniesASignedOutReaderNotFoundForANonOpenAccessItem() {
        CatalogueItem item = readyItem("item_c25", List.of());
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));
        SubjectRef signedOut = new SubjectRef(null, null);

        EntitlementDecision decision = query.check(signedOut, "item_c25");

        assertThat(decision.entitled()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenyReason.NOT_FOUND);
        verify(institutionLookup, never()).find(any());
    }

    @Test
    void checkAllLooksUpTheInstitutionAndEachPublisherOnceForTheWholeBatch() {
        CatalogueItem itemA = readyItem("item_a", List.of());
        CatalogueItem itemB = readyItem("item_b", List.of());
        when(catalogueItemStore.findById("item_a")).thenReturn(Optional.of(itemA));
        when(catalogueItemStore.findById("item_b")).thenReturn(Optional.of(itemB));
        when(publisherRepository.findAllById(List.of("pub_1"))).thenReturn(List.of(activePublisher("pub_1")));

        Map<String, EntitlementDecision> decisions = query.checkAll(SUBJECT, List.of("item_a", "item_b"));

        assertThat(decisions.get("item_a").reason()).isEqualTo(DenyReason.NO_ENTITLEMENT);
        assertThat(decisions.get("item_b").reason()).isEqualTo(DenyReason.NO_ENTITLEMENT);
        verify(institutionLookup, times(1)).find("inst_7f3");
        verify(publisherRepository, times(1)).findAllById(List.of("pub_1"));
        verify(publisherRepository, never()).findById(any());
    }

    @Test
    void checkAllDeniesEveryIdWithNotFoundWhenTheInstitutionIsUnknown() {
        // Both items are real (non-open-access), unlike leaving them unstubbed - this pins that an
        // unknown institution still denies REAL items across a whole batch, not just missing ones.
        CatalogueItem itemA = readyItem("item_a", List.of());
        CatalogueItem itemB = readyItem("item_b", List.of());
        when(catalogueItemStore.findById("item_a")).thenReturn(Optional.of(itemA));
        when(catalogueItemStore.findById("item_b")).thenReturn(Optional.of(itemB));
        when(publisherRepository.findAllById(List.of("pub_1"))).thenReturn(List.of(activePublisher("pub_1")));
        when(institutionLookup.find("inst_7f3")).thenReturn(Optional.empty());

        Map<String, EntitlementDecision> decisions = query.checkAll(SUBJECT, List.of("item_a", "item_b"));

        assertThat(decisions.get("item_a").reason()).isEqualTo(DenyReason.NOT_FOUND);
        assertThat(decisions.get("item_b").reason()).isEqualTo(DenyReason.NOT_FOUND);
        // The institution is still looked up only ONCE for the whole batch, not once per item.
        verify(institutionLookup, times(1)).find("inst_7f3");
    }

    @Test
    void checkAllGrantsOpenAccessItemsToASignedOutReaderAlongsideOthersInTheSameBatch() {
        CatalogueItem openItem = readyItem("item_open", List.of());
        openItem.setAccessTier(AccessTier.OPEN_ACCESS);
        CatalogueItem subscriptionItem = readyItem("item_sub", List.of());
        when(catalogueItemStore.findById("item_open")).thenReturn(Optional.of(openItem));
        when(catalogueItemStore.findById("item_sub")).thenReturn(Optional.of(subscriptionItem));
        when(publisherRepository.findAllById(List.of("pub_1"))).thenReturn(List.of(activePublisher("pub_1")));
        SubjectRef signedOut = new SubjectRef(null, null);

        Map<String, EntitlementDecision> decisions = query.checkAll(signedOut, List.of("item_open", "item_sub"));

        assertThat(decisions.get("item_open").entitled()).isTrue();
        assertThat(decisions.get("item_open").accessLevel()).isEqualTo(AccessLevel.OPEN_ACCESS);
        assertThat(decisions.get("item_sub").entitled()).isFalse();
        assertThat(decisions.get("item_sub").reason()).isEqualTo(DenyReason.NOT_FOUND);
        verify(institutionLookup, never()).find(any());
    }

    @Test
    void anItemGrantWinsOverAMorePermissiveCollectionGrant() {
        CatalogueItem item = readyItem("item_c25", List.of("col_1"));
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));

        Entitlement itemGrant = entitlement("ent_item", ScopeType.ITEM, "item_c25", 1, 14,
                LocalDate.now().plusDays(30));
        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(
                eq("inst_7f3"), eq(ScopeType.ITEM), eq("item_c25")))
                .thenReturn(Optional.of(itemGrant));

        Entitlement collectionGrant = entitlement("ent_collection", ScopeType.COLLECTION, "col_1", null, 21,
                LocalDate.now().plusDays(30));
        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(
                eq("inst_7f3"), eq(ScopeType.COLLECTION), eq("col_1")))
                .thenReturn(Optional.of(collectionGrant));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitlementId()).isEqualTo("ent_item");
        assertThat(decision.loanPeriodDays()).isEqualTo(14);
    }

    @Test
    void theMorePermissiveOfTwoCollectionGrantsWinsWhenAnItemBelongsToBoth() {
        CatalogueItem item = readyItem("item_c25", List.of("col_1", "col_2"));
        when(catalogueItemStore.findById("item_c25")).thenReturn(Optional.of(item));

        Entitlement limited = entitlement("ent_limited", ScopeType.COLLECTION, "col_1", 2, 14,
                LocalDate.now().plusDays(30));
        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(
                eq("inst_7f3"), eq(ScopeType.COLLECTION), eq("col_1")))
                .thenReturn(Optional.of(limited));

        Entitlement unlimited = entitlement("ent_unlimited", ScopeType.COLLECTION, "col_2", null, 14,
                LocalDate.now().plusDays(30));
        when(entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(
                eq("inst_7f3"), eq(ScopeType.COLLECTION), eq("col_2")))
                .thenReturn(Optional.of(unlimited));

        EntitlementDecision decision = query.check(SUBJECT, "item_c25");

        assertThat(decision.entitlementId()).isEqualTo("ent_unlimited");
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

    private Publisher activePublisher(String id) {
        Publisher publisher = new Publisher();
        publisher.setId(id);
        publisher.setStatus(RecordStatus.ACTIVE);
        return publisher;
    }

    private Publisher suspendedPublisher(String id) {
        Publisher publisher = new Publisher();
        publisher.setId(id);
        publisher.setStatus(RecordStatus.SUSPENDED);
        return publisher;
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
