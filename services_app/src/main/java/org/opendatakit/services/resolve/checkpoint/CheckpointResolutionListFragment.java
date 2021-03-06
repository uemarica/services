/*
 * Copyright (C) 2015 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.services.resolve.checkpoint;

import android.app.Activity;
import android.app.Fragment;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.content.Intent;
import android.content.Loader;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import org.opendatakit.consts.IntentConsts;
import org.opendatakit.fragment.ProgressDialogFragment;
import org.opendatakit.logging.WebLogger;
import org.opendatakit.services.resolve.listener.ResolutionListener;
import org.opendatakit.services.resolve.task.CheckpointResolutionListTask;
import org.opendatakit.services.resolve.views.components.ResolveRowEntry;
import org.opendatakit.services.R;

import java.util.ArrayList;

/**
 * @author mitchellsundt@gmail.com
 */
public class CheckpointResolutionListFragment extends ListFragment implements LoaderManager
    .LoaderCallbacks<ArrayList<ResolveRowEntry>>, ResolutionListener {

  private static final String TAG = "CheckpointResolutionListFragment";
  private static final int RESOLVE_ROW_LOADER = 0x02;

  public static final String NAME = "CheckpointResolutionListFragment";
  public static final int ID = R.layout.checkpoint_resolver_chooser_list;

  private static final String PROGRESS_DIALOG_TAG = "progressDialog";

  private static final String HAVE_RESOLVED_METADATA_CONFLICTS = "haveResolvedMetadataConflicts";

  private enum DialogState {
    Progress, Alert, None
  }

  private static CheckpointResolutionListTask checkpointResolutionListTask = null;

  private String mAppName;
  private String mTableId;
  private boolean mHaveResolvedMetadataConflicts = false;
  private ArrayAdapter<ResolveRowEntry> mAdapter;

  private Handler handler = new Handler();
  private ProgressDialogFragment progressDialog = null;

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);

    outState.putBoolean(HAVE_RESOLVED_METADATA_CONFLICTS, mHaveResolvedMetadataConflicts);
  }


  @Override
  public void onActivityCreated(Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);

    Intent incomingIntent = getActivity().getIntent();
    mAppName = incomingIntent.getStringExtra(IntentConsts.INTENT_KEY_APP_NAME);
    if (mAppName == null || mAppName.length() == 0) {
      getActivity().setResult(Activity.RESULT_CANCELED);
      getActivity().finish();
      return;
    }

    mTableId = incomingIntent.getStringExtra(IntentConsts.INTENT_KEY_TABLE_ID);
    if (mTableId == null || mTableId.length() == 0) {
      getActivity().setResult(Activity.RESULT_CANCELED);
      getActivity().finish();
      return;
    }

    mHaveResolvedMetadataConflicts = savedInstanceState != null &&
        (savedInstanceState.containsKey(HAVE_RESOLVED_METADATA_CONFLICTS) ?
         savedInstanceState.getBoolean(HAVE_RESOLVED_METADATA_CONFLICTS) :
         false);

    // render total instance view
    mAdapter = new ArrayAdapter<ResolveRowEntry>(getActivity(), android.R.layout.simple_list_item_1);
    setListAdapter(mAdapter);

    getLoaderManager().initLoader(RESOLVE_ROW_LOADER, null, this);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);

    View view = inflater.inflate(ID, container, false);
    Button buttonTakeAllOldest = (Button) view.findViewById(R.id.take_all_oldest);
    Button buttonTakeAllNewest = (Button) view.findViewById(R.id.take_all_newest);
    buttonTakeAllNewest.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        takeAllNewest();
      }
    });
    buttonTakeAllOldest.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        takeAllOldest();
      }
    });
    return view;
  }

  @Override
  public void onResume() {
    super.onResume();

    showProgressDialog();
  }

  private void takeAllNewest() {
    if ( mAdapter == null ) return;
    resolveConflictList(true);
  }

  private void takeAllOldest() {
    if ( mAdapter == null ) return;
    this.resolveConflictList(false);
  }

  @Override
  public void onListItemClick(ListView l, View v, int position, long id) {
    super.onListItemClick(l, v, position, id);

    ResolveRowEntry e = mAdapter.getItem(position);
    WebLogger.getLogger(mAppName).e(TAG,
        "[onListItemClick] clicked position: " + position + " rowId: " + e.rowId);
    if ( checkpointResolutionListTask == null ) {
      launchRowResolution(e);
    } else {
      Toast.makeText(getActivity(), R.string.resolver_already_active, Toast.LENGTH_LONG).show();
    }
  }

  private void launchRowResolution(ResolveRowEntry e) {
    Intent i = new Intent(getActivity(), CheckpointResolutionActivity.class);
    i.putExtra(IntentConsts.INTENT_KEY_APP_NAME, mAppName);
    i.putExtra(IntentConsts.INTENT_KEY_TABLE_ID, mTableId);
    i.putExtra(IntentConsts.INTENT_KEY_INSTANCE_ID, e.rowId);
    this.startActivityForResult(i, CheckpointResolutionActivity.RESOLVE_ROW);
  }

  @Override
  public Loader<ArrayList<ResolveRowEntry>> onCreateLoader(int id, Bundle args) {
    // Now create and return a OdkResolveCheckpointRowLoader that will take care of
    // creating an ArrayList<ResolveRowEntry> for the data being displayed.
    return new OdkResolveCheckpointRowLoader(getActivity(), mAppName, mTableId,
        mHaveResolvedMetadataConflicts);
  }

  @Override
  public void onLoadFinished(Loader<ArrayList<ResolveRowEntry>> loader,
      ArrayList<ResolveRowEntry> resolveRowEntryArrayList) {
    // we have resolved the metadata conflicts -- no need to try this again
    mHaveResolvedMetadataConflicts = true;

    // this toast may be silently swallowed if there is only one remaining checkpoint in the table.
    int silentlyResolvedCheckpoints =
        ((OdkResolveCheckpointRowLoader) loader).getNumberRowsSilentlyReverted();

    if ( silentlyResolvedCheckpoints != 0 ) {
      if ( silentlyResolvedCheckpoints == 1 ) {
        Toast.makeText(getActivity(), getActivity().getString(R.string
                .silently_resolved_single_checkpoint), Toast.LENGTH_LONG).show();
      } else {
        Toast.makeText(getActivity(), getActivity().getString(R.string.silently_resolved_checkpoints,
            silentlyResolvedCheckpoints), Toast.LENGTH_LONG).show();
      }
    }

    // Swap the new cursor in. (The framework will take care of closing the
    // old cursor once we return.)
    mAdapter.clear();
    if ( resolveRowEntryArrayList.size() == 1 ) {
      launchRowResolution(resolveRowEntryArrayList.get(0));
      return;
    } else if ( resolveRowEntryArrayList.isEmpty() ){
      Toast.makeText(getActivity(), R.string.checkpoint_auto_apply_all, Toast.LENGTH_SHORT).show();
      getActivity().setResult(Activity.RESULT_OK);
      getActivity().finish();
      return;
    }
    mAdapter.addAll(resolveRowEntryArrayList);

    if ( getView() == null ) {
      throw new IllegalStateException("Unexpectedly found no view!");
    }

    mAdapter.notifyDataSetChanged();
  }

  @Override
  public void onLoaderReset(Loader<ArrayList<ResolveRowEntry>> loader) {
    // This is called when the last ArrayList<ResolveRowEntry> provided to onLoadFinished()
    // above is about to be released. We need to make sure we are no
    // longer using it.
    mAdapter.clear();
  }

  @Override public void onDestroy() {
    if ( checkpointResolutionListTask != null ) {
      checkpointResolutionListTask.clearResolutionListener(null);
    }
    super.onDestroy();
  }

  private void resolveConflictList(boolean takeNewest) {
    if (mAdapter.getCount() > 0) {
      if (checkpointResolutionListTask == null) {
        checkpointResolutionListTask = new CheckpointResolutionListTask(getActivity(), takeNewest, mAppName);
        checkpointResolutionListTask.setTableId(mTableId);
        checkpointResolutionListTask.setResolveRowEntryAdapter(mAdapter);
        checkpointResolutionListTask.setResolutionListener(this);
        checkpointResolutionListTask.execute();
      } else {
        checkpointResolutionListTask.setResolutionListener(this);
        Toast.makeText(getActivity(), R.string.resolver_already_active, Toast.LENGTH_LONG)
            .show();
      }

      // show dialog box
      showProgressDialog();
    }
  }

  private void showProgressDialog() {
    if ( checkpointResolutionListTask != null ) {
      checkpointResolutionListTask.setResolutionListener(this);
      String progress = checkpointResolutionListTask.getProgress();

      if ( checkpointResolutionListTask.getResult() != null ) {
        resolutionComplete(checkpointResolutionListTask.getResult());
        return;
      }

      Button buttonTakeAllOldest = (Button) getView().findViewById(R.id.take_all_oldest);
      Button buttonTakeAllNewest = (Button) getView().findViewById(R.id.take_all_newest);

      buttonTakeAllOldest.setEnabled(false);
      buttonTakeAllNewest.setEnabled(false);

      // try to retrieve the active dialog
      Fragment dialog = getFragmentManager().findFragmentByTag(PROGRESS_DIALOG_TAG);

      if (dialog != null && ((ProgressDialogFragment) dialog).getDialog() != null) {
        ((ProgressDialogFragment) dialog).getDialog().setTitle(R.string.conflict_resolving_all);
        ((ProgressDialogFragment) dialog).setMessage(progress);
      } else if (progressDialog != null && progressDialog.getDialog() != null) {
        progressDialog.getDialog().setTitle(R.string.conflict_resolving_all);
        progressDialog.setMessage(progress);
      } else {
        if (progressDialog != null) {
          dismissProgressDialog();
        }
        progressDialog = ProgressDialogFragment.newInstance(getString(R.string.conflict_resolving_all), progress);
        progressDialog.show(getFragmentManager(), PROGRESS_DIALOG_TAG);
      }
    }
  }

  private void dismissProgressDialog() {
    final Fragment dialog = getFragmentManager().findFragmentByTag(PROGRESS_DIALOG_TAG);
    if (dialog != null && dialog != progressDialog) {
      // the UI may not yet have resolved the showing of the dialog.
      // use a handler to add the dismiss to the end of the queue.
      handler.post(new Runnable() {
        @Override
        public void run() {
          ((ProgressDialogFragment) dialog).dismiss();
        }
      });
    }
    if (progressDialog != null) {
      final ProgressDialogFragment scopedReference = progressDialog;
      progressDialog = null;
      // the UI may not yet have resolved the showing of the dialog.
      // use a handler to add the dismiss to the end of the queue.
      handler.post(new Runnable() {
        @Override public void run() {
          try {
            scopedReference.dismiss();
          } catch (Exception e) {
            // ignore... we tried!
          }
        }
      });
    }
  }


  @Override public void resolutionProgress(String progress) {
    if ( progressDialog != null ) {
      progressDialog.setMessage(progress);
    }
  }

  @Override public void resolutionComplete(String result) {
    checkpointResolutionListTask = null;
    Button buttonTakeAllOldest = (Button) getView().findViewById(R.id.take_all_oldest);
    Button buttonTakeAllNewest = (Button) getView().findViewById(R.id.take_all_newest);
    buttonTakeAllOldest.setEnabled(true);
    buttonTakeAllNewest.setEnabled(true);

    dismissProgressDialog();
    getLoaderManager().restartLoader(RESOLVE_ROW_LOADER, null, this);

    if ( result != null && result.length() != 0 ) {
      Toast.makeText(getActivity(), result, Toast.LENGTH_LONG).show();
    }
  }
}
