package com.creanger.app.tgnet.tl;

import com.creanger.app.tgnet.TLObject;
import java.util.ArrayList;
import java.util.List;

public class TL_phone {
    public static class groupCall extends TLObject {}
    public static class TL_groupCall extends groupCall {}
    public static class groupParticipants extends TLObject {}
    public static class TL_groupParticipants extends groupParticipants {}
    public static class TL_groupCallStreamChannel extends TLObject {}
    public static class TL_TL_groupCallStreamChannel extends TL_groupCallStreamChannel {}
    public static class groupCallStreamRtmpUrl extends TLObject {}
    public static class TL_groupCallStreamRtmpUrl extends groupCallStreamRtmpUrl {}
    public static class PhoneCall extends TLObject {}
    public static class TL_PhoneCall extends PhoneCall {}
    public static class PhoneCallProtocol extends TLObject {}
    public static class TL_PhoneCallProtocol extends PhoneCallProtocol {}
    public static class TL_phoneCall extends TLObject {}
    public static class TL_TL_phoneCall extends TL_phoneCall {}
    public static class TL_phoneCallEmpty extends TLObject {}
    public static class TL_TL_phoneCallEmpty extends TL_phoneCallEmpty {}
    public static class TL_dummy extends TLObject {}
    public static class getGroupCallStreamRtmpUrl extends TLObject {}
    public static class getGroupCall extends TLObject {}
    public static class getGroupParticipants extends TLObject {}
    public static class createConferenceCall extends TLObject {}
    public static class editGroupCallTitle extends TLObject {}
    public static class toggleGroupCallRecord extends TLObject {}
    public static class inviteToGroupCall extends TLObject {}
}
