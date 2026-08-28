package com.tf.reader.auth;

import java.time.Instant;

import com.tf.reader.catalogue.entity.Institution;
import com.tf.reader.catalogue.entity.InstitutionType;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.common.model.RecordStatus;

/**
 * The three real institutions the auth test suite signs in against, saved as real documents in
 * the real {@code institutions} collection - because {@code InstitutionLookup} now reads that
 * collection instead of a fixture map.
 *
 * <p>Seeded ACTIVE regardless of what {@code demo-dataset.json} says (there, Leeds is SUSPENDED
 * on purpose, for the catalogue team's own tests): the auth suite is testing sign-in itself, not
 * institution status.
 *
 * <p><b>The {@code code} values are deliberately not "imperial"/"ucl"/"leeds".</b>
 * {@link com.tf.reader.ContainerisedInfrastructure}'s Mongo container is static for the whole
 * JVM, shared by every test class that extends it - including the catalogue team's own
 * {@code InstitutionRepositoryTest}, which saves its own institution with {@code code: "imperial"}
 * and would collide with the {@code code} field's unique index if this class used the same one.
 */
public final class AuthTestInstitutions {

	public static final String IMPERIAL = "inst_7f3";
	public static final String IMPERIAL_NAME = "Imperial College London";
	public static final String UCL = "inst_ucl";
	public static final String UCL_NAME = "University College London";
	public static final String LEEDS = "inst_leeds";
	public static final String LEEDS_NAME = "University of Leeds";

	private AuthTestInstitutions() {
	}

	public static void seed(InstitutionRepository institutions) {
		institutions.save(institution(IMPERIAL, "auth-test-imperial", IMPERIAL_NAME));
		institutions.save(institution(UCL, "auth-test-ucl", UCL_NAME));
		institutions.save(institution(LEEDS, "auth-test-leeds", LEEDS_NAME));
	}

	private static Institution institution(String id, String code, String name) {
		Institution institution = new Institution();
		institution.setId(id);
		institution.setCode(code);
		institution.setName(name);
		institution.setType(InstitutionType.ACADEMIC);
		institution.setCountry("UK");
		institution.setCity("London");
		institution.setStatus(RecordStatus.ACTIVE);
		institution.setCatalogueVersion(1L);
		institution.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
		institution.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
		return institution;
	}
}
