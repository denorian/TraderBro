package com.traderbro.notify.telegram;

/**
 * Abstraction over the Telegram send API so the queue worker can be unit-tested without a real
 * network. Carries the outcome of a send, including Telegram's rate-limit backoff when hit.
 */
public interface TelegramSender {

    /** Outcome of one send attempt. */
    record SendResult(boolean ok, String messageId, long retryAfterSeconds) {

        public static SendResult ok(String messageId) {
            return new SendResult(true, messageId, 0);
        }

        public static SendResult failed() {
            return new SendResult(false, null, 0);
        }

        /** Rate-limited by Telegram; retry after the given number of seconds. */
        public static SendResult rateLimited(long retryAfterSeconds) {
            return new SendResult(false, null, retryAfterSeconds);
        }
    }

    SendResult sendHtml(String chatId, String html);
}