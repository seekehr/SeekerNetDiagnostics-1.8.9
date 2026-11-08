package com.seekehr.netdiagnostics.core.net;

/** Classification of one Minecraft ping sample. Only {@link #SUCCESS} carries a ping time. */
public enum ProbeOutcome {
    SUCCESS("OK", "Ping answered"),
    REFUSED("Refused", "The host answered but nothing is accepting connections on this port"),
    TIMEOUT("Connect timeout", "The TCP connection was not established in time. This does not prove packet "
            + "loss; the host may be unreachable or filtering this port"),
    NO_RESPONSE("No response", "Connected, but the server did not answer the status or ping request in time"),
    NO_PONG("No pong", "The server sent its status but did not answer the ping (some proxies close early)"),
    PROTOCOL_ERROR("Not Minecraft", "Connected, but the reply was not a valid Minecraft status response"),
    DNS_FAILURE("DNS failure", "The host name could not be resolved to an IP address"),
    UNREACHABLE("Unreachable", "The operating system reported no route to the host or network"),
    LOCAL_ERROR("Local error", "A local socket error occurred"),
    CANCELLED("Cancelled", "The sample was cancelled before it finished"),
    UNKNOWN("Unknown", "The sample failed for an unclassified reason");

    public final String label;
    public final String explanation;

    ProbeOutcome(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }
}
