package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build.VERSION;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.util.TypedValue;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Presenter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.ClickbaitRemover;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.LongClickPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.browse.video.GridFragmentHelper;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.complexcardview.ComplexImageCardView;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

/*
 * A CardPresenter is used to generate Views and bind Objects to them on demand.
 * It contains an Image CardView
 */
public class VideoCardPresenter extends LongClickPresenter {
    private static final String TAG = VideoCardPresenter.class.getSimpleName();
    private int mDefaultBackgroundColor = -1;
    private int mDefaultTextColor = -1;
    private int mSelectedBackgroundColor = -1;
    private int mSelectedTextColor = -1;
    private int mDefaultSubtitleColor = -1;
    private int mSelectedSubtitleColor = -1;
    private int mCardPreviewType;
    private int mThumbQuality;
    private int mWidth;
    private int mHeight;

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = parent.getContext();

        mDefaultBackgroundColor =
            ContextCompat.getColor(context, Helpers.getThemeAttr(context, R.attr.cardDefaultBackground));
        mDefaultTextColor =
                ViewUtil.isLightColor(mDefaultBackgroundColor) ? 0xFF212121 : ContextCompat.getColor(context, R.color.card_default_text);
        mSelectedBackgroundColor =
                ContextCompat.getColor(context, Helpers.getThemeAttr(context, R.attr.cardSelectedBackground));
        boolean selectedIsLight = ViewUtil.isLightColor(mSelectedBackgroundColor);
        mSelectedTextColor = selectedIsLight ?
                ContextCompat.getColor(context, R.color.card_selected_text_grey) : 0xFFFFFFFF;
        mDefaultSubtitleColor = ContextCompat.getColor(context,
                ViewUtil.isLightColor(mDefaultBackgroundColor) ? R.color.card_subtitle_text_dark : R.color.card_subtitle_text);
        mSelectedSubtitleColor = ContextCompat.getColor(context,
                selectedIsLight ? R.color.card_subtitle_text_dark : R.color.card_subtitle_text);

        mCardPreviewType = getCardPreviewType(context);
        mThumbQuality = getThumbQuality(context);

        boolean isCardMultilineTitleEnabled = isCardMultilineTitleEnabled(context);
        boolean isCardMultilineSubtitleEnabled = isCardMultilineSubtitleEnabled(context);
        boolean isCardTextAutoScrollEnabled = isCardTextAutoScrollEnabled(context);
        float cardTextScrollSpeed = getCardTextScrollSpeed(context);

        updateDimensions(context);

        ComplexImageCardView cardView = new ComplexImageCardView(context) {
            @Override
            public void setSelected(boolean selected) {
                updateCardBackgroundColor(this, selected);
                super.setSelected(selected);
            }
        };

        cardView.setTitleLinesNum(isCardMultilineTitleEnabled ? 2 : 1);
        cardView.setContentLinesNum(isCardMultilineSubtitleEnabled ? 2 : 1);
        cardView.enableTextAutoScroll(isCardTextAutoScrollEnabled);
        cardView.setTextScrollSpeed(cardTextScrollSpeed);
        cardView.setFocusable(true);
        cardView.setFocusableInTouchMode(true);
        cardView.enableBadge(isBadgeEnabled());
        cardView.enableTitle(isTitleEnabled());
        cardView.enableContent(isContentEnabled());
        cardView.setBackgroundColor(mDefaultBackgroundColor); // background is temporarily visible during animations
        //if (VERSION.SDK_INT >= 23 && MainUIData.instance(context).isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS)) {
        //    cardView.setForeground(ContextCompat.getDrawable(context, R.drawable.lb_card_outline));
        //}
        applyModernStyle(cardView);
        updateCardBackgroundColor(cardView, false);
        return new ViewHolder(cardView);
    }

    /** Modern look: rounded corners, roomy info area, clearer title typography. */
    private void applyModernStyle(ComplexImageCardView cardView) {
        float d = cardView.getResources().getDisplayMetrics().density;

        if (VERSION.SDK_INT >= 21) {
            final float radius = 10 * d;
            cardView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            cardView.setClipToOutline(true);
        }

        View infoField = cardView.findViewById(R.id.info_field);
        if (infoField != null) {
            infoField.setPadding(Math.round(10 * d), Math.round(8 * d), Math.round(10 * d), Math.round(10 * d));
        }

        TextView titleText = cardView.findViewById(R.id.title_text);
        if (titleText != null) {
            titleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            titleText.setLineSpacing(0f, 1.1f);
            titleText.setIncludeFontPadding(false);
            // Arabic titles hug the right edge, Latin titles the left one
            titleText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            titleText.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            ViewGroup.MarginLayoutParams lp = titleText.getLayoutParams() instanceof ViewGroup.MarginLayoutParams ?
                    (ViewGroup.MarginLayoutParams) titleText.getLayoutParams() : null;
            if (lp != null) {
                lp.leftMargin = 0;
                lp.topMargin = 0;
                titleText.setLayoutParams(lp);
            }
        }

        TextView contentText = cardView.findViewById(R.id.content_text);
        if (contentText != null) {
            contentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            contentText.setIncludeFontPadding(false);
            contentText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            contentText.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        }
    }

    private void updateCardBackgroundColor(ComplexImageCardView view, boolean selected) {
        boolean checked = Boolean.TRUE.equals(view.getTag()); // IPTV multi-select: ticked card
        int backgroundColor = checked ? 0xFF1B5E20 : selected ? mSelectedBackgroundColor : mDefaultBackgroundColor;
        int textColor = checked ? 0xFFFFFFFF : selected ? mSelectedTextColor : mDefaultTextColor;
        int subtitleColor = checked ? 0xFFDDEEDD : selected ? mSelectedSubtitleColor : mDefaultSubtitleColor;

        // Both background colors should be set because the view's
        // background is temporarily visible during animations.
        // NOTE: has visual bug with rounded corners
        //view.setBackgroundColor(backgroundColor);

        View infoField = view.findViewById(R.id.info_field);
        if (infoField != null) {
            infoField.setBackgroundColor(backgroundColor);
        }

        TextView titleText = view.findViewById(R.id.title_text);
        if (titleText != null) {
            titleText.setTextColor(textColor);
        }
        TextView contentText = view.findViewById(R.id.content_text);
        if (contentText != null) {
            contentText.setTextColor(subtitleColor);
        }

        updateCardFrame(view, checked, selected);
    }

    /** Green frame for ticked cards, thin white frame for the focused card. */
    private void updateCardFrame(ComplexImageCardView view, boolean checked, boolean selected) {
        if (VERSION.SDK_INT < 23) {
            return;
        }

        if (checked || selected) {
            float d = view.getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable();
            frame.setColor(android.graphics.Color.TRANSPARENT);
            frame.setStroke(Math.round((checked ? 5 : 2.5f) * d), checked ? 0xFF00E676 : 0xFFFFFFFF);
            frame.setCornerRadius(10 * d);
            view.setForeground(frame);
        } else {
            view.setForeground(null);
        }
    }

    /** Arabic-dominant text: RTL paragraph, hugs the right edge. Latin-dominant: LTR, left edge. */
    private static void applyTextDirection(TextView view, CharSequence text) {
        if (view == null || text == null) {
            return;
        }
        boolean rtl = com.liskovsoft.smartyoutubetv2.common.iptv.ArabicText.isRtl(text);
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        view.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
    }

    /** IPTV "works" marker: every "\u25CF" in a card text is painted green (plain text has no colour). */
    private static CharSequence greenDots(CharSequence text) {
        if (text == null || text.toString().indexOf('\u25CF') < 0) {
            return text;
        }
        android.text.SpannableString out = new android.text.SpannableString(text);
        String str = text.toString();
        for (int i = str.indexOf('\u25CF'); i >= 0; i = str.indexOf('\u25CF', i + 1)) {
            out.setSpan(new android.text.style.ForegroundColorSpan(0xFF00E676), i, i + 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** Ticked cards (IPTV multi-select) get a green frame + green info area so the choice is obvious. */
    private void applyCheckedState(ComplexImageCardView cardView, boolean checked) {
        cardView.setTag(checked ? Boolean.TRUE : null);
        updateCardBackgroundColor(cardView, cardView.isSelected());
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        super.onBindViewHolder(viewHolder, item);

        Video video = (Video) item;

        ComplexImageCardView cardView = (ComplexImageCardView) viewHolder.view;
        Context context = cardView.getContext();

        cardView.setTitleText(greenDots(video.getTitle()));
        cardView.setContentText(greenDots(video.getSecondTitle()));
        applyTextDirection((TextView) cardView.findViewById(R.id.title_text), video.getTitle());
        applyTextDirection((TextView) cardView.findViewById(R.id.content_text), video.getSecondTitle());
        // Count progress that very close to zero. E.g. when user closed video immediately.
        cardView.setProgress(video.percentWatched > 0 && video.percentWatched < 1 ? 1 : Math.round(video.percentWatched));
        cardView.setBadgeText(
                video.hasNewContent ? context.getString(R.string.badge_new_content) :
                video.isLive ? context.getString(R.string.badge_live) :
                video.isShorts ? context.getString(R.string.header_shorts).toUpperCase() :
                video.badge
        );
        cardView.setBadgeColor(video.hasNewContent || video.isLive || video.isUpcoming ?
                ContextCompat.getColor(context, R.color.dark_red) : ContextCompat.getColor(context, R.color.black));
        applyCheckedState(cardView, video.isIptvChecked);

        if (mCardPreviewType != MainUIData.CARD_PREVIEW_DISABLED) {
            cardView.setPreview(video);
            cardView.setMute(mCardPreviewType == MainUIData.CARD_PREVIEW_MUTED);
        }

        cardView.setMainImageDimensions(mWidth, mHeight);

        if (context instanceof Activity && ((Activity) context).isDestroyed()) {
            // Glide.with(context): IllegalArgumentException: You cannot start a load for a destroyed activity
            return;
        }

        Glide.with(context)
                //.asBitmap() // disable animation (webp, gif)
                .load(ClickbaitRemover.updateThumbnail(video, mThumbQuality))
                //.placeholder(mDefaultCardImage)
                .apply(ViewUtil.glideOptions())
                // improve image compression on low end devices
                .override(mWidth, mHeight)
                // com.liskovsoft.smartyoutubetv2.tv.util.CacheGlideModule
                // Cache makes app crashing on old android versions
                .diskCacheStrategy(VERSION.SDK_INT > 21 ? DiskCacheStrategy.ALL : DiskCacheStrategy.NONE)
                .listener(mErrorListener)
                .error(
                    // Updated thumbnail url not found
                    Glide.with(context)
                        .load(video.cardImageUrl) // always working
                        //.placeholder(mDefaultCardImage)
                        .apply(ViewUtil.glideOptions())
                        .listener(mErrorListener)
                        .error(R.drawable.card_placeholder) // R.color.lb_grey
                )
                .into(cardView.getMainImageView());
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        super.onUnbindViewHolder(viewHolder);

        ComplexImageCardView cardView = (ComplexImageCardView) viewHolder.view;

        // Remove references to images so that the garbage collector can free up memory.
        cardView.setBadgeImage(null);
        cardView.setMainImage(null);

        // Cleanup Glide resources. https://chatgpt.com/share/682120c5-e428-8010-b848-371b2dec0cd5
        Glide.with(cardView.getContext().getApplicationContext()).clear(cardView.getMainImageView());
    }

    private void updateDimensions(Context context) {
        Pair<Integer, Integer> dimens = getCardDimensPx(context);

        mWidth = dimens.first;
        mHeight = dimens.second;
    }
    
    protected Pair<Integer, Integer> getCardDimensPx(Context context) {
        return GridFragmentHelper.getCardDimensPx(context, R.dimen.card_width, R.dimen.card_height, MainUIData.instance(context).getVideoGridScale());
    }

    protected boolean isCardTextAutoScrollEnabled(Context context) {
        return MainUIData.instance(context).isCardTextAutoScrollEnabled();
    }

    protected int getCardPreviewType(Context context) {
        return MainUIData.instance(context).getCardPreviewType();
    }

    protected boolean isCardMultilineTitleEnabled(Context context) {
        return MainUIData.instance(context).isCardMultilineTitleEnabled();
    }

    protected boolean isCardMultilineSubtitleEnabled(Context context) {
        return MainUIData.instance(context).isCardMultilineSubtitleEnabled();
    }

    protected float getCardTextScrollSpeed(Context context) {
        return MainUIData.instance(context).getCardTextScrollSpeed();
    }

    protected int getThumbQuality(Context context) {
        return MainUIData.instance(context).getThumbQuality();
    }

    protected boolean isContentEnabled() {
        return true;
    }

    protected boolean isTitleEnabled() {
        return true;
    }

    protected boolean isBadgeEnabled() {
        return true;
    }

    private final RequestListener<Drawable> mErrorListener = new RequestListener<Drawable>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            return false;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };

    private final RequestListener<Bitmap> mErrorListener2 = new RequestListener<Bitmap>() {
        @Override
        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
            Log.e(TAG, "Glide load failed: " + e);
            return false;
        }

        @Override
        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
            return false;
        }
    };
}
