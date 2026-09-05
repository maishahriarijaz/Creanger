package com.creanger.app.tgnet;

import com.creanger.app.tgnet.TLRPC;
/**
 * Base class for all Telegram-serializable objects.
 * Minimal compatibility shim replacing TLObject.
 */
public abstract class TLObject {
    public TLRPC.MessageMedia media;
        public TLRPC.Chat migrated_to;
        public byte[] file_reference;
        public TLRPC.Photo fallback_photo;
        public int dc_id;
        public boolean day_of_week;
        public int date;
        public int channel_post;
        public long channel_id;
        public boolean blur;
        public boolean has_video;
    public int length() { return 0; }
    public int getObjectSize() {
        return 0;
    }
    
    public void serializeToStream(OutputSerializedData stream) {}
    
    public static boolean hasFlag(int flags, int flag) {
        return (flags & (1 << flag)) != 0;
    }
    
    public static void applyFlags() {}
    public static int setFlag(int flags, int flag, boolean value) {
        if (value) {
            return flags | (1 << flag);
        } else {
            return flags & ~(1 << flag);
        }
    }

    // Generic fields to satisfy UI references after purge — all subclasses inherit these
    public int flags;
        public boolean isOnline;
        public boolean can_set_stickers;
        public boolean isVerified;
        public boolean isPremium;
        public boolean checkPremium;
        public String username;
        public java.util.ArrayList chats = new java.util.ArrayList();
        public java.util.ArrayList users = new java.util.ArrayList();
        public Object toArray(Object[] a) { return null; }
        public java.util.ArrayList thumbs = new java.util.ArrayList();
        public int thumb_version;
        public long thumb_document_id;
        public java.util.ArrayList order = new java.util.ArrayList();
        public String emoticon;
        public boolean add(Object o) { return false; }
        public static Object TLdeserialize(Object s, int c, boolean e) { return null; }
        public String title;
        public java.util.ArrayList<TLRPC.RestrictionReason> restriction_reason = new java.util.ArrayList<>();
        public String hash;
        public java.util.ArrayList emoticons = new java.util.ArrayList();
        public boolean masks;
        public int until;
        public int position;
        public TLRPC.StickerSet stickerset;
        public String short_name;
        public java.util.ArrayList emojis = new java.util.ArrayList();
        public TLRPC.EmojiStatus emoji_status;
        public long icon_emoji_id;
        public TLRPC.Chat channel;
        public boolean creator;
        public String localThumbPath;
        public java.util.ArrayList groups = new java.util.ArrayList();
        public String alt;
        public long access_hash;
        public long id;
        public Object sticker;
        public Object action;
        public java.util.ArrayList documents;
        public Object random_id;
        public Object set;
    public Object local_id;
    public boolean long_date;
    public boolean long_time;
    public String motion;
    public String native_name;
    public Object pinned_msg_id;
    public String self;
    public Object volume_id;
    public Object document;
    public Object personal;
    public Object profile_photo;
    public Object location;
    public Object photo;
    public Object webpage;
    public Object personal_photo;
    public java.util.ArrayList video_sizes;
    public int flags2;
    public String key;
    public String value;
    public String lang_code;
    public String attachPath;
    public java.util.ArrayList strings;
    public int from_version;
    public int version;
    public Object settings;
    public String lang_pack;
    public String base_lang_code;
    public String from_id;
    public String name;
    public java.util.ArrayList objects;
    public String platform;
    public String text;
    public String slug;
    public int until_date;
    public boolean short_time;
    public boolean short_date;
    public boolean rtl;
    public String relative;
    public String reason;
    public String plural_code;
    public String stripped_thumb;
    public Object strippedBitmap;
    public String saved_from_peer;
    public int saved_from_msg_id;
    public int expires;
    public boolean by_me;
    public static final long NO_USER_ID = 0L;
    public static final int LAYER = 0;
    
    @Override
    public String toString() {
        return getClass().getSimpleName() + "{}";
    }
}