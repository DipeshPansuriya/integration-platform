// One probe per T-item: a file on main plus a needle that proves the feature's code is there. pending.mjs ON_MAIN
// may say true only while every probe holds, and may say false only while at least one fails (a stale gate).
// Measured against main b6106af2, 22-Oct (git log -- <file>, git merge-base --is-ancestor <commit> main).
const J = 'platform-app/src/main/java/io/integrationplatform/app';
const R = 'platform-routing/src/main/java/io/integrationplatform/routing';
const D = 'platform-delivery/src/main/java/io/integrationplatform/delivery';
const I = 'platform-ingest/src/main/java/io/integrationplatform/ingest';
const E = 'platform-e2e/src/test/java/io/integrationplatform/e2e';

export const PROBES = Object.freeze({
  T95: [[`platform-config/src/main/java/io/integrationplatform/config/system/application/SystemSettings.java`, 'SystemSettings']],
  T96: [[`${J}/message/DeliveryOperatorController.java`, 'sent again with the pinned versions'], [`${J}/message/DeliveryOperatorController.java`, 'not pinned']],
  T98: [[`${R}/adapter/mq/MqCsvOutbound.java`, 'MqCsvOutbound'], [`${R}/adapter/mq/MqAdapterConfiguration.java`, 'MqCsvOutbound']],
  T99: [[`${D}/port/TargetHalfOpenCap.java`, 'TargetHalfOpenCap'], [`${D}/internal/retry/RetryPolicy.java`, 'RetryPolicy']],
  T104: [[`${I}/internal/outbox/JdbcOutboxWriter.java`, 'JdbcOutboxWriter']],
  T105: [[`${I}/internal/timing/JdbcStageTimingWriter.java`, 'JdbcStageTimingWriter']],
  T106: [[`${I}/internal/relay/OutboxRelayRunnerConfiguration.java`, 'OutboxRelayRunnerConfiguration']],
  T107: [[`${I}/adapter/in/CorrelationFilter.java`, 'CorrelationFilter'], ['platform-app/src/main/resources/logback-spring.xml', 'logback']],
  T109: [[`${J}/audit/JdbcAuditEventStore.java`, 'JdbcAuditEventStore']],
  T110: [[`${J}/message/MessageTrackingController.java`, 'MessageTrackingController']],
  T111: [[`${J}/message/DeliveryOperatorController.java`, '/{deliveryId}/replay'], [`${D}/replay/DeliveryOperatorActions.java`, 'replay']],
  T112: [['ui/src/App.tsx', "from './pages/MessageDetail'"], ['ui/src/App.tsx', "from './pages/Failures'"]],
  T113: [['ui/src/pages/QueueDashboard.tsx', 'QueueDashboard']],
  T115: [[`${I}/adapter/in/IntakeController.java`, '/messages:bulk'], [`${I}/adapter/in/IntakeController.java`, 'INTAKE_BUSY'],
    ['platform-ingest/src/main/resources/db/migration/V2_1_1111__intake_idempotency.sql', 'idempot']],
  T120: [[`${J}/scheduler/DailyReportPlaceholderHandler.java`, 'DeliveryDailyAggregates']],
  T121: [['ui/src/App.tsx', '<DeliveryReport />']],
  T143: [['compose/compose.yaml', 'profiles: ["e2e"]']],
  T144: [[`${J}/clock/AdjustableClock.java`, 'class AdjustableClock']],
  T157: [[`${E}/seed/TenantBSeed.java`, 'class TenantBSeed']]
});
