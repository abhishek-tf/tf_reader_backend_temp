package com.tf.reader.hold.service;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tf.reader.hold.api.LiveOfferQuery;
import com.tf.reader.hold.api.LiveOfferView;
import com.tf.reader.hold.entity.HoldStatus;
import com.tf.reader.hold.repository.HoldRepository;

/**
 * Implementation of {@link LiveOfferQuery}.
 */
@Service
public class LiveOfferQueryImpl implements LiveOfferQuery {

	private final HoldRepository holds;
	private final Clock clock;

	public LiveOfferQueryImpl(HoldRepository holds, Clock clock) {
		this.holds = holds;
		this.clock = clock;
	}

	@Override
	public List<LiveOfferView> findAllLiveOffers() {
		return holds.findByStatus(HoldStatus.OFFERED).stream()
				.filter(h -> h.getOffer() != null && h.getOffer().getExpiresAt().isAfter(clock.instant()))
				.map(h -> new LiveOfferView(h.getScope(), h.getItemId(),
						h.getOffer().getLeaseToken(), h.getOffer().getExpiresAt()))
				.toList();
	}
}
