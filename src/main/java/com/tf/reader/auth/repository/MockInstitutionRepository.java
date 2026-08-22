package com.tf.reader.auth.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.tf.reader.auth.model.Institution;

/**
 * The institutions a user may sign in against.
 *
 * <p>All of them authenticate through the same SAML integration. Adding an institution is a
 * data change here and nothing else - no new registration, no new certificate, no new IdP.
 *
 * <p>Seeded in memory for the prototype. The real {@code institutions} collection is owned by
 * wokay, and this class is the seam that will read from it at integration.
 */
@Component
public class MockInstitutionRepository {

	private static final Map<String, Institution> INSTITUTIONS = Map.of(
			// Reproduced from the API Reference worked example, so fixtures agree across teams.
			"inst_7f3", new Institution("inst_7f3", "Imperial College London"),
			"inst_imperial", new Institution("inst_imperial", "Imperial College"),
			"inst_dsu", new Institution("inst_dsu", "Dayananda Sagar University"),
			"inst_xyz", new Institution("inst_xyz", "University XYZ"));

	public Optional<Institution> find(String institutionId) {
		return Optional.ofNullable(INSTITUTIONS.get(institutionId));
	}

	public List<Institution> all() {
		return INSTITUTIONS.values().stream()
				.sorted((left, right) -> left.institutionId().compareTo(right.institutionId()))
				.toList();
	}
}
