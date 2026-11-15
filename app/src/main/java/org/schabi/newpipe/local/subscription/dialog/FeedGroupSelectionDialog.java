package org.schabi.newpipe.local.subscription.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast; // ENH:group-dialog

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.NewPipeDatabase; // ENH:group-dialog
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.feed.model.FeedGroupEntity;
import org.schabi.newpipe.enhancements.groupdialog.GroupDialogSession; // ENH:group-dialog
import org.schabi.newpipe.enhancements.groupdialog.NamePrompt; // ENH:group-dialog
import org.schabi.newpipe.enhancements.groupdialog.PendingGroup; // ENH:group-dialog
import org.schabi.newpipe.enhancements.groupdialog.RoomGroupTable; // ENH:group-dialog
import org.schabi.newpipe.enhancements.groupdialog.RxChangeSaver; // ENH:group-dialog
import org.schabi.newpipe.enhancements.groupdialog.SelectionUi; // ENH:group-dialog
import org.schabi.newpipe.local.feed.FeedDatabaseManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class FeedGroupSelectionDialog extends DialogFragment implements SelectionUi { // ENH:group-dialog
    private static final String KEY_SUBSCRIPTION_ID = "subscription_id";

    private long subscriptionId;
    private List<FeedGroupEntity> allGroups = new ArrayList<>();
    private Set<Long> selectedGroupIds = new HashSet<>();
    private Set<Long> originalGroupIds = new HashSet<>();
    private GroupSelectionAdapter adapter;
    private CompositeDisposable disposables = new CompositeDisposable();
    private FeedDatabaseManager feedDatabaseManager;
    private boolean isDataLoaded = false;
    private GroupDialogSession session; // ENH:group-dialog
    private RxChangeSaver saver; // ENH:group-dialog
    private List<PendingGroup> pending = new ArrayList<>(); // ENH:group-dialog
    private boolean busy = false; // ENH:group-dialog
    private RecyclerView recyclerView; // ENH:group-dialog
    private Button saveButton; // ENH:group-dialog
    private Button cancelButton; // ENH:group-dialog
    private Button newGroupButton; // ENH:group-dialog
    private TextView loadError; // ENH:group-dialog
    private NamePrompt namePrompt; // ENH:group-dialog

    public static FeedGroupSelectionDialog newInstance(long subscriptionId) {
        FeedGroupSelectionDialog dialog = new FeedGroupSelectionDialog();
        Bundle args = new Bundle();
        args.putLong(KEY_SUBSCRIPTION_ID, subscriptionId);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        subscriptionId = getArguments().getLong(KEY_SUBSCRIPTION_ID);
        feedDatabaseManager = new FeedDatabaseManager(requireContext());

        // Initialize fresh state
        allGroups = new ArrayList<>();
        selectedGroupIds = new HashSet<>();
        originalGroupIds = new HashSet<>();
        isDataLoaded = false;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_feed_group_selection, null);

        setupRecyclerView(view);
        setupButtons(view);
        setupSession(view); // ENH:group-dialog
        loadDataSynchronously();

        return new AlertDialog.Builder(requireContext())
                .setView(view)
                .create();
    }

    private void setupRecyclerView(View view) {
        recyclerView = view.findViewById(R.id.groups_recycler_view); // ENH:group-dialog
        adapter = new GroupSelectionAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
    }

    private void setupButtons(View view) {
        cancelButton = view.findViewById(R.id.cancel_button); // ENH:group-dialog
        saveButton = view.findViewById(R.id.save_button); // ENH:group-dialog

        cancelButton.setOnClickListener(v -> dismiss());
        saveButton.setOnClickListener(v -> saveChanges());
    }

    private void setupSession(View view) { // ENH:group-dialog
        saver = new RxChangeSaver( // ENH:group-dialog
                new RoomGroupTable(NewPipeDatabase.getInstance(requireContext()))); // ENH:group-dialog
        session = new GroupDialogSession(subscriptionId, saver, this); // ENH:group-dialog
        loadError = view.findViewById(R.id.enh_group_dialog_load_error); // ENH:group-dialog
        newGroupButton = view.findViewById(R.id.enh_group_dialog_new_group_button); // ENH:group-dialog
        newGroupButton.setOnClickListener(v -> openNamePrompt()); // ENH:group-dialog
    } // ENH:group-dialog
    private void openNamePrompt() { // ENH:group-dialog
        if (namePrompt != null) { // ENH:group-dialog
            namePrompt.dismiss(); // ENH:group-dialog
        } // ENH:group-dialog
        namePrompt = new NamePrompt(requireContext(), session::submitName); // ENH:group-dialog
        namePrompt.show(); // ENH:group-dialog
    } // ENH:group-dialog
    private void loadDataSynchronously() {
        // Load all data in a single chain to avoid race conditions
        Disposable loadDisposable = feedDatabaseManager.groups()
                .firstOrError()
                .flatMap(groups -> {
                    // Store groups
                    allGroups.clear();
                    allGroups.addAll(groups);

                    // Now load group memberships for each group
                    List<Single<GroupMembershipInfo>> membershipChecks = new ArrayList<>();

                    for (FeedGroupEntity group : groups) {
                        Single<GroupMembershipInfo> membershipCheck = feedDatabaseManager
                                .subscriptionIdsForGroup(group.getUid())
                                .firstOrError()
                                .map(subscriptionIds -> new GroupMembershipInfo(
                                        group.getUid(),
                                        subscriptionIds.contains(subscriptionId)
                                ));
                        membershipChecks.add(membershipCheck);
                    }

                    // Combine all membership checks
                    if (membershipChecks.isEmpty()) {
                        return Single.just(new ArrayList<GroupMembershipInfo>());
                    }

                    return Single.zip(membershipChecks, objects -> {
                        List<GroupMembershipInfo> results = new ArrayList<>();
                        for (Object obj : objects) {
                            results.add((GroupMembershipInfo) obj);
                        }
                        return results;
                    });
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        membershipInfos -> {
                            // Clear and populate selected groups
                            selectedGroupIds.clear();
                            originalGroupIds.clear();

                            for (GroupMembershipInfo info : membershipInfos) {
                                if (info.isMember) {
                                    selectedGroupIds.add(info.groupId);
                                    originalGroupIds.add(info.groupId);
                                }
                            }

                            isDataLoaded = true;
                            adapter.notifyDataSetChanged();
                            List<String> names = new ArrayList<>(); // ENH:group-dialog
                            for (FeedGroupEntity group : allGroups) { // ENH:group-dialog
                                names.add(group.getName()); // ENH:group-dialog
                            } // ENH:group-dialog
                            session.onLoaded(names); // ENH:group-dialog
                        },
                        throwable -> {
                            // Handle error
                            isDataLoaded = true;
                            adapter.notifyDataSetChanged();
                            session.onLoadFailed(); // ENH:group-dialog
                        }
                );

        disposables.add(loadDisposable);
    }

    private void saveChanges() {
        if (!isDataLoaded) {
            return; // Don't save if data isn't loaded yet
        }

        session.confirm(selectedGroupIds, originalGroupIds); // ENH:group-dialog
    }

    @Override // ENH:group-dialog
    public void showPending(@NonNull List<PendingGroup> shown) { // ENH:group-dialog
        pending = shown; // ENH:group-dialog
        adapter.notifyDataSetChanged(); // ENH:group-dialog
        if (!shown.isEmpty()) { // ENH:group-dialog
            recyclerView.scrollToPosition(adapter.getItemCount() - 1); // ENH:group-dialog
        } // ENH:group-dialog
    } // ENH:group-dialog
    @Override // ENH:group-dialog
    public void showLoaded() { // ENH:group-dialog
        newGroupButton.setEnabled(true); // ENH:group-dialog
    } // ENH:group-dialog
    @Override // ENH:group-dialog
    public void showLoadError() { // ENH:group-dialog
        recyclerView.setVisibility(View.GONE); // ENH:group-dialog
        loadError.setVisibility(View.VISIBLE); // ENH:group-dialog
    } // ENH:group-dialog
    @Override // ENH:group-dialog
    public void setBusy(boolean nowBusy) { // ENH:group-dialog
        busy = nowBusy; // ENH:group-dialog
        saveButton.setEnabled(!busy); // ENH:group-dialog
        cancelButton.setEnabled(!busy); // ENH:group-dialog
        newGroupButton.setEnabled(!busy); // ENH:group-dialog
        setCancelable(!busy); // ENH:group-dialog
        adapter.notifyDataSetChanged(); // ENH:group-dialog
    } // ENH:group-dialog
    @Override // ENH:group-dialog
    public void showSaveError() { // ENH:group-dialog
        Toast.makeText(requireContext(), R.string.enh_group_dialog_save_failed, // ENH:group-dialog
                Toast.LENGTH_LONG).show(); // ENH:group-dialog
    } // ENH:group-dialog
    @Override // ENH:group-dialog
    public void close() { // ENH:group-dialog
        dismissAllowingStateLoss(); // ENH:group-dialog
    } // ENH:group-dialog
    @Override
    public void onDestroy() {
        super.onDestroy();
        disposables.clear();
        if (namePrompt != null) { // ENH:group-dialog
            namePrompt.dismiss(); // ENH:group-dialog
        } // ENH:group-dialog
        if (saver != null) { // ENH:group-dialog
            saver.detach(); // ENH:group-dialog
        } // ENH:group-dialog
    }

    // Helper class to store group membership information
    private static class GroupMembershipInfo {
        final long groupId;
        final boolean isMember;

        GroupMembershipInfo(long groupId, boolean isMember) {
            this.groupId = groupId;
            this.isMember = isMember;
        }
    }

    private class GroupSelectionAdapter extends RecyclerView.Adapter<GroupViewHolder> {
        @NonNull
        @Override
        public GroupViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_feed_group_selection, parent, false);
            return new GroupViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull GroupViewHolder holder, int position) {
            if (position < allGroups.size()) {
                FeedGroupEntity group = allGroups.get(position);
                holder.bind(group);
            } else if (position - allGroups.size() < pending.size()) { // ENH:group-dialog
                holder.bindPending(pending.get(position - allGroups.size())); // ENH:group-dialog
            } // ENH:group-dialog
        }

        @Override
        public int getItemCount() {
            return allGroups.size() + pending.size(); // ENH:group-dialog
        }
    }

    private class GroupViewHolder extends RecyclerView.ViewHolder {
        private final CheckBox checkBox;
        private final TextView groupName;
        private boolean isBinding = false;

        public GroupViewHolder(@NonNull View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.group_checkbox);
            groupName = itemView.findViewById(R.id.group_name);
        }

        public void bind(FeedGroupEntity group) {
            isBinding = true;

            groupName.setText(group.getName());

            // Set checkbox state without triggering listener
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(selectedGroupIds.contains(group.getUid()));

            isBinding = false;

            // Set up click listeners
            View.OnClickListener clickListener = v -> {
                if (isBinding || !isDataLoaded) return;

                boolean newState = !checkBox.isChecked();
                checkBox.setChecked(newState);
                updateGroupSelection(group.getUid(), newState);
            };

            itemView.setOnClickListener(clickListener);

            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isBinding || !isDataLoaded) return;
                updateGroupSelection(group.getUid(), isChecked);
            });
            itemView.setEnabled(!busy); // ENH:group-dialog
            checkBox.setEnabled(!busy); // ENH:group-dialog
        }
        public void bindPending(PendingGroup group) { // ENH:group-dialog
            groupName.setText(group.getName()); // ENH:group-dialog
            checkBox.setOnCheckedChangeListener(null); // ENH:group-dialog
            checkBox.setChecked(group.getChecked()); // ENH:group-dialog
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> { // ENH:group-dialog
                session.setChecked(group.getId(), isChecked); // ENH:group-dialog
                pending = session.getPending(); // ENH:group-dialog
            }); // ENH:group-dialog
            itemView.setOnClickListener(v -> checkBox.toggle()); // ENH:group-dialog
            itemView.setEnabled(!busy); // ENH:group-dialog
            checkBox.setEnabled(!busy); // ENH:group-dialog
        } // ENH:group-dialog

        private void updateGroupSelection(long groupId, boolean isSelected) {
            if (isSelected) {
                selectedGroupIds.add(groupId);
            } else {
                selectedGroupIds.remove(groupId);
            }
        }
    }
}
