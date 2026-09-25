package com.tf.reader.catalogue.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Builds the address of an institution's catalogue feed, from one configured base URL. */
@Component
public class CatalogueUrlBuilder {

    private final String baseUrl;

    public CatalogueUrlBuilder(@Value("${tf.catalogue.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String catalogueUrlFor(String institutionId) {
        return baseUrl + "/opds/v1/institutions/" + institutionId + "/catalogue";
    }

    public String groupUrlFor(String institutionId, String groupId) {
        return baseUrl + "/opds/v1/institutions/" + institutionId + "/groups/" + groupId;
    }

    public String publicationUrlFor(String institutionId, String itemId) {
        return baseUrl + "/opds/v1/institutions/" + institutionId + "/publications/" + itemId;
    }

    public String workUrlFor(String institutionId, String workId) {
        return baseUrl + "/opds/v1/institutions/" + institutionId + "/works/" + workId;
    }

    /** RFC 6570 template - {@code {?query}} is filled in by the OPDS client, not by us. */
    public String searchUrlTemplateFor(String institutionId) {
        return baseUrl + "/opds/v1/institutions/" + institutionId + "/search{?query}";
    }

    /** The bare search path, for a concrete self/next link on an actual result page -
     * {@link #searchUrlTemplateFor(String)} is for the untemplated signpost only. */
    public String searchUrlFor(String institutionId) {
        return baseUrl + "/opds/v1/institutions/" + institutionId + "/search";
    }

    public String publicCatalogueUrlFor() {
        return baseUrl + "/opds/v1/public/catalogue";
    }

    public String publicJournalsUrlFor() {
        return baseUrl + "/opds/v1/public/journals";
    }

    public String publicPublicationUrlFor(String itemId) {
        return baseUrl + "/opds/v1/public/publications/" + itemId;
    }

    /**
     * No institution to browse a journal's volumes/issues without one yet (see
     * PublicCatalogueScreen.tsx's own "NO JOURNALS HERE" note on the frontend) - this only names
     * the journal's id in a stable, fetchable-looking shape for {@code idFromHref} to read back,
     * the same as {@link #workUrlFor} does for an institution.
     */
    public String publicWorkUrlFor(String workId) {
        return baseUrl + "/opds/v1/public/works/" + workId;
    }

    public String publicSearchUrlFor() {
        return baseUrl + "/opds/v1/public/search";
    }

    public String publicWorkUrlFor(String workId) {
        return baseUrl + "/opds/v1/public/works/" + workId;
    }

    /** Where a book this caller cannot obtain sends them, per the {@code subscribe} link. */
    public String institutionsUrl() {
        return baseUrl + "/api/v1/institutions";
    }
}
