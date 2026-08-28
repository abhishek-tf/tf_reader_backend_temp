package com.tf.reader.reading.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tf.reader.reading.entity.DeviceFingerprint;
import com.tf.reader.reading.repository.DeviceRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev-only device fixtures for team flambeau, reading the {@code devices} array out of the shared
 * {@code flambeau-seed.json}. Same rails as {@link com.tf.reader.loan.service.LoanDevDataSeeder}:
 * local profile, {@code tnf.seed.enabled}, insert missing only — skipped per reader if a {@code
 * devices} document already exists for them, since the collection is one document per reader
 * ({@code userId} is uniquely indexed).
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "tnf.seed", name = "enabled", havingValue = "true")
public class DeviceDevDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DeviceDevDataSeeder.class);
	private static final String DATASET_PATH = "seed/flambeau-seed.json";

	private final DeviceRepository devices;
	private final ObjectMapper mapper;

	public DeviceDevDataSeeder(DeviceRepository devices, ObjectMapper mapper) {
		this.devices = devices;
		this.mapper = mapper;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		List<SeedDeviceFingerprint> seeds;
		try (InputStream in = new ClassPathResource(DATASET_PATH).getInputStream()) {
			JsonNode root = mapper.readTree(in);
			seeds = mapper.convertValue(root.get("devices"),
					mapper.getTypeFactory().constructCollectionType(List.class, SeedDeviceFingerprint.class));
		}

		int inserted = 0;
		for (SeedDeviceFingerprint seed : seeds) {
			if (devices.findByUserId(seed.userId()).isPresent()) {
				continue;
			}
			devices.save(seed.toEntity());
			inserted++;
		}
		log.info("flambeau device seed: {} inserted, {} already present", inserted, seeds.size() - inserted);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SeedDeviceFingerprint(String userId, List<SeedDevice> devices) {

		DeviceFingerprint toEntity() {
			Instant firstSeen = devices.stream().map(SeedDevice::firstSeenAt).min(Instant::compareTo).orElse(null);
			Instant lastSeen = devices.stream().map(SeedDevice::lastSeenAt).max(Instant::compareTo).orElse(null);
			DeviceFingerprint entity = new DeviceFingerprint();
			entity.setUserId(userId);
			entity.setDevices(devices.stream().map(SeedDevice::toDevice).toList());
			entity.setCreatedAt(firstSeen);
			entity.setUpdatedAt(lastSeen);
			return entity;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SeedDevice(String fingerprint, Instant firstSeenAt, Instant lastSeenAt) {

		DeviceFingerprint.Device toDevice() {
			return new DeviceFingerprint.Device(fingerprint, firstSeenAt, lastSeenAt);
		}
	}
}
