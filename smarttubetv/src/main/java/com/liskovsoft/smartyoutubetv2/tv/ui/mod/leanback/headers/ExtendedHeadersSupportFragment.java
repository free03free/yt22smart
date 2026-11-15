package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.headers;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import androidx.leanback.app.HeadersSupportFragment;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowHeaderPresenter;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;

public class ExtendedHeadersSupportFragment extends HeadersSupportFragment {
    public interface OnHeaderLongPressedListener {
        /**
         * Called when a header item has been long pressed.
         *
         * @param viewHolder Row ViewHolder object corresponding to the selected Header.
         * @param row Row object corresponding to the selected Header.
         */
        void onHeaderLongPressed(RowHeaderPresenter.ViewHolder viewHolder, Row row);
    }

    private OnHeaderLongPressedListener mOnHeaderLongPressedListener;
    private View mRailTop;
    private View mRailSearch;
    private View mRailAccount;

    public ExtendedHeadersSupportFragment() {
        Helpers.setField(this, "mAdapterListener", mCustomAdapterListener);
    }

    public void setOnHeaderLongPressedListener(OnHeaderLongPressedListener listener) {
        mOnHeaderLongPressedListener = listener;
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (androidx.leanback.widget.SidebarMode.rail) {
            addRailTop(view);
        }
    }

    /** YouTube TV style: account + search buttons live at the top of the sidebar rail. */
    private void addRailTop(View root) {
        if (!(root instanceof RelativeLayout) || root.findViewById(R.id.headers_rail_top) != null) {
            return;
        }

        // Sidebar root has an end padding (outside the gray list area) that pushes icons off-center in the narrow rail
        root.setPaddingRelative(root.getPaddingStart(), root.getPaddingTop(), 0, root.getPaddingBottom());

        View top = LayoutInflater.from(root.getContext()).inflate(R.layout.headers_rail_top, (ViewGroup) root, false);
        // Same px width as the collapsed sidebar (BrowseSupportFragment.getHeadersRailWidth) so buttons and icons share one center line
        int railPx = top.getLayoutParams().width;
        int railResId = root.getResources().getIdentifier("lb_browse_headers_rail_width", "dimen", root.getContext().getPackageName());
        if (railResId != 0) {
            railPx = root.getResources().getDimensionPixelSize(railResId);
        }
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(railPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.ALIGN_PARENT_START);
        ((ViewGroup) root).addView(top, lp);

        mRailTop = top;
        // a fresh start: sizes are measured again for the current orientation
        androidx.leanback.widget.SidebarMode.itemHeightPx = 0;
        androidx.leanback.widget.SidebarMode.iconPx = 0;
        androidx.leanback.widget.SidebarMode.padStartPx = 0;
        mRailSearch = top.findViewById(R.id.rail_search);
        mRailAccount = top.findViewById(R.id.rail_account);
        setupRailKeys();

        // Keep the section list below the account/search buttons. Padding (not margin) keeps the sidebar background uniform.
        androidx.leanback.widget.VerticalGridView grid = getVerticalGridView();
        if (grid != null) {
            applyRailGridPadding(grid);
            grid.setVerticalSpacing(0);
            grid.getViewTreeObserver().addOnGlobalLayoutListener(this::fitRailItems);
        }

        top.findViewById(R.id.rail_search).setOnClickListener(v ->
                SearchPresenter.instance(root.getContext()).startSearch(null));

        ImageView account = top.findViewById(R.id.rail_account);
        account.setOnClickListener(v -> AccountSelectionPresenter.instance(root.getContext()).nextAccountOrDialog());
        account.setOnLongClickListener(v -> {
            AccountSettingsPresenter.instance(root.getContext()).show();
            return true;
        });
        updateRailAccountIcon(account);
        account.postDelayed(() -> updateRailAccountIcon(account), 1_500); // avatar url may arrive later
    }

    /** YouTube TV style: a plain list that scrolls only when needed (stock leanback pins the selected item at a fixed offset). */
    @Override
    public void setAlignment(int windowAlignOffsetTop) {
        if (!androidx.leanback.widget.SidebarMode.rail) {
            super.setAlignment(windowAlignOffsetTop);
            return;
        }

        androidx.leanback.widget.VerticalGridView grid = getVerticalGridView();
        if (grid != null) {
            applyRailGridPadding(grid); // icons must start at the rail edge
            grid.setItemAlignmentOffset(0);
            grid.setItemAlignmentOffsetPercent(androidx.leanback.widget.VerticalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED);
            grid.setWindowAlignmentOffset(0);
            grid.setWindowAlignmentOffsetPercent(50f);
            grid.setWindowAlignment(androidx.leanback.widget.VerticalGridView.WINDOW_ALIGN_BOTH_EDGE);
        }
    }

    private void applyRailGridPadding(androidx.leanback.widget.VerticalGridView grid) {
        float density = grid.getResources().getDisplayMetrics().density;
        grid.setPaddingRelative(0, (int) (94 * density), grid.getPaddingEnd(), (int) (4 * density));
    }

    /**
     * Size rail items from the REAL rail width (px), so icons are always centered and never clipped,
     * and pick the biggest item height that lets all sections fit on screen without scrolling.
     */
    private void fitRailItems() {
        androidx.leanback.widget.VerticalGridView grid = getVerticalGridView();
        if (grid == null || grid.getAdapter() == null || mRailTop == null) {
            return;
        }

        int railW = mRailTop.getWidth(); // inflated at exactly the rail width
        int count = grid.getAdapter().getItemCount();
        int avail = grid.getHeight() - grid.getPaddingTop() - grid.getPaddingBottom();
        if (railW <= 0 || count <= 0 || avail <= 0) {
            return;
        }

        float unit = railW / 72f; // px per "rail dp"
        int h = Math.max((int) (30 * unit), Math.min((int) (48 * unit), avail / count));
        int icon = Math.min((int) (32 * unit), h - (int) (6 * unit));
        int padStart = Math.max(0, (railW - icon) / 2);

        if (h != androidx.leanback.widget.SidebarMode.itemHeightPx
                || icon != androidx.leanback.widget.SidebarMode.iconPx
                || padStart != androidx.leanback.widget.SidebarMode.padStartPx) {
            androidx.leanback.widget.SidebarMode.itemHeightPx = h;
            androidx.leanback.widget.SidebarMode.iconPx = icon;
            androidx.leanback.widget.SidebarMode.padStartPx = padStart;
            grid.setVerticalSpacing(0);
            if (getBridgeAdapter() != null) {
                getBridgeAdapter().notifyDataSetChanged();
            }
        }
    }

    /** Remote navigation between the rail buttons and the section list. */
    private void setupRailKeys() {
        mRailAccount.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return true; // nothing above: don't leave to the (hidden) title view
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                mRailSearch.requestFocus();
                return true;
            }
            return false;
        });

        mRailSearch.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                mRailAccount.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                androidx.leanback.widget.VerticalGridView grid = getVerticalGridView();
                if (grid != null) {
                    grid.setSelectedPosition(0);
                    grid.requestFocus();
                }
                return true;
            }
            return false;
        });
    }

    private void updateRailAccountIcon(ImageView account) {
        Account current = MediaServiceManager.instance().getSelectedAccount();
        if (current != null && current.getAvatarImageUrl() != null) {
            account.setImageTintList(null);
            account.setPadding(4, 4, 4, 4);
            Glide.with(account.getContext()).load(current.getAvatarImageUrl())
                    .apply(RequestOptions.circleCropTransform()).into(account);
        }
    }

    private final ItemBridgeAdapter.AdapterListener mCustomAdapterListener =
            new ItemBridgeAdapter.AdapterListener() {
                @Override
                public void onCreate(final ItemBridgeAdapter.ViewHolder viewHolder) {
                    ItemBridgeAdapter.Wrapper wrapper = (ItemBridgeAdapter.Wrapper) Helpers.getField(ExtendedHeadersSupportFragment.this, "mWrapper");
                    OnLayoutChangeListener layoutChangeListener = (OnLayoutChangeListener) Helpers.getField(ExtendedHeadersSupportFragment.this, "sLayoutChangeListener");

                    View headerView = viewHolder.getViewHolder().view;
                    headerView.setOnClickListener(v -> {
                        OnHeaderClickedListener onHeaderClickedListener =
                                (OnHeaderClickedListener) Helpers.getField(ExtendedHeadersSupportFragment.this, "mOnHeaderClickedListener");
                        if (onHeaderClickedListener != null) {
                            onHeaderClickedListener.onHeaderClicked(
                                    (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                    (Row) viewHolder.getItem());
                        }
                    });

                    // NEW CODE
                    headerView.setOnLongClickListener(v -> {
                        if (mOnHeaderLongPressedListener != null) {
                            mOnHeaderLongPressedListener.onHeaderLongPressed(
                                    (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                    (Row) viewHolder.getItem());
                            return true; // don't provoke single click event
                        }

                        return false; // work as usual
                    });

                    // NEW CODE
                    headerView.setOnKeyListener((v, keyCode, event) -> {
                        if (androidx.leanback.widget.SidebarMode.rail && mRailSearch != null
                                && keyCode == KeyEvent.KEYCODE_DPAD_UP && viewHolder.getAdapterPosition() == 0) {
                            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                mRailSearch.requestFocus();
                            }
                            return true;
                        }

                        if (mOnHeaderLongPressedListener != null) {
                            if (KeyHelpers.isMenuKey(keyCode)) {
                                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                                    mOnHeaderLongPressedListener.onHeaderLongPressed(
                                            (RowHeaderPresenter.ViewHolder) viewHolder.getViewHolder(),
                                            (Row) viewHolder.getItem());
                                }
                            }
                        }

                        return false; // enable navigation events
                    });

                    if (wrapper != null) {
                        viewHolder.itemView.addOnLayoutChangeListener(layoutChangeListener);
                    } else {
                        headerView.addOnLayoutChangeListener(layoutChangeListener);
                    }
                }

            };
}
