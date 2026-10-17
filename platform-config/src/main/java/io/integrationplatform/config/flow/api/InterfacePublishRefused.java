package io.integrationplatform.config.flow.api;

/** RED stub (row 278 C2, ADR-017 Decision 1): compiles only; GREEN gives it the contract. */
public class InterfacePublishRefused extends RuntimeException {

    public InterfacePublishRefused(String reason, Throwable cause) {
        super();
    }

    public String reason() {
        return null;
    }
}
