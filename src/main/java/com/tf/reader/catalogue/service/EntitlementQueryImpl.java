package com.tf.reader.catalogue.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.tf.reader.catalogue.api.AccessLevel;
import com.tf.reader.catalogue.api.DenyReason;
import com.tf.reader.catalogue.api.EntitlementDecision;
import com.tf.reader.catalogue.api.EntitlementQuery;
import com.tf.reader.catalogue.api.InstitutionLookup;
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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class EntitlementQueryImpl implements EntitlementQuery {

    private final CatalogueItemStore catalogueItemStore;
    private final EntitlementRepository entitlementRepository;
    private final PublisherRepository publisherRepository;
    private final InstitutionLookup institutionLookup;

    @Override
    public EntitlementDecision check(SubjectRef subject, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId is required");
        }

        // Institution lookup moved OUT of this method and into decide() itself, positioned after
        // the OPEN_ACCESS bypass - see that check's own comment. A signed-out reader has no
        // institutionId at all, and open access needs no institution, so gating every call on one
        // up front wrongly denied an anonymous reader NOT_FOUND before an open-access item's own
        // tier was ever looked at.
        boolean hasInstitution = knownInstitution(subject.institutionId());

        CatalogueItem item = catalogueItemStore.findById(itemId).orElse(null);
        Map<String, Publisher> publishersById = item == null ? Map.of()
                : publisherRepository.findById(item.getPublisherId())
                        .map(publisher -> Map.of(publisher.getId(), publisher)).orElse(Map.of());

        return decide(subject, item, publishersById, hasInstitution);
    }

    @Override
    public Map<String, EntitlementDecision> checkAll(SubjectRef subject, List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }

        // Looked up once for the whole batch, same as before - every item in one call shares the
        // same subject and institution. See check()'s own comment for why this no longer denies
        // the batch outright when it comes back false.
        boolean hasInstitution = knownInstitution(subject.institutionId());

        // One lookup per item rather than a single findAllById: different items can belong to
        // different publishers, each possibly routed to their own database, so there is no single
        // query that could answer this batch across all of them anyway.
        Map<String, CatalogueItem> itemsById = itemIds.stream()
                .map(catalogueItemStore::findById)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(CatalogueItem::getId, Function.identity()));

        List<String> publisherIds = itemsById.values().stream().map(CatalogueItem::getPublisherId).distinct()
                .toList();
        Map<String, Publisher> publishersById = publisherRepository.findAllById(publisherIds).stream()
                .collect(Collectors.toMap(Publisher::getId, Function.identity()));

        Map<String, EntitlementDecision> result = new LinkedHashMap<>();
        for (String itemId : itemIds) {
            result.put(itemId, decide(subject, itemsById.get(itemId), publishersById, hasInstitution));
        }
        return result;
    }

    // A suspended institution must read exactly like an unknown one, same reason InstitutionLookup
    // itself collapses the two. An anonymous caller's institutionId is null - findById(null) would
    // throw (Spring Data rejects a null id), and null is exactly "no institution" anyway, so it is
    // never even sent to the repository.
    private boolean knownInstitution(String institutionId) {
        return institutionId != null && institutionLookup.find(institutionId).isPresent();
    }

    private EntitlementDecision decide(SubjectRef subject, CatalogueItem item, Map<String, Publisher> publishersById,
            boolean hasInstitution) {
        if (item == null) {
            return denied(DenyReason.NOT_FOUND);
        }
        if (item.getStatus() != ItemStatus.PUBLISHED || item.getContentState() != ContentState.READY) {
            return denied(DenyReason.CONTENT_NOT_READY);
        }

        // A suspended (or retired) publisher's catalogue disappears whole, including its open
        // access titles - checked before the OPEN_ACCESS bypass below, or a suspended publisher's
        // open access book would keep granting itself regardless. Reuses NO_ENTITLEMENT rather
        // than a new DenyReason: the three callers that switch on DenyReason
        // (ReadBrokerService, BorrowService, QueueService) are flambeau's, and to a caller a
        // suspended publisher's book is indistinguishable from never having had a grant at all.
        Publisher publisher = publishersById.get(item.getPublisherId());
        if (publisher == null || publisher.getStatus() != RecordStatus.ACTIVE) {
            return denied(DenyReason.NO_ENTITLEMENT);
        }

        // Open access was never something an institution had to buy, so it needs no grant AND no
        // institution at all - this must run before both the grant lookup below and the
        // institution check that used to gate this whole method, or a signed-out reader (no
        // institutionId) and an institution's own open-access title were both wrongly denied
        // NOT_FOUND before this tier was ever looked at.
        if (item.getAccessTier() == AccessTier.OPEN_ACCESS) {
            return new EntitlementDecision(true, AccessLevel.OPEN_ACCESS, null, null, 0, null, null);
        }

        // Everything past this point is a real purchase, scoped to an institution - so this is
        // where a signed-out reader, or one whose institution is unknown/suspended, is denied.
        if (!hasInstitution) {
            return denied(DenyReason.NOT_FOUND);
        }

        Entitlement grant = mostPermissiveActiveGrant(subject.institutionId(), item);
        if (grant == null) {
            return denied(DenyReason.NO_ENTITLEMENT);
        }

        // A grant's copies count is a term of what the institution bought at that scope, and one
        // grant (a whole publisher, say) can cover books of more than one tier. Only ELITE is
        // copy limited by nature, so a SUBSCRIPTION book sharing that grant must never inherit a
        // concurrency limit meant for the ELITE titles alongside it - regardless of what copies
        // says on the matched row.
        boolean copyLimited = item.getAccessTier() == AccessTier.ELITE;

        return new EntitlementDecision(
                true,
                accessLevelFor(copyLimited, grant),
                grant.getId(),
                copyLimited ? grant.getCopies() : null,
                grant.getLoanPeriodDays(),
                grant.getValidTo() == null ? null : grant.getValidTo().atStartOfDay(ZoneOffset.UTC).toInstant(),
                null
        );
    }

    // Scope specificity, narrowest first. An ITEM grant always wins over a COLLECTION grant,
    // which always wins over a PUBLISHER grant, no matter what each one's copies says - a
    // narrower entitlement is a deliberate, more specific purchase and must not be shadowed by
    // a wider one that happens to look more generous.
    private Entitlement mostPermissiveActiveGrant(String institutionId, CatalogueItem item) {
        Entitlement itemGrant = activeGrant(entitlementRepository
                .findByInstitutionIdAndScopeTypeAndScopeId(institutionId, ScopeType.ITEM, item.getId()));
        if (itemGrant != null) {
            return itemGrant;
        }

        List<String> collectionIds = item.getCollectionIds() == null ? List.of() : item.getCollectionIds();
        List<Entitlement> collectionGrants = new ArrayList<>();
        for (String collectionId : collectionIds) {
            Entitlement grant = activeGrant(entitlementRepository
                    .findByInstitutionIdAndScopeTypeAndScopeId(institutionId, ScopeType.COLLECTION, collectionId));
            if (grant != null) {
                collectionGrants.add(grant);
            }
        }
        Entitlement bestCollectionGrant = mostPermissiveOf(collectionGrants);
        if (bestCollectionGrant != null) {
            return bestCollectionGrant;
        }

        return activeGrant(entitlementRepository
                .findByInstitutionIdAndScopeTypeAndScopeId(institutionId, ScopeType.PUBLISHER, item.getPublisherId()));
    }

    private Entitlement activeGrant(Optional<Entitlement> maybeGrant) {
        if (maybeGrant.isEmpty()) {
            return null;
        }
        Entitlement grant = maybeGrant.get();
        LocalDate today = LocalDate.now();
        if (grant.getStatus() != EntitlementStatus.ACTIVE) {
            return null;
        }
        if (grant.getValidTo() != null && grant.getValidTo().isBefore(today)) {
            return null;
        }
        return grant;
    }

    private Entitlement mostPermissiveOf(List<Entitlement> candidates) {
        Entitlement best = null;
        for (Entitlement candidate : candidates) {
            if (best == null || isMorePermissive(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isMorePermissive(Entitlement candidate, Entitlement current) {
        if (candidate.getCopies() == null) {
            return current.getCopies() != null;
        }
        if (current.getCopies() == null) {
            return false;
        }
        return candidate.getCopies() > current.getCopies();
    }

    // OPEN_ACCESS is handled earlier in check(), before a grant is looked up, so this is only
    // ever reached for a real grant now - never for an open access item.
    private AccessLevel accessLevelFor(boolean copyLimited, Entitlement grant) {
        if (!copyLimited) {
            return AccessLevel.ENTITLED_UNLIMITED;
        }
        return grant.getCopies() == null ? AccessLevel.ENTITLED_UNLIMITED : AccessLevel.ENTITLED_CONCURRENT;
    }

    private EntitlementDecision denied(DenyReason reason) {
        return new EntitlementDecision(false, null, null, null, 0, null, reason);
    }
}
