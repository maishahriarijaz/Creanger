package com.creanger.app.tgnet.tl;

import com.creanger.app.tgnet.TLObject;
import java.util.ArrayList;
import java.util.List;

public class TL_bots {
    public static class BotInfo extends TLObject {}
    public static class TL_BotInfo extends BotInfo {}
    public static class BotInlineResult extends TLObject {
        public Object query_id;
        public Object content;
        public Object thumb;}
    public static class TL_BotInlineResult extends BotInlineResult {}
    public static class botPreviewMedia extends TLObject {}
    public static class TL_botPreviewMedia extends botPreviewMedia {}
    public static class botVerification extends TLObject {}
    public static class TL_botVerification extends botVerification {}
    public static class TL_dummy extends TLObject {}
    public static class toggleUserEmojiStatusPermission extends TLObject {}
    public static class allowSendMessage extends TLObject {}
    public static class addPreviewMedia extends TLObject {}
    public static class invokeWebViewCustomMethod extends TLObject {}
    public static class reorderUsernames extends TLObject {}
    public static class getAdminedBots extends TLObject {}
    public static class TL_botInfo extends TLObject {}
    public static class TL_updateBotMenuButton extends TLObject {}
    public static class getBotRecommendations extends TLObject {}
    public static class toggleUsername extends TLObject {}
    public static class setBotInfo extends TLObject {}
    public static class getRequestedWebViewButton extends TLObject {}
    public static class canSendMessage extends TLObject {}
    public static class editPreviewMedia extends TLObject {}
}
