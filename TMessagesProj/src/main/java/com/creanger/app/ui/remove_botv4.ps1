param()
$ErrorActionPreference = 'Stop'
$path = 'C:\Users\shahriar ijaz\Desktop\Telegram-master-business-partial-removal-v12\Telegram-master\TMessagesProj\src\main\java\org\telegram\ui\ChatActivity.java'
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)

# 1. remove botDraftAnimationsPool.bind line
$text = $text.Replace("                        botDraftAnimationsPool.bind(old.messageOwner.id, messageObject.getId());`n", '')

# 2. getItemCount current shape (the -5 line was already removed)
$old = @'
        public int getItemCount() {
            if (isClearingHistory()) {
                if (currentUser != null && currentUser.bot && chatMode == 0 && (botInfo.size() > 0 && (botInfo.get(currentUser.id).description != null || botInfo.get(currentUser.id).description_photo != null || botInfo.get(currentUser.id).description_document != null) || UserObject.isReplyUser(currentUser) || UserObject.isBotForum(currentUser))) {
                    botInfoEmptyRow = 0;
                    return 1;
                }
                return 0;
            }
            return rowCount;
'@
$new = @'
        public int getItemCount() {
            if (isClearingHistory()) {
                return 0;
            }
            return rowCount;
'@
$text = $text.Replace($old, $new)

# 3. onBindViewHolder block (correct: `setup` lowercase)
$old = @'
            if (position == botInfoRow || position == botInfoEmptyRow) {
                BotHelpCell helpView = (BotHelpCell) holder.itemView;
                if (UserObject.isReplyUser(currentUser)) {
                    helpView.setText(false, LocaleController.getString(R.string.RepliesChatInfo));
                } else if (currentUser != null && currentUser.id == UserObject.VERIFY) {
                    helpView.setText(false, LocaleController.getString(R.string.VerifyChatInfo));
                } else {
                    TL_bots.BotInfo mBotInfo = botInfo.size() != 0 ? botInfo.get(currentUser.id) : null;
                    final boolean setup = (mBotInfo == null || TextUtils.isEmpty(mBotInfo.description) && mBotInfo.description_photo == null && mBotInfo.description_document == null) && UserObject.isBot(currentUser) && userInfo != null && userInfo.bot_manager_id != 0 && currentUser.bot_can_edit;
                    helpView.setText(
                        true,
                        currentUser == null ? 0 : currentUser.id,
                        mBotInfo != null ? mBotInfo.description : null,
                        mBotInfo != null ? mBotInfo.description_document != null ? mBotInfo.description_document : mBotInfo.description_photo : null,
                        mBotInfo,
                        setup ? DialogObject.getName(currentAccount, userInfo.bot_manager_id) : null
                    );
                }
                updateBotHelpCellClick(helpView);
            } else if (position == botForumStartThreadRow) {
'@
$new = @'
            if (position == botForumStartThreadRow) {
'@
$text = $text.Replace($old, $new)

[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
Write-Host 'done'
$text.Split("`n") | ForEach-Object { if ($_ -match 'botInfoRow|botInfoEmptyRow|BotHelpCell|BotAdView|botAdView|showBotAd|botDraftAnimationsPool|didPressCustomBotButton|updateBotHelpCellClick') { Write-Host $_ } }