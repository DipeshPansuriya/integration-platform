package io.integrationplatform.routing.adapter.mq;

/** A row the contract cannot express - a permanent failure: dead-lettered, nothing goes on the queue. */
public class CsvContractException extends RuntimeException {

    public CsvContractException(String message) {
        super(message);
    }
}
