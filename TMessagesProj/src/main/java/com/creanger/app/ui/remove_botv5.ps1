param()
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\shahriar ijaz\Desktop\Telegram-master-business-partial-removal-v12\Telegram-master\TMessagesProj\src\main\java\org\telegram'

# ============ ChatActivity.java ============
$p = "$root\ui\ChatActivity.java"
$t = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)

# 1. remove EnterView call sites (setBotsCount / updateBotWebView / setBotInfo)
$t = $t.Replace(@'
        chatActivityEnterView.setBotsCount(botsCount, hasBotsCommands, hasQuickReplies, false);
        chatActivityEnterView.updateBotWebView(false);
'@, '')
$t = $t.Replace(@'
                        if (chatActivityEnterView != null) {
                            chatActivityEnterView.setBotInfo(botInfo);
                        }
'@, '')
$t = $t.Replace(@'
                if (chatActivityEnterView != null) {
                    chatActivityEnterView.setBotsCount(botsCount, hasBotsCommands, hasQuickReplies, true);
                }
'@, '')
$t = $t.Replace(@'
                    if (chatActivityEnterView != null) {
                        chatActivityEnterView.setBotsCount(botsCount, hasBotsCommands, hasQuickReplies, true);
                        TLRPC.User bot = getMessagesController().getUser(info.user_id);
                        hasBotWebView = bot != null && bot.bot_menu_webview;
                        chatActivityEnterView.updateBotWebView(true);
                    }
'@, '')

# 2. checkBotCommands() -> no-op
$old = @'
    private void checkBotCommands() {
        URLSpanBotCommand.enabled = false;
        if (currentUser != null && currentUser.bot) {
            URLSpanBotCommand.enabled = !UserObject.isReplyUser(currentUser);
        } else if (chatInfo instanceof TLRPC.TL_chatFull) {
            for (int a = 0; a < chatInfo.participants.participants.size(); a++) {
                TLRPC.ChatParticipant participant = chatInfo.participants.participants.get(a);
                TLRPC.User user = getMessagesController().getUser(participant.user_id);
                if (user != null && user.bot) {
                    URLSpanBotCommand.enabled = true;
                    break;
                }
            }
        } else if (chatInfo instanceof TLRPC.TL_channelFull) {
            URLSpanBotCommand.enabled = !chatInfo.bot_info.isEmpty() && currentChat != null && currentChat.megagroup;
        }
    }
'@
$new = @'
    private void checkBotCommands() {
    }
'@
$t = $t.Replace($old, $new)

# 3. URLSpanBotCommand.enabled assignments in chat-custom blocks -> remove lines
$t = $t.Replace(@'
                    hasBotsCommands = false;
                    botInfo.clear();
                    botsCount = 0;
                    URLSpanBotCommand.enabled = false;
'@, @'
                    hasBotsCommands = false;
                    botInfo.clear();
                    botsCount = 0;
'@)
$t = $t.Replace(@'
                        if (user != null && user.bot) {
                            URLSpanBotCommand.enabled = true;
                            botsCount++;
'@, @'
                        if (user != null && user.bot) {
                            botsCount++;
'@)
$t = $t.Replace(@'
                    hasBotsCommands = false;
                    botInfo.clear();
                    URLSpanBotCommand.enabled = !chatInfo.bot_info.isEmpty() && currentChat != null && currentChat.megagroup;
                    botsCount = chatInfo.bot_info.size();
'@, @'
                    hasBotsCommands = false;
                    botInfo.clear();
                    botsCount = chatInfo.bot_info.size();
'@)

# 4. URLSpanBotCommand.enabled guards -> always true
$t = $t.Replace('if (URLSpanBotCommand.enabled) {', 'if (true) {')

Write-Host "ChatActivity left:"
$t.Split("`n") | ForEach-Object { if ($_ -match 'URLSpanBotCommand|setBotsCount|updateBotWebView|EnterView.setBotInfo') { Write-Host "  $_" } }
[System.IO.File]::WriteAllText($p, $t, (New-Object System.Text.UTF8Encoding($false)))

# ============ RichMessageLayout.java ============
$p2 = "$root\messenger\RichMessageLayout.java"
$t2 = [System.IO.File]::ReadAllText($p2, [System.Text.Encoding]::UTF8)
$t2 = $t2.Replace('new URLSpanBotCommand(getString(text), isOut() ? 1 : 0)', 'new URLSpanNoUnderline(getString(text))')
Write-Host "RichMessageLayout left:"
$t2.Split("`n") | ForEach-Object { if ($_ -match 'URLSpanBotCommand') { Write-Host "  $_" } }
[System.IO.File]::WriteAllText($p2, $t2, (New-Object System.Text.UTF8Encoding($false)))

# ============ PremiumPreviewFragment.java ============
$p3 = "$root\ui\PremiumPreviewFragment.java"
$t3 = [System.IO.File]::ReadAllText($p3, [System.Text.Encoding]::UTF8)
$t3 = $t3.Replace('new URLSpanBotCommand(url, t, run)', 'new URLSpanNoUnderline(url, run)')
Write-Host "PremiumPreviewFragment left:"
$t3.Split("`n") | ForEach-Object { if ($_ -match 'URLSpanBotCommand') { Write-Host "  $_" } }
[System.IO.File]::WriteAllText($p3, $t3, (New-Object System.Text.UTF8Encoding($false)))
Write-Host 'done'