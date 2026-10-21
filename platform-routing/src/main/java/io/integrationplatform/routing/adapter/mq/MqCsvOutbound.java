package io.integrationplatform.routing.adapter.mq;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T-125 send wiring: the target's stored settings and contract decide the body bytes at send time - the
 * endpoint read yields its contract and rows, {@link CsvRenderer} produces the queue bytes, and nothing else
 * shapes the message. A row the contract cannot express leaves as {@link CsvContractException}; MqTargetCall
 * classes it PERMANENT, so it never reaches the broker.
 */
public final class MqCsvOutbound implements MqOutbound {

    private final MqCsvTargets targets;
    private final MqCsvRows rows;

    public MqCsvOutbound(MqCsvTargets targets, MqCsvRows rows) {
        this.targets = targets;
        this.rows = rows;
    }

    @Override
    public Optional<MqMessage> resolve(Stage2Send send) {
        Optional<MqCsvTargets.MqCsvTarget> found = targets.byEndpointId(send.targetEndpointId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<Map<String, ?>>> resolved = rows.rowsFor(send);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        MqCsvTargets.MqCsvTarget target = found.get();
        byte[] body;
        try {
            body = CsvRenderer.render(target.contract(), resolved.get());
        } catch (CsvContractException contract) {
            // the zeroing MqTargetCall's finally never sees a failure before its message exists - clear it here
            if (target.password() != null) {
                Arrays.fill(target.password(), '\0');
            }
            throw contract;
        }
        return Optional.of(new MqMessage(target.settings(), target.user(), target.password(), body));
    }
}
