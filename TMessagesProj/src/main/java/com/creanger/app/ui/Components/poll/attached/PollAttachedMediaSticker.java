package com.creanger.app.ui.Components.poll.attached;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import com.creanger.app.messenger.DocumentObject;
import com.creanger.app.messenger.ImageLocation;
import com.creanger.app.messenger.ImageReceiver;
import com.creanger.app.messenger.MediaController;
import com.creanger.app.messenger.MessageObject;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.ui.ActionBar.Theme;
import com.creanger.app.ui.Components.EmojiView;
import com.creanger.app.ui.Components.poll.PollAttachedMedia;

public class PollAttachedMediaSticker extends PollAttachedMedia {
    public final TLRPC.Document sticker;
    public final Object parent;
    public final boolean isEmoji;

    public PollAttachedMediaSticker(TLRPC.Document sticker, Object parent) {
        this.sticker = sticker;
        this.parent = parent;
        this.isEmoji = MessageObject.isAnimatedEmoji(sticker);
        setupImageReceiver(imageReceiver);
    }

    private void setupImageReceiver(ImageReceiver imageReceiver) {
        final boolean isWebpSticker = MessageObject.isStickerDocument(sticker) || MessageObject.isVideoSticker(sticker);
        final boolean isAnimatedSticker = MessageObject.isAnimatedStickerDocument(sticker, true);

        final Drawable thumb = DocumentObject.getSvgThumb(sticker, Theme.key_chat_serviceBackground, 1.0f);
        imageReceiver.setImage(ImageLocation.getForDocument(sticker), "38_38", thumb, sticker.size,
            isWebpSticker ? "webp" : null, parent, 0);
    }

    @Override
    protected void draw(Canvas canvas, int w, int h) {
        imageReceiver.setImageCoords(0, 0, w, h);
        imageReceiver.draw(canvas);
    }
}
