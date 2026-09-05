package com.creanger.app.tgnet.tl;

import com.creanger.app.tgnet.TLObject;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.tgnet.InputSerializedData;
import java.util.ArrayList;
import java.util.List;

/** Minimal stub - RPC types removed, only data types preserved if needed */
public class TL_account {
    public static class TL_accountDaysTTL extends TLObject {
        public int days;
    }
    public static class Password extends TLObject {}
    public static class privacyRules extends TLObject {}
    public static class RequirementToContact extends TLObject {}
    public static class requirementToContactEmpty extends RequirementToContact {}
    public static class requirementToContactPremium extends RequirementToContact {}
    public static class requirementToContactPaidMessages extends RequirementToContact { public long stars_amount; }
    public static class WallPaper extends TLObject {}
    public static class TL_wallPapers extends TLObject {}
    public static class ThemeWallPaper extends TLObject {}
    public static class TL_themes extends TLObject {}
    public static class TL_themesNotModified extends TLObject {}
    public static class TL_chatThemes extends TLObject {}
    public static class TL_chatThemesNotModified extends TLObject {}
    public static class Tl_getUniqueGiftChatThemes extends TLObject {}
    public static class Tl_chatThemes extends TLObject {}
    public static class autoDownloadSettings extends TLObject {}
    public static class contactBirthdays extends TLObject {
        public List<TL_contactBirthday> contacts = new ArrayList<>();
    }
    public static class TL_contactBirthday extends TLObject {
        public long contact_id; public TL_birthday birthday;
        public static TL_contactBirthday TLdeserialize(InputSerializedData stream, int constructor, boolean exception) { return new TL_contactBirthday(); }
    }
    public static class TL_birthday extends TLObject { public int day; public int month; public int year; }
    public static class TL_birthdays extends TLObject {}
    public static class TL_savedRingtones extends TLObject {}
    public static class TL_savedRingtonesNotModified extends TLObject {}
    public static class TL_passkey extends TLObject {}
    public static class TL_inputPasskeyCredentialPublicKey extends TLObject {}
    public static class TL_inputPasskeyResponseRegister extends TLObject {}
    public static class TL_inputPasskeyResponseLogin extends TLObject {}
    public static class Passkey extends TLObject {}
    public static class inputPasskeyResponseRegister extends TLObject {}
    public static class inputPasskeyResponseLogin extends TLObject {}
    public static class TL_passkeyLogin extends TLObject {}
    // RPC stubs - empty
    public static class uploadRingtone extends TLObject {}
    public static class getSavedRingtones extends TLObject {}
    public static class getWebPagePreview extends TLObject {}
    public static class webPagePreview extends TLObject {}
    public static class getPassword extends TLObject {}
    public static class getGlobalPrivacySettings extends TLObject {}
    public static class getAccountTTL extends TLObject {}
    public static class getPrivacy extends TLObject {}
    public static class initPasskeyRegistration extends TLObject {}
    public static class registerPasskey extends TLObject { public Object credential; }
    public static class initPasskeyLogin extends TLObject {}
    public static class finishPasskeyLogin extends TLObject { public Object credential; }
    public static class getChatThemes extends TLObject {}
    public static class Tl_getUniqueGiftChatThemes2 extends TLObject {}
    public static class getWallPaper extends TLObject {}
    public static class getTheme extends TLObject {}
    public static class getWallPapers extends TLObject {}
    public static class getAutoDownloadSettings extends TLObject {}
    public static class saveAutoDownloadSettings extends TLObject {}
    public static class changeAuthorizationSettings extends TLObject {}
    public static class resetAuthorization extends TLObject {}
    public static class getBirthdays extends TLObject {}
    public static class tmpPassword extends TLObject { public static tmpPassword TLdeserialize(InputSerializedData s,int c,boolean e){return new tmpPassword();} }
    public static class inputCheckPasswordSRP extends TLObject {}
    public static class getContactSignUpNotification extends TLObject {}
    public static class getAuthorizationForm extends TLObject {}
    public static class getDefaultEmojiStatuses extends TLObject {}
    public static class toggleSponsoredMessages extends TLObject {}
    public static class reorderUsernames extends TLObject {}
    public static class TL_webBrowserSettingsNotModified extends TLObject {}
    public static class contentSettings extends TLObject {}
    public static class saveWallPaper extends TLObject {}
    public static class installWallPaper extends TLObject {}
    public static class getChannelDefaultEmojiStatuses extends TLObject {}
    public static class deletePasskey extends TLObject {}
    public static class authorizations extends TLObject {}
    public static class TL_savedRingtoneConverted extends TLObject {}
    public static class reportProfilePhoto extends TLObject {}
    public static class resetWebAuthorizations extends TLObject {}
    public static class setContentSettings extends TLObject {}
    public static class TL_savedMusicIds extends TLObject {}
    public static class getWebBrowserSettings extends TLObject {}
    public static class setGlobalPrivacySettings extends TLObject {}
    public static class resetNotifySettings extends TLObject {}
    public static class setPrivacy extends TLObject {}
    public static class confirmPasswordEmail extends TLObject {}
    public static class getNotifyExceptions extends TLObject {}
    public static class registerDevice extends TLObject {}
    public static class saveTheme extends TLObject {}
    public static class getAuthorizations extends TLObject {}
    public static class getRecentEmojiStatuses extends TLObject {}
    public static class checkUsername extends TLObject {}
    public static class getChannelRestrictedStatusEmojis extends TLObject {}
    public static class unregisterDevice extends TLObject {}
    public static class passwordInputSettings extends TLObject {}
    public static class toggleNoPaidMessagesException extends TLObject {}
    public static class EmojiStatuses extends TLObject {}
    public static class saveRingtone extends TLObject {}
    public static class paidMessagesRevenue extends TLObject {}
    public static class setContactSignUpNotification extends TLObject {}
    public static class updateStatus extends TLObject {}
    public static class resendPasswordEmail extends TLObject {}
    public static class resetPasswordFailedWait extends TLObject {}
    public static class sentEmailCode extends TLObject {}
    public static class resetPasswordOk extends TLObject {}
    public static class getPaidMessagesRevenue extends TLObject {}
    public static class getDefaultProfilePhotoEmojis extends TLObject {}
    public static class changePhone extends TLObject {}
    public static class getPasskeys extends TLObject {}
    public static class createTheme extends TLObject {}
    public static class updateNotifySettings extends TLObject {}
    public static class getWebAuthorizations extends TLObject {}
    public static class updateColor extends TLObject {}
    public static class uploadWallPaper extends TLObject {}
    public static class updateProfile extends TLObject {}
    public static class installTheme extends TLObject {}
    public static class getNotifySettings extends TLObject {}
    public static class setAccountTTL extends TLObject {}
    public static class sendConfirmPhoneCode extends TLObject {}
    public static class toggleUsername extends TLObject {}
    public static class sendVerifyEmailCode extends TLObject {}
    public static class TL_reactionNotificationsFromContacts extends TLObject {}
    public static class TL_emojiStatusesNotModified extends TLObject {}
    public static class clearRecentEmojiStatuses extends TLObject {}
    public static class uploadTheme extends TLObject {}
    public static class getReactionsNotifySettings extends TLObject {}
    public static class webAuthorizations extends TLObject {}
    public static class deleteAccount extends TLObject {}
    public static class sendChangePhoneCode extends TLObject {}
    public static class TL_reactionsNotifySettings extends TLObject {}
    public static class declinePasswordReset extends TLObject {}
    public static class getMultiWallPapers extends TLObject {}
    public static class inputPasskeyCredentialPublicKey extends TLObject {}
    public static class TL_emojiStatuses extends TLObject {}
    public static class setAuthorizationTTL extends TLObject {}
    public static class verifyPhone extends TLObject {}
    public static class resetPasswordRequestedWait extends TLObject {}
    public static class verifyEmail extends TLObject {}
    public static class resetWebAuthorization extends TLObject {}
    public static class TL_emailVerifiedLogin extends TLObject {}
    public static class getContentSettings extends TLObject {}
    public static class saveSecureValue extends TLObject {}
    public static class WebBrowserSettings extends TLObject {}
    public static class setReactionsNotifySettings extends TLObject {}
    public static class updateTheme extends TLObject {}
    public static class getThemes extends TLObject {}
    public static class toggleWebBrowserSettingsException extends TLObject {}
    public static class TL_reactionNotificationsFromAll extends TLObject {}
    public static class updateWebBrowserSettings extends TLObject {}
    public static class confirmPhone extends TLObject {}
    public static class updateUsername extends TLObject {}
    public static class WebDomainException extends TLObject {}
    public static class updateBirthday extends TLObject {}
    public static class TL_emailVerified extends TLObject {}
    public static class getDefaultBackgroundEmojis extends TLObject {}
    public static class getTmpPassword extends TLObject {}
    public static class resetWallPapers extends TLObject {}
    public static class updateEmojiStatus extends TLObject {}
    public static class updatePersonalChannel extends TLObject {}
    public static class TL_webBrowserSettings extends TLObject {}
    public static class getSavedMusicIds extends TLObject {}
    public static class cancelPasswordEmail extends TLObject {}
    public static class reportPeer extends TLObject {}
    public static class deleteWebBrowserSettingsExceptions extends TLObject {}
    public static class resetPassword extends TLObject {}
    public static class getRequirementsToContact extends TLObject {}
    public static class getPasswordSettings extends TLObject {}
    public static class TL_password extends TLObject {}
    public static class passwordSettings extends TLObject {}
    public static class updatePasswordSettings extends TLObject {}
}
