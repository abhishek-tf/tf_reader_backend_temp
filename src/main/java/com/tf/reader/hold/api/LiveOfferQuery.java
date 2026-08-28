package com.tf.reader.hold.api;

import java.util.List;

/**
 * Published contract: every live, unexpired offer, across every institution and title —
 * the reconciler's rebuild read for the {@code CopyLease} slot an offer occupies.
 */
public interface LiveOfferQuery {

	List<LiveOfferView> findAllLiveOffers();
}
