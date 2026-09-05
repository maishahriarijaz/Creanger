package com.creanger.app.messenger.creanger;

import com.creanger.app.messenger.creanger.model.ChatModels.CreangerChat;

/**
 * Gate that decides whether a chat/dialog is a Creanger chat (driven by the
 * REST data plane) or a legacy Telegram chat (MTProto). Pure and JVM-testable by
 * design: no Android types leak in, so the legacy-vs-creanger decision can be
 * unit-tested exactly as it runs in the app.
 *
 * A Creanger chat is identified by its chat UUID carried as a distinct
 * argument key ({@link #ARG_CREANGER_CHAT_ID}); a legacy chat is identified by
 * its Telegram long dialog id (user_id / -chat_id / encrypted). The gate is
 * intentionally conservative: unless BOTH the dedicated Creanger argument is
 * present AND the Creanger auth layer is enabled, the dialog is treated as a
 * legacy MTProto chat — so enabling/disabling Creanger never changes how
 * existing Telegram chats are opened.
 */
public final class CreangerChatDetection {

    /** Bundle key ({@code String}) that carries the Creanger chat UUID. */
    public static final String ARG_CREANGER_CHAT_ID = "creanger_chat_id";

    /** {@code messageOwner.params} key: plain HTTPS URL of a confirmed Creanger video. */
    public static final String PARAM_VIDEO_URL = "creanger_video_url";

    /** {@code messageOwner.params} key: poster/thumbnail URL of a confirmed Creanger video. */
    public static final String PARAM_POSTER_URL = "creanger_poster_url";

    /** {@code messageOwner.params} key: plain HTTPS URL of a confirmed Creanger audio file. */
    public static final String PARAM_AUDIO_URL = "creanger_audio_url";

    /** {@code messageOwner.params} key: plain HTTPS URL of a confirmed Creanger voice file. */
    public static final String PARAM_VOICE_URL = "creanger_voice_url";

    /** {@code messageOwner.params} key: plain HTTPS URL of a confirmed Creanger document file. */
    public static final String PARAM_DOCUMENT_URL = "creanger_document_url";

    private CreangerChatDetection() {
    }

    /**
     * @param creangerChatId value of {@link #ARG_CREANGER_CHAT_ID} from the
     *                       arguments, or null
     * @param creangerEnabled whether the Creanger auth layer is enabled
     */
    public static boolean isCreangerChat(String creangerChatId, boolean creangerEnabled) {
        return creangerEnabled && creangerChatId != null && !creangerChatId.isEmpty();
    }

    /** Legacy Telegram chat: everything that is not an explicit Creanger chat. */
    public static boolean isLegacyTelegramChat(String creangerChatId, boolean creangerEnabled) {
        return !isCreangerChat(creangerChatId, creangerEnabled);
    }

    /** Convenience: a real {@link CreangerChat} is always a Creanger chat. */
    public static boolean isCreangerChat(CreangerChat chat) {
        return chat != null && chat.id != null && !chat.id.isEmpty();
    }
}