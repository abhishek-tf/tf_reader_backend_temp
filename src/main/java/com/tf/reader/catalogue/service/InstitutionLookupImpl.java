package com.tf.reader.catalogue.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.tf.reader.catalogue.api.InstitutionLookup;
import com.tf.reader.catalogue.api.InstitutionRef;
import com.tf.reader.catalogue.entity.Institution;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.common.model.RecordStatus;

import lombok.RequiredArgsConstructor;

/**
 * The seam auth signs in against: the real {@code institutions} collection, not a fixture.
 *
 * <p>ACTIVE only, and for the same reason {@link InstitutionSearchRepository#findActiveById}
 * is - a suspended institution must read exactly like an unknown one, or its status becomes
 * visible to anyone who can type an id at {@code /saml/start} or {@code /oidc/start}.
 */
@Service
@RequiredArgsConstructor
class InstitutionLookupImpl implements InstitutionLookup {

	private final InstitutionRepository institutions;

	@Override
	public Optional<InstitutionRef> find(String institutionId) {
		return institutions.findById(institutionId)
				.filter(institution -> institution.getStatus() == RecordStatus.ACTIVE)
				.map(this::toRef);
	}

	private InstitutionRef toRef(Institution institution) {
		return new InstitutionRef(institution.getId(), institution.getName());
	}
}
