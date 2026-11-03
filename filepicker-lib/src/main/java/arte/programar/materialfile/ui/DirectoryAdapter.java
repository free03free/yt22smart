package arte.programar.materialfile.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

import arte.programar.materialfile.R;
import arte.programar.materialfile.utils.FileTypeUtils;

class DirectoryAdapter extends RecyclerView.Adapter<DirectoryAdapter.DirectoryViewHolder> {

    interface SelectionProvider {
        boolean isMultiSelect();

        boolean isSelected(File file);
    }

    interface OnItemLongClickListener {
        void onItemLongClick(View view, int position);
    }

    private final List<File> mFiles;
    private OnItemClickListener mOnItemClickListener;
    private OnItemLongClickListener mOnItemLongClickListener;
    private SelectionProvider mSelection;

    DirectoryAdapter(List<File> files) {
        mFiles = files;
    }

    void setOnItemClickListener(OnItemClickListener listener) {
        mOnItemClickListener = listener;
    }

    void setOnItemLongClickListener(OnItemLongClickListener listener) {
        mOnItemLongClickListener = listener;
    }

    void setSelectionProvider(SelectionProvider provider) {
        mSelection = provider;
    }

    @NonNull
    @Override
    public DirectoryViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);

        return new DirectoryViewHolder(view, mOnItemClickListener, mOnItemLongClickListener);
    }

    @Override
    public void onBindViewHolder(DirectoryViewHolder holder, int position) {
        File currentFile = mFiles.get(position);

        FileTypeUtils.FileType fileType = FileTypeUtils.getFileType(currentFile);
        holder.mFileImage.setImageResource(fileType.getIcon());
        holder.mFileSubtitle.setText(fileType.getDescription());
        holder.mFileTitle.setText(currentFile.getName());

        boolean multi = mSelection != null && mSelection.isMultiSelect();
        boolean selected = multi && mSelection.isSelected(currentFile);
        holder.mCheck.setVisibility(multi ? View.VISIBLE : View.GONE);
        holder.mCheck.setChecked(selected);
        holder.itemView.setActivated(selected);
        holder.itemView.setAlpha(1f);
    }

    @Override
    public int getItemCount() {
        return mFiles.size();
    }

    File getModel(int index) {
        return mFiles.get(index);
    }

    static class DirectoryViewHolder extends RecyclerView.ViewHolder {

        private final ImageView mFileImage;
        private final TextView mFileTitle;
        private final TextView mFileSubtitle;
        private final CheckBox mCheck;

        DirectoryViewHolder(View itemView, final OnItemClickListener clickListener, final OnItemLongClickListener longClickListener) {
            super(itemView);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && clickListener != null) {
                    clickListener.onItemClick(v, pos);
                }
            });

            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && longClickListener != null) {
                    longClickListener.onItemLongClick(v, pos);
                    return true;
                }
                return false;
            });

            mFileImage = itemView.findViewById(R.id.item_file_image);
            mFileTitle = itemView.findViewById(R.id.item_file_title);
            mFileSubtitle = itemView.findViewById(R.id.item_file_subtitle);
            mCheck = itemView.findViewById(R.id.item_file_check);
        }
    }
}
