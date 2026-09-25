package com.tf.reader.catalogue.opds.controller;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.ContentType;
import com.tf.reader.catalogue.opds.dto.OpdsPublicationDocument;
import com.tf.reader.catalogue.opds.dto.OpdsPublicationFeed;
import com.tf.reader.catalogue.opds.service.OpdsPublicFeedService;
import com.tf.reader.common.error.ApiException;
import com.tf.reader.common.error.ErrorCode;
import com.tf.reader.common.page.PageQuery;

/**
 * The no-sign-in entry point into the app (Workstream 9): every open access title, and a
 * discovery search across everything published, entitled or not. Thin by design, same split
 * as {@link OpdsCatalogueController} - dispatch here, feed-building in
 * {@link OpdsPublicFeedService}.
 */
@RestController
@RequestMapping("/opds/v1/public")
public class OpdsPublicCatalogueController {

    private static final String OPDS_MEDIA_TYPE = "application/opds+json";
    private static final String OPDS_PUBLICATION_MEDIA_TYPE = "application/opds-publication+json";

    private final OpdsPublicFeedService publicFeedService;

    public OpdsPublicCatalogueController(OpdsPublicFeedService publicFeedService) {
        this.publicFeedService = publicFeedService;
    }

    @GetMapping(value = "/catalogue", produces = OPDS_MEDIA_TYPE)
    public ResponseEntity<OpdsPublicationFeed> catalogue(PageQuery page) {
        return ok(publicFeedService.catalogueFeed(page));
    }

    // Same "no institution, no entitlement, same result for every caller" reasoning as search
    // below - a journal carries no acquisition of its own, so there is nothing here that could
    // differ per caller.
    @GetMapping(value = "/journals", produces = OPDS_MEDIA_TYPE)
    public ResponseEntity<OpdsPublicationFeed> journals() {
        return ok(publicFeedService.journalsFeed());
    }

    // Same result for every caller (no institution, no entitlement), so a shared, global cache
    // is correct here - not keyed on anything, unlike an institution feed's per-institution ETag.
    @GetMapping(value = "/search", produces = OPDS_MEDIA_TYPE)
    public ResponseEntity<OpdsPublicationFeed> search(
            @RequestParam String query,
            PageQuery page,
            @RequestParam(required = false) ContentType contentType,
            @RequestParam(required = false) AccessTier accessTier) {
        // "Present" is not "meaningful" - an empty term would otherwise match every book via an
        // empty regex, silently turning this into an unentitled browse of the whole catalogue.
        if (query.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "query must not be blank");
        }
        // A control character (a bare null byte, most notably) fails BSON encoding several
        // layers down as an unhandled 500 - reject it here, at the edge, as the 400 it is.
        if (query.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "query must not contain control characters");
        }
        // Trimmed once, here, so the metadata title and the self/next links reflect exactly what
        // was searched for rather than whatever leading or trailing whitespace the caller typed.
        String trimmedQuery = query.trim();
        return ok(publicFeedService.searchFeed(trimmedQuery, page, contentType, accessTier));
    }

    // 404 only for genuinely unknown/archived/not-yet-ready (OpdsPublicFeedService), never for
    // "not entitled" - the caller may have just seen this item in their own search results.
    @GetMapping(value = "/publications/{itemId}", produces = OPDS_PUBLICATION_MEDIA_TYPE)
    public ResponseEntity<OpdsPublicationDocument> publication(@PathVariable String itemId) {
        return ok(publicFeedService.publicationDocument(itemId));
    }

    // DRAFT, additive: a Journal/Volume's children (navigation) or an Issue's open-access
    // Article children (a publication feed) - the no-institution mirror of the authenticated
    // works/{workId} endpoint.
    @GetMapping(value = "/works/{workId}", produces = OPDS_MEDIA_TYPE)
    public ResponseEntity<Object> work(@PathVariable String workId) {
        return ok(publicFeedService.workFeed(workId));
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(body);
    }
}
