package com.creanger.app.messenger.creanger.data;

import com.creanger.app.tgnet.tl.TL_iv;
import com.creanger.app.tgnet.TLRPC;
import androidx.annotation.Nullable;

import com.creanger.app.messenger.creanger.model.MessageModels.CreangerMessage;
import com.creanger.app.messenger.creanger.model.MessageModels.MediaAttachment;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageStatus;
import com.creanger.app.messenger.creanger.model.MessageModels.MessageType;
import com.creanger.app.messenger.creanger.model.MessageModels.ReactionSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * Display-facing projection of a {@link CreangerMessage} for the chat screen.
 *
 * Deliberately lightweight and TLRPC-free: the chat UI renders from these rows
 * directly, so the whole send/reconcile/ordering logic stays unit-testable on
 * the JVM without messaging/account infrastructure. {@code out == true} marks
 * a message the current user sent (drives bubble alignment/checks).
 * {@code messageType} + {@code attachments} let media messages render from
 * metadata only (the adapter maps them onto TLRPC Photo/Document objects).
 */
public final class CreangerMessageUiModel {

    public final String id;
    public final String chatId;
    public final String senderId;
    public final String content;
    public final String status; // MessageStatus.*
    @Nullable
    public final String clientMessageId;
    @Nullable
    public final Long chatSeq;
    @Nullable
    public final String createdAt;
    /**
     * Authoritative Creanger UUID of the replied-to message, or null when this
     * is not a reply. Kept separate from any synthetic Telegram Long id.
     */
    @Nullable
    public final String replyToMessageId;
    public final boolean isLocal;
    public final boolean out;
    /**
     * {@link MessageType} of the message (text by default); media messages
     * carry one of IMAGE/VIDEO/DOCUMENT/AUDIO/VOICE.
     */
    public final String messageType;
    /**
     * Aggregated reaction state for this message (distinct-user counts and the
     * current user's own reactions), or empty when none exist / none are loaded
     * yet. The adapter maps this onto Telegram's reaction chips.
     */
    public final List<ReactionSummary> reactions;
    /**
     * Media metadata attached to a media message (empty for text). UI-only
     * projection — the adapter maps these onto {@code TLRPC.Photo} /
     * {@code TLRPC.Document}.
     */
    public final List<MediaAttachment> attachments;
    /**
     * Rich text formatting entities in Telegram's {@code TL_iv.RichText} format.
     * Stored as a JSON string. When null, the message is plain text.
     */
    @Nullable
    public String richText;

    public CreangerMessageUiModel(String id, String chatId, String senderId, String content, String status,
                                  @Nullable String clientMessageId, @Nullable Long chatSeq,
                                  @Nullable String createdAt, boolean isLocal, boolean out) {
        this(id, chatId, senderId, content, status, clientMessageId, chatSeq, createdAt,
                null, isLocal, out, MessageType.TEXT, new ArrayList<>(), new ArrayList<>(), null);
    }

    public CreangerMessageUiModel(String id, String chatId, String senderId, String content, String status,
                                  @Nullable String clientMessageId, @Nullable Long chatSeq,
                                  @Nullable String createdAt, @Nullable String replyToMessageId,
                                  boolean isLocal, boolean out) {
        this(id, chatId, senderId, content, status, clientMessageId, chatSeq, createdAt,
                replyToMessageId, isLocal, out, MessageType.TEXT, new ArrayList<>(), new ArrayList<>(), null);
    }

    public CreangerMessageUiModel(String id, String chatId, String senderId, String content, String status,
                                  @Nullable String clientMessageId, @Nullable Long chatSeq,
                                  @Nullable String createdAt, @Nullable String replyToMessageId,
                                  boolean isLocal, boolean out, List<ReactionSummary> reactions) {
        this(id, chatId, senderId, content, status, clientMessageId, chatSeq, createdAt,
                replyToMessageId, isLocal, out, MessageType.TEXT, reactions, new ArrayList<>(), null);
    }

    /** Media variant: carries {@code messageType} + {@code attachments}. */
    public CreangerMessageUiModel(String id, String chatId, String senderId, String content, String status,
                                  @Nullable String clientMessageId, @Nullable Long chatSeq,
                                  @Nullable String createdAt, @Nullable String replyToMessageId,
                                  boolean isLocal, boolean out, String messageType,
                                  List<MediaAttachment> attachments) {
        this(id, chatId, senderId, content, status, clientMessageId, chatSeq, createdAt,
                replyToMessageId, isLocal, out, messageType, new ArrayList<>(), attachments, null);
    }

    public CreangerMessageUiModel(String id, String chatId, String senderId, String content, String status,
                                  @Nullable String clientMessageId, @Nullable Long chatSeq,
                                  @Nullable String createdAt, @Nullable String replyToMessageId,
                                  boolean isLocal, boolean out, String messageType,
                                  List<ReactionSummary> reactions, List<MediaAttachment> attachments) {
        this(id, chatId, senderId, content, status, clientMessageId, chatSeq, createdAt,
                replyToMessageId, isLocal, out, messageType, reactions, attachments, null);
    }

    public CreangerMessageUiModel(String id, String chatId, String senderId, String content, String status,
                                  @Nullable String clientMessageId, @Nullable Long chatSeq,
                                  @Nullable String createdAt, @Nullable String replyToMessageId,
                                  boolean isLocal, boolean out, String messageType,
                                  List<ReactionSummary> reactions, List<MediaAttachment> attachments,
                                  @Nullable String richText) {
        this.id = id;
        this.chatId = chatId;
        this.senderId = senderId;
        this.content = content;
        this.status = status;
        this.clientMessageId = clientMessageId;
        this.chatSeq = chatSeq;
        this.createdAt = createdAt;
        this.replyToMessageId = replyToMessageId;
        this.isLocal = isLocal;
        this.out = out;
        this.messageType = messageType != null ? messageType : MessageType.TEXT;
        this.reactions = reactions != null ? reactions : new ArrayList<>();
        this.attachments = attachments != null ? attachments : new ArrayList<>();
        this.richText = richText;
    }

    public static CreangerMessageUiModel from(CreangerMessage m, String ownerId) {
        return from(m, null, new ArrayList<>());
    }

    public static CreangerMessageUiModel from(CreangerMessage m, String ownerId,
                                              List<ReactionSummary> reactions) {
        return new CreangerMessageUiModel(
                m.id, m.chatId, m.senderId,
                m.content,
                m.status != null ? m.status : MessageStatus.SENT,
                m.clientMessageId, m.chatSeq, m.createdAt,
                m.replyToMessageId,
                m.isLocal,
                ownerId != null && ownerId.equals(m.senderId),
                m.messageType != null ? m.messageType : MessageType.TEXT,
                reactions,
                m.attachments,
                m.richText != null ? m.richText.toString() : null);
    }

    public boolean isPending() {
        return MessageStatus.PENDING.equals(status);
    }

    public boolean isFailed() {
        return MessageStatus.FAILED.equals(status);
    }

    public boolean isMedia() {
        return !MessageType.TEXT.equals(messageType);
    }

    public boolean isShowingStatus() {
        return false;
    }
}
