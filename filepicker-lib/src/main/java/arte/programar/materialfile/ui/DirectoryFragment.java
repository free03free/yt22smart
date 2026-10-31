package arte.programar.materialfile.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.io.File;

import arte.programar.materialfile.R;
import arte.programar.materialfile.filter.FileFilter;
import arte.programar.materialfile.utils.FileUtils;
import arte.programar.materialfile.widget.EmptyRecyclerView;

public class DirectoryFragment extends Fragment {

    private static final String ARG_FILE = "arg_file_path";
    private static final String ARG_FILTER = "arg_filter";

    private View mEmptyView;
    private File mFile;

    private FileFilter mFilter;

    private EmptyRecyclerView mDirectoryRecyclerView;
    private DirectoryAdapter mDirectoryAdapter;
    private FileClickListener mFileClickListener;

    static DirectoryFragment getInstance(
            File file,
            FileFilter filter
    ) {
        final DirectoryFragment instance = new DirectoryFragment();

        final Bundle args = new Bundle();
        args.putSerializable(ARG_FILE, file);
        args.putSerializable(ARG_FILTER, filter);
        instance.setArguments(args);

        return instance;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mFileClickListener = (FileClickListener) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mFileClickListener = null;
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mDirectoryRecyclerView = view.findViewById(R.id.directory_recycler_view);
        mEmptyView = view.findViewById(R.id.directory_empty_view);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initArgs();
        initFilesList();
    }

    private long mLastDirClickMs;

    private void initFilesList() {
        mDirectoryAdapter = new DirectoryAdapter(FileUtils.getFileList(mFile, mFilter));

        mDirectoryAdapter.setSelectionProvider(new DirectoryAdapter.SelectionProvider() {
            @Override
            public boolean isMultiSelect() {
                return mFileClickListener != null && mFileClickListener.isMultiSelect();
            }

            @Override
            public boolean isSelected(File file) {
                return mFileClickListener != null && mFileClickListener.isSelected(file);
            }
        });

        mDirectoryAdapter.setOnItemClickListener((view, position) -> {
            if (mFileClickListener == null) {
                return;
            }

            File file = mDirectoryAdapter.getModel(position);

            if (file.isDirectory()) {
                // throttle only the navigation, so quick ticking of files is never dropped
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - mLastDirClickMs < 600) {
                    return;
                }
                mLastDirClickMs = now;
                mFileClickListener.onFileClicked(file);
            } else if (mFileClickListener.isMultiSelect()) {
                // stay in place: just tick / untick the file
                mFileClickListener.onFileToggled(file);
                mDirectoryAdapter.notifyItemChanged(position);
            } else {
                mFileClickListener.onFileClicked(file);
            }
        });

        mDirectoryAdapter.setOnItemLongClickListener((view, position) -> {
            if (mFileClickListener == null || !mFileClickListener.isMultiSelect()) {
                return;
            }

            // long press: tick a file or a whole folder
            mFileClickListener.onFileToggled(mDirectoryAdapter.getModel(position));
            mDirectoryAdapter.notifyItemChanged(position);
        });

        mDirectoryRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
        mDirectoryRecyclerView.setAdapter(mDirectoryAdapter);
        mDirectoryRecyclerView.setEmptyView(mEmptyView);
    }

    private void initArgs() {
        final Bundle arguments = requireArguments();

        if (arguments.containsKey(ARG_FILE)) {
            mFile = (File) getArguments().getSerializable(ARG_FILE);
        }

        mFilter = (FileFilter) getArguments().getSerializable(ARG_FILTER);
    }

    interface FileClickListener {
        void onFileClicked(File clickedFile);

        boolean isMultiSelect();

        boolean isSelected(File file);

        void onFileToggled(File file);
    }
}
