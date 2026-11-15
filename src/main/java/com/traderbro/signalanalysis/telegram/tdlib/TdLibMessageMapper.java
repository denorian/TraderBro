package com.traderbro.signalanalysis.telegram.tdlib;

import com.traderbro.signalanalysis.domain.TelegramMessageContent;
import com.traderbro.signalanalysis.telegram.TelegramMessage;
import it.tdlight.jni.TdApi;
import it.tdlight.jni.TdApi.FormattedText;
import it.tdlight.jni.TdApi.Message;
import it.tdlight.jni.TdApi.MessageAnimation;
import it.tdlight.jni.TdApi.MessageAudio;
import it.tdlight.jni.TdApi.MessageContent;
import it.tdlight.jni.TdApi.MessageDocument;
import it.tdlight.jni.TdApi.MessageForwardInfo;
import it.tdlight.jni.TdApi.MessagePhoto;
import it.tdlight.jni.TdApi.MessageReplyTo;
import it.tdlight.jni.TdApi.MessageSenderChat;
import it.tdlight.jni.TdApi.MessageSenderUser;
import it.tdlight.jni.TdApi.MessageSticker;
import it.tdlight.jni.TdApi.MessageText;
import it.tdlight.jni.TdApi.MessageVideo;
import it.tdlight.jni.TdApi.MessageVoiceNote;
import it.tdlight.jni.TdApi.MessageReplyToMessage;
import it.tdlight.jni.TdApi.TextEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Converts a {@code TdApi.Message} into the domain {@link TelegramMessage} snapshot, normalizing
 * content into stable JSON strings. This is the single place that reads message content from
 * TDLib; nothing downstream sees raw {@code TdApi} objects. Files are never downloaded; media is
 * captured as metadata only.
 */
public final class TdLibMessageMapper {

    private final ObjectMapper json;

    public TdLibMessageMapper() {
        this.json = new ObjectMapper();
    }

    public TelegramMessage map(Message m) {
        NormalizedContent nc = normalize(m.content);
        Long replyToId = replyToMessageId(m.replyTo);
        String senderType = null;
        Long senderId = null;
        if (m.senderId instanceof MessageSenderUser u) {
            senderType = "USER";
            senderId = u.userId;
        } else if (m.senderId instanceof MessageSenderChat c) {
            senderType = "CHAT";
            senderId = c.chatId;
        }
        String forwardJson = forwardMetadata(m.forwardInfo);
        return new TelegramMessage(
                m.id,
                m.chatId,
                senderType,
                senderId,
                m.authorSignature,
                toInstant(m.date),
                m.editDate == 0 ? null : toInstant(m.editDate),
                replyToId,
                new TelegramMessageContent(nc.contentType, nc.text, nc.entitiesJson,
                        nc.mediaJson, forwardJson));
    }

    private record NormalizedContent(String contentType, String text, String entitiesJson,
                                     String mediaJson) {
    }

    /** Map just the content (used for {@code updateMessageContent}, which has no message header). */
    public TelegramMessageContent mapContent(MessageContent content) {
        NormalizedContent nc = normalize(content);
        return new TelegramMessageContent(nc.contentType, nc.text, nc.entitiesJson, nc.mediaJson,
                null);
    }

    private NormalizedContent normalize(MessageContent content) {
        if (content instanceof MessageText t) {
            return new NormalizedContent("TEXT", t.text.text, entitiesJson(t.text),
                    null);
        }
        FormattedText caption = captionOf(content);
        String text = caption == null ? null : caption.text;
        String entities = caption == null ? null : entitiesJson(caption);
        String kind = mediaKind(content);
        String mediaJson = kind == null ? null : write(Map.of("kind", kind));
        String type = kind == null ? "UNSUPPORTED" : kind.toUpperCase();
        return new NormalizedContent(type, text, entities, mediaJson);
    }

    private static FormattedText captionOf(MessageContent content) {
        if (content instanceof MessagePhoto p) {
            return p.caption;
        }
        if (content instanceof MessageVideo v) {
            return v.caption;
        }
        if (content instanceof MessageDocument d) {
            return d.caption;
        }
        if (content instanceof MessageAudio a) {
            return a.caption;
        }
        if (content instanceof MessageVoiceNote v) {
            return v.caption;
        }
        if (content instanceof MessageAnimation a) {
            return a.caption;
        }
        if (content instanceof MessageSticker) {
            return null;
        }
        return null;
    }

    private static String mediaKind(MessageContent content) {
        if (content instanceof MessagePhoto) {
            return "photo";
        }
        if (content instanceof MessageVideo) {
            return "video";
        }
        if (content instanceof MessageDocument) {
            return "document";
        }
        if (content instanceof MessageAudio) {
            return "audio";
        }
        if (content instanceof MessageVoiceNote) {
            return "voice";
        }
        if (content instanceof MessageAnimation) {
            return "animation";
        }
        if (content instanceof MessageSticker) {
            return "sticker";
        }
        return null;
    }

    private String entitiesJson(FormattedText text) {
        if (text.entities == null || text.entities.length == 0) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TextEntity e : text.entities) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("offset", e.offset);
            m.put("length", e.length);
            m.put("type", entityType(e));
            out.add(m);
        }
        return write(out);
    }

    private static String entityType(TextEntity e) {
        if (e.type == null) {
            return null;
        }
        String simple = e.type.getClass().getSimpleName();
        if (simple.startsWith("TextEntityType")) {
            simple = simple.substring("TextEntityType".length());
        }
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    private static Long replyToMessageId(MessageReplyTo replyTo) {
        if (replyTo instanceof MessageReplyToMessage r) {
            return r.messageId;
        }
        return null;
    }

    private String forwardMetadata(MessageForwardInfo info) {
        if (info == null || info.origin == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("origin", info.origin.getClass().getSimpleName());
        m.put("date", info.date == 0 ? null : info.date);
        return write(m);
    }

    private static Instant toInstant(int unixSeconds) {
        return Instant.ofEpochSecond(unixSeconds);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}