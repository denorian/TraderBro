package com.traderbro.signalanalysis.telegram.tdlib;

import com.traderbro.signalanalysis.SignalAnalysisProperties;
import com.traderbro.signalanalysis.telegram.ChatList;
import com.traderbro.signalanalysis.telegram.MembershipStatus;
import com.traderbro.signalanalysis.telegram.TelegramAuthState;
import com.traderbro.signalanalysis.telegram.TelegramChat;
import com.traderbro.signalanalysis.telegram.TelegramMessage;
import com.traderbro.signalanalysis.telegram.TelegramSessionManager;
import com.traderbro.signalanalysis.telegram.TelegramSupergroup;
import com.traderbro.signalanalysis.telegram.TelegramUpdate;
import com.traderbro.signalanalysis.telegram.TelegramUserClient;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import it.tdlight.jni.TdApi.Chat;
import it.tdlight.jni.TdApi.ChatTypeSupergroup;
import it.tdlight.jni.TdApi.ChatMemberStatus;
import it.tdlight.jni.TdApi.Messages;
import it.tdlight.jni.TdApi.Update;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Adapter over TDLight's {@link SimpleTelegramClient}. This is the ONLY package that touches
 * {@code TdApi}. Callbacks only translate updates into {@link TelegramUpdate} and hand them to a
 * registered consumer; no DB/RPC work happens in the native callback thread.
 */
@Slf4j
public class TdLibTelegramUserClient implements TelegramUserClient {

    private final SimpleTelegramClient client;
    private final TdLibMessageMapper mapper = new TdLibMessageMapper();
    private final TelegramSessionManager session;
    private volatile Consumer<TelegramUpdate> consumer = u -> { };

    public TdLibTelegramUserClient(SignalAnalysisProperties props, TelegramSessionManager session) {
        this.session = session;
        SignalAnalysisProperties.Ingestion cfg = props.getIngestion();
        int apiId = Math.toIntExact(cfg.getApiId());
        TDLibSettings settings = TDLibSettings.create(new APIToken(apiId, cfg.getApiHash()));
        settings.setDatabaseDirectoryPath(Path.of(cfg.getSessionDatabaseDirectory()));
        settings.setDownloadedFilesDirectoryPath(Path.of(cfg.getSessionFilesDirectory()));
        // Local TDLib databases for resume + offline lookup; secret chats are never enabled.
        settings.setFileDatabaseEnabled(true);
        settings.setChatInfoDatabaseEnabled(true);
        settings.setMessageDatabaseEnabled(true);

        SimpleTelegramClientFactory factory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder builder = factory.builder(settings);
        builder.addUpdatesHandler(this::onUpdate);
        // TODO: verify API — resume of a persisted session may need the full interactive supplier;
        // on a fresh session this reaches WAIT_CODE/WAIT_PASSWORD and the main app stays in
        // AUTH_REQUIRED until the one-time login runner (profile signal-auth) has been executed.
        this.client = builder.build(AuthenticationSupplier.user(cfg.getPhoneNumber()));
    }

    @Override
    public void onUpdate(Consumer<TelegramUpdate> c) {
        this.consumer = c;
    }

    private void onUpdate(Update update) {
        if (update instanceof TdApi.UpdateNewMessage u) {
            consumer.accept(new TelegramUpdate.NewMessage(mapper.map(u.message)));
        } else if (update instanceof TdApi.UpdateMessageEdited e) {
            consumer.accept(new TelegramUpdate.MessageEdited(e.chatId, e.messageId,
                    e.editDate == 0 ? null : java.time.Instant.ofEpochSecond(e.editDate)));
        } else if (update instanceof TdApi.UpdateMessageContent c) {
            consumer.accept(new TelegramUpdate.MessageContent(c.chatId, c.messageId,
                    mapper.mapContent(c.newContent)));
        } else if (update instanceof TdApi.UpdateDeleteMessages d) {
            consumer.accept(new TelegramUpdate.DeleteMessages(d.chatId, d.messageIds,
                    d.isPermanent, d.fromCache));
        } else if (update instanceof TdApi.UpdateNewChat c) {
            consumer.accept(new TelegramUpdate.NewChat(mapChat(c.chat, null)));
        } else if (update instanceof TdApi.UpdateSupergroup s) {
            consumer.accept(new TelegramUpdate.Supergroup(mapSupergroup(s.supergroup)));
        } else if (update instanceof TdApi.UpdateAuthorizationState a) {
            TelegramAuthState st = mapAuthState(a.authorizationState);
            session.set(st);
            consumer.accept(new TelegramUpdate.AuthorizationState(st));
        }
    }

    @Override
    public CompletableFuture<Void> loadChats(ChatList list, int limit) {
        return client.send(new TdApi.LoadChats(toChatList(list), limit))
                .thenApply(ok -> null);
    }

    @Override
    public CompletableFuture<List<Long>> getChatIds(ChatList list, int limit) {
        return client.send(new TdApi.GetChats(toChatList(list), limit))
                .thenApply(c -> java.util.Arrays.stream(c.chatIds).boxed().toList());
    }

    @Override
    public CompletableFuture<TelegramChat> getChat(long chatId) {
        return client.send(new TdApi.GetChat(chatId)).thenCompose(this::enrichChat);
    }

    private CompletableFuture<TelegramChat> enrichChat(Chat chat) {
        TelegramChat base = mapChat(chat, null);
        if (!(chat.type instanceof ChatTypeSupergroup s) || !s.isChannel) {
            return CompletableFuture.completedFuture(base);
        }
        // Membership + username live on the supergroup, not the chat; fetch it for channels.
        return client.send(new TdApi.GetSupergroup(s.supergroupId))
                .thenApply(sg -> mapChat(chat, mapSupergroup(sg)));
    }

    private TelegramChat mapChat(Chat chat, TelegramSupergroup sg) {
        boolean isChannel = chat.type instanceof ChatTypeSupergroup s && s.isChannel;
        boolean archived = isArchived(chat);
        String username = sg == null ? null : sg.username();
        MembershipStatus membership = sg == null ? MembershipStatus.UNKNOWN : sg.membershipStatus();
        Long supergroupId = chat.type instanceof ChatTypeSupergroup s ? s.supergroupId : null;
        return new TelegramChat(chat.id, supergroupId, chat.title, username, isChannel, archived,
                membership);
    }

    private static boolean isArchived(Chat chat) {
        if (chat.positions == null) {
            return false;
        }
        for (TdApi.ChatPosition p : chat.positions) {
            if (p.list instanceof TdApi.ChatListArchive) {
                return true;
            }
        }
        return false;
    }

    private static TelegramSupergroup mapSupergroup(TdApi.Supergroup sg) {
        String username = sg.usernames == null ? null : sg.usernames.editableUsername;
        return new TelegramSupergroup(sg.id, sg.isChannel, username, mapMembership(sg.status));
    }

    private static MembershipStatus mapMembership(ChatMemberStatus status) {
        if (status instanceof TdApi.ChatMemberStatusMember) {
            return MembershipStatus.MEMBER;
        }
        if (status instanceof TdApi.ChatMemberStatusAdministrator) {
            return MembershipStatus.ADMINISTRATOR;
        }
        if (status instanceof TdApi.ChatMemberStatusCreator) {
            return MembershipStatus.CREATOR;
        }
        if (status instanceof TdApi.ChatMemberStatusRestricted) {
            return MembershipStatus.RESTRICTED;
        }
        if (status instanceof TdApi.ChatMemberStatusLeft) {
            return MembershipStatus.LEFT;
        }
        if (status instanceof TdApi.ChatMemberStatusBanned) {
            return MembershipStatus.BANNED;
        }
        return MembershipStatus.UNKNOWN;
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

    private static TdApi.ChatList toChatList(ChatList list) {
        return list == ChatList.ARCHIVE ? new TdApi.ChatListArchive() : new TdApi.ChatListMain();
    }

    @Override
    public CompletableFuture<List<TelegramMessage>> getChatHistory(long chatId, long fromMessageId,
                                                                   int limit) {
        // offset=0, onlyLocal=false; fromMessageId is the cursor of the newest message already seen.
        return client.send(new TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))
                .thenApply(this::mapMessages);
    }

    @Override
    public CompletableFuture<TelegramMessage> getMessage(long chatId, long messageId) {
        return client.send(new TdApi.GetMessage(chatId, messageId)).thenApply(mapper::map);
    }

    private List<TelegramMessage> mapMessages(Messages messages) {
        if (messages == null || messages.messages == null) {
            return new ArrayList<>();
        }
        List<TelegramMessage> out = new ArrayList<>(messages.messages.length);
        for (TdApi.Message m : messages.messages) {
            out.add(mapper.map(m));
        }
        return out;
    }

    @Override
    public void closeAndWait() {
        try {
            client.closeAndWait();
        } catch (Exception e) {
            log.warn("TDLib client close interrupted: {}", e.getMessage());
        }
    }
}