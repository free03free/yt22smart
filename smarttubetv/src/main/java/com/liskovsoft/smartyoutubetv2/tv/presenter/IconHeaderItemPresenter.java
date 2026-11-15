package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.PageRow;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowHeaderPresenter;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.util.ViewUtil;

public class IconHeaderItemPresenter extends RowHeaderPresenter {
    private static final String TAG = IconHeaderItemPresenter.class.getSimpleName();
    private float mUnselectedAlpha;
    private final int mResId;
    private final String mIconUrl;
    private Drawable mDefaultIcon;

    public IconHeaderItemPresenter(int resId, String iconUrl) {
        mResId = resId;
        mIconUrl = iconUrl;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup) {
        mUnselectedAlpha = androidx.leanback.widget.SidebarMode.rail ? 0.85f : viewGroup.getResources()
                .getFraction(R.fraction.lb_browse_header_unselect_alpha, 1, 1);
        LayoutInflater inflater = (LayoutInflater) viewGroup.getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mDefaultIcon = new ColorDrawable(ContextCompat.getColor(viewGroup.getContext(), R.color.lb_grey));

        View view = inflater.inflate(androidx.leanback.widget.SidebarMode.rail ? R.layout.icon_header_item_rail : R.layout.icon_header_item, null);
        view.setAlpha(mUnselectedAlpha); // Initialize icons to be at half-opacity.

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        HeaderItem headerItem;

        if (item instanceof PageRow) {
            headerItem = ((PageRow) item).getHeaderItem();
        } else {
            headerItem = ((ListRow) item).getHeaderItem();
        }

        View rootView = viewHolder.view;
        rootView.setFocusable(true);
        if (androidx.leanback.widget.SidebarMode.rail) {
            applyRailSize(rootView);
        }

        ImageView iconView = rootView.findViewById(R.id.header_icon);
        if (iconView != null) {
            // Layout tints icons with a focus selector (dark on white pill). Remote icons (channel avatars) must keep original colors.
            if (mIconUrl != null && androidx.leanback.widget.SidebarMode.rail) {
                iconView.setImageTintList(null);
                iconView.setPadding(0, 0, 0, 0);
            }
            if (mIconUrl != null) {
                Glide.with(rootView.getContext())
                        .load(mIconUrl)
                        .apply(ViewUtil.glideOptions().error(mDefaultIcon))
                        .listener(mErrorListener)
                        .into(iconView);

                //ViewUtil.makeMonochrome(iconView);
            } else {
                Drawable icon = mResId > 0 ? ContextCompat.getDrawable(rootView.getContext(), mResId) : mDefaultIcon;
                iconView.setImageDrawable(icon);
            }
        }

        boolean lightSidebar = ViewUtil.isLightColor(
                ViewUtil.getThemeColor(rootView.getContext(), R.attr.brandColor));

        if (lightSidebar && iconView != null && mIconUrl == null) {
            iconView.setColorFilter(0xFF424242, android.graphics.PorterDuff.Mode.SRC_IN);
        }

        TextView label = rootView.findViewById(R.id.header_label);
        if (label != null) {
            label.setText(headerItem.getName());
            if (lightSidebar) {
                label.setTextColor(0xFF212121);
            }
        }
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        // NOP
    }

    // TODO: This is a temporary fix. Remove me when leanback onCreateViewHolder no longer sets the
    // mUnselectAlpha, and also assumes the xml inflation will return a RowHeaderView.
    @Override
    protected void onSelectLevelChanged(RowHeaderPresenter.ViewHolder holder) {
        holder.view.setAlpha(mUnselectedAlpha + holder.getSelectLevel() *
                (1.0f - mUnselectedAlpha));
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

    /** Rail mode: apply the measured (px) item height / icon size so that every section fits and is centered in the rail. */
    private void applyRailSize(View rootView) {
        int h = androidx.leanback.widget.SidebarMode.itemHeightPx;
        int icon = androidx.leanback.widget.SidebarMode.iconPx;
        int padStart = androidx.leanback.widget.SidebarMode.padStartPx;
        if (h <= 0 || icon <= 0) {
            return;
        }

        ViewGroup.LayoutParams lp = rootView.getLayoutParams();
        if (lp == null) {
            lp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        } else {
            lp.height = h;
        }
        rootView.setLayoutParams(lp);

        ImageView iconView = rootView.findViewById(R.id.header_icon);
        if (iconView != null) {
            ViewGroup.LayoutParams ilp = iconView.getLayoutParams();
            ilp.width = icon;
            ilp.height = icon;
            iconView.setLayoutParams(ilp);
            iconView.setPadding(0, 0, 0, 0);
        }
        rootView.setPaddingRelative(padStart, 0, rootView.getPaddingEnd(), 0);
    }
}
