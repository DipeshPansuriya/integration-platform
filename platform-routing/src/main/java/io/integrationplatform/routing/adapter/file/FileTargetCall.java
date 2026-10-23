package io.integrationplatform.routing.adapter.file;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.integrationplatform.config.destination.ConfinedPath;
import io.integrationplatform.domain.delivery.OutcomeClass;
import io.integrationplatform.routing.stage2.Stage2Send;
import io.integrationplatform.routing.stage2.TargetCall;
import io.integrationplatform.routing.stage2.TargetFaultException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T-150: sends one due delivery to the File target with the controls (mirrors MqTargetCall): a breaker per
 * endpoint, the destination path guard ({@link ConfinedPath}, the save-time rule of ADR-010 section 8), write
 * then rename ({@link FileWrite}, so a crash or refusal leaves no final-named file), and metrics, trace and
 * log per attempt. A mapped name that escapes the folder is refused PERMANENT - nothing is written at all.
 */
public final class FileTargetCall implements TargetCall {

    /** Same breaker shape as the API, MQ and Kafka target calls. */
    static final CircuitBreakerConfig BREAKER = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(2).build();

    private static final Logger LOG = LoggerFactory.getLogger(FileTargetCall.class);

    private final FileOutbound outbound;
    private final FolderResolver folders;
    private final CircuitBreakerRegistry breakers;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public FileTargetCall(FileOutbound outbound, FolderResolver folders, CircuitBreakerRegistry breakers, MeterRegistry meters,
            ObservationRegistry observations) {
        this.outbound = outbound;
        this.folders = folders;
        this.breakers = breakers;
        this.meters = meters;
        this.observations = observations;
    }

    /** The longest name whose temp name (name + ".tmp-" + 36-character uuid) still fits the 255-byte limit. */
    static final int MAX_NAME_BYTES = 255 - ".tmp-".length() - 36;

    private static final java.util.regex.Pattern DEVICE_NAME = java.util.regex.Pattern.compile(
            "(?i)(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])");

    private static boolean plainName(String name) {
        return name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf(':') < 0
                && name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_NAME_BYTES
                && !deviceName(name);
    }

    /** Windows treats CON, PRN, AUX, NUL, COM1-9 and LPT1-9 as devices with any extension, case and trailing dots. */
    private static boolean deviceName(String name) {
        String stem = name.strip();
        int dot = stem.indexOf('.');
        stem = (dot < 0 ? stem : stem.substring(0, dot)).stripTrailing();
        return DEVICE_NAME.matcher(stem).matches();
    }

    @Override
    public void call(Stage2Send send) {
        CircuitBreaker breaker = breakers.circuitBreaker("file:" + send.targetEndpointId(), BREAKER);
        if (!breaker.tryAcquirePermission()) {
            throw TargetFaultException.CircuitOpenException.of(breaker, send.targetEndpointId());
        }
        long started = System.nanoTime();
        Observation observation = Observation.createNotStarted("file.target.send", observations).start();
        OutcomeClass outcome = OutcomeClass.TRANSIENT;
        FileMessage message = null;
        try {
            Optional<FileMessage> resolved;
            try {
                resolved = outbound.resolve(send);
            } catch (RuntimeException format) {
                if (io.integrationplatform.routing.stage2.PlatformReadFaults.isTransient(format)) {
                    // T190: the platform's own pool/database blip, not an inexpressible file: retry, never dead-letter
                    breaker.releasePermission();
                    throw new TargetFaultException.TransientFaultException("the platform database could not be read for"
                            + " file target endpoint " + send.targetEndpointId() + "; the send is retried");
                }
                // a mapped file name or body the target cannot express is permanent: nothing is written
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the file target endpoint " + send.targetEndpointId()
                        + " cannot express delivery " + send.deliveryId() + " (" + format.getMessage() + ")");
            }
            if (resolved.isEmpty()) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "delivery " + send.deliveryId() + " has no file message");
            }
            message = resolved.get();
            // F4 @security: the tenant-typed root is confined to the operator's base folders (real-path check); the
            // answer is the real folder, used below as is - the settings text is never resolved a second time
            Optional<Path> directory = folders.resolve(message.settings().directory());
            if (directory.isEmpty()) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the target folder of endpoint " + send.targetEndpointId()
                        + " is not available - nothing is written");
            }
            if (!plainName(message.fileName())) {
                // R5 (CWE-73/918): the file name is checked as a name BEFORE ConfinedPath resolves it on disk -
                // an old order probed a UNC name under Windows first, which sends NTLM credentials to it; a file
                // name is a name: no folders, drive letters, device names or over-long names
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the mapped file name of delivery " + send.deliveryId()
                        + " is not a plain file name for target endpoint " + send.targetEndpointId());
            }
            Optional<Path> target = ConfinedPath.under(directory.get(), message.fileName());
            if (target.isEmpty()) {
                // @security: a mapped name outside the folder (e.g. "../") never touches the file system
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the mapped file name of delivery " + send.deliveryId()
                        + " is refused for target endpoint " + send.targetEndpointId()
                        + " (outside the target's folder)");
            }
            try {
                FileWrite.deliver(target.get(), message.body());
                outcome = OutcomeClass.SUCCESS;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } catch (FileWrite.FileTooLargeToVerifyException tooLarge) {
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the file name of delivery " + send.deliveryId()
                        + " holds a file too large to verify in the folder of endpoint " + send.targetEndpointId());
            } catch (FileWrite.FileNameTakenException taken) {
                // the answer is final: the name holds another file and is never overwritten, a retry cannot help
                outcome = OutcomeClass.PERMANENT;
                breaker.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                throw new TargetFaultException(outcome, "the file name of delivery " + send.deliveryId()
                        + " is already taken in the folder of endpoint " + send.targetEndpointId());
            } catch (IOException failed) {
                breaker.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, failed);
                throw new TargetFaultException.TransientFaultException("the write to the target folder of "
                        + send.targetEndpointId() + " failed (" + failed.getClass().getSimpleName() + ")");
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            String tag = outcome.name();
            Timer.builder("file.target.send").tag("target_type", "FILE").tag("outcome", tag).register(meters)
                    .record(elapsed, TimeUnit.NANOSECONDS);
            Counter.builder("file.target.outcome").tag("target_type", "FILE").tag("outcome", tag).register(meters)
                    .increment();
            observation.lowCardinalityKeyValue("outcome", tag).stop();
            LOG.info("file send delivery={} endpoint={} outcome={} ms={}", send.deliveryId(),
                    send.targetEndpointId(), tag, TimeUnit.NANOSECONDS.toMillis(elapsed));
        }
    }
}