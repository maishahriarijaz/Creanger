/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package com.creanger.app.messenger;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.creanger.app.tgnet.ConnectionsManager;
import com.creanger.app.tgnet.TLObject;
import com.creanger.app.tgnet.TLRPC;
import com.creanger.app.tgnet.tl.TL_account;

import java.text.CollationKey;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public class ContactsController extends BaseController {

    private int loadingGlobalSettings;
    private int loadingDeleteInfo;
    private int deleteAccountTTL;
    private int[] loadingPrivacyInfo = new int[PRIVACY_RULES_TYPE_COUNT];
    private ArrayList<TLRPC.PrivacyRule> lastseenPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> groupPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> callPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> p2pPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> profilePhotoPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> bioPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> musicPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> forwardsPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> phonePrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> addedByPhonePrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> voiceMessagesRules;
    private ArrayList<TLRPC.PrivacyRule> birthdayPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> giftsPrivacyRules;
    private ArrayList<TLRPC.PrivacyRule> noPaidMessagesPrivacyRules;
    private TLRPC.GlobalPrivacySettings globalPrivacySettings;

    public final static int PRIVACY_RULES_TYPE_LASTSEEN = 0;
    public final static int PRIVACY_RULES_TYPE_INVITE = 1;
    public final static int PRIVACY_RULES_TYPE_CALLS = 2;
    public final static int PRIVACY_RULES_TYPE_P2P = 3;
    public final static int PRIVACY_RULES_TYPE_PHOTO = 4;
    public final static int PRIVACY_RULES_TYPE_FORWARDS = 5;
    public final static int PRIVACY_RULES_TYPE_PHONE = 6;
    public final static int PRIVACY_RULES_TYPE_ADDED_BY_PHONE = 7;
    public final static int PRIVACY_RULES_TYPE_VOICE_MESSAGES = 8;
    public final static int PRIVACY_RULES_TYPE_BIO = 9;
    public final static int PRIVACY_RULES_TYPE_MESSAGES = 10;
    public final static int PRIVACY_RULES_TYPE_BIRTHDAY = 11;
    public final static int PRIVACY_RULES_TYPE_GIFTS = 12;
    public final static int PRIVACY_RULES_TYPE_NO_PAID_MESSAGES = 13;
    public final static int PRIVACY_RULES_TYPE_MUSIC = 14;

    public final static int PRIVACY_RULES_TYPE_COUNT = 15;

    private static Locale cachedCollatorLocale;
    private static Collator cachedCollator;
    public static Collator getLocaleCollator() {
        if (cachedCollator == null || cachedCollatorLocale != Locale.getDefault()) {
            try {
                cachedCollator = Collator.getInstance(cachedCollatorLocale = Locale.getDefault());
                cachedCollator.setStrength(Collator.SECONDARY);
            } catch (Exception e) {
                FileLog.e(e, true);
            }
        }
        if (cachedCollator == null) {
            try {
                cachedCollator = Collator.getInstance();
                cachedCollator.setStrength(Collator.SECONDARY);
            } catch (Exception e) {
                FileLog.e(e, true);
            }
        }
        if (cachedCollator == null) {
            cachedCollator = new Collator() {
                @Override
                public int compare(String source, String target) {
                    if (source == null || target == null) {
                        return 0;
                    }
                    return source.compareTo(target);
                }
                @Override
                public CollationKey getCollationKey(String source) {
                    return null;
                }
                @Override
                public int hashCode() {
                    return 0;
                }
            };
        }
        return cachedCollator;
    }

    private static volatile ContactsController[] Instance = new ContactsController[UserConfig.MAX_ACCOUNT_COUNT];
    public static ContactsController getInstance(int num) {
        ContactsController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (ContactsController.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new ContactsController(num);
                }
            }
        }
        return localInstance;
    }

    public ContactsController(int instance) {
        super(instance);
    }

    public static class Contact {
        public long user_id;
        public long contact_id;
        public long id;
        public TLRPC.User user;
        public String first_name;
        public String last_name;
        public String phone;
        public String shortPhone;
        public boolean imported;
        public boolean saved;
        public ArrayList<String> phones = new ArrayList<>();
        public ArrayList<String> shortPhones = new ArrayList<>();
    }

    public final ArrayList<TLRPC.TL_contact> contacts = new ArrayList<>();
    public final ConcurrentHashMap<Long, TLRPC.TL_contact> contactsDict = new ConcurrentHashMap<>();
    public final HashMap<String, TLRPC.TL_contact> contactsByPhone = new HashMap<>();
    public final HashMap<String, TLRPC.TL_contact> contactsByShortPhone = new HashMap<>();
    public final ArrayList<Contact> phoneBookContacts = new ArrayList<>();
    public final HashMap<String, ArrayList<TLRPC.TL_contact>> usersSectionsDict = new HashMap<>();
    public final ArrayList<String> sortedUsersSectionsArray = new ArrayList<>();
    public volatile boolean contactsLoaded = true;
    public volatile boolean doneLoadingContacts = true;

    public boolean isContact(long userId) {
        return false;
    }

    public boolean isLoadingContacts() {
        return false;
    }

    public void loadContacts(boolean force, int schedule) {

    }

    public void forceImportContacts() {

    }

    public void resetImportedContacts() {

    }

    public void deleteContact(ArrayList<TLRPC.User> users, boolean single) {

    }

    public void deleteAllContacts(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    public void checkInviteText() {

    }

    public void checkAppAccount() {

    }

    public void syncPhoneBookByAlert(HashMap<String, Contact> contactHashMap, boolean first, boolean schedule, boolean cancel) {

    }

    public void markAsContacted(String contactUri) {

    }

    public void createOrUpdateConnectionServiceContact(long userId, String firstName, String lastName) {

    }

    public void deleteConnectionServiceContact() {

    }

    public static boolean hasContactsPermission() {
        return false;
    }

    public String getInviteText(int num) {
        return "";
    }

    public void cleanup() {
        loadingGlobalSettings = 0;
        loadingDeleteInfo = 0;
        deleteAccountTTL = 0;
        Arrays.fill(loadingPrivacyInfo, 0);
        lastseenPrivacyRules = null;
        groupPrivacyRules = null;
        callPrivacyRules = null;
        p2pPrivacyRules = null;
        profilePhotoPrivacyRules = null;
        bioPrivacyRules = null;
        musicPrivacyRules = null;
        birthdayPrivacyRules = null;
        giftsPrivacyRules = null;
        forwardsPrivacyRules = null;
        phonePrivacyRules = null;
        addedByPhonePrivacyRules = null;
        voiceMessagesRules = null;
        noPaidMessagesPrivacyRules = null;
    }

    public void loadGlobalPrivacySetting() {
        if (loadingGlobalSettings == 0) {
            loadingGlobalSettings = 1;
            TL_account.getGlobalPrivacySettings req = new TL_account.getGlobalPrivacySettings();
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error == null) {
                    globalPrivacySettings = (TLRPC.GlobalPrivacySettings) response;
                    loadingGlobalSettings = 2;
                } else {
                    loadingGlobalSettings = 0;
                }
                getNotificationCenter().postNotificationName(NotificationCenter.privacyRulesUpdated);
            }));
        }
    }

    public void loadPrivacySettings() {
        loadPrivacySettings(false);
    }
    public void loadPrivacySettings(boolean force) {
        if (loadingDeleteInfo == 0) {
            loadingDeleteInfo = 1;
            TL_account.getAccountTTL req = new TL_account.getAccountTTL();
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error == null) {
                    TLRPC.TL_accountDaysTTL ttl = (TLRPC.TL_accountDaysTTL) response;
                    deleteAccountTTL = ttl.days;
                    loadingDeleteInfo = 2;
                } else {
                    loadingDeleteInfo = 0;
                }
                getNotificationCenter().postNotificationName(NotificationCenter.privacyRulesUpdated);
            }));
        }
        loadGlobalPrivacySetting();
        for (int a = 0; a < loadingPrivacyInfo.length; a++) {
            if (force ? loadingPrivacyInfo[a] == 1 : loadingPrivacyInfo[a] != 0) {
                continue;
            }
            loadingPrivacyInfo[a] = 1;
            final int num = a;

            TL_account.getPrivacy req = new TL_account.getPrivacy();

            switch (num) {
                case PRIVACY_RULES_TYPE_LASTSEEN:
                    req.key = new TLRPC.TL_inputPrivacyKeyStatusTimestamp();
                    break;
                case PRIVACY_RULES_TYPE_INVITE:
                    req.key = new TLRPC.TL_inputPrivacyKeyChatInvite();
                    break;
                case PRIVACY_RULES_TYPE_CALLS:
                    req.key = new TLRPC.TL_inputPrivacyKeyPhoneCall();
                    break;
                case PRIVACY_RULES_TYPE_P2P:
                    req.key = new TLRPC.TL_inputPrivacyKeyPhoneP2P();
                    break;
                case PRIVACY_RULES_TYPE_PHOTO:
                    req.key = new TLRPC.TL_inputPrivacyKeyProfilePhoto();
                    break;
                case PRIVACY_RULES_TYPE_BIO:
                    req.key = new TLRPC.TL_inputPrivacyKeyAbout();
                    break;
                case PRIVACY_RULES_TYPE_MUSIC:
                    req.key = new TLRPC.TL_inputPrivacyKeySavedMusic();
                    break;
                case PRIVACY_RULES_TYPE_FORWARDS:
                    req.key = new TLRPC.TL_inputPrivacyKeyForwards();
                    break;
                case PRIVACY_RULES_TYPE_PHONE:
                    req.key = new TLRPC.TL_inputPrivacyKeyPhoneNumber();
                    break;
                case PRIVACY_RULES_TYPE_VOICE_MESSAGES:
                    req.key = new TLRPC.TL_inputPrivacyKeyVoiceMessages();
                    break;
                case PRIVACY_RULES_TYPE_BIRTHDAY:
                    req.key = new TLRPC.TL_inputPrivacyKeyBirthday();
                    break;
                case PRIVACY_RULES_TYPE_GIFTS:
                    req.key = new TLRPC.TL_inputPrivacyKeyStarGiftsAutoSave();
                    break;
                case PRIVACY_RULES_TYPE_NO_PAID_MESSAGES:
                    req.key = new TLRPC.TL_inputPrivacyKeyNoPaidMessages();
                    break;
                case PRIVACY_RULES_TYPE_ADDED_BY_PHONE:
                    req.key = new TLRPC.TL_inputPrivacyKeyAddedByPhone();
                    break;
                default:
                    continue;
            }

            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error == null) {
                    TL_account.privacyRules rules = (TL_account.privacyRules) response;
                    getMessagesController().putUsers(rules.users, false);
                    getMessagesController().putChats(rules.chats, false);

                    switch (num) {
                        case PRIVACY_RULES_TYPE_LASTSEEN:
                            lastseenPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_INVITE:
                            groupPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_CALLS:
                            callPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_P2P:
                            p2pPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_PHOTO:
                            profilePhotoPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_BIO:
                            bioPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_MUSIC:
                            musicPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_BIRTHDAY:
                            birthdayPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_GIFTS:
                            giftsPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_NO_PAID_MESSAGES:
                            noPaidMessagesPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_FORWARDS:
                            forwardsPrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_PHONE:
                            phonePrivacyRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_VOICE_MESSAGES:
                            voiceMessagesRules = rules.rules;
                            break;
                        case PRIVACY_RULES_TYPE_ADDED_BY_PHONE:
                        default:
                            addedByPhonePrivacyRules = rules.rules;
                            break;
                    }
                    loadingPrivacyInfo[num] = 2;
                } else {
                    loadingPrivacyInfo[num] = 0;
                }
                getNotificationCenter().postNotificationName(NotificationCenter.privacyRulesUpdated);
            }));
        }
        getNotificationCenter().postNotificationName(NotificationCenter.privacyRulesUpdated);
    }

    public void setDeleteAccountTTL(int ttl) {
        deleteAccountTTL = ttl;
    }

    public int getDeleteAccountTTL() {
        return deleteAccountTTL;
    }

    public boolean getLoadingDeleteInfo() {
        return loadingDeleteInfo != 2;
    }

    public boolean getLoadingGlobalSettings() {
        return loadingGlobalSettings != 2;
    }

    public boolean getLoadingPrivacyInfo(int type) {
        return loadingPrivacyInfo[type] != 2;
    }

    public TLRPC.GlobalPrivacySettings getGlobalPrivacySettings() {
        return globalPrivacySettings;
    }

    public ArrayList<TLRPC.PrivacyRule> getPrivacyRules(int type) {
        switch (type) {
            case PRIVACY_RULES_TYPE_LASTSEEN:
                return lastseenPrivacyRules;
            case PRIVACY_RULES_TYPE_INVITE:
                return groupPrivacyRules;
            case PRIVACY_RULES_TYPE_CALLS:
                return callPrivacyRules;
            case PRIVACY_RULES_TYPE_P2P:
                return p2pPrivacyRules;
            case PRIVACY_RULES_TYPE_PHOTO:
                return profilePhotoPrivacyRules;
            case PRIVACY_RULES_TYPE_BIO:
                return bioPrivacyRules;
            case PRIVACY_RULES_TYPE_MUSIC:
                return musicPrivacyRules;
            case PRIVACY_RULES_TYPE_BIRTHDAY:
                return birthdayPrivacyRules;
            case PRIVACY_RULES_TYPE_GIFTS:
                return giftsPrivacyRules;
            case PRIVACY_RULES_TYPE_NO_PAID_MESSAGES:
                return noPaidMessagesPrivacyRules;
            case PRIVACY_RULES_TYPE_FORWARDS:
                return forwardsPrivacyRules;
            case PRIVACY_RULES_TYPE_PHONE:
                return phonePrivacyRules;
            case PRIVACY_RULES_TYPE_ADDED_BY_PHONE:
                return addedByPhonePrivacyRules;
            case PRIVACY_RULES_TYPE_VOICE_MESSAGES:
                return voiceMessagesRules;
        }
        return null;
    }

    public void setPrivacyRules(ArrayList<TLRPC.PrivacyRule> rules, int type) {
        switch (type) {
            case PRIVACY_RULES_TYPE_LASTSEEN:
                lastseenPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_INVITE:
                groupPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_CALLS:
                callPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_P2P:
                p2pPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_PHOTO:
                profilePhotoPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_BIO:
                bioPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_MUSIC:
                musicPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_BIRTHDAY:
                birthdayPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_GIFTS:
                giftsPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_NO_PAID_MESSAGES:
                noPaidMessagesPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_FORWARDS:
                forwardsPrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_PHONE:
                phonePrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_ADDED_BY_PHONE:
                addedByPhonePrivacyRules = rules;
                break;
            case PRIVACY_RULES_TYPE_VOICE_MESSAGES:
                voiceMessagesRules = rules;
                break;
        }
        getNotificationCenter().postNotificationName(NotificationCenter.privacyRulesUpdated);
    }

    public static String formatName(TLObject object) {
        if (object instanceof TLRPC.User) {
            return formatName((TLRPC.User) object);
        } else if (object instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) object;
            return chat.title;
        } else {
            return LocaleController.getString(R.string.HiddenName);
        }
    }

    @NonNull
    public static String formatName(TLRPC.User user) {
        if (user == null) {
            return "";
        }
        return formatName(user.first_name, user.last_name, 0);
    }

    @NonNull
    public static String formatName(String firstName, String lastName) {
        return formatName(firstName, lastName, 0);
    }

    @NonNull
    public static String formatName(String firstName, String lastName, int maxLength) {
        if (firstName != null) {
            firstName = firstName.trim();
        }
        if (firstName != null && lastName == null && maxLength > 0 && firstName.contains(" ") ) {
            int i = firstName.indexOf(" ");
            lastName = firstName.substring(i + 1);
            firstName = firstName.substring(0, i);
        }
        if (lastName != null) {
            lastName = lastName.trim();
        }
        StringBuilder result = new StringBuilder((firstName != null ? firstName.length() : 0) + (lastName != null ? lastName.length() : 0) + 1);
        if (LocaleController.nameDisplayOrder == 1) {
            if (firstName != null && firstName.length() > 0) {
                if (maxLength > 0 && firstName.length() > maxLength + 2) {
                    return firstName.substring(0, maxLength) + "…";
                }
                result.append(firstName);
                if (lastName != null && lastName.length() > 0) {
                    result.append(" ");
                    if (maxLength > 0 && result.length() + lastName.length() > maxLength) {
                        result.append(lastName.charAt(0));
                    } else {
                        result.append(lastName);
                    }
                }
            } else if (lastName != null && lastName.length() > 0) {
                if (maxLength > 0 && lastName.length() > maxLength + 2) {
                    return lastName.substring(0, maxLength) + "…";
                }
                result.append(lastName);
            }
        } else {
            if (lastName != null && lastName.length() > 0) {
                if (maxLength > 0 && lastName.length() > maxLength + 2) {
                    return lastName.substring(0, maxLength) + "…";
                }
                result.append(lastName);
                if (firstName != null && firstName.length() > 0) {
                    result.append(" ");
                    if (maxLength > 0 && result.length() + firstName.length() > maxLength) {
                        result.append(firstName.charAt(0));
                    } else {
                        result.append(firstName);
                    }
                }
            } else if (firstName != null && firstName.length() > 0) {
                if (maxLength > 0 && firstName.length() > maxLength + 2) {
                    return firstName.substring(0, maxLength) + "…";
                }
                result.append(firstName);
            }
        }
        return result.toString();
    }

    public static <T extends TLRPC.PrivacyRule> T findRule(ArrayList<TLRPC.PrivacyRule> rules, Class<T> clazz) {
        if (rules == null) {
            return null;
        }
        for (TLRPC.PrivacyRule rule : rules) {
            if (clazz.isInstance(rule)) {
                return clazz.cast(rule);
            }
        }
        return null;
    }
}
