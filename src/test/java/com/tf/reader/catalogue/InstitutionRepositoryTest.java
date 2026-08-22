package com.tf.reader.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.TextCriteria;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.catalogue.entity.Institution;
import com.tf.reader.catalogue.repository.InstitutionRepository;
import com.tf.reader.common.model.RecordStatus;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InstitutionRepositoryTest {

	@Autowired
	private InstitutionRepository institutionRepository;

	private Institution newInstitution(String code, String name) {
		Institution institution = new Institution();
		institution.setCode(code);
		institution.setName(name);
		institution.setStatus(RecordStatus.ACTIVE);
		return institution;
	}

	@Test
	void savesAndReadsBackAnInstitution() {
		Institution saved = institutionRepository.save(newInstitution("imperial", "Imperial College London"));
		Institution found = institutionRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getCode()).isEqualTo("imperial");
		assertThat(found.getName()).isEqualTo("Imperial College London");
	}

	@Test
	void rejectsASecondInstitutionWithTheSameCode() {
		institutionRepository.save(newInstitution("dupe", "First"));

		assertThatThrownBy(() -> institutionRepository.save(newInstitution("dupe", "Second")))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void findsInstitutionsByNameOrCityTextSearch() {
		Institution institution = newInstitution("imperial-search", "Imperial College London");
		institution.setCity("London");
		institutionRepository.save(institution);

		List<Institution> byName = institutionRepository
				.findAllBy(TextCriteria.forDefaultLanguage().matchingAny("Imperial"));
		List<Institution> byCity = institutionRepository
				.findAllBy(TextCriteria.forDefaultLanguage().matchingAny("London"));

		assertThat(byName).extracting(Institution::getCode).contains("imperial-search");
		assertThat(byCity).extracting(Institution::getCode).contains("imperial-search");
	}

}
