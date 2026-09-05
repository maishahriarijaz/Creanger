/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Grishka, 2013-2016.
 */

package com.creanger.app.messenger.voip;

import com.creanger.app.messenger.DialogObject;
import com.creanger.app.tgnet.TLRPC;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

public class VoIPService {

	public static final int QUALITY_SMALL = 0;
	public static final int QUALITY_MEDIUM = 1;
	public static final int QUALITY_FULL = 2;

	public static VoIPService getSharedInstance() {
		return null;
	}

	public static class ProxyVideoSink implements VideoSink {

		private VideoSink target;
		private VideoSink background;

		@Override
		synchronized public void onFrame(VideoFrame frame) {
			if (target != null) {
				target.onFrame(frame);
			}
			if (background != null) {
				background.onFrame(frame);
			}
		}

		synchronized public void setTarget(VideoSink newTarget) {
			if (target != newTarget) {
				if (target != null) {
					target.setParentSink(null);
				}
				target = newTarget;
				if (target != null) {
					target.setParentSink(this);
				}
			}
		}

		synchronized public void setBackground(VideoSink newBackground) {
			if (background != null) {
				background.setParentSink(null);
			}
			background = newBackground;
			if (background != null) {
				background.setParentSink(this);
			}
		}

		synchronized public void removeTarget(VideoSink target) {
			if (this.target == target) {
				this.target = null;
			}
		}

		synchronized public void removeBackground(VideoSink background) {
			if (this.background == background) {
				this.background = null;
			}
		}

		synchronized public void swap() {
			VideoSink oldTarget = target;
			target = background;
			background = oldTarget;
		}
	}

	public static class RequestedParticipant {
		public int audioSsrc;
		public long userId;
		public TLRPC.GroupCallParticipant participant;

		public RequestedParticipant(TLRPC.GroupCallParticipant p, int ssrc) {
			participant = p;
			audioSsrc = ssrc;
			userId = p == null ? 0 : DialogObject.getPeerDialogId(p.peer);
		}
	}
}