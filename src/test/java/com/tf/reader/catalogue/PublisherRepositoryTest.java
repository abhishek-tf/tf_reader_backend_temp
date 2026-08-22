package com.tf.reader.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;

import com.tf.reader.catalogue.entity.Publisher;
import com.tf.reader.catalogue.repository.PublisherRepository;
import com.tf.reader.common.model.RecordStatus;
import com.tf.reader.TestcontainersConfiguration;

import org.springframework.beans.factory.annotation.Autowired;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PublisherRepositoryTest {

	@Autowired
	private PublisherRepository publisherRepository;

	@Test
	void savesAndReadsBackAPublisher() {
		Publisher publisher = new Publisher(null, "RTLG", "Routledge", "Academic imprint",
				"https://cdn.tf/logos/rtlg.png", RecordStatus.ACTIVE, Instant.now(), Instant.now());

		Publisher saved = publisherRepository.save(publisher);
		Publisher found = publisherRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getCode()).isEqualTo("RTLG");
		assertThat(found.getName()).isEqualTo("Routledge");
	}

	@Test
	void rejectsASecondPublisherWithTheSameCode() {
		publisherRepository.save(new Publisher(null, "DUPE", "First", null, null,
				RecordStatus.ACTIVE, Instant.now(), Instant.now()));

		assertThatThrownBy(() -> publisherRepository.save(new Publisher(null, "DUPE", "Second", null, null,
				RecordStatus.ACTIVE, Instant.now(), Instant.now())))
				.isInstanceOf(DuplicateKeyException.class);
	}

}
