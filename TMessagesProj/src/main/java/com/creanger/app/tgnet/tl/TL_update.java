package com.creanger.app.tgnet.tl;

import com.creanger.app.tgnet.TLObject;
import com.creanger.app.tgnet.TLRPC;
import java.util.List;

/**
 * Minimal TL_update compatibility shim.
 */
public class TL_update {

    public static abstract class Update extends TLObject {}
    
    public static class TL_updateNewMessage extends Update {
        public TLRPC.Message message;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateMessageID extends Update {
        public int id;
        public long random_id;
    }
    
    public static class TL_updateDeleteMessages extends Update {
        public int[] messages;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateUserTyping extends Update {
        public TLRPC.PeerUser user;
        public TLRPC.SendAction action;
    }
    
    public static class TL_updateChatUserTyping extends Update {
        public long chat_id;
        public TLRPC.PeerUser user;
        public TLRPC.SendAction action;
    }
    
    public static class TL_updateChatParticipants extends Update {
        public TLRPC.ChatParticipants participants;
    }
    
    public static class TL_updateUserStatus extends Update {
        public long user_id;
        public TLRPC.UserStatus status;
    }
    
    public static class TL_updateUserName extends Update {
        public long user_id;
        public String first_name;
        public String last_name;
        public String username;
    }
    
    public static class TL_updateUserPhoto extends Update {
        public long user_id;
        public TLRPC.UserProfilePhoto photo;
        public long previous_photo_id;
    }
    
    public static class TL_updateContactRegistered extends Update {
        public long user_id;
        public int date;
    }
    
    public static class TL_updateContactLink extends Update {
        public long user_id;
        public TLRPC.ContactLink link;
    }
    
    public static class TL_updateNewAuthorization extends Update {
        public long auth_key_id;
        public int date;
        public String device;
        public String location;
    }
    
    public static class TL_updateNewChannelMessage extends Update {
        public TLRPC.Message message;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateReadHistoryInbox extends Update {
        public TLRPC.Peer peer;
        public int max_id;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateReadHistoryOutbox extends Update {
        public TLRPC.Peer peer;
        public int max_id;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateWebPage extends Update {
        public TLRPC.WebPage web_page;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateReadMessagesContents extends Update {
        public int[] messages;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateChannelTooLong extends Update {
        public long channel_id;
        public int pts;
    }
    
    public static class TL_updateChannel extends Update {
        public long channel_id;
    }
    
    public static class TL_updateNewStickerSet extends Update {
        public TLRPC.messages_StickerSet stickerset;
    }
    
    public static class TL_updateStickerSetsOrder extends Update {
        public int order;
        public long[] sticker_sets;
    }
    
    public static class TL_updateStickerSets extends Update {}
    
    public static class TL_updateSavedGifs extends Update {}
    
    public static class TL_updateBotInlineQuery extends Update {
        public long query_id;
        public long user_id;
        public String query;
        public TLRPC.GeoPoint geo;
        public String offset;
    }
    
    public static class TL_updateBotInlineSend extends Update {
        public long user_id;
        public TLRPC.InputBotInlineMessageID id;
        public TLRPC.BotInlineMessage message;
    }
    
    public static class TL_updateEditChannelMessage extends Update {
        public TLRPC.Message message;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateChannelViewForumAsMessages extends Update {
        public long channel_id;
        public boolean enabled;
    }
    
    public static class TL_updateChannelMessageForwards extends Update {
        public long channel_id;
        public int id;
        public int forwards;
    }
    
    public static class TL_updateChannelUserTyping extends Update {
        public long channel_id;
        public long user_id;
        public TLRPC.SendAction action;
        public int top_msg_id;
    }
    
    public static class TL_updateReadChannelInbox extends Update {
        public long channel_id;
        public int max_id;
        public int pts;
    }
    
    public static class TL_updateDeleteChannelMessages extends Update {
        public long channel_id;
        public int[] messages;
        public int pts;
        public int pts_count;
    }
    
    public static class TL_updateChannelPinnedTopic extends Update {
        public long channel_id;
        public int topic_id;
        public boolean pinned;
    }
    
    public static class TL_updatePeerBlocked extends Update {
        public TLRPC.Peer peer_id;
        public boolean blocked;
    }
    
    public static class TL_updateDialogPinned extends Update {
        public TLRPC.Peer peer;
        public int folder_id;
        public boolean pinned;
    }
    
    public static class TL_updatePinnedDialogs extends Update {
        public int order;
        public TLRPC.Peer[] peers;
    }
    
    public static class TL_updatePeerSettings extends Update {
        public TLRPC.Peer peer;
        public TLRPC.PeerSettings settings;
    }
    
    public static class TL_updateDialogFilters extends Update {}
    
    public static class TL_updateDialogFilter extends Update {
        public int filter_id;
        public TLRPC.DialogFilter filter;
    }
    
    public static class TL_updateDialogFilterOrder extends Update {
        public int[] order;
    }
    
    public static class TL_updateRecentStickers extends Update {}
    
    public static class TL_updateFeaturedStickers extends Update {}
    
    public static class TL_updateConfig extends Update {}
    
    public static class TL_updatePts extends Update {
        public int pts;
    }
    
    public static class TL_updateQts extends Update {
        public int qts;
    }
    
    public static class TL_updateChannelWebPage extends Update {
        public long channel_id;
        public TLRPC.WebPage web_page;
        public int pts;
        public int pts_count;
    }
    public static class TL_updateReadFeaturedEmojiStickers extends TLObject {}
    public static class TL_updateNewScheduledMessage extends TLObject {}
    public static class TL_updateEditEphemeralMessage extends TLObject {}
    public static class TL_updateDraftMessage extends TLObject {}
    public static class TL_updateReadMonoForumOutbox extends TLObject {}
    public static class TL_updateChatParticipantDelete extends TLObject {}
    public static class TL_updateReadChannelDiscussionOutbox extends TLObject {}
    public static class TL_updateBotCommands extends TLObject {}
    public static class TL_updateQuickReplyMessage extends TLObject {}
    public static class TL_updateDeleteEphemeralMessages extends TLObject {}
    public static class TL_updateEncryption extends TLObject {}
    public static class TL_updateTranscribeAudio extends TLObject {}
    public static class TL_updateFolderPeers extends TLObject {}
    public static class TL_updateTranscribedAudio extends TLObject {}
    public static class TL_updateDialogUnreadMark extends TLObject {}
    public static class TL_updatePinnedForumTopic extends TLObject {}
    public static class TL_updateSavedRingtones extends TLObject {}
    public static class TL_updateGroupCallConnection extends TLObject {}
    public static class TL_updatePinnedChannelMessages extends TLObject {}
    public static class TL_updateFavedStickers extends TLObject {}
    public static class TL_updateWebBrowserSettings extends TLObject {}
    public static class TL_updateReadChannelOutbox extends TLObject {}
    public static class TL_updateRecentEmojiStatuses extends TLObject {}
    public static class TL_updateNewEphemeralMessage extends TLObject {}
    public static class TL_updateTheme extends TLObject {}
    public static class TL_updateUserEmojiStatus extends TLObject {}
    public static class TL_updateServiceNotification extends TLObject {}
    public static class TL_updateGroupCallEncryptedMessage extends TLObject {}
    public static class TL_updateEncryptedMessagesRead extends TLObject {}
    public static class TL_updateEncryptedChatTyping extends TLObject {}
    public static class TL_updatePinnedMessages extends TLObject {}
    public static class TL_updateDeleteGroupCallMessages extends TLObject {}
    public static class TL_updateMessageExtendedMedia extends TLObject {}
    public static class TL_updateUserPhone extends TLObject {}
    public static class TL_updateStarsBalance extends TLObject {}
    public static class TL_updateDcOptions extends TLObject {}
    public static class TL_updateChannelMessageViews extends TLObject {}
    public static class TL_updateWebBrowserException extends TLObject {}
    public static class TL_updateChatDefaultBannedRights extends TLObject {}
    public static class TL_updateNewEncryptedMessage extends TLObject {}
    public static class TL_updateMessagePoll extends TLObject {}
    public static class TL_updateAttachMenuBots extends TLObject {}
    public static class TL_updatePendingJoinRequests extends TLObject {}
    public static class TL_updateStoryID extends TLObject {}
    public static class TL_updateGroupCallParticipants extends TLObject {}
    public static class TL_updateWebViewResultSent extends TLObject {}
    public static class TL_updateChat extends TLObject {}
    public static class TL_updateNotifySettings extends TLObject {}
    public static class TL_updateSentStoryReaction extends TLObject {}
    public static class TL_updateMessageReactions extends TLObject {}
    public static class TL_updateChatParticipantAdd extends TLObject {}
    public static class TL_updateChannelReadMessagesContents extends TLObject {}
    public static class TL_updateSavedReactionTags extends TLObject {}
    public static class TL_updateLangPackTooLong extends TLObject {}
    public static class TL_updateLangPack extends TLObject {}
    public static class TL_updateGeoLiveViewed extends TLObject {}
    public static class TL_updatePaidReactionPrivacy extends TLObject {}
    public static class TL_updateReadFeaturedStickers extends TLObject {}
    public static class TL_updateRecentReactions extends TLObject {}
    public static class TL_updatePeerWallpaper extends TLObject {}
    public static class TL_updateReadChannelDiscussionInbox extends TLObject {}
    public static class TL_updateUser extends TLObject {}
    public static class TL_updateEditMessage extends TLObject {}
    public static class TL_updateGroupCall extends TLObject {}
    public static class TL_updateChannelAvailableMessages extends TLObject {}
    public static class TL_updatePeerHistoryTTL extends TLObject {}
    public static class TL_updateStarsRevenueStatus extends TLObject {}
    public static class TL_updateReadMonoForumInbox extends TLObject {}
    public static class TL_updateMoveStickerSetToTop extends TLObject {}
    public static class TL_updateChatParticipantRank extends TLObject {}
    public static class TL_updatePinnedSavedDialogs extends TLObject {}
    public static class TL_updatePrivacy extends TLObject {}
    public static class TL_updatePinnedForumTopics extends TLObject {}
    public static class TL_updateEmojiGameInfo extends TLObject {}
    public static class TL_updateSavedDialogPinned extends TLObject {}
    public static class TL_updateDeleteScheduledMessages extends TLObject {}
    public static class TL_updateMonoForumNoPaidException extends TLObject {}
    public static class TL_updateChatParticipantAdmin extends TLObject {}
    public static class TL_updateSentPhoneCode extends TLObject {}
    public static class TL_updateGroupCallMessage extends TLObject {}
    public static class TL_updateNewStoryReaction extends TLObject {}
}