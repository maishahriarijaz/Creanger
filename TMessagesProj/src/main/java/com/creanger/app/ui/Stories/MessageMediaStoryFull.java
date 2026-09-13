package com.creanger.app.ui.Stories;

import com.creanger.app.messenger.MessagesController;
import com.creanger.app.messenger.UserConfig;
import com.creanger.app.tgnet.AbstractSerializedData;
import com.creanger.app.tgnet.InputSerializedData;
import com.creanger.app.tgnet.OutputSerializedData;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.tgnet.tl.TL_stories;

public class MessageMediaStoryFull extends TLRPC.TL_messageMediaStory {

    public static int constructor = 0xc79aee1d;

    public void readParams(InputSerializedData stream, boolean exception) {
        user_id = stream.readInt64(exception);
        id = stream.readInt32(exception);
        storyItem = TL_stories.StoryItem.TLdeserialize(stream, stream.readInt32(exception), exception);
        via_mention = stream.readBool(exception);
        peer = MessagesController.getInstance(UserConfig.selectedAccount).getPeer(user_id);
    }

    public void serializeToStream(OutputSerializedData stream) {
        stream.writeInt32(constructor);
        stream.writeInt64(user_id);
        stream.writeInt32(id);
        storyItem.serializeToStream(stream);
        stream.writeBool(via_mention);
    }
}