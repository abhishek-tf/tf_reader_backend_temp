package com.tf.reader.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.catalogue.entity.BookCollection;
import com.tf.reader.catalogue.repository.BookCollectionRepository;

@SpringBootTest(properties = "tnf.auth.jwt.secret=" + ContainerisedInfrastructure.JWT_SECRET)
class BookCollectionRepositoryTest extends ContainerisedInfrastructure {

	@Autowired
	private BookCollectionRepository bookCollectionRepository;

	private BookCollection newCollection(String publisherId, String code, String name) {
		BookCollection collection = new BookCollection();
		collection.setPublisherId(publisherId);
		collection.setCode(code);
		collection.setName(name);
		return collection;
	}

	@Test
	void savesAndReadsBackACollection() {
		BookCollection saved = bookCollectionRepository.save(newCollection("pub_rtlg", "LAW2024", "Law and Technology 2024"));
		BookCollection found = bookCollectionRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getCode()).isEqualTo("LAW2024");
		assertThat(found.getPublisherId()).isEqualTo("pub_rtlg");
	}

	@Test
	void rejectsASecondCollectionWithTheSameCodeUnderTheSamePublisher() {
		bookCollectionRepository.save(newCollection("pub_rtlg", "DUPE", "First"));

		assertThatThrownBy(() -> bookCollectionRepository.save(newCollection("pub_rtlg", "DUPE", "Second")))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void allowsTheSameCodeUnderADifferentPublisher() {
		bookCollectionRepository.save(newCollection("pub_a", "SHARED", "A's collection"));

		BookCollection saved = bookCollectionRepository.save(newCollection("pub_b", "SHARED", "B's collection"));

		assertThat(saved.getId()).isNotNull();
	}

}
