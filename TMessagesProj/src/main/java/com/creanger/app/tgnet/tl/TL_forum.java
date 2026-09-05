package com.creanger.app.tgnet.tl;

import com.creanger.app.tgnet.TLObject;
import java.util.ArrayList;
import java.util.List;

public class TL_forum {
    public static class TL_messages_getForumTopics extends TLObject {}
    public static class TL_TL_messages_getForumTopics extends TL_messages_getForumTopics {}
    public static class TL_messages_getForumTopicsByID extends TLObject {}
    public static class TL_TL_messages_getForumTopicsByID extends TL_messages_getForumTopicsByID {}
    public static class TL_dummy extends TLObject {}
    public static class TL_messages_editForumTopic extends TLObject {}
    public static class TL_messages_deleteTopicHistory extends TLObject {}
    public static class TL_messages_updatePinnedForumTopic extends TLObject {}
    public static class TL_messages_createForumTopic extends TLObject {}
    public static class TL_messages_reorderPinnedForumTopics extends TLObject {}
}
