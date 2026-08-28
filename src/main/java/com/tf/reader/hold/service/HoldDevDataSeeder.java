package com.tf.reader.hold.service;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tf.reader.hold.entity.Hold;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.entity.Offer;
import com.tf.reader.hold.repository.HoldRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dev-only hold/queue fixtures for team flambeau, reading the {@code holds} array out of the
 * shared {@code flambeau-seed.json}. Same rails as {@link
 * com.tf.reader.loan.service.LoanDevDataSeeder}: local profile, {@code tnf.seed.enabled}, insert
 * missing only.
 *
 * <p>A QUEUED hold also needs a matching member in the Redis queue ZSET — {@code QueueService}
 * reads position from Redis, never from Mongo — so this seeder writes both stores, and bumps the
 * ticket counter so a real join afterwards continues numbering correctly rather than colliding.
 * An OFFERED hold gets no ZSET member, matching {@code PromotionService}'s own rule that a
 * promoted reader has already left the queue.
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "tnf.seed", name = "enabled", havingValue = "true")
public class HoldDevDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(HoldDevDataSeeder.class);
	private static final String DATASET_PATH = "seed/flambeau-seed.json";

	private final HoldRepository holds;
	private final StringRedisTemplate redis;
	private final ObjectMapper mapper;

	public HoldDevDataSeeder(HoldRepository holds, StringRedisTemplate redis, ObjectMapper mapper) {
		this.holds = holds;
		this.redis = redis;
		this.mapper = mapper;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		List<SeedHold> seeds;
		try (InputStream in = new ClassPathResource(DATASET_PATH).getInputStream()) {
			JsonNode root = mapper.readTree(in);
			seeds = mapper.convertValue(root.get("holds"),
					mapper.getTypeFactory().constructCollectionType(List.class, SeedHold.class));
		}

		int inserted = 0;
		for (SeedHold seed : seeds) {
			if (holds.findByHoldId(seed.holdId()).isPresent()) {
				continue;
			}
			holds.save(seed.toHold());
			if (seed.status() == HoldStatus.QUEUED) {
				String queueKey = QueueKeys.queueKey(seed.scope(), seed.itemId());
				redis.opsForZSet().add(queueKey, QueueKeys.member(seed.userId()), seed.ticket());
			}
			bumpTicketCounter(seed.scope(), seed.itemId(), seed.ticket());
			inserted++;
		}
		log.info("flambeau hold seed: {} inserted, {} already present", inserted, seeds.size() - inserted);
	}

	// INCR past whatever this seed's ticket used, so the next real join() hands out a ticket
	// that has never been seen, instead of colliding with a seeded one.
	private void bumpTicketCounter(String scope, String itemId, long usedTicket) {
		String ticketKey = QueueKeys.ticketKey(scope, itemId);
		String current = redis.opsForValue().get(ticketKey);
		long have = current == null ? 0 : Long.parseLong(current);
		if (usedTicket > have) {
			redis.opsForValue().set(ticketKey, String.valueOf(usedTicket));
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SeedHold(
			String holdId,
			String userId,
			String scope,
			String itemId,
			HoldStatus status,
			long ticket,
			Instant placedAt,
			SeedOffer offer) {

		Hold toHold() {
			Hold hold = new Hold();
			hold.setHoldId(holdId);
			hold.setUserId(userId);
			hold.setScope(scope);
			hold.setItemId(itemId);
			hold.setStatus(status);
			hold.setTicket(ticket);
			hold.setPlacedAt(placedAt);
			hold.setOffer(offer == null ? null : offer.toOffer());
			return hold;
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SeedOffer(String offerId, Instant offeredAt, Instant expiresAt, String leaseToken) {

		Offer toOffer() {
			return new Offer(offerId, offeredAt, expiresAt, leaseToken);
		}
	}
}
