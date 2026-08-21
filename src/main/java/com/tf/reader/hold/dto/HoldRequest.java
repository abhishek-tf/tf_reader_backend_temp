package com.tf.reader.hold.dto;

import jakarta.validation.constraints.NotBlank;

// Request body for placing a hold. itemId and nothing else — there is no
// userId here. The token says who is asking; a parameter naming whose hold
// to create is one somebody will eventually change.
public record HoldRequest(@NotBlank String itemId) {
}
