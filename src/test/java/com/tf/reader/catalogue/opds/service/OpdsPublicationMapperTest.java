package com.tf.reader.catalogue.opds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentType;
import com.tf.reader.catalogue.entity.WorkType;
import com.tf.reader.catalogue.opds.dto.OpdsPublication;
import com.tf.reader.catalogue.service.CatalogueUrlBuilder;
import com.tf.reader.catalogue.service.FlambeauUrlBuilder;
import com.tf.reader.ingest.service.CoverUrlResolver;

/** team1's Q-1b: an ARTICLE item must carry a distinguishable {@code @type}, not the same
 * {@code schema.org/Book} value a standalone book gets - that was the whole bug (an article
 * landing in the frontend's eBooks section because nothing on the wire told it otherwise). */
class OpdsPublicationMapperTest {

    private final CatalogueUrlBuilder catalogueUrlBuilder = mock(CatalogueUrlBuilder.class);
    private final FlambeauUrlBuilder flambeauUrlBuilder = mock(FlambeauUrlBuilder.class);
    private final CoverUrlResolver coverUrlResolver = mock(CoverUrlResolver.class);

    private final OpdsPublicationMapper mapper =
            new OpdsPublicationMapper(catalogueUrlBuilder, flambeauUrlBuilder, coverUrlResolver);

    private static CatalogueItem item(WorkType workType, ContentType contentType) {
        CatalogueItem item = new CatalogueItem();
        item.setId("item_1");
        item.setPublisherId("pub_1");
        item.setTitle("Title");
        item.setWorkType(workType);
        item.setContentType(contentType);
        item.setAccessTier(AccessTier.OPEN_ACCESS);
        return item;
    }

    @Test
    void anArticleGetsItsOwnSchemaTypeRegardlessOfContentFormat() {
        OpdsPublication publication =
                mapper.toDiscoveryPublication(item(WorkType.ARTICLE, ContentType.PDF), "self", Map.of());

        assertThat(publication.metadata().type()).isEqualTo("http://schema.org/ScholarlyArticle");
    }

    @Test
    void aStandaloneBookOrAPreMigrationNullWorkTypeStillGetsBookOrAudiobook() {
        OpdsPublication book =
                mapper.toDiscoveryPublication(item(WorkType.BOOK, ContentType.EPUB), "self", Map.of());
        OpdsPublication legacyItem =
                mapper.toDiscoveryPublication(item(null, ContentType.PDF), "self", Map.of());
        OpdsPublication audiobook =
                mapper.toDiscoveryPublication(item(WorkType.BOOK, ContentType.AUDIO), "self", Map.of());

        assertThat(book.metadata().type()).isEqualTo("http://schema.org/Book");
        assertThat(legacyItem.metadata().type()).isEqualTo("http://schema.org/Book");
        assertThat(audiobook.metadata().type()).isEqualTo("http://schema.org/Audiobook");
    }

}
