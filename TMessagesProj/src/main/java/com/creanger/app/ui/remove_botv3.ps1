param()
$ErrorActionPreference = 'Stop'

$path = 'C:\Users\shahriar ijaz\Desktop\Telegram-master-business-partial-removal-v12\Telegram-master\TMessagesProj\src\main\java\org\telegram\ui\ChatActivity.java'
$lines = [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)

function GetTrim([string]$s) { if ($null -eq $s) { return '' } return $s.Trim() }

# ---------- 1. single line deletions ----------
$singleDeletes = @(
  'private BotAdView botAdView;',
  'botAdView = null;',
  'private final BotForumHelper.BotDraftAnimationsPool botDraftAnimationsPool = new BotForumHelper.BotDraftAnimationsPool();',
  'chatMessageCell.draftAnimationsPool = botDraftAnimationsPool;',
  'boolean showBotAd = currentUser != null && currentUser.bot && messages.size() >= 2 && botSponsoredMessage != null;',
  'topPanelLayout.setViewVisible(botAdView, showBotAd, animated);',
  'private int botInfoRow = -5;',
  'private int botInfoEmptyRow = -5;',
  'botInfoRow = -5;',
  'int prevBotInfoRow = botInfoRow;',
  'botInfoEmptyRow = -5;'
)

foreach ($d in $singleDeletes) {
  $out = New-Object System.Collections.Generic.List[string]
  $cnt = 0
  foreach ($l in $lines) {
    if ((GetTrim $l) -eq $d) { $cnt++ } else { $out.Add($l) }
  }
  if ($cnt -gt 0) { Write-Host "deleted x$cnt : $d" }
  $lines = $out.ToArray()
}

# ---------- 2. block deletions ----------
function RemoveBlock([string[]]$src, [string]$startContent) {
  for ($i = 0; $i -lt $src.Count; $i++) {
    if ((GetTrim $src[$i]) -ne $startContent) { continue }
    $startIdx = $i
    if ($i -gt 0 -and (GetTrim $src[$i - 1]) -eq '@Override') {
      if ($i -gt 1 -and (GetTrim $src[$i - 2]) -eq '') { $startIdx = $i - 2 } else { $startIdx = $i - 1 }
    }
    $depth = 0
    $j = $i
    for (; $j -lt $src.Count; $j++) {
      $a = ([regex]::Matches($src[$j], '\{')).Count
      $c = ([regex]::Matches($src[$j], '\}')).Count
      $depth += $a - $c
      if ($j -gt $i -and $depth -le 0) { break }
    }
    $out = New-Object System.Collections.Generic.List[string]
    for ($k = 0; $k -lt $src.Count; $k++) { if ($k -lt $startIdx -or $k -gt $j) { $out.Add($src[$k]) } }
    Write-Host "block removed [$startContent] (orig lines $i..$j)"
    return ,$out.ToArray()
  }
  Write-Host "BLOCK NOT FOUND: $startContent"
  return ,$src
}

$lines = RemoveBlock $lines 'if (showBotAd) {'
$lines = RemoveBlock $lines 'private void createBotAdView() {'
$lines = RemoveBlock $lines 'public void didPressCustomBotButton(ChatMessageCell cell, BotInlineKeyboard.ButtonCustom button) {'
$lines = RemoveBlock $lines 'private void updateBotHelpCellClick(BotHelpCell cell) {'

# ---------- 3. literal replacements ----------
$text = [string]::Join("`n", $lines)

# 3a. drawChatMessageElements: remove BotHelpCell branch
$old = @'
                    if (chatAdapter.isBot && child instanceof BotHelpCell) {
                        BotHelpCell botCell = (BotHelpCell) child;
                        float top = (getMeasuredHeight() - chatListViewPaddingTop - blurredViewBottomOffset) / 2 - child.getMeasuredHeight() / 2 + chatListViewPaddingTop;
                        if (!botCell.animating() && !chatListView.fastScrollAnimationRunning) {
                            if (child.getTop() > top) {
                                child.setTranslationY(top - child.getTop());
                            } else {
                                child.setTranslationY(0);
                            }
                        }
                        break;
                    } else if (child instanceof UserInfoCell) {
'@
$new = @'
                    if (child instanceof UserInfoCell) {
'@
$text = $text.Replace($old, $new)

# 3b. side menu update else-if (BotAskCell comes before BotHelpCell)
$old = @'
                } else if (view instanceof BotAskCell) {
                    view.invalidate();
                } else if (view instanceof BotHelpCell) {
                    view.invalidate();
                }
'@
$new = @'
                } else if (view instanceof BotAskCell) {
                    view.invalidate();
                }
'@
$text = $text.Replace($old, $new)

# 3b2. scroll visible part else-if (before ChatLoadingCell)
$old = @'
            } else if (view instanceof BotHelpCell) {
                view.invalidate();
            } else if (view instanceof ChatLoadingCell) {
'@
$new = @'
            } else if (view instanceof ChatLoadingCell) {
'@
$text = $text.Replace($old, $new)

# 3c. createViewHolder viewType == 3
$old = @'
            } else if (viewType == 3) {
                view = new BotHelpCell(mContext, currentAccount, themeDelegate) {
                    @Override
                    public int getSideMenuWidth() {
                        return ChatActivity.this.getSideMenuWidth();
                    }
                };
                ((BotHelpCell) view).setDelegate(url -> {
                    if (url.startsWith("@")) {
                        getMessagesController().openByUserName(url.substring(1), ChatActivity.this, 0);
                    } else if (url.startsWith("#") || url.startsWith("$")) {
                        DialogsActivity fragment = new DialogsActivity(null);
                        fragment.setSearchString(url);
                        presentFragment(fragment);
                    } else if (url.startsWith("/")) {
                        chatActivityEnterView.setCommand(null, url, false, false);
                        if (chatActivityEnterView.getFieldText() == null) {
                            hideFieldPanel(false);
                        }
                    } else {
                        processExternalUrl(0, url, null, null, false, false);
                    }
                });
            } else if (viewType == 4) {
'@
$new = @'
            } else if (viewType == 4) {
'@
$text = $text.Replace($old, $new)

# 3d. onBindViewHolder: bot info block
$old = @'
            if (position == botInfoRow || position == botInfoEmptyRow) {
                BotHelpCell helpView = (BotHelpCell) holder.itemView;
                if (UserObject.isReplyUser(currentUser)) {
                    helpView.setText(false, LocaleController.getString(R.string.RepliesChatInfo));
                } else if (currentUser != null && currentUser.id == UserObject.VERIFY) {
                    helpView.setText(false, LocaleController.getString(R.string.VerifyChatInfo));
                } else {
                    TL_bots.BotInfo mBotInfo = botInfo.size() != 0 ? botInfo.get(currentUser.id) : null;
                    final boolean setUp = (mBotInfo == null || TextUtils.isEmpty(mBotInfo.description) && mBotInfo.description_photo == null && mBotInfo.description_document == null) && UserObject.isBot(currentUser) && userInfo != null && userInfo.bot_manager_id != 0 && currentUser.bot_can_edit;
                    helpView.setText(
                        true,
                        currentUser == null ? 0 : currentUser.id,
                        mBotInfo != null ? mBotInfo.description : null,
                        mBotInfo != null ? mBotInfo.description_document != null ? mBotInfo.description_document : mBotInfo.description_photo : null,
                        mBotInfo,
                        setUp ? DialogObject.getName(currentAccount, userInfo.bot_manager_id) : null
                    );
                }
                updateBotHelpCellClick(helpView);
            } else if (position == botForumStartThreadRow) {
'@
$new = @'
            if (position == botForumStartThreadRow) {
'@
$text = $text.Replace($old, $new)

# 3e. getItemId
$old = @'
        public long getItemId(int position) {
            if (isClearingHistory()) {
                if (position == botInfoEmptyRow) {
                    return 1;
                }
            }
'@
$new = @'
        public long getItemId(int position) {
'@
$text = $text.Replace($old, $new)

$old = @'
            } else if (position == botInfoRow || position == botInfoEmptyRow) {
                return 1;
            } else if (position == loadingUpRow) {
'@
$new = @'
            } else if (position == loadingUpRow) {
'@
$text = $text.Replace($old, $new)

# 3f. getItemViewType
$old = @'
        public int getItemViewType(int position) {
            if (isClearingHistory()) {
                if (position == botInfoEmptyRow) {
                    return 3;
                }
            }
'@
$new = @'
        public int getItemViewType(int position) {
'@
$text = $text.Replace($old, $new)

$old = @'
            } else if (position == botInfoRow) {
                return 3;
            } else if (position == userInfoRow) {
'@
$new = @'
            } else if (position == userInfoRow) {
'@
$text = $text.Replace($old, $new)

# 3g. getItemCount
$old = @'
        public int getItemCount() {
            botInfoEmptyRow = -5;
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

# 3h. updateRowsSafe
$old = @'
            int prevRowCount = rowCount;
            int prevBotInfoRow = botInfoRow;
            int prevUserInfoRow = userInfoRow;
'@
$new = @'
            int prevRowCount = rowCount;
            int prevUserInfoRow = userInfoRow;
'@
$text = $text.Replace($old, $new)

$old = @'
            if (prevRowCount != rowCount || prevBotInfoRow != botInfoRow ||
'@
$new = @'
            if (prevRowCount != rowCount ||
'@
$text = $text.Replace($old, $new)

# 3i. updateRowsInternal bot rows
$old = @'
                } else if ((UserObject.isReplyUser(currentUser) || currentUser != null && currentUser.bot && !MessagesController.isSupportUser(currentUser) && chatMode == MODE_DEFAULT) && endReached[0]) {
                    botInfoRow = rowCount++;
                }
'@
$new = ''
$text = $text.Replace($old, $new)

$old = @'
                } else if (UserObject.isReplyUser(currentUser) || currentUser != null && currentUser.bot && !MessagesController.isSupportUser(currentUser) && chatMode == MODE_DEFAULT) {
                    botInfoRow = rowCount++;
                }
'@
$text = $text.Replace($old, $new)

# 3j. didUpdateBotInfo handler
$old = @'
                    if (chatAdapter != null) {
                        int prevRow = chatAdapter.botInfoRow;
                        chatAdapter.updateRowsInternal();
                        if (prevRow < 0 && chatAdapter.botInfoRow >= 0) {
                            chatAdapter.notifyItemInserted(chatAdapter.botInfoRow);
                        } else if (prevRow >= 0 && chatAdapter.botInfoRow < 0) {
                            chatAdapter.notifyItemRemoved(prevRow);
                        } else if (prevRow >= 0 && chatAdapter.botInfoRow >= 0) {
                            chatAdapter.notifyItemChanged(chatAdapter.botInfoRow);
                        }
                    }
'@
$new = @'
                    if (chatAdapter != null) {
                        chatAdapter.updateRowsInternal();
                    }
'@
$text = $text.Replace($old, $new)

# 3k. remaining one-liners
$text = $text.Replace('&& chatAdapter.botInfoRow < 0', '')
$text = $text.Replace('showProgressView(chatAdapter.botInfoRow < 0);', 'showProgressView(true);')
$text = $text.Replace('new Class[]{ChatMessageCell.class, BotHelpCell.class}', 'new Class[]{ChatMessageCell.class}')

[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host '=== residual references ==='
$text.Split("`n") | ForEach-Object { if ($_ -match 'botInfoRow|botInfoEmptyRow|BotHelpCell|BotAdView|botAdView|showBotAd|botDraftAnimationsPool|didPressCustomBotButton|updateBotHelpCellClick') { Write-Host $_ } }
Write-Host '=== done ==='