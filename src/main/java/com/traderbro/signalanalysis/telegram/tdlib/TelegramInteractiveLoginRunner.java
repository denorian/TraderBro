package com.traderbro.signalanalysis.telegram.tdlib;

import com.traderbro.signalanalysis.SignalAnalysisProperties;
import com.traderbro.signalanalysis.service.TelegramAuthInputBridge;
import com.traderbro.signalanalysis.telegram.TelegramAuthState;
import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.ClientInteraction;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-time interactive login for the personal Telegram account. Run explicitly with the
 * {@code signal-auth} profile against the same application JAR. The phone number comes from env
 * ({@code TELEGRAM_PHONE_NUMBER}); the login code and the 2FA password are both entered through
 * the local UI (WebSocket on 127.0.0.1) via {@link TelegramAuthInputBridge}. The finished session
 * is written into the TDLib database directory and the runner exits, after which the main
 * application resumes it without re-entering a code.
 *
 * <p>Usage: {@code mvn spring-boot:run -Dspring-boot.run.profiles=signal-auth}
 */
@Slf4j
@Component
@Profile("signal-auth")
@ConditionalOnProperty(name = "signal-analysis.ingestion.enabled", havingValue = "true")
public class TelegramInteractiveLoginRunner implements ApplicationRunner {

    private final SignalAnalysisProperties props;
    private final TelegramAuthInputBridge bridge;
    private final TelegramSessionManager session;

    public TelegramInteractiveLoginRunner(SignalAnalysisProperties props,
                                          TelegramAuthInputBridge bridge,
                                          TelegramSessionManager session) {
        this.props = props;
        this.bridge = bridge;
        this.session = session;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        SignalAnalysisProperties.Ingestion cfg = props.getIngestion();
        int apiId = Math.toIntExact(cfg.getApiId());
        TDLibSettings settings = TDLibSettings.create(new APIToken(apiId, cfg.getApiHash()));
        settings.setDatabaseDirectoryPath(Path.of(cfg.getSessionDatabaseDirectory()));
        settings.setDownloadedFilesDirectoryPath(Path.of(cfg.getSessionFilesDirectory()));
        settings.setFileDatabaseEnabled(true);
        settings.setChatInfoDatabaseEnabled(true);
        settings.setMessageDatabaseEnabled(true);

        SimpleTelegramClientFactory factory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder builder = factory.builder(settings);
        builder.setClientInteraction(new BridgeClientInteraction(bridge));
        CompletableFuture<Void> ready = new CompletableFuture<>();
        builder.addUpdatesHandler(update -> {
            if (update instanceof TdApi.UpdateAuthorizationState a) {
                // Keep the UI status endpoint in sync during the interactive login.
                TelegramAuthState st = mapAuthState(a.authorizationState);
                session.set(st);
                if (st == TelegramAuthState.READY) {
                    ready.complete(null);
                }
            }
        });
        // user(phone) supplies the phone from env; the code and 2FA password are entered in the
        // UI over the local WebSocket (see BridgeClientInteraction).
        SimpleTelegramClient client = builder.build(
                AuthenticationSupplier.user(cfg.getPhoneNumber()));
        try {
            log.info("Interactive Telegram login started. Enter the code and 2FA in the UI "
                    + "(Каналы Telegram).");
            ready.get(5, TimeUnit.MINUTES);
            log.info("Authorization OK — session saved. Closing client.");
            client.closeAndWait();
        } catch (Exception e) {
            log.error("Interactive Telegram login failed: {}", sanitize(e));
            bridge.cancel();
            try {
                client.closeAndWait();
            } catch (Exception ignored) {
                // best effort
            }
            throw e;
        }
    }

    /**
     * {@link ClientInteraction} that resolves both the login code and the 2FA password from the
     * local WebSocket bridge. Neither value ever touches the console, REST, logs or storage.
     */
    private static final class BridgeClientInteraction implements ClientInteraction {
        private final TelegramAuthInputBridge bridge;

        private BridgeClientInteraction(TelegramAuthInputBridge bridge) {
            this.bridge = bridge;
        }

        @Override
        public CompletableFuture<String> onParameterRequest(InputParameter parameter,
                                                            ParameterInfo info) {
            if (parameter == InputParameter.ASK_PASSWORD) {
                return bridge.awaitPassword();
            }
            return bridge.awaitCode();
        }
    }

    private static String sanitize(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    private static TelegramAuthState mapAuthState(TdApi.AuthorizationState s) {
        if (s instanceof TdApi.AuthorizationStateReady) {
            return TelegramAuthState.READY;
        }
        if (s instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            return TelegramAuthState.WAIT_TDLIB_PARAMETERS;
        }
        if (s instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            return TelegramAuthState.WAIT_PHONE_NUMBER;
        }
        if (s instanceof TdApi.AuthorizationStateWaitCode) {
            return TelegramAuthState.WAIT_CODE;
        }
        if (s instanceof TdApi.AuthorizationStateWaitPassword) {
            return TelegramAuthState.WAIT_PASSWORD;
        }
        if (s instanceof TdApi.AuthorizationStateLoggingOut) {
            return TelegramAuthState.LOGGING_OUT;
        }
        if (s instanceof TdApi.AuthorizationStateClosed) {
            return TelegramAuthState.CLOSED;
        }
        return TelegramAuthState.FATAL;
    }
}