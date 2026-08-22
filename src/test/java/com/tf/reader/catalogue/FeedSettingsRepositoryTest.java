package com.tf.reader.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.catalogue.entity.FeedSettings;
import com.tf.reader.catalogue.entity.Shelf;
import com.tf.reader.catalogue.repository.FeedSettingsRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FeedSettingsRepositoryTest {

	@Autowired
	private FeedSettingsRepository feedSettingsRepository;

	private FeedSettings newFeedSettings(String institutionId) {
		FeedSettings feedSettings = new FeedSettings();
		feedSettings.setInstitutionId(institutionId);
		feedSettings.setFeedTitle("Imperial College Library");
		feedSettings.setPageSize(20);
		feedSettings.setShelves(List.of(
				new Shelf("shelf_1", "New this month", 1, List.of("item_42")),
				new Shelf("shelf_2", "Law essentials", 2, List.of()),
				new Shelf("shelf_3", "Audio picks", 3, List.of())));
		return feedSettings;
	}

	@Test
	void savesAndReadsBackFeedSettings() {
		FeedSettings saved = feedSettingsRepository.save(newFeedSettings("inst_7f3"));
		FeedSettings found = feedSettingsRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getInstitutionId()).isEqualTo("inst_7f3");
		assertThat(found.getShelves()).hasSize(3);
	}

	@Test
	void rejectsASecondFeedSettingsForTheSameInstitution() {
		feedSettingsRepository.save(newFeedSettings("inst_dupe"));

		assertThatThrownBy(() -> feedSettingsRepository.save(newFeedSettings("inst_dupe")))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void rejectsFeedSettingsWithoutExactlyThreeShelves() {
		FeedSettings feedSettings = newFeedSettings("inst_twoshelf");
		feedSettings.setShelves(List.of(
				new Shelf("shelf_1", "New this month", 1, List.of())));

		assertThatThrownBy(() -> feedSettingsRepository.save(feedSettings))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsAShelfWithMoreThanFiftyItems() {
		List<String> tooManyItems = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			tooManyItems.add("item_" + i);
		}

		FeedSettings feedSettings = newFeedSettings("inst_overfull");
		feedSettings.setShelves(List.of(
				new Shelf("shelf_1", "New this month", 1, tooManyItems),
				new Shelf("shelf_2", "Law essentials", 2, List.of()),
				new Shelf("shelf_3", "Audio picks", 3, List.of())));

		assertThatThrownBy(() -> feedSettingsRepository.save(feedSettings))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
