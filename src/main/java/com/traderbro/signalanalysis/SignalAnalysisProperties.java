package com.traderbro.signalanalysis;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the stage 4.0 signal-analysis section ({@code signal-analysis.*}): personal
 * Telegram account ingestion of broadcast channels. All secrets (api-id/api-hash/encryption key)
 * come from environment variables and are never logged. REST and UI are always available; the
 * native TDLib client is only created when {@code signal-analysis.ingestion.enabled=true}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "signal-analysis")
public class SignalAnalysisProperties {

    /** Overall section flag (default on: REST + UI + metadata are served regardless). */
    private boolean enabled = true;
    private Api api = new Api();
    private Ingestion ingestion = new Ingestion();

    /** REST/UI defaults for the channel list and status polling. */
    @Getter
    @Setter
    public static class Api {
        private int defaultPageSize = 50;
        private int maxPageSize = 200;
        private long channelListRefreshMs = 30_000L;
        private long statusRefreshMs = 10_000L;
    }

    /** Native TDLib ingestion: session, inbox, discovery, history and lifecycle tuning. */
    @Getter
    @Setter
    public static class Ingestion {
        private boolean enabled = false;
        private String sessionDatabaseDirectory = "/var/lib/traderbro/telegram-user/tdlib-db";
        private String sessionFilesDirectory = "/var/lib/traderbro/telegram-user/files";
        /** Not exposed by TDLight's TDLibSettings in this version — see {@code TdLibTelegramUserClient}. */
        private String databaseEncryptionKey = "";
        private long apiId = 0;
        private String apiHash = "";
        private String phoneNumber = "";
        private Duration monitoringPollInterval = Duration.ofSeconds(5);
        private Inbox inbox = new Inbox();
        private Discovery discovery = new Discovery();
        private History history = new History();
        private Lifecycle lifecycle = new Lifecycle();

        /** Durable inbox: bounded queue, claim batches, retries and raw-event retention. */
        @Getter
        @Setter
        public static class Inbox {
            private int queueCapacity = 10_000;
            private int claimBatchSize = 100;
            private int maxAttempts = 10;
            private Duration initialRetryDelay = Duration.ofSeconds(2);
            private Duration maxRetryDelay = Duration.ofMinutes(5);
            private int rawEventRetentionDays = 30;
            private Duration cleanupInterval = Duration.ofHours(1);
        }

        /** Channel list discovery (Main + Archive) and periodic reconciliation. */
        @Getter
        @Setter
        public static class Discovery {
            private int pageSize = 100;
            private Duration syncInterval = Duration.ofMinutes(15);
            private Duration completionTimeout = Duration.ofMinutes(2);
        }

        /** Full history backfill and tail reconciliation. */
        @Getter
        @Setter
        public static class History {
            private boolean enabled = true;
            private int pageSize = 100;
            private boolean fullBackfillEnabled = true;
            private int maxConcurrentChannelBackfills = 2;
            private int tailSize = 100;
            private Duration tailSyncInterval = Duration.ofMinutes(5);
            private Duration requestPacingDelay = Duration.ofMillis(250);
            private int maxNoProgressPages = 3;
        }

        /** Thread pools and heartbeat for the native client lifecycle. */
        @Getter
        @Setter
        public static class Lifecycle {
            private int callbackThreads = 1;
            private int inboxThreads = 2;
            private int historyThreads = 2;
            private Duration heartbeatInterval = Duration.ofSeconds(10);
            private Duration heartbeatTimeout = Duration.ofSeconds(45);
        }
    }
}