package io.integrationplatform.routing.adapter.mq;

/**
 * One resolved MQ send: where it goes, who connects, and the rendered body (ADR-018, T98).
 * {@code user} may be null (connect with no user); {@code password} is zeroed by {@link MqTargetCall} after the attempt.
 */
public record MqMessage(MqSettings settings, String user, char[] password, byte[] body) {

    /** Never the password or the body (row 327: no credential in a log line or a fault message). */
    @Override
    public String toString() {
        return "MqMessage[settings=" + settings + ", user=" + user + ", password=***, body=" +
                (body == null ? 0 : body.length) + " bytes]";
    }
}
