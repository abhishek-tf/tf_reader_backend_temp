package com.tf.reader.catalogue.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.tf.reader.catalogue.entity.AccessTier;
import com.tf.reader.catalogue.entity.CatalogueItem;
import com.tf.reader.catalogue.entity.ContentState;
import com.tf.reader.catalogue.entity.ItemStatus;

public interface CatalogueItemRepository extends MongoRepository<CatalogueItem, String> {

	List<CatalogueItem> findByPublisherIdAndStatus(String publisherId, ItemStatus status);

	List<CatalogueItem> findByCollectionIdsAndStatusAndContentState(String collectionId, ItemStatus status,
			ContentState contentState);

	List<CatalogueItem> findByCollectionIds(String collectionId);

	List<CatalogueItem> findByAccessTierAndStatus(AccessTier accessTier, ItemStatus status);

	Optional<CatalogueItem> findByIsbn(String isbn);

	List<CatalogueItem> findAllBy(TextCriteria criteria);

	long countByPublisherId(String publisherId);

	long countByCollectionIds(String collectionId);

}
