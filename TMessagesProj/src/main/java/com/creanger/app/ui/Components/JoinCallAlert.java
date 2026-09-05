package com.creanger.app.ui.Components;

import com.creanger.app.messenger.MessageObject;
import com.creanger.app.tgnet.TLRPC;

import java.util.ArrayList;

public class JoinCallAlert {

    public interface JoinCallAlertDelegate {
        void didSelectChat(TLRPC.InputPeer peer, boolean hasFewPeers, boolean schedule, boolean isRtmpStream);
    }

    private static ArrayList<TLRPC.Peer> cachedChats;
    private static long lastCacheDid;
    private static int lastCachedAccount;

    public static void processDeletedChat(int account, long did) {
        if (lastCachedAccount != account || cachedChats == null || did > 0) {
            return;
        }
        for (int a = 0, N = cachedChats.size(); a < N; a++) {
            if (MessageObject.getPeerId(cachedChats.get(a)) == did) {
                cachedChats.remove(a);
                break;
            }
        }
        if (cachedChats.isEmpty()) {
            cachedChats = null;
        }
    }
}