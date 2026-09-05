package com.creanger.app.tgnet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import android.graphics.drawable.BitmapDrawable;

/**
 * Minimal TLRPC compatibility shim.
 * Provides only the most commonly used model types.
 * NO Telegram protocol/RPC implementation.
 */
public class TLRPC {

    public static final int LAYER = 0;

    public static class TL_error {
        public int code;
        public String text;
    }


    public static class Message extends TLObject {
        public int id;
        public int local_id;
        public long dialog_id;
        public int date;
        public int out;
        public boolean unread;
        public String message;
        public int send_state;
        public PeerUser from_id;
        public PeerChat peer_id;
        public MessageMedia media;
        public ReplyMarkup reply_markup;
        public List<MessageEntity> entities = new ArrayList<>();
        public Map<String, Object> params = new HashMap<>();
        public int flags;
        public boolean isOnline;
        public boolean isVerified;
        public boolean isPremium;
        public boolean checkPremium;
        public java.util.ArrayList chats = new java.util.ArrayList();
        public java.util.ArrayList users = new java.util.ArrayList();
        public java.util.ArrayList<String> emoticons = new java.util.ArrayList<>();
        public Object toArray(Object[] a) { return null; }
        public java.util.ArrayList thumbs = new java.util.ArrayList();
        public Object thumb_version;
        public Object thumb_document_id;
        public java.util.ArrayList order = new java.util.ArrayList();
        public boolean add(Object o) { return false; }
        public static Object TLdeserialize(Object s, int c, boolean e) { return null; }
        public long random_id;
        public int until;
        public int position;
        public Object lang_code;
        public boolean rtl;
        public Object reason;
        public Object profile_photo;
        public Object short_date;
        public Object saved_from_peer;
        public Object relative;
        public Object settings;
        public Object self;
        public Object short_time;
        public Object emoji_status;
        public Object icon_emoji_id;
        public java.util.ArrayList<TLRPC.VideoSize> video_sizes;
        public int saved_from_msg_id;
        public Object native_name;
        public Object channel;
        public Object creator;
        public java.util.ArrayList objects = new java.util.ArrayList();
        public int channel_post;
        public Object sticker;
        public boolean day_of_week;
        public Object localThumbPath;
        public Object base_lang_code;
        public int until_date;
        public java.util.ArrayList groups = new java.util.ArrayList();
        public java.util.ArrayList strings = new java.util.ArrayList();
        public Object fallback_photo;
        public Object plural_code;
        public boolean blur;
        public int pinned_msg_id;
        public Chat migrated_to;
        public ChatBannedRights default_banned_rights;
        public Object slug;
        public int version;
        public Object strippedBitmap;
        public boolean motion;
        public byte[] stripped_thumb;
        public Object lang_pack;
        public Object platform;
        public Object key;
        public int from_version;
        public Object personal_photo;
        public int flags2;
        public int views;
        public int forwards;
        public Object replies;
        public boolean edit_hide;
        public int ttl;
        public MessageFwdHeader fwd_from;
        public int via_bot_id;
        public TL_messageReplyHeader reply_to;
        public Message replyMessage;
        public int edit_date;
        public String post_author;
        public long grouped_id;
        public Object reactions;
        public List<Object> restriction_reason = new ArrayList<>();
        public int ttl_period;
        public int quick_reply_shortcut_id;
        public int effect;
        public Object factcheck;
        public int paid_message_stars;
        public Object suggested_post;
        public int schedule_repeat_period;
        public Object summary_from_language;
        public Object rich_message;
        public String customReplyName;
        
        public Message() {}
    }

    public static class TL_message extends Message {}
    public static class TL_messageReplyHeader extends TLObject {
        public int reply_to_msg_id;
        public int flags;
    }
    public static class TL_messageReactions extends TLObject {
        public int flags;
        public List<ReactionCount> results = new ArrayList<>();
    }
    public static class TL_reactionCount extends TLObject {
        public Reaction reaction;
        public int count;
        public boolean chosen;
        public int flags;
        public int chosen_order;
    }
    public static abstract class Reaction extends TLObject {}
    public static class TL_reactionEmoji extends Reaction {
        public String emoticon;
    }
    public static class TL_reactionCustomEmoji extends Reaction {
        public long document_id;
    }
    public static class ReactionCount extends TLObject {
        public Reaction reaction;
        public int count;
        public boolean chosen;
    }

    public static class PeerUser extends TLObject {
        public long user_id;
        public long access_hash;
    }
    public static class PeerChat extends TLObject {
        public long chat_id;
        public long access_hash;
    }
    public static class PeerChannel extends TLObject {
        public long channel_id;
        public long access_hash;
    }
    public static class TL_peerUser extends PeerUser {}
    public static class TL_peerChat extends PeerChat {}
    public static class TL_peerChannel extends PeerChannel {}
    public static abstract class Peer extends TLObject {}
    public static class TL_peerUser1 extends Peer {}
    public static class PeerSettings extends TLObject {}
    public static class ContactLink extends TLObject {}
    public static class DialogFilter extends TLObject {}
    public static class messages_StickerSet extends TLObject {}
    public static class ChatParticipants extends TLObject {}

    public static abstract class MessageMedia extends TLObject { public Document document; public Photo photo; public WebPage webpage; }

    public static class TL_messageMediaEmpty extends MessageMedia {}
    
    public static class TL_messageMediaPhoto extends MessageMedia {
        public Photo photo;
        public int flags;
    }
    
    public static class TL_messageMediaDocument extends MessageMedia {
        public Document document;
        public int flags;
        public boolean video;
        public boolean voice;
    }

    public static class Photo extends TLObject {
        public java.util.ArrayList<VideoSize> video_sizes = new java.util.ArrayList<>();
        public long id;
        public long access_hash;
        public byte[] file_reference = new byte[0];
        public int date;
        public int dc_id;
        public int flags;
        public java.util.ArrayList<PhotoSize> sizes = new java.util.ArrayList<>();
        public static Photo TLdeserialize(InputSerializedData s, int c, boolean e) { return new Photo(); }
        public byte[] stripped_thumb;
    }

    public static class PhotoSize extends TLObject {
        public String type;
        public FileLocation location;
        public int w;
        public int h;
        public int size;
        public String url;
        public byte[] bytes;
        public byte[] stripped_thumb;
        public byte[] strippedBitmap;
        public boolean motion;
        public boolean blur;
    }
    public static class TL_photo extends Photo {}
    public static class TL_photoSize extends PhotoSize {}
    public static class TL_photoSizeEmpty extends PhotoSize {}

    public static class PhotoSizeLocation extends TLObject {}

    public static class Document extends TLObject {
        public long id;
        public long access_hash;
        public byte[] file_reference = new byte[0];
        public String mime_type;
        public int size;
        public int dc_id;
        public java.util.ArrayList<PhotoSize> thumbs = new java.util.ArrayList<>();
        public java.util.ArrayList<PhotoSize> video_thumbs = new java.util.ArrayList<>();
        public java.util.ArrayList<DocumentAttribute> attributes = new java.util.ArrayList<>();
        public int date;
        public int flags;
        public static Document TLdeserialize(InputSerializedData s, int c, boolean e) { return new Document(); }
    }
    public static class TL_document extends Document {}
    public static class TL_documentAttributeFilename extends DocumentAttribute {
        public String file_name;
    }

    public static abstract class DocumentAttribute extends TLObject { public String alt; public boolean mask; public StickerSet stickerset; }
    
    public static class TL_documentAttributeVideo extends DocumentAttribute {
        public int duration;
        public int w;
        public int h;
        public boolean round_message;
        public boolean supports_streaming;
        public boolean nosound;
        public int flags;
        public boolean motion;
    }
    
    public static class TL_documentAttributeAudio extends DocumentAttribute {
        public int duration;
        public String title;
        public String performer;
        public boolean voice;
        public int flags;
    }
    
    public static class TL_documentAttributeAnimated extends DocumentAttribute {}
    public static class TL_documentAttributeSticker extends DocumentAttribute {
        public String alt;
        public boolean mask;
        public TLRPC.StickerSet stickerset;
    }
    
    public static class InputDocument extends TLObject {
        public long id;
        public long access_hash;
        public String file_reference;
    }

    public static abstract class MessageEntity extends TLObject {
        public int offset;
        public int length;
    }
    
    public static class TL_messageEntityBold extends MessageEntity {}
    public static class TL_messageEntityItalic extends MessageEntity {}
    public static class TL_messageEntityUnderline extends MessageEntity {}
    public static class TL_messageEntityStrike extends MessageEntity {}
    public static class TL_messageEntityCode extends MessageEntity {}
    
    public static class TL_messageEntityTextUrl extends MessageEntity {
        public String url;
    }
    
    public static class TL_messageEntityMentionName extends MessageEntity {
        public long user_id;
    }
    
    public static class TL_messageEntityPre extends MessageEntity {
        public String language;
    }
    
    public static class TL_messageEntityCustomEmoji extends MessageEntity {
        public long document_id;
    }

    public static abstract class ReplyMarkup extends TLObject {}
    
    public static class TL_replyKeyboardHide extends ReplyMarkup {}
    public static class TL_replyKeyboardForceReply extends ReplyMarkup {}
    public static class TL_replyKeyboardMarkup extends ReplyMarkup {
        public List<List<KeyboardButton>> rows = new ArrayList<>();
        public boolean resize;
        public boolean single_use;
        public boolean selective;
    }
    
    public static class TL_replyInlineMarkup extends ReplyMarkup {
        public List<List<KeyboardButton>> rows = new ArrayList<>();
    }
    
    public static class KeyboardButton extends TLObject {
        public String text;
        public String url;
        public boolean request_contact;
        public boolean request_location;
        public boolean request_poll;
    }

    public static class User extends TLObject {
        public java.util.ArrayList<RestrictionReason> restriction_reason = new java.util.ArrayList<>();
        public long id;
        public String first_name;
        public String last_name;
        public String username;
        public String phone;
        public boolean premium;
        public boolean bot;
        public int flags;
        public TLRPC.UserProfilePhoto photo;
        public TLRPC.UserStatus status;
        public static User TLdeserialize(InputSerializedData s, int c, boolean e) { return new User(); }
    }

    public static class UserProfilePhoto extends TLObject {
        public long photo_id;
        public java.util.ArrayList<PhotoSize> sizes = new java.util.ArrayList<>();
        public int dc_id;
        public boolean has_video;
        public boolean personal;
    }

    public static abstract class UserStatus extends TLObject {
        public int expires;
        public boolean by_me;
        public int was_online;
    }
    public static class TL_userStatusEmpty extends UserStatus {}
    public static class TL_userStatusOnline extends UserStatus {
        public int expires;
    }
    public static class TL_userStatusOffline extends UserStatus {
        public int was_online;
    }
    public static class TL_userStatusRecently extends UserStatus {}
    public static class TL_userStatusLastWeek extends UserStatus {}
    public static class TL_userStatusLastMonth extends UserStatus {}

    public static class Chat extends TLObject {
        public java.util.ArrayList<RestrictionReason> restriction_reason = new java.util.ArrayList<>();
        public long id;
        public String title;
        public String username;
        public int participants_count;
        public boolean megagroup;
        public static Chat TLdeserialize(InputSerializedData s, int c, boolean e) { return new Chat(); }
        public long channel_id;
        public Photo photo;
        public ChatBannedRights default_banned_rights;
        public ChatBannedRights banned_rights;
        public Chat migrated_to;
    }

    public static class ChatFull extends TLObject {
        public long id;
        public String about;
        public int participants_count;
        public List<ChatParticipant> participants = new ArrayList<>();
        public ChatPhoto photo;
        public ChatBannedRights default_banned_rights;
        public boolean can_set_stickers;
        public StickerSetCovered stickerset;
        public StickerSet emojiset;
    }

    public static class ChatPhoto extends TLObject {
        public long photo_id;
        public PhotoSize photo_small;
        public PhotoSize photo_big;
        public byte[] stripped_thumb;
        public byte[] strippedBitmap;
    }

    public static class ChatParticipant extends TLObject {
        public long user_id;
        public int date;
        public TLRPC.ChatParticipantRole role;
    }

    public static class ChatBannedRights extends TLObject {
        public boolean send_stickers;
        public boolean send_plain;
    }

    public static abstract class ChatParticipantRole extends TLObject {}
    public static class TL_chatParticipantRoleCreator extends ChatParticipantRole {}
    public static class TL_chatParticipantRoleAdmin extends ChatParticipantRole {
        public int admin_rights;
        public int rank;
    }
    public static class TL_chatParticipantRoleMember extends ChatParticipantRole {}

    public static class Updates extends TLObject {
        public List<Update> updates = new ArrayList<>();
        public List<User> users = new ArrayList<>();
        public List<Chat> chats = new ArrayList<>();
        public int date;
        public int seq;
    }

    public static abstract class Update extends TLObject {}

    public static class WebPage extends TLObject {
        public String url;
        public String display_url;
        public String type;
        public String site_name;
        public String title;
        public String description;
        public Photo photo;
        public Document document;
        public int duration;
        public String author;
        public String embed_url;
        public String embed_type;
        public int embed_w;
        public int embed_h;
        public static WebPage TLdeserialize(InputSerializedData s, int c, boolean e) { return new WebPage(); }
        public String text;
    }

    public static abstract class SendAction extends TLObject {}
    public static class TL_sendMessageTypingAction extends SendAction {}
    public static class TL_sendMessageCancelAction extends SendAction {}
    public static class TL_sendMessageRecordAudioAction extends SendAction {}
    public static class TL_sendMessageUploadAudioAction extends SendAction {
        public int progress;
    }
    public static class TL_sendMessageRecordVideoAction extends SendAction {}
    public static class TL_sendMessageUploadVideoAction extends SendAction {
        public int progress;
    }
    public static class TL_sendMessageUploadPhotoAction extends SendAction {
        public int progress;
    }
    public static class TL_sendMessageUploadDocumentAction extends SendAction {
        public int progress;
    }
    public static class TL_sendMessageGeoLocationAction extends SendAction {}
    public static class TL_sendMessageChooseContactAction extends SendAction {}
    public static class TL_sendMessageRecordRoundAction extends SendAction {}
    public static class TL_sendMessageUploadRoundAction extends SendAction {
        public int progress;
    }
    public static class TL_sendMessageRecordVideoNoteAction extends SendAction {}
    public static class TL_sendMessageUploadVideoNoteAction extends SendAction {
        public int progress;
    }
    public static class TL_sendMessageGamePlayAction extends SendAction {}
    public static class TL_sendMessageHistoryImportAction extends SendAction {
        public int progress;
    }

    public static class InputBotInlineMessageID extends TLObject {
        public long dc_id;
        public long id;
        public long access_hash;
    }

    public static class BotInlineMessage extends TLObject {}
    public static class TL_botInlineMessageMediaAuto extends BotInlineMessage {
        public String message;
        public ReplyMarkup reply_markup;
        public List<MessageEntity> entities = new ArrayList<>();
    }
    public static class TL_botInlineMessageText extends BotInlineMessage {
        public String message;
        public ReplyMarkup reply_markup;
        public List<MessageEntity> entities = new ArrayList<>();
    }
    public static class TL_botInlineMessageMediaGeo extends BotInlineMessage {
        public TLRPC.GeoPoint geo;
        public ReplyMarkup reply_markup;
    }
    public static class TL_botInlineMessageMediaVenue extends BotInlineMessage {
        public TLRPC.GeoPoint geo;
        public String title;
        public String address;
        public String provider;
        public String venue_id;
        public String venue_type;
        public ReplyMarkup reply_markup;
    }
    public static class TL_botInlineMessageMediaContact extends BotInlineMessage {
        public String phone_number;
        public String first_name;
        public String last_name;
        public String vcard;
        public ReplyMarkup reply_markup;
    }

    public static class GeoPoint extends TLObject {
        public double lat;
        public double lon;
        public int access_hash;
    }

    public static class StickerSet extends TLObject {
        public long id;
        public long access_hash;
        public String title;
        public String short_name;
        public int count;
        public boolean official;
        public boolean archived;
        public boolean masks;
        public boolean animated;
        public boolean emojis;
        public java.util.ArrayList<Document> documents = new java.util.ArrayList<>();
        public java.util.ArrayList<StickerSetCovered> covers = new java.util.ArrayList<>();
        public boolean gifs;
    }

    public static class Vector<T> extends ArrayList<T> {}

    // Missing input types for UI compilation (stubs)
    public static class InputUser extends TLObject { public long user_id; public long access_hash; }
    public static class TL_inputUser extends InputUser {}
    public static class TL_inputUserEmpty extends InputUser {}
    public static class InputPeer extends TLObject {}
    public static class TL_inputPeerUser extends InputPeer { public long user_id; public long access_hash; }
    public static class TL_inputPeerChat extends InputPeer { public long chat_id; }
    public static class TL_inputPeerChannel extends InputPeer { public long channel_id; public long access_hash; }
    public static class TL_inputPeerEmpty extends InputPeer {}
    public static class InputMedia extends TLObject {}
    public static class TL_inputMediaEmpty extends InputMedia {}
    public static class TL_inputMediaPhoto extends InputMedia { public long id; }
    public static class TL_inputMediaDocument extends InputMedia { public long id; }
    public static class TL_inputMediaGeoPoint extends InputMedia { public GeoPoint geo_point; }
    public static class InputGeoPoint extends TLObject {}
    public static class TL_inputGeoPoint extends InputGeoPoint { public double lat; public double lon; }
    public static class TL_inputGeoPointEmpty extends InputGeoPoint {}
    public static class InputPrivacyRule extends TLObject {}
    public static class InputInvoice extends TLObject {}
    public static class TL_inputInvoiceMessage extends InputInvoice {}
    public static class Poll extends TLObject { public long id; public String question; public List<PollAnswer> answers = new ArrayList<>(); public PollResults results; public boolean closed; }
    public static class PollAnswer extends TLObject { public String text; public byte[] option; }
    public static class PollResults extends TLObject { public List<PollAnswerVoters> results = new ArrayList<>(); public int total_voters; }
    public static class PollAnswerVoters extends TLObject { public byte[] option; public int voters; public boolean chosen; }
    public static class MediaArea extends TLObject {}
    public static class TL_mediaAreaChannelPost extends MediaArea { public long channel_id; public int msg_id; }
    public static class VideoSize extends TLObject { public String type; public int w; public int h; public int size; public FileLocation location; }
    public static class TodoItem extends TLObject { public int id; public String title; public boolean completed; }
    public static class TL_availableEffect extends TLObject { public long id; public String emoticon; public long document_id; }
    public static class TL_webPage extends WebPage {}
    public static class TL_inputFile extends TLObject { public long id; public int parts; public String name; public String md5_checksum; }
    public static class FileLocation extends TLObject { public int dc_id; public long volume_id; public int local_id; public long secret; public FileLocation location; }
    public static class TL_fileLocationToBeDeprecated extends FileLocation {}
    public static class TL_inputPollAnswer extends TLObject { public String text; public byte[] option; }
    public static class TL_secureFile extends TLObject {}
    public static class InputCheckPasswordSRP extends TLObject {}
    public static class TL_inputCheckPasswordSRP extends InputCheckPasswordSRP {}
    public static class TL_messages_editMessage extends TLObject {}
    public static class BotInlineResult extends TLObject {
        public Object content;
        public Object thumb; public String id; public String type; public String title; public Document document; public Photo photo; }
    public static class TL_inputRichMessage extends TLObject {}
    public static class EmojiStatus extends TLObject {}
    public static class TL_emojiStatus extends EmojiStatus {}
    public static class TL_emojiStatusEmpty extends EmojiStatus {}
    public static class EncryptedChat extends TLObject { public int id; public long access_hash; public byte[] auth_key; public byte[] key_hash; }
    public static class InputEncryptedFile extends TLObject {}
    public static class TL_inputEncryptedFile extends InputEncryptedFile {}
    public static class TL_inputEncryptedFileEmpty extends InputEncryptedFile {}
    public static class InputFileLocation extends TLObject {}
    public static class TL_inputFileLocation extends InputFileLocation {}
    public static class InputWebFileLocation extends TLObject {}
    public static class TL_inputWebFileLocation extends InputWebFileLocation {}
    public static class InputReplyTo extends TLObject {}
    public static class TL_inputReplyToMessage extends InputReplyTo { public int reply_to_msg_id; }
    public static class MessagesFilter extends TLObject {}
    public static class ProfileTab extends TLObject {}
    public static class StoryView extends TLObject {}
    public static class StoryViews extends TLObject { public List<StoryView> views = new ArrayList<>(); }
    public static class StoryViewsList extends TLObject {}
    public static class TL_attachMenuBot extends TLObject {}
    public static class TL_chatInviteImporter extends TLObject { public long user_id; public int date; }
    public static class TL_fileHash extends TLObject { public String hash; public int offset; public int limit; }
    public static class TL_messages_chatInviteImporters extends TLObject {}
    public static class TL_messages_forwardMessages extends TLObject {}
    public static class TL_messages_sendInlineBotResult extends TLObject {}
    public static class TL_messages_sendMedia extends TLObject { public InputPeer peer; public InputMedia media; public String message; public long random_id; }
    public static class TL_messages_sendMessage extends TLObject { public InputPeer peer; public String message; public long random_id; public TLRPC.Peer peer2; }
    public static class TL_messages_sendMultiMedia extends TLObject { public InputPeer peer; public List<TL_messages_sendMedia> multi_media = new ArrayList<>(); }
    public static class TL_photoPathSize extends PhotoSize { public byte[] bytes; }
    public static class TL_photoStrippedSize extends PhotoSize { public byte[] bytes; }
    public static class TL_searchResultPosition extends TLObject {}
    public static class TL_updatePendingJoinRequests extends TLObject {}
    public static class TL_updateStory extends TLObject {}
    public static class TL_upload_cdnFile extends TLObject {}
    public static class TL_upload_file extends TLObject { public int type; public int mtime; public byte[] bytes; }
    public static class TL_upload_webFile extends TLObject {}
    public static class TL_videoSizeStickerMarkup extends VideoSize {}
    public static class UserFull extends TLObject { public User user; public String about; public Photo personal_photo; public Photo fallback_photo; }
    public static class EncryptedFile extends TLObject {}
    public static class TL_inputMediaGame extends InputMedia {}
    public static class TL_inputMediaPoll extends InputMedia { public Poll poll; }
    public static class TL_messageMediaPoll extends MessageMedia { public Poll poll; public PollResults results; }
    public static class TL_messages_sendInlineBotResult2 extends TLObject {}
    public static class TL_messages_getHistory extends TLObject { public InputPeer peer; public int offset_id; public int offset_date; public int add_offset; public int limit; public int max_id; public int min_id; public long hash; }
    public static class TL_messages_Messages extends TLObject {}
    public static class TL_messages_ChannelMessages extends TLObject {}
    public static class TL_updates extends Updates {}
    public static class TL_updateShort extends TLObject {}

    public static class StickerSetCovered extends TLObject {
        public String short_name;
        public boolean masks;
        public boolean emojis;
        public String alt;
        public StickerSet stickerset;
        public StickerSet set;
        public boolean archived;
        public boolean official;
        public int count;
        public String title;
        public long id;
        public long access_hash;
        public java.util.ArrayList<StickerSetCovered> covers = new java.util.ArrayList<>();
        public StickerSetCovered cover;
    }
    public static class TL_messages_stickerSet extends TLObject { public StickerSet set; public java.util.ArrayList<Document> documents = new java.util.ArrayList<>(); public java.util.ArrayList<StickerSetCovered> packs = new java.util.ArrayList<>(); }
    public static class TL_emojiURL extends TLObject { public String url; }
    public static class InputFile extends TLObject {}
    public static class TL_emojiStatusCollectible extends TLObject {}
    public static class TL_wallPaper extends TLObject { public WallPaperSettings settings; }
    public static class TL_chatBannedRights extends TLObject {}
    public static class TL_availableReaction extends TLObject {}
    public static class StoryReaction extends TLObject {}
    public static class InputStickerSet extends TLObject { public long id; public long access_hash; public String short_name; }
    public static class TL_theme extends TLObject {}
    public static class TL_forumTopic extends TLObject {}
    public static class TL_chatAdminRights extends TLObject {}
    public static class BroadcastRevenueTransaction extends TLObject {}
    public static class TL_stories_storyViews extends TLObject {}
    public static class TL_statsGroupTopInviter extends TLObject {}
    public static class TL_statsGroupTopAdmin extends TLObject {}
    public static class TL_statsAbsValueAndPrev extends TLObject {}
    public static class TL_starsRevenueStatus extends TLObject {}
    public static class Tl_starsRating extends TLObject {}
    public static class TL_peerColorCollectible extends TLObject {}
    public static class TL_password extends TLObject {}
    public static class TLParseException extends TLObject {}
    public static class TL_messages_getMyStickers extends TLObject {}
    public static class TL_megagroupStats extends TLObject {}
    public static class TL_help_termsOfService extends TLObject {}
    public static class GroupCallParticipant extends TLObject {}
    public static class TL_sponsoredPeer extends TLObject {}
    public static class TL_messages_emojiGroups extends TLObject {}
    public static class TL_username extends TLObject {}
    public static class TL_updateGroupCallParticipants extends TLObject {}
    public static class GroupCall extends TLObject {}
    public static class WallPaper extends TLObject { public WallPaperSettings settings; }
    public static class TL_premiumSubscriptionOption extends TLObject {}
    public static class TL_groupCallParticipantVideo extends TLObject {}
    public static class InputGroupCall extends TLObject {}
    public static class ChannelParticipant extends TLObject {}
    public static class WebDocument extends TLObject {}
    public static class TL_updateGroupCall extends TLObject {}
    public static class TL_payments_paymentReceiptStars extends TLObject {}
    public static class TL_messageActionPrizeStars extends TLObject {}
    public static class TL_messageActionPaymentRefunded extends TLObject {}
    public static class Dialog extends TLObject {}
    public static class ThemeSettings extends TLObject {}
    public static class TL_contact extends TLObject {}
    public static class TL_help_appUpdate extends TLObject {}
    public static class ResultCallback extends TLObject {}
    public static class ChatTheme extends TLObject {}
    public static class TL_topPeer extends TLObject {}
    public static class TL_starGiftUnique extends TLObject {}
    public static class RequestPeerType extends TLObject {}
    public static class RecentMeUrl extends TLObject {}
    public static class InputChatTheme extends TLObject {}
    public static class TL_premiumGiftOption extends TLObject {}
    public static class TL_poll extends TLObject {}
    public static class TL_messageActionGiftTon extends TLObject {}
    public static class TL_attachMenuBotIcon extends TLObject {}
    public static class DraftMessage extends TLObject {}
    public static class TL_help_premiumPromo extends TLObject {}
    public static class TL_channels_sendAsPeers extends TLObject {}
    public static class TL_attachMenuBots extends TLObject {}
    public static class TL_messages_allStickers extends TLObject {}
    public static class TL_messageMediaVenue extends MessageMedia {}
    public static class messages_Messages extends TLObject {}
    public static class DecryptedMessage extends TLObject {}
    public static class TL_updateEncryption extends TLObject {}
    public static class TL_textWithEntities extends TLObject {}
    public static class TL_messages_stickerSetInstallResultArchive extends TLObject {}
    public static class TL_messages_sendEncryptedMultiMedia extends TLObject {}
    public static class AttachMenuBots extends TLObject {}
    public static class AttachMenuPeerType extends TLObject {}
    public static class BaseTheme extends TLObject {}
    public static class Bool extends TLObject {}
    public static class BotApp extends TLObject {}
    public static class BotCommand extends TLObject {}
    public static class ChannelParticipantsFilter extends TLObject {}
    public static class ChatInvite extends TLObject {}
    public static class ChatReactions extends TLObject {}
    public static class DecryptedMessageAction extends TLObject {}
    public static class DialogPeer extends TLObject {}
    public static class DisallowedGiftsSettings extends TLObject {}
    public static class EmojiGameInfo extends TLObject {}
    public static class EmojiGroup extends TLObject { public java.util.ArrayList<String> emoticons = new java.util.ArrayList<>(); }
    public static class EmojiKeyword extends TLObject {}
    public static class EncryptedMessage extends TLObject {}
    public static class EphemeralMessage extends TLObject {}
    public static class ExportedChatInvite extends TLObject {}
    public static class ForumTopic extends TLObject {}
    public static class GlobalPrivacySettings extends TLObject {}
    public static class InputChannel extends TLObject {}
    public static class InputChatPhoto extends TLObject {}
    public static class InputDialogPeer extends TLObject {}
    public static class InputPhoto extends TLObject {
        public long id;
        public long access_hash;
        public byte[] file_reference = new byte[0];
    }
    public static class InputStorePaymentPurpose extends TLObject {}
    public static class InputWallPaper extends TLObject {}
    public static class JSONValue extends TLObject {}
    public static class LangPackString extends TLObject { public String key; public String value; public String zero_value; public String one_value; public String two_value; public String few_value; public String many_value; public String other_value; }
    public static class TL_langPackString extends LangPackString {}
    public static class TL_langPackStringDeleted extends LangPackString {}
    public static class TL_langPackStringPluralized extends LangPackString {
        public String zero_value;
        public String one_value;
        public String two_value;
        public String few_value;
        public String many_value;
        public String other_value;
    }
    public static class MessageAction extends TLObject {}
    public static class MessageExtendedMedia extends TLObject {}
    public static class MessageFwdHeader extends TLObject {
        public Peer saved_from_peer;
        public int saved_from_msg_id;
        public Peer from_id;
        public int channel_post;}
    public static class MessagePeerReaction extends TLObject {}
    public static class MessagePeerVote extends TLObject {}
    public static class MessageReactions extends TLObject {}
    public static class MessageReactor extends TLObject {}
    public static class MessageReplies extends TLObject {}
    public static class MessageReplyHeader extends TLObject {}
    public static class NotificationSound extends TLObject {}
    public static class PasswordKdfAlgo extends TLObject {}
    public static class PaymentForm extends TLObject {}
    public static class PaymentReceipt extends TLObject {}
    public static class PeerColor extends TLObject {}
    public static class PeerNotifySettings extends TLObject {}
    public static class PhoneCallDiscardReason extends TLObject {}
    public static class PrivacyRule extends TLObject {}
    public static class RestrictionReason extends TLObject {}
    public static class SearchPostsFlood extends TLObject {}
    public static class SecurePasswordKdfAlgo extends TLObject {}
    public static class SecureValueType extends TLObject {}
    public static class SendMessageAction extends TLObject {}
    public static class SuggestedPost extends TLObject {}
    public static class TL_accountDaysTTL extends TLObject {}
    public static class TL_account_reorderProfileTabs extends TLObject {}
    public static class TL_account_saveMusic extends TLObject {}
    public static class TL_account_setMainProfileTab extends TLObject {}
    public static class TL_attachMenuBot_layer162 extends TLObject {}
    public static class TL_attachMenuBotsBot extends TLObject {}
    public static class TL_attachMenuBotsNotModified extends TLObject {}
    public static class TL_attachMenuPeerTypeBotPM extends TLObject {}
    public static class TL_attachMenuPeerTypeBroadcast extends TLObject {}
    public static class TL_attachMenuPeerTypeChat extends TLObject {}
    public static class TL_attachMenuPeerTypePM extends TLObject {}
    public static class TL_attachMenuPeerTypeSameBotPM extends TLObject {}
    public static class TL_auth_acceptLoginToken extends TLObject {}
    public static class TL_auth_authorization extends TLObject {}
    public static class TL_auth_authorizationSignUpRequired extends TLObject {}
    public static class TL_auth_cancelCode extends TLObject {}
    public static class TL_auth_checkPassword extends TLObject {}
    public static class TL_auth_checkRecoveryPassword extends TLObject {}
    public static class TL_auth_codeTypeCall extends TLObject {}
    public static class TL_auth_codeTypeFlashCall extends TLObject {}
    public static class TL_auth_codeTypeFragmentSms extends TLObject {}
    public static class TL_auth_codeTypeMissedCall extends TLObject {}
    public static class TL_auth_codeTypeSms extends TLObject {}
    public static class TL_auth_logOut extends TLObject {}
    public static class TL_auth_loggedOut extends TLObject {}
    public static class TL_auth_passwordRecovery extends TLObject {}
    public static class TL_auth_recoverPassword extends TLObject {}
    public static class TL_auth_reportMissingCode extends TLObject {}
    public static class TL_auth_requestFirebaseSms extends TLObject {}
    public static class TL_auth_requestPasswordRecovery extends TLObject {}
    public static class TL_auth_resendCode extends TLObject {}
    public static class TL_auth_resetAuthorizations extends TLObject {}
    public static class TL_auth_resetLoginEmail extends TLObject {}
    public static class TL_auth_sendCode extends TLObject {}
    public static class TL_auth_sentCode extends TLObject {}
    public static class TL_auth_sentCodePaymentRequired extends TLObject {}
    public static class TL_auth_sentCodeSuccess extends TLObject {}
    public static class TL_auth_sentCodeTypeApp extends TLObject {}
    public static class TL_auth_sentCodeTypeCall extends TLObject {}
    public static class TL_auth_sentCodeTypeEmailCode extends TLObject {}
    public static class TL_auth_sentCodeTypeFirebaseSms extends TLObject {}
    public static class TL_auth_sentCodeTypeFlashCall extends TLObject {}
    public static class TL_auth_sentCodeTypeFragmentSms extends TLObject {}
    public static class TL_auth_sentCodeTypeMissedCall extends TLObject {}
    public static class TL_auth_sentCodeTypeSetUpEmailRequired extends TLObject {}
    public static class TL_auth_sentCodeTypeSms extends TLObject {}
    public static class TL_auth_sentCodeTypeSmsPhrase extends TLObject {}
    public static class TL_auth_sentCodeTypeSmsWord extends TLObject {}
    public static class TL_auth_signIn extends TLObject {}
    public static class TL_auth_signUp extends TLObject {}
    public static class TL_authorization extends TLObject {}
    public static class TL_autoDownloadSettings extends TLObject {}
    public static class TL_bankCardOpenUrl extends TLObject {}
    public static class TL_baseThemeArctic extends TLObject {}
    public static class TL_baseThemeClassic extends TLObject {}
    public static class TL_baseThemeDay extends TLObject {}
    public static class TL_baseThemeNight extends TLObject {}
    public static class TL_baseThemeTinted extends TLObject {}
    public static class TL_boolFalse extends TLObject {}
    public static class TL_boolTrue extends TLObject {}
    public static class TL_botInlineMediaResult extends TLObject {}
    public static class TL_botInlineMessageMediaInvoice extends TLObject {}
    public static class TL_botInlineMessageMediaWebPage extends TLObject {}
    public static class TL_botInlineMessageRichMessage extends TLObject {}
    public static class TL_channel extends TLObject {}
    public static class TL_channelAdminLogEvent extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeAbout extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeAvailableReactions extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeBackgroundEmoji extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeColor extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeEmojiStatus extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeEmojiStickerSet extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeHistoryTTL extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeLinkedChat extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeLocation extends TLObject {}
    public static class TL_channelAdminLogEventActionChangePeerColor extends TLObject {}
    public static class TL_channelAdminLogEventActionChangePhoto extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeProfilePeerColor extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeStickerSet extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeTheme extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeTitle extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeUsername extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeUsernames extends TLObject {}
    public static class TL_channelAdminLogEventActionChangeWallpaper extends TLObject {}
    public static class TL_channelAdminLogEventActionCreateTopic extends TLObject {}
    public static class TL_channelAdminLogEventActionDefaultBannedRights extends TLObject {}
    public static class TL_channelAdminLogEventActionDeleteMessage extends TLObject {}
    public static class TL_channelAdminLogEventActionDeleteTopic extends TLObject {}
    public static class TL_channelAdminLogEventActionDiscardGroupCall extends TLObject {}
    public static class TL_channelAdminLogEventActionEditMessage extends TLObject {}
    public static class TL_channelAdminLogEventActionEditTopic extends TLObject {}
    public static class TL_channelAdminLogEventActionExportedInviteDelete extends TLObject {}
    public static class TL_channelAdminLogEventActionExportedInviteEdit extends TLObject {}
    public static class TL_channelAdminLogEventActionExportedInviteRevoke extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantEditRank extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantInvite extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantJoin extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantJoinByInvite extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantJoinByRequest extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantLeave extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantMute extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantSubExtend extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantToggleAdmin extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantToggleBan extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantUnmute extends TLObject {}
    public static class TL_channelAdminLogEventActionParticipantVolume extends TLObject {}
    public static class TL_channelAdminLogEventActionPinTopic extends TLObject {}
    public static class TL_channelAdminLogEventActionSendMessage extends TLObject {}
    public static class TL_channelAdminLogEventActionStartGroupCall extends TLObject {}
    public static class TL_channelAdminLogEventActionStopPoll extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleAntiSpam extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleAutotranslation extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleForum extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleGroupCallSetting extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleInvites extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleNoForwards extends TLObject {}
    public static class TL_channelAdminLogEventActionTogglePreHistoryHidden extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleSignatureProfiles extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleSignatures extends TLObject {}
    public static class TL_channelAdminLogEventActionToggleSlowMode extends TLObject {}
    public static class TL_channelAdminLogEventActionUpdatePinned extends TLObject {}
    public static class TL_channelAdminLogEventsFilter extends TLObject {}
    public static class TL_channelForbidden extends TLObject {}
    public static class TL_channelFull extends TLObject {}
    public static class TL_channelLocation extends TLObject {}
    public static class TL_channelLocationEmpty extends TLObject {}
    public static class TL_channelMessagesFilterEmpty extends TLObject {}
    public static class TL_channelParticipant extends TLObject {}
    public static class TL_channelParticipantAdmin extends TLObject {}
    public static class TL_channelParticipantBanned extends TLObject {}
    public static class TL_channelParticipantCreator extends TLObject {}
    public static class TL_channelParticipantSelf extends TLObject {}
    public static class TL_channelParticipantsAdmins extends TLObject {}
    public static class TL_channelParticipantsBanned extends TLObject {}
    public static class TL_channelParticipantsBots extends TLObject {}
    public static class TL_channelParticipantsContacts extends TLObject {}
    public static class TL_channelParticipantsKicked extends TLObject {}
    public static class TL_channelParticipantsMentions extends TLObject {}
    public static class TL_channelParticipantsRecent extends TLObject {}
    public static class TL_channelParticipantsSearch extends TLObject {}
    public static class TL_channel_layer48 extends TLObject {}
    public static class TL_channel_layer67 extends TLObject {}
    public static class TL_channel_layer72 extends TLObject {}
    public static class TL_channel_layer77 extends TLObject {}
    public static class TL_channel_layer92 extends TLObject {}
    public static class TL_channel_old extends TLObject {}
    public static class TL_channels_adminLogResults extends TLObject {}
    public static class TL_channels_channelParticipant extends TLObject {}
    public static class TL_channels_channelParticipants extends TLObject {}
    public static class TL_channels_checkSearchPostsFlood extends TLObject {}
    public static class TL_channels_checkUsername extends TLObject {}
    public static class TL_channels_convertToGigagroup extends TLObject {}
    public static class TL_channels_createChannel extends TLObject {}
    public static class TL_channels_deactivateAllUsernames extends TLObject {}
    public static class TL_channels_deleteChannel extends TLObject {}
    public static class TL_channels_deleteHistory extends TLObject {}
    public static class TL_channels_deleteMessages extends TLObject {}
    public static class TL_channels_deleteParticipantHistory extends TLObject {}
    public static class TL_channels_editAdmin extends TLObject {}
    public static class TL_channels_editBanned extends TLObject {}
    public static class TL_channels_editCreator extends TLObject {}
    public static class TL_channels_editLocation extends TLObject {}
    public static class TL_channels_editPhoto extends TLObject {}
    public static class TL_channels_editTitle extends TLObject {}
    public static class TL_channels_exportMessageLink extends TLObject {}
    public static class TL_channels_getAdminLog extends TLObject {}
    public static class TL_channels_getAdminedPublicChannels extends TLObject {}
    public static class TL_channels_getChannelRecommendations extends TLObject {}
    public static class TL_channels_getChannels extends TLObject {}
    public static class TL_channels_getFullChannel extends TLObject {}
    public static class TL_channels_getFutureCreatorAfterLeave extends TLObject {}
    public static class TL_channels_getGroupsForDiscussion extends TLObject {}
    public static class TL_channels_getInactiveChannels extends TLObject {}
    public static class TL_channels_getMessageAuthor extends TLObject {}
    public static class TL_channels_getMessages extends TLObject {}
    public static class TL_channels_getParticipant extends TLObject {}
    public static class TL_channels_getParticipants extends TLObject {}
    public static class TL_channels_getSendAs extends TLObject {}
    public static class TL_channels_inviteToChannel extends TLObject {}
    public static class TL_channels_joinChannel extends TLObject {}
    public static class TL_channels_leaveChannel extends TLObject {}
    public static class TL_channels_readHistory extends TLObject {}
    public static class TL_channels_readMessageContents extends TLObject {}
    public static class TL_channels_reorderProfileTabs extends TLObject {}
    public static class TL_channels_reorderUsernames extends TLObject {}
    public static class TL_channels_reportAntiSpamFalsePositive extends TLObject {}
    public static class TL_channels_reportSpam extends TLObject {}
    public static class TL_channels_restrictSponsoredMessages extends TLObject {}
    public static class TL_channels_searchPosts extends TLObject {}
    public static class TL_channels_setDiscussionGroup extends TLObject {}
    public static class TL_channels_setEmojiStickers extends TLObject {}
    public static class TL_channels_setMainProfileTab extends TLObject {}
    public static class TL_channels_setStickers extends TLObject {}
    public static class TL_channels_sponsoredMessageReportResultAdsHidden extends TLObject {}
    public static class TL_channels_sponsoredMessageReportResultChooseOption extends TLObject {}
    public static class TL_channels_sponsoredMessageReportResultReported extends TLObject {}
    public static class TL_channels_toggleAntiSpam extends TLObject {}
    public static class TL_channels_toggleAutotranslation extends TLObject {}
    public static class TL_channels_toggleForum extends TLObject {}
    public static class TL_channels_toggleJoinRequest extends TLObject {}
    public static class TL_channels_toggleJoinToSend extends TLObject {}
    public static class TL_channels_toggleParticipantsHidden extends TLObject {}
    public static class TL_channels_togglePreHistoryHidden extends TLObject {}
    public static class TL_channels_toggleSignatures extends TLObject {}
    public static class TL_channels_toggleSlowMode extends TLObject {}
    public static class TL_channels_toggleUsername extends TLObject {}
    public static class TL_channels_toggleViewForumAsMessages extends TLObject {}
    public static class TL_channels_updateColor extends TLObject {}
    public static class TL_channels_updateEmojiStatus extends TLObject {}
    public static class TL_channels_updateUsername extends TLObject {}
    public static class TL_chat extends TLObject {}
    public static class TL_chatAdminWithInvites extends TLObject {}
    public static class TL_chatChannelParticipant extends TLObject {}
    public static class TL_chatEmpty extends TLObject {}
    public static class TL_chatForbidden extends TLObject {}
    public static class TL_chatFull extends TLObject {}
    public static class TL_chatInviteExported extends TLObject {}
    public static class TL_chatInviteJoinResultOk extends TLObject {}
    public static class TL_chatInviteJoinResultWebView extends TLObject {}
    public static class TL_chatInvitePeek extends TLObject {}
    public static class TL_chatInvitePublicJoinRequests extends TLObject {}
    public static class TL_chatOnlines extends TLObject {}
    public static class TL_chatParticipant extends TLObject {}
    public static class TL_chatParticipantAdmin extends TLObject {}
    public static class TL_chatParticipantCreator extends TLObject {}
    public static class TL_chatParticipants extends TLObject {}
    public static class TL_chatParticipantsForbidden extends TLObject {}
    public static class TL_chatPhotoEmpty extends TLObject {}
    public static class TL_chatReactionsAll extends TLObject {}
    public static class TL_chatReactionsNone extends TLObject {}
    public static class TL_chatReactionsSome extends TLObject {}
    public static class TL_chatTheme extends TLObject {}
    public static class TL_chatThemeUniqueGift extends TLObject {}
    public static class TL_chat_layer92 extends TLObject {}
    public static class TL_chat_old extends TLObject {}
    public static class TL_chat_old2 extends TLObject {}
    public static class TL_checkPaidAuth extends TLObject {}
    public static class TL_codeSettings extends TLObject {}
    public static class TL_community extends TLObject {}
    public static class TL_communityForbidden extends TLObject {}
    public static class TL_communityFull extends TLObject {}
    public static class TL_config extends TLObject {}
    public static class TL_contacts_acceptContact extends TLObject {}
    public static class TL_contacts_block extends TLObject {}
    public static class TL_contacts_blockFromReplies extends TLObject {}
    public static class TL_contacts_blocked extends TLObject {}
    public static class TL_contacts_blockedSlice extends TLObject {}
    public static class TL_contacts_exportContactToken extends TLObject {}
    public static class TL_contacts_found extends TLObject {}
    public static class TL_contacts_getBlocked extends TLObject {}
    public static class TL_contacts_getSponsoredPeers extends TLObject {}
    public static class TL_contacts_getTopPeers extends TLObject {}
    public static class TL_contacts_importContactToken extends TLObject {}
    public static class TL_contacts_importContacts extends TLObject {}
    public static class TL_contacts_resetTopPeerRating extends TLObject {}
    public static class TL_contacts_resolvePhone extends TLObject {}
    public static class TL_contacts_resolveUsername extends TLObject {}
    public static class TL_contacts_resolvedPeer extends TLObject {}
    public static class TL_contacts_search extends TLObject {}
    public static class TL_contacts_setBlocked extends TLObject {}
    public static class TL_contacts_sponsoredPeers extends TLObject {}
    public static class TL_contacts_sponsoredPeersEmpty extends TLObject {}
    public static class TL_contacts_toggleTopPeers extends TLObject {}
    public static class TL_contacts_topPeers extends TLObject {}
    public static class TL_contacts_topPeersDisabled extends TLObject {}
    public static class TL_contacts_unblock extends TLObject {}
    public static class TL_dataJSON extends TLObject {}
    public static class TL_decryptedMessage extends TLObject {}
    public static class TL_decryptedMessageActionAbortKey extends TLObject {}
    public static class TL_decryptedMessageActionAcceptKey extends TLObject {}
    public static class TL_decryptedMessageActionCommitKey extends TLObject {}
    public static class TL_decryptedMessageActionDeleteMessages extends TLObject {}
    public static class TL_decryptedMessageActionFlushHistory extends TLObject {}
    public static class TL_decryptedMessageActionNoop extends TLObject {}
    public static class TL_decryptedMessageActionNotifyLayer extends TLObject {}
    public static class TL_decryptedMessageActionReadMessages extends TLObject {}
    public static class TL_decryptedMessageActionRequestKey extends TLObject {}
    public static class TL_decryptedMessageActionResend extends TLObject {}
    public static class TL_decryptedMessageActionScreenshotMessages extends TLObject {}
    public static class TL_decryptedMessageActionSetMessageTTL extends TLObject {}
    public static class TL_decryptedMessageActionTyping extends TLObject {}
    public static class TL_decryptedMessageLayer extends TLObject {}
    public static class TL_decryptedMessageMediaAudio extends TLObject {}
    public static class TL_decryptedMessageMediaContact extends TLObject {}
    public static class TL_decryptedMessageMediaDocument extends TLObject {}
    public static class TL_decryptedMessageMediaDocument_layer8 extends TLObject {}
    public static class TL_decryptedMessageMediaEmpty extends TLObject {}
    public static class TL_decryptedMessageMediaExternalDocument extends TLObject {}
    public static class TL_decryptedMessageMediaGeoPoint extends TLObject {}
    public static class TL_decryptedMessageMediaPhoto extends TLObject {}
    public static class TL_decryptedMessageMediaVenue extends TLObject {}
    public static class TL_decryptedMessageMediaVideo extends TLObject {}
    public static class TL_decryptedMessageMediaWebPage extends TLObject {}
    public static class TL_decryptedMessageService extends TLObject {}
    public static class TL_decryptedMessage_layer45 extends TLObject {}
    public static class TL_defaultHistoryTTL extends TLObject {}
    public static class TL_deleteFactCheck extends TLObject {}
    public static class TL_dialog extends TLObject {}
    public static class TL_dialogCommunity extends TLObject {}
    public static class TL_dialogFolder extends TLObject {}
    public static class TL_dialogPeer extends TLObject {}
    public static class TL_documentAttributeCustomEmoji extends TLObject {}
    public static class TL_documentAttributeHasStickers extends TLObject {}
    public static class TL_documentAttributeImageSize extends TLObject {}
    public static class TL_documentAttributeSticker_layer55 extends TLObject {}
    public static class TL_documentAttributeVideo_layer159 extends TLObject {}
    public static class TL_documentEmpty extends TLObject {}
    public static class TL_documentEncrypted extends TLObject {}
    public static class TL_document_layer82 extends TLObject {}
    public static class TL_draftMessage extends TLObject {}
    public static class TL_draftMessageEmpty extends TLObject {}
    public static class TL_editCloseFriends extends TLObject {}
    public static class TL_editFactCheck extends TLObject {}
    public static class TL_emailVerificationCode extends TLObject {}
    public static class TL_emailVerificationGoogle extends TLObject {}
    public static class TL_emailVerifyPurposeLoginChange extends TLObject {}
    public static class TL_emailVerifyPurposeLoginSetup extends TLObject {}
    public static class TL_emojiGameDiceInfo extends TLObject {}
    public static class TL_emojiGroupGreeting extends TLObject {}
    public static class TL_emojiGroupPremium extends TLObject {}
    public static class TL_emojiKeyword extends TLObject {}
    public static class TL_emojiKeywordDeleted extends TLObject {}
    public static class TL_emojiKeywordsDifference extends TLObject {}
    public static class TL_emojiList extends TLObject {}
    public static class TL_emojiListNotModified extends TLObject {}
    public static class TL_encryptedChat extends TLObject {}
    public static class TL_encryptedChatDiscarded extends TLObject {}
    public static class TL_encryptedChatRequested extends TLObject {}
    public static class TL_encryptedChatWaiting extends TLObject {}
    public static class TL_encryptedFile extends TLObject {}
    public static class TL_ephemeral_deleteMessage extends TLObject {}
    public static class TL_ephemeral_getCallbackAnswer extends TLObject {}
    public static class TL_ephemeral_reportMessage extends TLObject {}
    public static class TL_ephemeral_sendMessage extends TLObject {}
    public static class TL_exportedContactToken extends TLObject {}
    public static class TL_exportedMessageLink extends TLObject {}
    public static class TL_factCheck extends TLObject {}
    public static class TL_fileEncryptedLocation extends TLObject {}
    public static class TL_fileLocationUnavailable extends TLObject {}
    public static class TL_fileLocation_layer82 extends TLObject {}
    public static class TL_fileLocation_layer97 extends TLObject {}
    public static class TL_folder extends TLObject {}
    public static class TL_folderPeer extends TLObject {}
    public static class TL_folders_editPeerFolders extends TLObject {}
    public static class TL_forumTopicDeleted extends TLObject {}
    public static class TL_game extends TLObject {}
    public static class TL_geoPoint extends TLObject {}
    public static class TL_geoPointEmpty extends TLObject {}
    public static class TL_getFactCheck extends TLObject {}
    public static class TL_getSavedMusic extends TLObject {}
    public static class TL_globalPrivacySettings extends TLObject {}
    public static class TL_groupCall extends TLObject {}
    public static class TL_groupCallDiscarded extends TLObject {}
    public static class TL_groupCallParticipant extends TLObject {}
    public static class TL_groupCallParticipantVideoSourceGroup extends TLObject {}
    public static class TL_help_acceptTermsOfService extends TLObject {}
    public static class TL_help_appConfig extends TLObject {}
    public static class TL_help_appConfigNotModified extends TLObject {}
    public static class TL_help_countriesList extends TLObject {}
    public static class TL_help_country extends TLObject {}
    public static class TL_help_countryCode extends TLObject {}
    public static class TL_help_deepLinkInfo extends TLObject {}
    public static class TL_help_dismissSuggestion extends TLObject {}
    public static class TL_help_getAppConfig extends TLObject {}
    public static class TL_help_getAppUpdate extends TLObject {}
    public static class TL_help_getCountriesList extends TLObject {}
    public static class TL_help_getDeepLinkInfo extends TLObject {}
    public static class TL_help_getNearestDc extends TLObject {}
    public static class TL_help_getPeerColors extends TLObject {}
    public static class TL_help_getPeerProfileColors extends TLObject {}
    public static class TL_help_getPremiumPromo extends TLObject {}
    public static class TL_help_getPromoData extends TLObject {}
    public static class TL_help_getRecentMeUrls extends TLObject {}
    public static class TL_help_getSupport extends TLObject {}
    public static class TL_help_getTermsOfServiceUpdate extends TLObject {}
    public static class TL_help_hidePromoData extends TLObject {}
    public static class TL_help_noAppUpdate extends TLObject {}
    public static class TL_help_peerColorOption extends TLObject {}
    public static class TL_help_peerColorProfileSet extends TLObject {}
    public static class TL_help_peerColorSet extends TLObject {}
    public static class TL_help_peerColors extends TLObject {}
    public static class TL_help_promoData extends TLObject {}
    public static class TL_help_promoDataEmpty extends TLObject {}
    public static class TL_help_recentMeUrls extends TLObject {}
    public static class TL_help_saveAppLog extends TLObject {}
    public static class TL_help_support extends TLObject {}
    public static class TL_help_termsOfServiceUpdate extends TLObject {}
    public static class TL_help_termsOfServiceUpdateEmpty extends TLObject {}
    public static class TL_inlineBotSwitchPM extends TLObject {}
    public static class TL_inputAppEvent extends TLObject {}
    public static class TL_inputChannel extends TLObject {}
    public static class TL_inputChannelEmpty extends TLObject {}
    public static class TL_inputChannelFromMessage extends TLObject {}
    public static class TL_inputChatPhoto extends TLObject {}
    public static class TL_inputChatPhotoEmpty extends TLObject {}
    public static class TL_inputChatUploadedPhoto extends TLObject {}
    public static class TL_inputCheckPasswordEmpty extends TLObject {}
    public static class TL_inputDialogPeer extends TLObject {}
    public static class TL_inputDialogPeerCommunity extends TLObject {}
    public static class TL_inputDocument extends TLObject {}
    public static class TL_inputDocumentEmpty extends TLObject {}
    public static class TL_inputDocumentFileLocation extends TLObject {}
    public static class TL_inputEmojiStatusCollectible extends TLObject {}
    public static class TL_inputEncryptedChat extends TLObject {}
    public static class TL_inputEncryptedFileBigUploaded extends TLObject {}
    public static class TL_inputEncryptedFileLocation extends TLObject {}
    public static class TL_inputEncryptedFileUploaded extends TLObject {}
    public static class TL_inputFileBig extends TLObject {}
    public static class TL_inputFileStoryDocument extends TLObject {}
    public static class TL_inputFolderPeer extends TLObject {}
    public static class TL_inputGameShortName extends TLObject {}
    public static class TL_inputGroupCall extends TLObject {}
    public static class TL_inputGroupCallSlug extends TLObject {}
    public static class TL_inputInvoiceChatInviteSubscription extends TLObject {}
    public static class TL_inputInvoicePremiumAuthCode extends TLObject {}
    public static class TL_inputInvoicePremiumGiftCode extends TLObject {}
    public static class TL_inputInvoicePremiumGiftStars extends TLObject {}
    public static class TL_inputInvoiceSlug extends TLObject {}
    public static class TL_inputInvoiceStars extends TLObject {}
    public static class TL_inputMediaContact extends TLObject {}
    public static class TL_inputMediaDice extends TLObject {}
    public static class TL_inputMediaDocumentExternal extends TLObject {}
    public static class TL_inputMediaGeoLive extends TLObject {}
    public static class TL_inputMediaPaidMedia extends TLObject {}
    public static class TL_inputMediaStakeDice extends TLObject {}
    public static class TL_inputMediaStory extends TLObject {}
    public static class TL_inputMediaTodo extends TLObject {}
    public static class TL_inputMediaUploadedDocument extends TLObject {}
    public static class TL_inputMediaUploadedPhoto extends TLObject {}
    public static class TL_inputMediaVenue extends TLObject {}
    public static class TL_inputMediaWebPage extends TLObject {}
    public static class TL_inputMessageEntityMentionName extends TLObject {}
    public static class TL_inputMessageReadMetric extends TLObject {}
    public static class TL_inputMessagesFilterChatPhotos extends TLObject {}
    public static class TL_inputMessagesFilterDocument extends TLObject {}
    public static class TL_inputMessagesFilterEmpty extends TLObject {}
    public static class TL_inputMessagesFilterGif extends TLObject {}
    public static class TL_inputMessagesFilterMusic extends TLObject {}
    public static class TL_inputMessagesFilterPhotoVideo extends TLObject {}
    public static class TL_inputMessagesFilterPhotos extends TLObject {}
    public static class TL_inputMessagesFilterPinned extends TLObject {}
    public static class TL_inputMessagesFilterPoll extends TLObject {}
    public static class TL_inputMessagesFilterRoundVoice extends TLObject {}
    public static class TL_inputMessagesFilterUrl extends TLObject {}
    public static class TL_inputMessagesFilterVideo extends TLObject {}
    public static class TL_inputNotifyBroadcasts extends TLObject {}
    public static class TL_inputNotifyChats extends TLObject {}
    public static class TL_inputNotifyCommunity extends TLObject {}
    public static class TL_inputNotifyForumTopic extends TLObject {}
    public static class TL_inputNotifyPeer extends TLObject {}
    public static class TL_inputNotifyUsers extends TLObject {}
    public static class TL_inputPaymentCredentials extends TLObject {}
    public static class TL_inputPaymentCredentialsGooglePay extends TLObject {}
    public static class TL_inputPaymentCredentialsSaved extends TLObject {}
    public static class TL_inputPeerChannelFromMessage extends TLObject {}
    public static class TL_inputPeerNotifySettings extends TLObject {}
    public static class TL_inputPeerPhotoFileLocation extends TLObject {}
    public static class TL_inputPeerSelf extends TLObject {}
    public static class TL_inputPeerUserFromMessage extends TLObject {}
    public static class TL_inputPhoto extends TLObject {}
    public static class TL_inputPhotoEmpty extends TLObject {}
    public static class TL_inputPhotoFileLocation extends TLObject {}
    public static class TL_inputPrivacyKeyAbout extends TLObject {}
    public static class TL_inputPrivacyKeyAddedByPhone extends TLObject {}
    public static class TL_inputPrivacyKeyBirthday extends TLObject {}
    public static class TL_inputPrivacyKeyChatInvite extends TLObject {}
    public static class TL_inputPrivacyKeyForwards extends TLObject {}
    public static class TL_inputPrivacyKeyNoPaidMessages extends TLObject {}
    public static class TL_inputPrivacyKeyPhoneCall extends TLObject {}
    public static class TL_inputPrivacyKeyPhoneNumber extends TLObject {}
    public static class TL_inputPrivacyKeyPhoneP2P extends TLObject {}
    public static class TL_inputPrivacyKeyProfilePhoto extends TLObject {}
    public static class TL_inputPrivacyKeySavedMusic extends TLObject {}
    public static class TL_inputPrivacyKeyStarGiftsAutoSave extends TLObject {}
    public static class TL_inputPrivacyKeyStatusTimestamp extends TLObject {}
    public static class TL_inputPrivacyKeyVoiceMessages extends TLObject {}
    public static class TL_inputPrivacyValueAllowAll extends TLObject {}
    public static class TL_inputPrivacyValueAllowBots extends TLObject {}
    public static class TL_inputPrivacyValueAllowChatParticipants extends TLObject {}
    public static class TL_inputPrivacyValueAllowCloseFriends extends TLObject {}
    public static class TL_inputPrivacyValueAllowContacts extends TLObject {}
    public static class TL_inputPrivacyValueAllowPremium extends TLObject {}
    public static class TL_inputPrivacyValueAllowUsers extends TLObject {}
    public static class TL_inputPrivacyValueDisallowAll extends TLObject {}
    public static class TL_inputPrivacyValueDisallowBots extends TLObject {}
    public static class TL_inputPrivacyValueDisallowChatParticipants extends TLObject {}
    public static class TL_inputPrivacyValueDisallowUsers extends TLObject {}
    public static class TL_inputQuickReplyShortcut extends TLObject {}
    public static class TL_inputQuickReplyShortcutId extends TLObject {}
    public static class TL_inputReplyToEphemeralMessage extends TLObject {}
    public static class TL_inputReplyToMonoForum extends TLObject {}
    public static class TL_inputReplyToStory extends TLObject {}
    public static class TL_inputReportReasonChildAbuse extends TLObject {}
    public static class TL_inputReportReasonGeoIrrelevant extends TLObject {}
    public static class TL_inputReportReasonIllegalDrugs extends TLObject {}
    public static class TL_inputReportReasonPersonalDetails extends TLObject {}
    public static class TL_inputReportReasonPornography extends TLObject {}
    public static class TL_inputReportReasonSpam extends TLObject {}
    public static class TL_inputReportReasonViolence extends TLObject {}
    public static class TL_inputSecureFileLocation extends TLObject {}
    public static class TL_inputSingleMedia extends TLObject {}
    public static class TL_inputStickerSetAnimatedEmoji extends TLObject {}
    public static class TL_inputStickerSetDice extends TLObject {}
    public static class TL_inputStickerSetEmojiChannelDefaultStatuses extends TLObject {}
    public static class TL_inputStickerSetEmojiDefaultStatuses extends TLObject {}
    public static class TL_inputStickerSetEmojiDefaultTopicIcons extends TLObject {}
    public static class TL_inputStickerSetEmojiGenericAnimations extends TLObject {}
    public static class TL_inputStickerSetEmpty extends TLObject {}
    public static class TL_inputStickerSetID extends InputStickerSet { public long id; public long access_hash; }
    public static class TL_inputStickerSetItem extends InputStickerSet { public long id; public long access_hash; }
    public static class TL_inputStickerSetPremiumGifts extends TLObject {}
    public static class TL_inputStickerSetShortName extends TLObject {}
    public static class TL_inputStickerSetThumb extends TLObject {}
    public static class TL_inputStickerSetTonGifts extends TLObject {}
    public static class TL_inputStickeredMediaDocument extends TLObject { public TL_inputDocument id; }
    public static class TL_inputStickeredMediaPhoto extends TLObject { public TL_inputPhoto id; }
    public static class TL_inputStorePaymentAuthCode extends TLObject {}
    public static class TL_inputStorePaymentGiftPremium extends TLObject {}
    public static class TL_inputStorePaymentPremiumGiftCode extends TLObject {}
    public static class TL_inputStorePaymentPremiumSubscription extends TLObject {}
    public static class TL_inputStorePaymentStarsGift extends TLObject {}
    public static class TL_inputStorePaymentStarsGiveaway extends TLObject {}
    public static class TL_inputStorePaymentStarsTopup extends TLObject {}
    public static class TL_inputTheme extends TLObject {}
    public static class TL_inputThemeSettings extends TLObject {}
    public static class TL_inputThemeSlug extends TLObject {}
    public static class TL_inputUserFromMessage extends TLObject {}
    public static class TL_inputUserSelf extends TLObject {}
    public static class TL_inputWallPaper extends TLObject {}
    public static class TL_inputWallPaperNoFile extends TLObject {}
    public static class TL_inputWallPaperSlug extends TLObject {}
    public static class TL_inputWebFileGeoPointLocation extends TLObject {}
    public static class TL_jsonArray extends TLObject {}
    public static class TL_jsonBool extends TLObject {}
    public static class TL_jsonNull extends TLObject {}
    public static class TL_jsonNumber extends TLObject {}
    public static class TL_jsonObject extends TLObject {}
    public static class TL_jsonObjectValue extends TLObject {}
    public static class TL_jsonString extends TLObject {}
    public static class TL_keyboardButton extends TLObject {}
    public static class TL_keyboardButtonBuy extends TLObject {}
    public static class TL_keyboardButtonCallback extends TLObject {}
    public static class TL_keyboardButtonCopy extends TLObject {}
    public static class TL_keyboardButtonGame extends TLObject {}
    public static class TL_keyboardButtonRequestPeer extends TLObject {}
    public static class TL_keyboardButtonRow extends TLObject {}
    public static class TL_keyboardButtonSwitchInline extends TLObject {}
    public static class TL_keyboardButtonUrl extends TLObject {}
    public static class TL_keyboardButtonUrlAuth extends TLObject {}
    public static class TL_keyboardButtonUserProfile extends TLObject {}
    public static class TL_labeledPrice extends TLObject {}
    public static class TL_langPackDifference extends TLObject { public String lang_code; public int from_version; public int version; public java.util.ArrayList<LangPackString> strings = new java.util.ArrayList<>(); }
    public static class TL_langPackLanguage extends TLObject {}

    public static class TL_langpack_getDifference extends TLObject {}
    public static class TL_langpack_getLangPack extends TLObject {}
    public static class TL_langpack_getLanguage extends TLObject {}
    public static class TL_langpack_getLanguages extends TLObject {}
    public static class TL_langpack_getStrings extends TLObject {}
    public static class TL_maskCoords extends TLObject {}
    public static class TL_messageActionAttachMenuBotAllowed extends TLObject {}
    public static class TL_messageActionBoostApply extends TLObject {}
    public static class TL_messageActionBotAllowed extends TLObject {}
    public static class TL_messageActionChangeCommunity extends TLObject {}
    public static class TL_messageActionChangeCreator extends TLObject {}
    public static class TL_messageActionChannelCreate extends TLObject {}
    public static class TL_messageActionChannelMigrateFrom extends TLObject {}
    public static class TL_messageActionChatAddUser extends TLObject {}
    public static class TL_messageActionChatCreate extends TLObject {}
    public static class TL_messageActionChatDeletePhoto extends TLObject {}
    public static class TL_messageActionChatDeleteUser extends TLObject {}
    public static class TL_messageActionChatEditPhoto extends TLObject {}
    public static class TL_messageActionChatEditTitle extends TLObject {}
    public static class TL_messageActionChatJoinedByLink extends TLObject {}
    public static class TL_messageActionChatJoinedByRequest extends TLObject {}
    public static class TL_messageActionChatMigrateTo extends TLObject {}
    public static class TL_messageActionConferenceCall extends TLObject {}
    public static class TL_messageActionContactSignUp extends TLObject {}
    public static class TL_messageActionCreatedBroadcastList extends TLObject {}
    public static class TL_messageActionCustomAction extends TLObject {}
    public static class TL_messageActionEmpty extends TLObject {}
    public static class TL_messageActionGameScore extends TLObject {}
    public static class TL_messageActionGeoProximityReached extends TLObject {}
    public static class TL_messageActionGiftCode extends TLObject {}
    public static class TL_messageActionGiftPremium extends TLObject {}
    public static class TL_messageActionGiftStars extends TLObject {}
    public static class TL_messageActionGroupCall extends TLObject {}
    public static class TL_messageActionGroupCallScheduled extends TLObject {}
    public static class TL_messageActionHistoryClear extends TLObject {}
    public static class TL_messageActionInviteToGroupCall extends TLObject {}
    public static class TL_messageActionLoginUnknownLocation extends TLObject {}
    public static class TL_messageActionManagedBotCreated extends TLObject {}
    public static class TL_messageActionNewCreatorPending extends TLObject {}
    public static class TL_messageActionNoForwardsRequest extends TLObject {}
    public static class TL_messageActionNoForwardsToggle extends TLObject {}
    public static class TL_messageActionPaidMessagesPrice extends TLObject {}
    public static class TL_messageActionPaidMessagesRefunded extends TLObject {}
    public static class TL_messageActionPaymentSent extends TLObject {}
    public static class TL_messageActionPaymentSentMe extends TLObject {}
    public static class TL_messageActionPhoneCall extends TLObject {}
    public static class TL_messageActionPinMessage extends TLObject {}
    public static class TL_messageActionPollAppendAnswer extends TLObject {}
    public static class TL_messageActionPollDeleteAnswer extends TLObject {}
    public static class TL_messageActionRequestedPeer extends TLObject {}
    public static class TL_messageActionScreenshotTaken extends TLObject {}
    public static class TL_messageActionSecureValuesSent extends TLObject {}
    public static class TL_messageActionSetChatTheme extends TLObject {}
    public static class TL_messageActionSetChatWallPaper extends TLObject {}
    public static class TL_messageActionSetMessagesTTL extends TLObject {}
    public static class TL_messageActionSetSameChatWallPaper extends TLObject {}
    public static class TL_messageActionStarGift extends TLObject {}
    public static class TL_messageActionStarGiftPurchaseOffer extends TLObject {}
    public static class TL_messageActionStarGiftPurchaseOfferDeclined extends TLObject {}
    public static class TL_messageActionStarGiftUnique extends TLObject {}
    public static class TL_messageActionSuggestBirthday extends TLObject {}
    public static class TL_messageActionSuggestProfilePhoto extends TLObject {}
    public static class TL_messageActionSuggestedPostApproval extends TLObject {}
    public static class TL_messageActionSuggestedPostRefund extends TLObject {}
    public static class TL_messageActionSuggestedPostSuccess extends TLObject {}
    public static class TL_messageActionTTLChange extends TLObject {}
    public static class TL_messageActionTodoAppendTasks extends TLObject {}
    public static class TL_messageActionTodoCompletions extends TLObject {}
    public static class TL_messageActionTopicCreate extends TLObject {}
    public static class TL_messageActionTopicEdit extends TLObject {}
    public static class TL_messageActionUserJoined extends TLObject {}
    public static class TL_messageActionUserUpdatedPhoto extends TLObject {}
    public static class TL_messageActionWebViewDataSent extends TLObject {}
    public static class TL_messageEmpty extends TLObject {}
    public static class TL_messageEncryptedAction extends TLObject {}
    public static class TL_messageEntityBankCard extends TLObject {}
    public static class TL_messageEntityBlockquote extends TLObject {}
    public static class TL_messageEntityBotCommand extends TLObject {}
    public static class TL_messageEntityCashtag extends TLObject {}
    public static class TL_messageEntityDiffDelete extends TLObject {}
    public static class TL_messageEntityDiffInsert extends TLObject {}
    public static class TL_messageEntityDiffReplace extends TLObject {}
    public static class TL_messageEntityEmail extends TLObject {}
    public static class TL_messageEntityFormattedDate extends TLObject {
        public long date;
        public boolean relative;
        public int flags;
    }
    public static class TL_messageEntityHashtag extends TLObject {}
    public static class TL_messageEntityMention extends TLObject {}
    public static class TL_messageEntityPhone extends TLObject {}
    public static class TL_messageEntitySpoiler extends TLObject {}
    public static class TL_messageEntityUrl extends TLObject {}
    public static class TL_messageExtendedMedia extends TLObject {}
    public static class TL_messageExtendedMediaPreview extends TLObject {}
    public static class TL_messageForwarded_old extends TLObject {}
    public static class TL_messageForwarded_old2 extends TLObject {}
    public static class TL_messageFwdHeader extends TLObject {}
    public static class TL_messageMediaContact extends MessageMedia {}
    public static class TL_messageMediaDice extends MessageMedia {}
    public static class TL_messageMediaDocument_layer68 extends MessageMedia {}
    public static class TL_messageMediaDocument_layer74 extends MessageMedia {}
    public static class TL_messageMediaDocument_old extends MessageMedia {}
    public static class TL_messageMediaGame extends MessageMedia {}
    public static class TL_messageMediaGeo extends MessageMedia {}
    public static class TL_messageMediaGeoLive extends MessageMedia {}
    public static class TL_messageMediaInvoice extends MessageMedia {}
    public static class TL_messageMediaPaidMedia extends MessageMedia {}
    public static class TL_messageMediaPhoto_layer68 extends MessageMedia {}
    public static class TL_messageMediaPhoto_layer74 extends MessageMedia {}
    public static class TL_messageMediaPhoto_old extends MessageMedia {}
    public static class TL_messageMediaStory extends MessageMedia {}
    public static class TL_messageMediaToDo extends MessageMedia {}
    public static class TL_messageMediaUnsupported extends MessageMedia {}
    public static class TL_messageMediaUnsupported_old extends MessageMedia {}
    public static class TL_messageMediaVideoStream extends MessageMedia {}
    public static class TL_messageMediaWebPage extends MessageMedia {}
    public static class TL_messagePeerReaction extends TLObject {}
    public static class TL_messagePeerVoteInputOption extends TLObject {}
    public static class TL_messageReactor extends TLObject {}
    public static class TL_messageReplies extends TLObject {}
    public static class TL_messageReplyStoryHeader extends TLObject {}
    public static class TL_messageReportOption extends TLObject {}
    public static class TL_messageService extends TLObject {}
    public static class TL_messageViews extends TLObject {}
    public static class TL_message_old extends TLObject {}
    public static class TL_message_old2 extends TLObject {}
    public static class TL_message_old3 extends TLObject {}
    public static class TL_message_old4 extends TLObject {}
    public static class TL_message_secret extends TLObject {}
    public static class TL_messages_acceptEncryption extends TLObject {}
    public static class TL_messages_acceptUrlAuth extends TLObject {}
    public static class TL_messages_addChatUser extends TLObject {}
    public static class TL_messages_addPollAnswer extends TLObject {}
    public static class TL_messages_affectedHistory extends TLObject {}
    public static class TL_messages_affectedMessages extends TLObject {}
    public static class TL_messages_appendTodoList extends TLObject {}
    public static class TL_messages_archivedStickers extends TLObject {}
    public static class TL_messages_availableEffects extends TLObject {}
    public static class TL_messages_availableEffectsNotModified extends TLObject {}
    public static class TL_messages_availableReactions extends TLObject {}
    public static class TL_messages_availableReactionsNotModified extends TLObject {}
    public static class TL_messages_botCallbackAnswer extends TLObject {}
    public static class TL_messages_botResults extends TLObject {}
    public static class TL_messages_channelMessages extends TLObject {}
    public static class TL_messages_chatAdminsWithInvites extends TLObject {}
    public static class TL_messages_chatFull extends TLObject {}
    public static class TL_messages_chats extends TLObject {}
    public static class TL_messages_chatsSlice extends TLObject {}
    public static class TL_messages_checkChatInvite extends TLObject {}
    public static class TL_messages_checkHistoryImport extends TLObject {}
    public static class TL_messages_checkHistoryImportPeer extends TLObject {}
    public static class TL_messages_checkUrlAuthMatchCode extends TLObject {}
    public static class TL_messages_checkedHistoryImportPeer extends TLObject {}
    public static class TL_messages_clearAllDrafts extends TLObject {}
    public static class TL_messages_clearRecentReactions extends TLObject {}
    public static class TL_messages_clearRecentStickers extends TLObject {}
    public static class TL_messages_clickSponsoredMessage extends TLObject {}
    public static class TL_messages_createChat extends TLObject {}
    public static class TL_messages_declineUrlAuth extends TLObject {}
    public static class TL_messages_deleteChat extends TLObject {}
    public static class TL_messages_deleteChatUser extends TLObject {}
    public static class TL_messages_deleteExportedChatInvite extends TLObject {}
    public static class TL_messages_deleteHistory extends TLObject {}
    public static class TL_messages_deleteMessages extends TLObject {}
    public static class TL_messages_deleteParticipantReaction extends TLObject {}
    public static class TL_messages_deleteParticipantReactions extends TLObject {}
    public static class TL_messages_deletePollAnswer extends TLObject {}
    public static class TL_messages_deleteRevokedExportedChatInvites extends TLObject {}
    public static class TL_messages_deleteSavedHistory extends TLObject {}
    public static class TL_messages_deleteScheduledMessages extends TLObject {}
    public static class TL_messages_dhConfig extends TLObject {}
    public static class TL_messages_dialogs extends TLObject {}
    public static class TL_messages_discardEncryption extends TLObject {}
    public static class TL_messages_discussionMessage extends TLObject {}
    public static class TL_messages_editChatAbout extends TLObject {}
    public static class TL_messages_editChatAdmin extends TLObject {}
    public static class TL_messages_editChatDefaultBannedRights extends TLObject {}
    public static class TL_messages_editChatParticipantRank extends TLObject {}
    public static class TL_messages_editChatPhoto extends TLObject {}
    public static class TL_messages_editChatTitle extends TLObject {}
    public static class TL_messages_editExportedChatInvite extends TLObject {}
    public static class TL_messages_emojiGameOutcome extends TLObject {}
    public static class TL_messages_emojiGroupsNotModified extends TLObject {}
    public static class TL_messages_exportChatInvite extends TLObject {}
    public static class TL_messages_exportedChatInvite extends TLObject {}
    public static class TL_messages_exportedChatInviteReplaced extends TLObject {}
    public static class TL_messages_exportedChatInvites extends TLObject {}
    public static class TL_messages_faveSticker extends TLObject {}
    public static class TL_messages_favedStickers extends TLObject {}
    public static class TL_messages_featuredStickers extends TLObject { public java.util.ArrayList<StickerSetCovered> sets = new java.util.ArrayList<>(); }
    public static class TL_messages_featuredStickersNotModified extends TLObject {}
    public static class TL_messages_forumTopics extends TLObject {}
    public static class TL_messages_foundStickerSets extends TLObject {}
    public static class TL_messages_foundStickers extends TLObject {}
    public static class TL_messages_getAdminsWithInvites extends TLObject {}
    public static class TL_messages_getAllDrafts extends TLObject {}
    public static class TL_messages_getAllStickers extends TLObject {}
    public static class TL_messages_getArchivedStickers extends TLObject {}
    public static class TL_messages_getAttachMenuBot extends TLObject {}
    public static class TL_messages_getAttachMenuBots extends TLObject {}
    public static class TL_messages_getAttachedStickers extends TLObject {}
    public static class TL_messages_getAvailableEffects extends TLObject {}
    public static class TL_messages_getAvailableReactions extends TLObject {}
    public static class TL_messages_getBotCallbackAnswer extends TLObject {}
    public static class TL_messages_getChatInviteImporters extends TLObject {}
    public static class TL_messages_getChats extends TLObject {}
    public static class TL_messages_getCommonChats extends TLObject {}
    public static class TL_messages_getCustomEmojiDocuments extends TLObject {}
    public static class TL_messages_getDefaultHistoryTTL extends TLObject {}
    public static class TL_messages_getDefaultTagReactions extends TLObject {}
    public static class TL_messages_getDhConfig extends TLObject {}
    public static class TL_messages_getDialogUnreadMarks extends TLObject {}
    public static class TL_messages_getDialogs extends TLObject {}
    public static class TL_messages_getDiscussionMessage extends TLObject {}
    public static class TL_messages_getEmojiGameInfo extends TLObject {}
    public static class TL_messages_getEmojiGroups extends TLObject {}
    public static class TL_messages_getEmojiKeywords extends TLObject {}
    public static class TL_messages_getEmojiKeywordsDifference extends TLObject {}
    public static class TL_messages_getEmojiProfilePhotoGroups extends TLObject {}
    public static class TL_messages_getEmojiStatusGroups extends TLObject {}
    public static class TL_messages_getEmojiStickerGroups extends TLObject {}
    public static class TL_messages_getEmojiStickers extends TLObject {}
    public static class TL_messages_getEmojiURL extends TLObject {}
    public static class TL_messages_getExportedChatInvite extends TLObject {}
    public static class TL_messages_getExportedChatInvites extends TLObject {}
    public static class TL_messages_getExtendedMedia extends TLObject {}
    public static class TL_messages_getFavedStickers extends TLObject {}
    public static class TL_messages_getFeaturedEmojiStickers extends TLObject {}
    public static class TL_messages_getFeaturedStickers extends TLObject {}
    public static class TL_messages_getFullChat extends TLObject {}
    public static class TL_messages_getInlineBotResults extends TLObject {
        public Object query;}
    public static class TL_messages_getMaskStickers extends TLObject {}
    public static class TL_messages_getMessageEditData extends TLObject {}
    public static class TL_messages_getMessageReactionsList extends TLObject {}
    public static class TL_messages_getMessageReadParticipants extends TLObject {}
    public static class TL_messages_getMessages extends TLObject {}
    public static class TL_messages_getMessagesReactions extends TLObject {}
    public static class TL_messages_getMessagesViews extends TLObject {}
    public static class TL_messages_getOldFeaturedStickers extends TLObject { public int offset; public int limit; }
    public static class TL_messages_getOnlines extends TLObject {}
    public static class TL_messages_getOutboxReadDate extends TLObject {}
    public static class TL_messages_getPaidReactionPrivacy extends TLObject {}
    public static class TL_messages_getPeerDialogs extends TLObject {}
    public static class TL_messages_getPeerSettings extends TLObject {}
    public static class TL_messages_getPinnedDialogs extends TLObject {}
    public static class TL_messages_getPollResults extends TLObject {}
    public static class TL_messages_getPollVotes extends TLObject {}
    public static class TL_messages_getQuickReplyMessages extends TLObject {}
    public static class TL_messages_getRecentLocations extends TLObject {}
    public static class TL_messages_getRecentReactions extends TLObject {}
    public static class TL_messages_getRecentStickers extends TLObject {}
    public static class TL_messages_getReplies extends TLObject {}
    public static class TL_messages_getSavedDialogs extends TLObject {}
    public static class TL_messages_getSavedDialogsByID extends TLObject {}
    public static class TL_messages_getSavedGifs extends TLObject {}
    public static class TL_messages_getSavedHistory extends TLObject {}
    public static class TL_messages_getSavedReactionTags extends TLObject {}
    public static class TL_messages_getScheduledHistory extends TLObject {}
    public static class TL_messages_getScheduledMessages extends TLObject {}
    public static class TL_messages_getSearchCounters extends TLObject {}
    public static class TL_messages_getSearchResultsCalendar extends TLObject {}
    public static class TL_messages_getSearchResultsPositions extends TLObject {}
    public static class TL_messages_getSponsoredMessages extends TLObject {}
    public static class TL_messages_getStatsURL extends TLObject {}
    public static class TL_messages_getStickerSet extends TLObject {}
    public static class TL_messages_getStickers extends TLObject {}
    public static class TL_messages_getTopReactions extends TLObject {}
    public static class TL_messages_getUnreadMentions extends TLObject {}
    public static class TL_messages_getUnreadPollVotes extends TLObject {}
    public static class TL_messages_getUnreadReactions extends TLObject {}
    public static class TL_messages_getWebPage extends TLObject {}
    public static class TL_messages_hideChatJoinRequest extends TLObject {}
    public static class TL_messages_hidePeerSettingsBar extends TLObject {}
    public static class TL_messages_historyImport extends TLObject {}
    public static class TL_messages_historyImportParsed extends TLObject {}
    public static class TL_messages_importChatInvite extends TLObject {}
    public static class TL_messages_inactiveChats extends TLObject {}
    public static class TL_messages_initHistoryImport extends TLObject {}
    public static class TL_messages_installStickerSet extends TLObject { public TL_inputStickerSetID stickerset; }
    public static class TL_messages_invitedUsers extends TLObject {}
    public static class TL_messages_markDialogUnread extends TLObject {}
    public static class TL_messages_messageReactionsList extends TLObject {}
    public static class TL_messages_messageViews extends TLObject {}
    public static class TL_messages_messages extends TLObject {}
    public static class TL_messages_messagesNotModified extends TLObject {}
    public static class TL_messages_messagesSlice extends TLObject {}
    public static class TL_messages_migrateChat extends TLObject {}
    public static class TL_messages_myStickers extends TLObject {}
    public static class TL_messages_peerDialogs extends TLObject {}
    public static class TL_messages_peerSettings extends TLObject {}
    public static class TL_messages_rateTranscribedAudio extends TLObject {}
    public static class TL_messages_reactions extends TLObject {}
    public static class TL_messages_reactionsNotModified extends TLObject {}
    public static class TL_messages_readDiscussion extends TLObject {}
    public static class TL_messages_readEncryptedHistory extends TLObject {}
    public static class TL_messages_readFeaturedStickers extends TLObject {}
    public static class TL_messages_readHistory extends TLObject {}
    public static class TL_messages_readMentions extends TLObject {}
    public static class TL_messages_readMessageContents extends TLObject {}
    public static class TL_messages_readPollVotes extends TLObject {}
    public static class TL_messages_readReactions extends TLObject {}
    public static class TL_messages_readSavedHistory extends TLObject {}
    public static class TL_messages_receivedQueue extends TLObject {}
    public static class TL_messages_recentStickers extends TLObject {}
    public static class TL_messages_reorderPinnedDialogs extends TLObject {}
    public static class TL_messages_reorderPinnedSavedDialogs extends TLObject {}
    public static class TL_messages_reorderStickerSets extends TLObject {}
    public static class TL_messages_report extends TLObject {}
    public static class TL_messages_reportEncryptedSpam extends TLObject {}
    public static class TL_messages_reportMusicListen extends TLObject {}
    public static class TL_messages_reportReaction extends TLObject {}
    public static class TL_messages_reportReadMetrics extends TLObject {}
    public static class TL_messages_reportSpam extends TLObject {}
    public static class TL_messages_reportSponsoredMessage extends TLObject {}
    public static class TL_messages_requestEncryption extends TLObject {}
    public static class TL_messages_requestUrlAuth extends TLObject {}
    public static class TL_messages_saveDefaultSendAs extends TLObject {}
    public static class TL_messages_saveDraft extends TLObject {}
    public static class TL_messages_saveGif extends TLObject {}
    public static class TL_messages_saveRecentSticker extends TLObject {}
    public static class TL_messages_savedDialogs extends TLObject {}
    public static class TL_messages_savedDialogsNotModified extends TLObject {}
    public static class TL_messages_savedDialogsSlice extends TLObject {}
    public static class TL_messages_savedGifs extends TLObject {}
    public static class TL_messages_savedReactionsTags extends TLObject {}
    public static class TL_messages_savedReactionsTagsNotModified extends TLObject {}
    public static class TL_messages_search extends TLObject {}
    public static class TL_messages_searchCounter extends TLObject {}
    public static class TL_messages_searchCustomEmoji extends TLObject {}
    public static class TL_messages_searchEmojiStickerSets extends TLObject {}
    public static class TL_messages_searchGlobal extends TLObject {}
    public static class TL_messages_searchResultsCalendar extends TLObject {}
    public static class TL_messages_searchResultsPositions extends TLObject {}
    public static class TL_messages_searchStickerSets extends TLObject {}
    public static class TL_messages_searchStickers extends TLObject {}
    public static class TL_messages_sendBotRequestedPeer extends TLObject {}
    public static class TL_messages_sendEncrypted extends TLObject {}
    public static class TL_messages_sendEncryptedFile extends TLObject {}
    public static class TL_messages_sendEncryptedService extends TLObject {}
    public static class TL_messages_sendPaidReaction extends TLObject {}
    public static class TL_messages_sendReaction extends TLObject {}
    public static class TL_messages_sendScheduledMessages extends TLObject {}
    public static class TL_messages_sendScreenshotNotification extends TLObject {}
    public static class TL_messages_sendVote extends TLObject {}
    public static class TL_messages_setChatAvailableReactions extends TLObject {}
    public static class TL_messages_setChatTheme extends TLObject {}
    public static class TL_messages_setChatWallPaper extends TLObject {}
    public static class TL_messages_setDefaultHistoryTTL extends TLObject {}
    public static class TL_messages_setEncryptedTyping extends TLObject {}
    public static class TL_messages_setHistoryTTL extends TLObject {}
    public static class TL_messages_setTyping extends TLObject {}
    public static class TL_messages_sponsoredMessages extends TLObject {}
    public static class TL_messages_startBot extends TLObject {}
    public static class TL_messages_startHistoryImport extends TLObject {}
    public static class TL_messages_stickers extends TLObject {}
    public static class TL_messages_summarizeText extends TLObject {}
    public static class TL_messages_toggleBotInAttachMenu extends TLObject {}
    public static class TL_messages_toggleDialogPin extends TLObject {}
    public static class TL_messages_toggleNoForwards extends TLObject {}
    public static class TL_messages_togglePeerTranslations extends TLObject {}
    public static class TL_messages_toggleStickerSets extends TLObject {}
    public static class TL_messages_toggleSuggestedPostApproval extends TLObject {}
    public static class TL_messages_toggleTodoCompleted extends TLObject {}
    public static class TL_messages_transcribeAudio extends TLObject {}
    public static class TL_messages_transcribedAudio extends TLObject {}
    public static class TL_messages_translateResult extends TLObject {}
    public static class TL_messages_translateRichMessage extends TLObject {}
    public static class TL_messages_translateText extends TLObject {}
    public static class TL_messages_translatedRichMessage extends TLObject {}
    public static class TL_messages_uninstallStickerSet extends TLObject {}
    public static class TL_messages_unpinAllMessages extends TLObject {}
    public static class TL_messages_updateDialogFiltersOrder extends TLObject {}
    public static class TL_messages_updatePinnedMessage extends TLObject {}
    public static class TL_messages_updateSavedReactionTag extends TLObject {}
    public static class TL_messages_uploadImportedMedia extends TLObject {}
    public static class TL_messages_uploadMedia extends TLObject {}
    public static class TL_messages_viewSponsoredMessage extends TLObject {}
    public static class TL_messages_votesList extends TLObject {}
    public static class TL_messages_webPage extends TLObject {}
    public static class TL_missingInvitee extends TLObject {}
    public static class TL_monoForumDialog extends TLObject {}
    public static class TL_nearestDc extends TLObject {}
    public static class TL_notificationSoundDefault extends TLObject {}
    public static class TL_notificationSoundLocal extends TLObject {}
    public static class TL_notificationSoundNone extends TLObject {}
    public static class TL_notificationSoundRingtone extends TLObject {}
    public static class TL_notifyBroadcasts extends TLObject {}
    public static class TL_notifyChats extends TLObject {}
    public static class TL_notifyForumTopic extends TLObject {}
    public static class TL_notifyPeer extends TLObject {}
    public static class TL_notifyUsers extends TLObject {}
    public static class TL_null extends TLObject {}
    public static class TL_outboxReadDate extends TLObject {}
    public static class TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow extends TLObject {}
    public static class TL_passwordKdfAlgoUnknown extends TLObject {}
    public static class TL_paymentFormMethod extends TLObject {}
    public static class TL_paymentRequestedInfo extends TLObject {}
    public static class TL_paymentSavedCredentialsCard extends TLObject {}
    public static class TL_payments_applyGiftCode extends TLObject {}
    public static class TL_payments_assignPlayMarketTransaction extends TLObject {}
    public static class TL_payments_bankCardData extends TLObject {}
    public static class TL_payments_canPurchaseStore extends TLObject {}
    public static class TL_payments_checkGiftCode extends TLObject {}
    public static class TL_payments_checkedGiftCode extends TLObject {}
    public static class TL_payments_clearSavedInfo extends TLObject {}
    public static class TL_payments_getBankCardData extends TLObject {}
    public static class TL_payments_getPaymentForm extends TLObject {}
    public static class TL_payments_getPaymentReceipt extends TLObject {}
    public static class TL_payments_getPremiumGiftCodeOptions extends TLObject {}
    public static class TL_payments_getStarsRevenueAdsAccountUrl extends TLObject {}
    public static class TL_payments_getStarsRevenueStats extends TLObject {}
    public static class TL_payments_getStarsRevenueWithdrawalUrl extends TLObject {}
    public static class TL_payments_paymentFormStars extends TLObject {}
    public static class TL_payments_paymentResult extends TLObject {}
    public static class TL_payments_paymentVerificationNeeded extends TLObject {}
    public static class TL_payments_sendPaymentForm extends TLObject {}
    public static class TL_payments_starsRevenueAdsAccountUrl extends TLObject {}
    public static class TL_payments_starsRevenueStats extends TLObject {}
    public static class TL_payments_starsRevenueWithdrawalUrl extends TLObject {}
    public static class TL_payments_validateRequestedInfo extends TLObject {}
    public static class TL_payments_validatedRequestedInfo extends TLObject {}
    public static class TL_peerBlocked extends TLObject {}
    public static class TL_peerChannel_layer131 extends TLObject {}
    public static class TL_peerChat_layer131 extends TLObject {}
    public static class TL_peerColor extends TLObject {}
    public static class TL_peerNotifySettings extends TLObject {}
    public static class TL_peerNotifySettingsEmpty_layer77 extends TLObject {}
    public static class TL_peerUser_layer131 extends TLObject {}
    public static class TL_pendingSuggestion extends TLObject {}
    public static class TL_phoneCallDiscardReasonBusy extends TLObject {}
    public static class TL_phoneCallDiscardReasonMissed extends TLObject {}
    public static class TL_phone_getGroupCallStreamChannels extends TLObject {}
    public static class TL_phone_groupCallStreamChannels extends TLObject {}
    public static class TL_photoCachedSize extends TLObject {}
    public static class TL_photoEmpty extends Photo {}
    public static class TL_photoSizeProgressive extends TLObject {}
    public static class TL_photoSize_layer127 extends TLObject {}
    public static class TL_photos_deletePhotos extends TLObject {}
    public static class TL_photos_getUserPhotos extends TLObject {}
    public static class TL_photos_photo extends TLObject {}
    public static class TL_photos_photos extends TLObject {}
    public static class TL_photos_updateProfilePhoto extends TLObject {}
    public static class TL_photos_uploadProfilePhoto extends TLObject {}
    public static class TL_pollAnswer extends TLObject {}
    public static class TL_pollResults extends TLObject {}
    public static class TL_postAddress extends TLObject {}
    public static class TL_premiumGiftCodeOption extends TLObject {}
    public static class TL_privacyKeyAbout extends TLObject {}
    public static class TL_privacyKeyAddedByPhone extends TLObject {}
    public static class TL_privacyKeyBirthday extends TLObject {}
    public static class TL_privacyKeyChatInvite extends TLObject {}
    public static class TL_privacyKeyForwards extends TLObject {}
    public static class TL_privacyKeyPhoneCall extends TLObject {}
    public static class TL_privacyKeyPhoneNumber extends TLObject {}
    public static class TL_privacyKeyPhoneP2P extends TLObject {}
    public static class TL_privacyKeyProfilePhoto extends TLObject {}
    public static class TL_privacyKeyStarGiftsAutoSave extends TLObject {}
    public static class TL_privacyKeyStatusTimestamp extends TLObject {}
    public static class TL_privacyKeyVoiceMessages extends TLObject {}
    public static class TL_privacyValueAllowAll extends TLObject {}
    public static class TL_privacyValueAllowBots extends TLObject {}
    public static class TL_privacyValueAllowChatParticipants extends TLObject {}
    public static class TL_privacyValueAllowCloseFriends extends TLObject {}
    public static class TL_privacyValueAllowContacts extends TLObject {}
    public static class TL_privacyValueAllowPremium extends TLObject {}
    public static class TL_privacyValueAllowUsers extends TLObject {}
    public static class TL_privacyValueDisallowAll extends TLObject {}
    public static class TL_privacyValueDisallowBots extends TLObject {}
    public static class TL_privacyValueDisallowChatParticipants extends TLObject {}
    public static class TL_privacyValueDisallowUsers extends TLObject {}
    public static class TL_profileTabFiles extends TLObject {}
    public static class TL_profileTabGifs extends TLObject {}
    public static class TL_profileTabLinks extends TLObject {}
    public static class TL_profileTabMedia extends TLObject {}
    public static class TL_profileTabMusic extends TLObject {}
    public static class TL_profileTabPosts extends TLObject {}
    public static class TL_profileTabVoice extends TLObject {}
    public static class TL_reactionEmpty extends TLObject {}
    public static class TL_reactionPaid extends TLObject {}
    public static class TL_readParticipantDate extends TLObject {}
    public static class TL_recentMeUrlChat extends TLObject {}
    public static class TL_recentMeUrlChatInvite extends TLObject {}
    public static class TL_recentMeUrlStickerSet extends TLObject {}
    public static class TL_recentMeUrlUnknown extends TLObject {}
    public static class TL_recentMeUrlUser extends TLObject {}
    public static class TL_recentStory extends TLObject {}
    public static class TL_reportMessagesDelivery extends TLObject {}
    public static class TL_reportResultAddComment extends TLObject {}
    public static class TL_reportResultChooseOption extends TLObject {}
    public static class TL_reportResultReported extends TLObject {}
    public static class TL_requestPeerTypeBroadcast extends TLObject {}
    public static class TL_requestPeerTypeChat extends TLObject {}
    public static class TL_requestPeerTypeUser extends TLObject {}
    public static class TL_savedMusic extends TLObject {}
    public static class TL_savedReactionTag extends TLObject {}
    public static class TL_searchResultsCalendarPeriod extends TLObject {}
    public static class TL_securePasswordKdfAlgoPBKDF2HMACSHA512iter100000 extends TLObject {}
    public static class TL_securePasswordKdfAlgoSHA512 extends TLObject {}
    public static class TL_securePasswordKdfAlgoUnknown extends TLObject {}
    public static class TL_secureSecretSettings extends TLObject {}
    public static class TL_secureValueTypeAddress extends TLObject {}
    public static class TL_secureValueTypeBankStatement extends TLObject {}
    public static class TL_secureValueTypeDriverLicense extends TLObject {}
    public static class TL_secureValueTypeEmail extends TLObject {}
    public static class TL_secureValueTypeIdentityCard extends TLObject {}
    public static class TL_secureValueTypeInternalPassport extends TLObject {}
    public static class TL_secureValueTypePassport extends TLObject {}
    public static class TL_secureValueTypePassportRegistration extends TLObject {}
    public static class TL_secureValueTypePersonalDetails extends TLObject {}
    public static class TL_secureValueTypePhone extends TLObject {}
    public static class TL_secureValueTypeRentalAgreement extends TLObject {}
    public static class TL_secureValueTypeTemporaryRegistration extends TLObject {}
    public static class TL_secureValueTypeUtilityBill extends TLObject {}
    public static class TL_sendAsPeer extends TLObject {}
    public static class TL_sendMessageChooseStickerAction extends TLObject {}
    public static class TL_sendMessageEmojiInteraction extends TLObject {}
    public static class TL_sendMessageEmojiInteractionSeen extends TLObject {}
    public static class TL_shippingOption extends TLObject {}
    public static class TL_speakingInGroupCallAction extends TLObject {}
    public static class TL_sponsoredMessage extends TLObject {}
    public static class TL_sponsoredMessageReportOption extends TLObject {}
    public static class TL_statsURL extends TLObject {}
    public static class TL_stickerKeyword extends TLObject {}
    public static class TL_stickerPack extends TLObject {}
    public static class TL_stickerSet extends TLObject {}
    public static class TL_stickerSetFullCovered extends TLObject {}
    public static class TL_stickerSetNoCovered extends TLObject {}
    public static class TL_stickers_addStickerToSet extends TLObject {}
    public static class TL_stickers_changeStickerPosition extends TLObject {}
    public static class TL_stickers_checkShortName extends TLObject {}
    public static class TL_stickers_createStickerSet extends TLObject {}
    public static class TL_stickers_deleteStickerSet extends TLObject {}
    public static class TL_stickers_removeStickerFromSet extends TLObject {}
    public static class TL_stickers_renameStickerSet extends TLObject {}
    public static class TL_stickers_replaceSticker extends TLObject {}
    public static class TL_stickers_suggestShortName extends TLObject {}
    public static class TL_stickers_suggestedShortName extends TLObject {}
    public static class TL_todoCompletion extends TLObject {}
    public static class TL_topPeerCategoryBotsApp extends TLObject {}
    public static class TL_topPeerCategoryBotsGuestChat extends TLObject {}
    public static class TL_topPeerCategoryBotsInline extends TLObject {}
    public static class TL_topPeerCategoryCorrespondents extends TLObject {}
    public static class TL_topPeerCategoryPeers extends TLObject {}
    public static class TL_updateAttachMenuBots extends TLObject {}
    public static class TL_updateBotCommands extends TLObject {}
    public static class TL_updateBotMenuButton extends TLObject {}
    public static class TL_updateChannelViewForumAsMessages extends TLObject {}
    public static class TL_updateContactNote extends TLObject {}
    public static class TL_updateContactsReset extends TLObject {}
    public static class TL_updateDialogFilter extends TLObject {}
    public static class TL_updateDialogFilterOrder extends TLObject {}
    public static class TL_updateDialogFilters extends TLObject {}
    public static class TL_updateDialogPinned extends TLObject {}
    public static class TL_updateDialogUnreadMark extends TLObject {}
    public static class TL_updateDraftMessage extends TLObject {}
    public static class TL_updateFavedStickers extends TLObject {}
    public static class TL_updateMoveStickerSetToTop extends TLObject {}
    public static class TL_updateNewAuthorization extends TLObject {}
    public static class TL_updateNewStickerSet extends TLObject {}
    public static class TL_updateNotifySettings extends TLObject {}
    public static class TL_updatePeerHistoryTTL extends TLObject {}
    public static class TL_updatePhoneCall extends TLObject {}
    public static class TL_updatePhoneCallSignalingData extends TLObject {}
    public static class TL_updatePinnedDialogs extends TLObject {}
    public static class TL_updatePinnedSavedDialogs extends TLObject {}
    public static class TL_updatePrivacy extends TLObject {}
    public static class TL_updateReadFeaturedEmojiStickers extends TLObject {}
    public static class TL_updateReadFeaturedStickers extends TLObject {}
    public static class TL_updateRecentEmojiStatuses extends TLObject {}
    public static class TL_updateRecentReactions extends TLObject {}
    public static class TL_updateSavedDialogPinned extends TLObject {}
    public static class TL_updateSavedGifs extends TLObject {}
    public static class TL_updateSavedReactionTags extends TLObject {}
    public static class TL_updateSavedRingtones extends TLObject {}
    public static class TL_updateShortChatMessage extends TLObject {}
    public static class TL_updateShortMessage extends TLObject {}
    public static class TL_updateShortSentMessage extends TLObject {}
    public static class TL_updateStickerSets extends TLObject {}
    public static class TL_updateStickerSetsOrder extends TLObject {}
    public static class TL_updateTheme extends TLObject {}
    public static class TL_updateTranscribeAudio extends TLObject {}
    public static class TL_updateTranscribedAudio extends TLObject {}
    public static class TL_updateWebViewResultSent extends TLObject {}
    public static class TL_updatesCombined extends TLObject {}
    public static class TL_updatesTooLong extends TLObject {}
    public static class TL_updates_channelDifference extends TLObject {}
    public static class TL_updates_channelDifferenceEmpty extends TLObject {}
    public static class TL_updates_channelDifferenceTooLong extends TLObject {}
    public static class TL_updates_difference extends TLObject {}
    public static class TL_updates_differenceEmpty extends TLObject {}
    public static class TL_updates_differenceSlice extends TLObject {}
    public static class TL_updates_differenceTooLong extends TLObject {}
    public static class TL_updates_getChannelDifference extends TLObject {}
    public static class TL_updates_getDifference extends TLObject {}
    public static class TL_updates_getState extends TLObject {}
    public static class TL_updates_state extends TLObject {}
    public static class TL_upload_cdnFileReuploadNeeded extends TLObject {}
    public static class TL_upload_fileCdnRedirect extends TLObject {}
    public static class TL_upload_getCdnFile extends TLObject {}
    public static class TL_upload_getCdnFileHashes extends TLObject {}
    public static class TL_upload_getFile extends TLObject {}
    public static class TL_upload_getWebFile extends TLObject {}
    public static class TL_upload_reuploadCdnFile extends TLObject {}
    public static class TL_upload_saveBigFilePart extends TLObject {}
    public static class TL_upload_saveFilePart extends TLObject {}
    public static class TL_urlAuthResultAccepted extends TLObject {}
    public static class TL_urlAuthResultDefault extends TLObject {}
    public static class TL_urlAuthResultRequest extends TLObject {}
    public static class TL_user extends TLObject {}
    public static class TL_userContact_old2 extends User {}
    public static class TL_userDeleted_old2 extends TLObject {}
    public static class TL_userEmpty extends User {}
    public static class TL_userForeign_old2 extends TLObject {}
    public static class TL_userProfilePhoto extends TLObject {}
    public static class TL_userProfilePhotoEmpty extends TLObject {}
    public static class TL_userRequest_old2 extends TLObject {}
    public static class TL_userSelf_old3 extends TLObject {}
    public static class TL_usersSlice extends TLObject {}
    public static class TL_users_getFullUser extends TLObject {}
    public static class TL_users_getUsers extends TLObject {}
    public static class TL_users_userFull extends TLObject {}
    public static class TL_videoSize extends VideoSize {}
    public static class TL_videoSizeEmojiMarkup extends VideoSize {}
    public static class TL_videoSize_layer127 extends VideoSize {}
    public static class TL_wallPaperNoFile extends TLObject {}
    public static class TL_wallPaperSettings extends TLObject {}
    public static class TL_webAuthorization extends TLObject {}
    public static class TL_webDocument extends TLObject {}
    public static class TL_webDocumentNoProxy extends TLObject {}
    public static class TL_webPageAttributeStickerSet extends TLObject {}
    public static class TL_webPageAttributeStory extends TLObject {}
    public static class TL_webPageAttributeTheme extends TLObject {}
    public static class TL_webPageEmpty extends TLObject {}
    public static class TL_webPageNotModified extends TLObject {}
    public static class TL_webPagePending extends TLObject {}
    public static class TL_webPageUrlPending extends TLObject {}
    public static class Theme extends TLObject {}
    public static class Tl_inputChatTheme extends TLObject {}
    public static class Tl_inputChatThemeEmpty extends TLObject {}
    public static class Tl_inputChatThemeUniqueGift extends TLObject {}
    public static class TodoCompletion extends TLObject {}
    public static class TodoList extends TLObject {}
    public static class UrlAuthResult extends TLObject {}
    public static class Users extends TLObject {}
    public static class WallPaperSettings extends TLObject { public boolean motion; public boolean blur; }
    public static class WebPageAttribute extends TLObject {}
    public static class auth_Authorization extends TLObject {}
    public static class auth_SentCode extends TLObject {}
    public static class contacts_Blocked extends TLObject {}
    public static class help_AppConfig extends TLObject {}
    public static class help_AppUpdate extends TLObject {}
    public static class help_PeerColorSet extends TLObject {}
    public static class messages_AvailableEffects extends TLObject {}
    public static class messages_BotResults extends TLObject {
        public Object next_offset;
        public Object cache_time;
        public Object query_id;}
    public static class messages_Chats extends TLObject {}
    public static class messages_DhConfig extends TLObject {}
    public static class messages_Dialogs extends TLObject {}
    public static class messages_EmojiGroups extends TLObject {}
    public static class messages_FoundStickerSets extends TLObject {}
    public static class messages_SavedReactionTags extends TLObject {}
    public static class messages_SentEncryptedMessage extends TLObject {}
    public static class messages_SponsoredMessages extends TLObject {}
    public static class payments_PaymentResult extends TLObject {}
    public static class photos_Photos extends TLObject {}
    public static class savedDialog extends TLObject {}
    public static class updates_ChannelDifference extends TLObject {}
    public static class updates_Difference extends TLObject {}
    public static class Boost extends TLObject { public static final long NO_USER_ID = 0L; }
    public static class TL_emojiStatuses extends TLObject { public int flags; }
    public static class StickersFilter extends TLObject { public int flags; }
    // Common constructors (for compatibility)
    public static final int TL_message_constructor = 0;
    public static final int TL_photo_constructor = 0;
    public static final int TL_document_constructor = 0;
    public static final int TL_user_constructor = 0;
    public static final int TL_chat_constructor = 0;
    public static final int TL_webPage_constructor = 0;
}