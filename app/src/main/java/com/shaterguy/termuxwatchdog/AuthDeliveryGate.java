package com.shaterguy.termuxwatchdog;
/** Admission, revocation and final Parcel serialization use this same monitor. */
final class AuthDeliveryGate {
    private long epoch;
    synchronized long epoch() { return epoch; }
    synchronized void revoke() { epoch++; }
    synchronized boolean current(long acceptedEpoch) { return acceptedEpoch==epoch; }
}
