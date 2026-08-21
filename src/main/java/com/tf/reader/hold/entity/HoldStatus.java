package com.tf.reader.hold.entity;

// Lifecycle states of a hold.
//
// Only two. A finished hold — cancelled, accepted or lapsed — is deleted,
// not retired, so there is no third state to represent "done."
public enum HoldStatus {
    QUEUED,
    OFFERED
}
