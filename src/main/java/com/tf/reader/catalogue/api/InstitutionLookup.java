package com.tf.reader.catalogue.api;

import java.util.Optional;

public interface InstitutionLookup {

	/** The institution a user may sign in against, or empty if it does not exist or is not ACTIVE. */
	Optional<InstitutionRef> find(String institutionId);
}
