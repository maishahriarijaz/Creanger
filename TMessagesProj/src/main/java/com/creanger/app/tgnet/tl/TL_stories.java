package com.creanger.app.tgnet.tl;

import com.creanger.app.tgnet.TLObject;
import com.creanger.app.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal TL_stories compatibility shim.
 */
public class TL_stories {

    public static class StoryItem extends TLObject {
        public long id;
        public TLRPC.Peer peer;
        public TLRPC.MessageMedia media;
        public int date;
        public Map<String, Object> media_areas = new HashMap<>();
        public List<TLRPC.Reaction> reactions = new ArrayList<>();
    }
    
    public static class PeerStories extends TLObject {
        public TLRPC.Peer peer;
        public List<StoryItem> stories = new ArrayList<>();
        public int max_id;
        public int unread_count;
    }
    
    public static class TL_storiesStoryItem extends StoryItem {}
    
    public static class TL_mediaAreaChannelPost extends TLObject {
        public long channel_id;
        public int msg_id;
    }
    
    public static class TL_mediaAreaVenue extends TLObject {
        public TLRPC.GeoPoint geo;
        public String title;
        public String address;
        public String provider;
        public String venue_id;
        public String venue_type;
    }
    
    public static class TL_mediaAreaPost extends TLObject {
        public long channel_id;
        public int msg_id;
    }
    
    public static class TL_mediaAreaSuggestedReaction extends TLObject {
        public TLRPC.Reaction reaction;
    }
    
    public static class TL_storyAlbum extends TLObject {
        public long id;
        public String title;
        public List<StoryItem> items = new ArrayList<>();
    }
    
    public static class TL_storiesStealthMode extends TLObject {
        public int until_date;
        public int hide_timer;
        public List<TLRPC.Peer> peers = new ArrayList<>();
    }
    
    public static class TL_stories_allStoriesNotModified extends TLObject {}
    public static class TL_stories_allStories extends TLObject {
        public List<PeerStories> peer_stories = new ArrayList<>();
    }
    public static class TL_stories_canSendStory extends TLObject {
        public boolean can_send;
    }
    public static class TL_stories_sendStory extends TLObject {
        public TLRPC.Peer peer;
        public TLRPC.MessageMedia media;
        public String caption;
        public int privacy;
        public int random_id;
        public int ttl;
    }
    public static class TL_stories_deleteStories extends TLObject {
        public TLRPC.Peer peer;
        public int[] ids;
    }
    public static class TL_stories_editStory extends TLObject {
        public TLRPC.Peer peer;
        public int id;
        public TLRPC.MessageMedia media;
        public String caption;
        public int privacy;
    }
    public static class TL_stories_getAllStories extends TLObject {
        public int max_id;
    }
    public static class TL_stories_togglePeerStoriesHidden extends TLObject {
        public TLRPC.Peer peer;
        public boolean hidden;
    }
    public static class TL_stories_stories extends TLObject {
        public List<PeerStories> peer_stories = new ArrayList<>();
        public int max_id;
    }
    public static class TL_stories_getPeerStories extends TLObject {
        public TLRPC.Peer peer;
        public int max_id;
    }
    public static class TL_stories_getPinnedStories extends TLObject {
        public TLRPC.Peer peer;
        public int max_id;
    }
    public static class TL_stories_getStoriesArchive extends TLObject {
        public TLRPC.Peer peer;
        public int max_id;
    }
    public static class TL_stories_readStories extends TLObject {
        public TLRPC.Peer peer;
        public int max_id;
    }
    public static class TL_stories_incrementStoryViews extends TLObject {
        public TLRPC.Peer peer;
        public int[] ids;
    }
    public static class TL_stories_getStoryViewsList extends TLObject {
        public TLRPC.Peer peer;
        public int id;
        public int max_id;
        public int limit;
    }
    public static class TL_stories_getStoriesByID extends TLObject {
        public TLRPC.Peer peer;
        public int[] ids;
    }
    public static class TL_stories_searchPosts extends TLObject {
        public TLRPC.Peer peer;
        public String q;
        public int max_id;
        public int limit;
    }
    public static class TL_stories_getStoriesViews extends TLObject {
        public TLRPC.Peer peer;
        public int id;
        public int max_id;
        public int limit;
    }
    public static class TL_stories_exportStoryLink extends TLObject {
        public TLRPC.Peer peer;
        public int id;
    }
    public static class TL_stories_report extends TLObject {
        public TLRPC.Peer peer;
        public int id;
        public int reason;
        public String message;
    }
    public static class TL_stories_getAllReadPeerStories extends TLObject {}
    public static class TL_stories_getPeerMaxIDs extends TLObject {
        public List<TLRPC.Peer> peers = new ArrayList<>();
    }
    public static class TL_stories_activateStealthMode extends TLObject {
        public TL_storiesStealthMode stealth_mode;
    }
    public static class TL_stories_sendReaction extends TLObject {
        public TLRPC.Peer peer;
        public int id;
        public TLRPC.Reaction reaction;
        public boolean big;
        public boolean add_to_recent;
    }
    public static class TL_stories_getChatsToSend extends TLObject {
        public int max_id;
    }
    public static class MediaArea extends TLObject {}
    public static class TL_stories_storyViews extends TLObject {}
    public static class StoryView extends TLObject {}
    public static class StoryViews extends TLObject {}
    public static class StoryReaction extends TLObject {}
    public static class TL_createAlbum extends TLObject {}
    public static class TL_mediaAreaStarGift extends TLObject {}
    public static class TL_storyViewPublicRepost extends TLObject {}
    public static class TL_foundStories extends TLObject {}
    public static class TL_getAlbums extends TLObject {}
    public static class TL_mediaAreaUrl extends TLObject {}
    public static class TL_mediaAreaWeather extends TLObject {}
    public static class TL_storyViews extends TLObject {}
    public static class TL_storyItem extends TLObject {}
    public static class TL_updateStory extends TLObject {}
    public static class TL_deleteAlbum extends TLObject {}
    public static class TL_stories_getAlbumStories extends TLObject {}
    public static class TL_stats_storyStats extends TLObject {}
    public static class TL_storyItemDeleted extends TLObject {}
    public static class TL_mediaAreaGeoPoint extends TLObject {}
    public static class TL_storyReactionPublicRepost extends TLObject {}
    public static class TL_inputMediaAreaChannelPost extends TLObject {}
    public static class TL_togglePinnedToTop extends TLObject {}
    public static class TL_updateStoriesStealthMode extends TLObject {}
    public static class TL_myBoost extends TLObject {}
    public static class TL_storyView extends TLObject {}
    public static class TL_storyViewPublicForward extends TLObject {}
    public static class StoryViewsList extends TLObject {}
    public static class TL_publicForwardStory extends TLObject {}
    public static class TL_storyReactionPublicForward extends TLObject {}
    public static class TL_storyReaction extends TLObject {}
    public static class TL_albumsNotModified extends TLObject {}
    public static class TL_storyReactionsList extends TLObject {}
    public static class TL_premium_applyBoost extends TLObject {}
    public static class Boost extends TLObject {}
    public static class TL_premium_getMyBoosts extends TLObject {}
    public static class TL_stories_peerStories extends TLObject {}
    public static class TL_storyItemSkipped extends TLObject {}
    public static class TL_premium_myBoosts extends TLObject {}
    public static class StoryAlbum extends TLObject {}
    public static class TL_mediaAreaCoordinates extends TLObject {}
    public static class TL_albums extends TLObject {}
    public static class TL_updateAlbum extends TLObject {}
    public static class togglePinned extends TLObject {}
    public static class TL_peerStories extends TLObject {}
    public static class canSendStoryCount extends TLObject {}
    public static class TL_getStoryReactionsList extends TLObject {}
    public static class TL_foundStory extends TLObject {}
    public static class TL_geoPointAddress extends TLObject {}
    public static class TL_stats_getStoryStats extends TLObject {}
    public static class TL_reorderAlbums extends TLObject {}
    public static class TL_updateReadStories extends TLObject {}
    public static class TL_inputMediaAreaVenue extends TLObject {}
}