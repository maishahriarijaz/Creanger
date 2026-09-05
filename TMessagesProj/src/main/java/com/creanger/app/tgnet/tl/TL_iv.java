package com.creanger.app.tgnet.tl;

import com.creanger.app.tgnet.TLObject;
import com.creanger.app.tgnet.InputSerializedData;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal TL_iv (Instant View) compatibility shim.
 */
public class TL_iv {

    public static abstract class RichText extends TLObject {
        public static RichText TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return new textEmpty();
        }
    }
    
    public static class textEmpty extends RichText {}
    public static class textPlain extends RichText {
        public String text;
    }
    public static class textBold extends RichText {
        public RichText text;
    }
    public static class textItalic extends RichText {
        public RichText text;
    }
    public static class textUnderline extends RichText {
        public RichText text;
    }
    public static class textStrike extends RichText {
        public RichText text;
    }
    public static class textFixed extends RichText {
        public RichText text;
    }
    public static class textSubscript extends RichText {
        public RichText text;
    }
    public static class textSuperscript extends RichText {
        public RichText text;
    }
    public static class textSpoiler extends RichText {
        public RichText text;
    }
    public static class textMarked extends RichText {
        public RichText text;
    }
    public static class textUrl extends RichText {
        public RichText text;
        public String url;
    }
    public static class textConcat extends RichText {
        public List<RichText> texts = new ArrayList<>();
    }

    public static abstract class PageBlock extends TLObject {
        public static PageBlock TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return new pageBlockParagraph();
        }
    }
    
    public static class pageBlockParagraph extends PageBlock {
        public RichText text;
    }
    public static class pageBlockTitle extends PageBlock {
        public RichText text;
    }
    public static class pageBlockSubtitle extends PageBlock {
        public RichText text;
    }
    public static class pageBlockHeader extends PageBlock {
        public RichText text;
    }
    public static class pageBlockSubheader extends PageBlock {
        public RichText text;
    }
    public static class pageBlockKicker extends PageBlock {
        public RichText text;
    }
    public static class pageBlockBlockquote extends PageBlock {
        public RichText text;
    }
    public static class pageBlockPullquote extends PageBlock {
        public RichText text;
    }
    public static class pageBlockAuthorDate extends PageBlock {
        public RichText author;
        public int date;
    }
    public static class pageBlockPhoto extends PageBlock {
        public long photo_id;
        public RichText caption;
        public String url;
    }
    public static class pageBlockVideo extends PageBlock {
        public String src;
        public RichText caption;
    }
    public static class pageBlockAudio extends PageBlock {
        public String src;
        public RichText title;
        public RichText performer;
    }
    public static class pageBlockEmbed extends PageBlock {
        public String src;
        public int w;
        public int h;
        public RichText caption;
    }
    public static class pageBlockEmbedPost extends PageBlock {
        public String url;
        public long post_id;
        public RichText caption;
    }
    public static class pageBlockSlideshow extends PageBlock {
        public List<PageBlock> items = new ArrayList<>();
        public RichText caption;
    }
    public static class pageBlockCollage extends PageBlock {
        public List<PageBlock> items = new ArrayList<>();
        public RichText caption;
    }
    public static class pageBlockTable extends PageBlock {
        public List<List<RichText>> rows = new ArrayList<>();
        public List<Boolean> column_align = new ArrayList<>();
    }
    public static class pageBlockDetails extends PageBlock {
        public RichText title;
        public List<PageBlock> blocks = new ArrayList<>();
        public boolean open;
    }
    public static class pageBlockRelatedArticles extends PageBlock {
        public String title;
        public List<PageBlock> blocks = new ArrayList<>();
    }
    public static class pageBlockMap extends PageBlock {
        public double lat;
        public double lng;
        public int zoom;
        public RichText caption;
    }
    public static class pageBlockFooter extends PageBlock {
        public RichText text;
    }
    public static class pageBlockPreformatted extends PageBlock {
        public String text;
        public String language;
    }
    public static class pageBlockDivider extends PageBlock {}
    public static class pageBlockAnchor extends PageBlock {
        public String name;
    }
    public static class pageBlockList extends PageBlock {
        public List<RichText> items = new ArrayList<>();
        public boolean numbered;
    }
    public static class pageBlockBlockquoteBlocks extends PageBlock {
        public RichText text;
        public List<PageBlock> blocks = new ArrayList<>();
    }
    public static class pageBlockMath extends PageBlock {
        public String equation;
    }

    public static class RichMessage extends TLObject {
        public String text;
        public List<PageBlock> blocks = new ArrayList<>();
    }
    public static class pageBlockChannel extends PageBlock { public long channel_id; public int msg_id; }
    public static class pageTableCell extends TLObject { public RichText text; public boolean header; public int colspan; public int rowspan; public int align; public int valign; }
    public static class textAnchor extends RichText { public String name; public RichText text; }
    public static class TL_inputRichMessage extends TLObject {}
    public static class TL_page extends TLObject { public List<PageBlock> blocks = new ArrayList<>(); public List<Object> photos = new ArrayList<>(); public List<Object> documents = new ArrayList<>(); }
    public static class TL_pagePart_layer82 extends TLObject {}
    public static class textBankCard extends TLObject {}
    public static class textPhone extends TLObject {}
    public static class TL_pageListOrderedItemText extends TLObject {}
    public static class textMentionName extends TLObject {}
    public static class TL_pageListItemBlocks extends TLObject {}
    public static class pageBlockUnsupported extends TLObject {}
    public static class pageBlockCover extends TLObject {}
    public static class textMention extends TLObject {}
    public static class Page extends TLObject {}
    public static class PageListOrderedItem extends TLObject {}
    public static class pageBlockHeading4 extends TLObject {}
    public static class pageBlockHeading6 extends TLObject {}
    public static class TL_pageListOrderedItemBlocks extends TLObject {}
    public static class getRichMessage extends TLObject {}
    public static class textEmail extends TLObject {}
    public static class pageRelatedArticle extends TLObject {}
    public static class pageBlockOrderedList extends TLObject {}
    public static class pageBlockHeading2 extends TLObject {}
    public static class textCustomEmoji extends TLObject {}
    public static class textDate extends TLObject {}
    public static class inputPageBlockMap extends TLObject {}
    public static class textAutoUrl extends TLObject {}
    public static class textBotCommand extends TLObject {}
    public static class pageBlockHeading3 extends TLObject {}
    public static class pageBlockHeading5 extends TLObject {}
    public static class textHashtag extends TLObject {}
    public static class pageBlockHeading1 extends TLObject {}
    public static class pageBlockThinking extends TLObject {}
    public static class pageTableRow extends TLObject {}
    public static class PageCaption extends TLObject {}
    public static class textMath extends TLObject {}
    public static class textAutoEmail extends TLObject {}
    public static class TL_pageListItemText extends TLObject {}
    public static class textAutoPhone extends TLObject {}
    public static class textCashtag extends TLObject {}
    public static class PageListItem extends TLObject {}
    public static class textImage extends TLObject {}
}