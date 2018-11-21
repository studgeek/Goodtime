package com.apps.adrcotfas.goodtime.Statistics.AllSessions;

import android.os.Bundle;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.apps.adrcotfas.goodtime.Main.LabelsViewModel;
import com.apps.adrcotfas.goodtime.R;
import com.apps.adrcotfas.goodtime.Session;
import com.apps.adrcotfas.goodtime.Statistics.Main.RecyclerItemClickListener;
import com.apps.adrcotfas.goodtime.Statistics.SessionViewModel;
import com.apps.adrcotfas.goodtime.databinding.StatisticsFragmentAllEntriesBinding;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class AllSessionsFragment extends Fragment {

    private AllSessionsAdapter mAdapter;
    private ActionMode mActionMode;
    private List<Long> mSelectedEntries = new ArrayList<>();
    private boolean mIsMultiSelect = false;
    private Menu mMenu;
    private SessionViewModel mSessionViewModel;
    private LabelsViewModel mLabelsViewModel;
    private Session mSessionToEdit;
    private List<Session> mSessions;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        StatisticsFragmentAllEntriesBinding binding = DataBindingUtil.inflate(inflater, R.layout.statistics_fragment_all_entries, container, false);

        mSessionViewModel = ViewModelProviders.of(this).get(SessionViewModel.class);
        mLabelsViewModel = ViewModelProviders.of(getActivity()).get(LabelsViewModel.class);

        View view = binding.getRoot();
        setHasOptionsMenu(true);

        RecyclerView recyclerView = binding.mainRecylcerView;
        recyclerView.setLayoutManager(new LinearLayoutManager(getActivity(), RecyclerView.VERTICAL, false));

        //TODO: this might be dangerous because view might be returned before this is executed
        mLabelsViewModel.getLabels().observe(this, labels -> {
            mAdapter = new AllSessionsAdapter(labels);
            recyclerView.setAdapter(mAdapter);

            mLabelsViewModel.crtExtendedLabel.observe(this, labelAndColor -> refreshCurrentLabel());

            recyclerView.addItemDecoration(new DividerItemDecoration(getActivity(), LinearLayoutManager.VERTICAL));
            recyclerView.addOnItemTouchListener(new RecyclerItemClickListener(getActivity(), recyclerView, new RecyclerItemClickListener.OnItemClickListener() {
                @Override
                public void onItemClick(View view, int position) {
                    if (mIsMultiSelect) {
                        multiSelect(position);
                    }
                }

                @Override
                public void onItemLongClick(View view, int position) {
                    if (!mIsMultiSelect) {
                        mAdapter.setSelectedItems(new ArrayList<>());
                        mIsMultiSelect = true;

                        if (mActionMode == null) {
                            mActionMode = getActivity().startActionMode(mActionModeCallback);
                        }
                    }
                    multiSelect(position);
                }
            }));
        });
        return view;
    }

    private void refreshCurrentLabel() {
        if (mLabelsViewModel.crtExtendedLabel.getValue() != null && mAdapter != null) {
            if (mLabelsViewModel.crtExtendedLabel.getValue().label.equals("total")) {
                mSessionViewModel.getAllSessionsByEndTime().observe(this, sessions -> {
                    mAdapter.setData(sessions);
                    mSessions = sessions;
                });
            } else if (mLabelsViewModel.crtExtendedLabel.getValue().label.equals("unlabeled")) {
                mSessionViewModel.getAllSessionsUnlabeled().observe(this, sessions -> {
                    mAdapter.setData(sessions);
                    mSessions = sessions;
                });
            } else {
                mSessionViewModel.getSessions(mLabelsViewModel.crtExtendedLabel.getValue().label).observe(this, sessions -> {
                    mAdapter.setData(sessions);
                    mSessions = sessions;
                });
            }
        }
    }

    public void multiSelect(int position) {
        Session s = mAdapter.mEntries.get(position);
        if (s != null) {
            if (mActionMode != null) {
                if (mSelectedEntries.contains(s.id)) {
                    mSelectedEntries.remove(s.id);
                }  else {
                    mSelectedEntries.add(s.id);
                }
                if (mSelectedEntries.size() == 1) {
                    mMenu.getItem(0).setVisible(true);
                    mActionMode.setTitle(String.valueOf(mSelectedEntries.size()));
                } else if (mSelectedEntries.size() > 1) {
                    mMenu.getItem(0).setVisible(false);
                    mActionMode.setTitle(String.valueOf(mSelectedEntries.size()));
                }  else {
                    mActionMode.setTitle("");
                    mActionMode.finish();
                }
                mAdapter.setSelectedItems(mSelectedEntries);

                // hack bellow to avoid multiple dialogs because of observe
                if (mSelectedEntries.size() == 1) {
                    final Long sessionId = mAdapter.mSelectedEntries.get(0);
                    mSessionViewModel.getSession(sessionId).observe(AllSessionsFragment.this, session -> mSessionToEdit = session);
                }
            }
        }
    }

    private void deleteSessions() {
        for (Long i : mAdapter.mSelectedEntries) {
            mSessionViewModel.deleteSession(i);
        }
        mAdapter.mSelectedEntries.clear();
        if (mActionMode != null) {
            mActionMode.finish();
        }
    }

    private void selectAll() {
        mSelectedEntries.clear();
        for (int i = 0; i < mSessions.size(); ++i) {
            mSelectedEntries.add(i, mSessions.get(i).id);
        }

        if (mSelectedEntries.size() == 1) {
            mMenu.getItem(0).setVisible(true);
            mActionMode.setTitle(String.valueOf(mSelectedEntries.size()));
            mAdapter.setSelectedItems(mSelectedEntries);
        } else if (mSelectedEntries.size() > 1) {
            mMenu.getItem(0).setVisible(false);
            mActionMode.setTitle(String.valueOf(mSelectedEntries.size()));
            mAdapter.setSelectedItems(mSelectedEntries);
        }  else {
            mActionMode.setTitle("");
            mActionMode.finish();
        }
    }

    private ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            mMenu = menu;
            inflater.inflate(R.menu.menu_all_entries_selection, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false; // Return false if nothing is done
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.action_edit:
                    if (mSessionToEdit != null) {
                        FragmentManager fragmentManager = getActivity().getSupportFragmentManager();
                        AddEditEntryDialog newFragment = AddEditEntryDialog.newInstance(mSessionToEdit);
                        newFragment.show(fragmentManager, "");
                        mActionMode.finish();
                    }
                    break;
                case R.id.action_select_all:
                    selectAll();
                    break;
                case R.id.action_delete:
                    new AlertDialog.Builder(getActivity())
                            .setTitle("Delete selected entries?")
                            .setPositiveButton("OK", (dialog, id) -> deleteSessions())
                            .setNegativeButton("Cancel", (dialog, id) -> dialog.cancel())
                    .show();
                    break;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
            mIsMultiSelect = false;
            mSelectedEntries = new ArrayList<>();
            mAdapter.setSelectedItems(new ArrayList<>());
        }
    };
}