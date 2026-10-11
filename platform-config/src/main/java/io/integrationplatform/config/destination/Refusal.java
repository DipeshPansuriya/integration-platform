package io.integrationplatform.config.destination;

/** Why the destination guard refused an address (closed set; the audit and the API answer carry the name). */
public enum Refusal {
    /** The scheme is not http or https. */
    SCHEME_NOT_ALLOWED,
    /** The address carries user information (credentials never ride in an address). */
    CREDENTIALS_IN_ADDRESS,
    /** The address names no host. */
    NO_HOST,
    /** The host is not on the operator's allow-list. */
    NOT_ON_ALLOW_LIST,
    /** The host is, or resolves to, a refused range (loopback, private, link-local, ...). */
    REFUSED_ADDRESS
}
