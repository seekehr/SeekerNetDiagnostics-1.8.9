package com.seekehr.netdiagnostics.core.net;

/** Classification of a single TCP connection attempt. Only {@link #SUCCESS} carries a valid RTT. */
public enum ProbeOutcome {
    SUCCESS("Reachable", "TCP handshake completed"),
    REFUSED("Refused", "The host answered but nothing is accepting connections on this port (TCP reset)"),
    TIMEOUT("Timed out", "No handshake reply within the timeout. This does not prove packet loss; "
            + "the port may be filtered or the host unreachable"),
    DNS_FAILURE("DNS failure", "The host name could not be resolved to an IP address"),
    UNREACHABLE("Unreachable", "The operating system reported no route to the host or network"),
    LOCAL_ERROR("Local error", "A local socket error occurred before the handshake could complete"),
    CANCELLED("Cancelled", "The attempt was cancelled before it finished"),
    UNKNOWN("Unknown", "The attempt failed for an unclassified reason");

    public final String label;
    public final String explanation;

    ProbeOutcome(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }
}
