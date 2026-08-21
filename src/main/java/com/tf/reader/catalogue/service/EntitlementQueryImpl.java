package com.tf.reader.catalogue.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

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

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class EntitlementQueryImpl implements EntitlementQuery {

    private final CatalogueItemRepository catalogueItemRepository;
    private final EntitlementRepository entitlementRepository;

    @Override
    public EntitlementDecision check(SubjectRef subject, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId is required");
        }

        Optional<CatalogueItem> maybeItem = catalogueItemRepository.findById(itemId);
        if (maybeItem.isEmpty()) {
            return denied(DenyReason.NOT_FOUND);
        }

        CatalogueItem item = maybeItem.get();
        if (item.getStatus() != ItemStatus.PUBLISHED || item.getContentState() != ContentState.READY) {
            return denied(DenyReason.CONTENT_NOT_READY);
        }

        Entitlement grant = mostPermissiveActiveGrant(subject.institutionId(), item);
        if (grant == null) {
            return denied(DenyReason.NO_ENTITLEMENT);
        }

        return new EntitlementDecision(
                true,
                accessLevelFor(item, grant),
                grant.getId(),
                grant.getCopies(),
                grant.getLoanPeriodDays(),
                grant.getValidTo() == null ? null : grant.getValidTo().atStartOfDay(ZoneOffset.UTC).toInstant(),
                null
        );
    }

    private Entitlement mostPermissiveActiveGrant(String institutionId, CatalogueItem item) {
        List<Entitlement> candidates = new ArrayList<>();
        entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(institutionId, ScopeType.ITEM, item.getId())
                .ifPresent(candidates::add);
        List<String> collectionIds = item.getCollectionIds() == null ? List.of() : item.getCollectionIds();
        for (String collectionId : collectionIds) {
            entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(institutionId, ScopeType.COLLECTION, collectionId)
                    .ifPresent(candidates::add);
        }
        entitlementRepository.findByInstitutionIdAndScopeTypeAndScopeId(institutionId, ScopeType.PUBLISHER, item.getPublisherId())
                .ifPresent(candidates::add);

        LocalDate today = LocalDate.now();
        Entitlement best = null;
        for (Entitlement candidate : candidates) {
            if (candidate.getStatus() != EntitlementStatus.ACTIVE) {
                continue;
            }
            if (candidate.getValidTo() != null && candidate.getValidTo().isBefore(today)) {
                continue;
            }
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

    private AccessLevel accessLevelFor(CatalogueItem item, Entitlement grant) {
        if (item.getAccessTier() == AccessTier.OPEN_ACCESS) {
            return AccessLevel.OPEN_ACCESS;
        }
        return grant.getCopies() == null ? AccessLevel.ENTITLED_UNLIMITED : AccessLevel.ENTITLED_CONCURRENT;
    }

    private EntitlementDecision denied(DenyReason reason) {
        return new EntitlementDecision(false, null, null, null, 0, null, reason);
    }
}
