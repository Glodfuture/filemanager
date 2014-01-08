package com.michaldabski.fileexplorer.folders;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.AbsListView;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ShareActionProvider;
import android.widget.TextView;
import android.widget.Toast;

import com.michaldabski.fileexplorer.AppPreferences;
import com.michaldabski.fileexplorer.FileExplorerApplication;
import com.michaldabski.fileexplorer.MainActivity;
import com.michaldabski.fileexplorer.R;
import com.michaldabski.fileexplorer.clipboard.Clipboard;
import com.michaldabski.fileexplorer.clipboard.Clipboard.FileAction;
import com.michaldabski.fileexplorer.folders.FileAdapter.OnFileSelectedListener;
import com.michaldabski.utils.AsyncResult;
import com.michaldabski.utils.FileUtils;
import com.michaldabski.utils.OnResultListener;
import com.readystatesoftware.systembartint.SystemBarTintManager;

public class FolderFragment extends Fragment implements OnItemClickListener, OnScrollListener, OnItemLongClickListener, MultiChoiceModeListener, OnFileSelectedListener
{
	public static final class FilterHidden implements FileFilter
	{
		@Override
		public boolean accept(File file)
		{
			return file.isHidden() == false;
		}
	}

	private static final String LOG_TAG = "FolderFragment";
	private final int DISTANCE_TO_HIDE_ACTIONBAR = 0;
	public static final String 
		EXTRA_DIR = "directory",
		EXTRA_SELECTED_FILES = "selected_files",
		EXTRA_SCROLL_POSITION = "scroll_position";
	File currentDir,
		nextDir = null;
	int topVisibleItem=0;
	List<File> files = null;
	@SuppressWarnings("rawtypes")
	AsyncTask loadFilesTask=null;
	ListView listView = null;
	FileAdapter fileAdapter;
	private ActionMode actionMode = null;
	private final HashSet<File> selectedFiles = new HashSet<File>();
	private ShareActionProvider shareActionProvider;
	// set to true when selection shouldnt be cleared from switching out fragments
	boolean preserveSelection = false;
	
	public ListView getListView()
	{
		return listView;
	}
	
	private void setListAdapter(FileAdapter fileAdapter)
	{
		this.fileAdapter = fileAdapter;
		if (listView != null)
		{
			listView.setAdapter(fileAdapter);
			listView.setSelection(topVisibleItem);
			
			getView().findViewById(R.id.layoutMessage).setVisibility(View.GONE);
			listView.setVisibility(View.VISIBLE);
		}
	}
	
	FileExplorerApplication getApplication()
	{
		if (getActivity() == null) return null;
		return (FileExplorerApplication) getActivity().getApplication();
	}
	
	AppPreferences getPreferences()
	{
		if (getApplication() == null) return null;
		return getApplication().getAppPreferences();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setRetainInstance(true);
		
		Log.d(LOG_TAG, "Fragment created");
		
		if (savedInstanceState != null)
		{
			this.topVisibleItem = savedInstanceState.getInt(EXTRA_SCROLL_POSITION, 0);
			this.selectedFiles.addAll((HashSet<File>) savedInstanceState.getSerializable(EXTRA_SELECTED_FILES));
		}
		
		Bundle arguments = getArguments();
		
		if (arguments != null && arguments.containsKey(EXTRA_DIR))
			currentDir = new File(arguments.getString(EXTRA_DIR));
		else 
			currentDir = getPreferences().getStartFolder();
		
		setHasOptionsMenu(true);
		
		loadFileList();
	}
	
	void showMessage(int message)
	{
		View view = getView();
		if (view != null)
		{
			getListView().setVisibility(View.GONE);
			view.findViewById(R.id.layoutMessage).setVisibility(View.VISIBLE);
			TextView tvMessage = (TextView) view.findViewById(R.id.tvMessage);
			tvMessage.setText(message);
					
		}
	}
	
	void showList()
	{
		getListView().setVisibility(View.VISIBLE);
		getView().findViewById(R.id.layoutMessage).setVisibility(View.GONE);
	}
	
	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState)
	{
		View view = inflater.inflate(R.layout.fragment_list, container, false);
		this.listView = (ListView) view.findViewById(android.R.id.list);
		return view;
	}
	
	void loadFileList()
	{
		if (loadFilesTask != null) return;
		this.loadFilesTask = new AsyncTask<File, Void, AsyncResult<File[]>>()
		{
			@Override
			protected AsyncResult<File[]> doInBackground(File... params)
			{
				try
				{
					File[] files =params[0].listFiles(new FilterHidden());
					if (files == null)
						throw new NullPointerException(getString(R.string.cannot_read_directory_s, params[0].getName()));
					if (isCancelled())
						throw new Exception("Task cancelled");
					Arrays.sort(files, getPreferences().getFileSortingComparator());
					return new AsyncResult<File[]>(files);
				}
				catch (Exception e)
				{
					return new AsyncResult<File[]>(e);
				}
			}
			
			@Override
			protected void onCancelled(AsyncResult<File[]> result) 
			{
				loadFilesTask = null;
			}
			
			@Override
			protected void onPostExecute(AsyncResult<File[]> result) 
			{
				Log.d("folder fragment", "Task finished");
				loadFilesTask = null;
				
				FileAdapter adapter;
				try
				{
					files = Arrays.asList(result.getResult());
					
					if (files.isEmpty())
					{
						showMessage(R.string.folder_empty);
						return;
					}
					adapter = new FileAdapter(getActivity(), files);
					adapter.setSelectedFiles(selectedFiles);
					adapter.setOnFileSelectedListener(FolderFragment.this);
					
				} catch (Exception e)
				{
					// exception was thrown while loading files
					Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_SHORT).show();
					adapter = new FileAdapter(getActivity());
				}
				setListAdapter(adapter);
				getActivity().invalidateOptionsMenu();
			}
		}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, currentDir);
	}
	
	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater)
	{
		super.onCreateOptionsMenu(menu, inflater);
		inflater.inflate(R.menu.folder_browser, menu);
		
		if (files == null)
			menu.findItem(R.id.menu_selectAll).setVisible(false);
	}
	
	void showEditTextDialog(int title, int okButtonText, final OnResultListener<CharSequence> enteredTextResult)
	{
		final EditText view = new EditText(getActivity());
		new AlertDialog.Builder(getActivity())
			.setTitle(title)
			.setView(view)
			.setPositiveButton(okButtonText, new OnClickListener()
			{
				
				@Override
				public void onClick(DialogInterface dialog, int which)
				{
					enteredTextResult.onResult(new AsyncResult<CharSequence>(view.getText()));
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId())
		{				
			case R.id.menu_selectAll:
				selectFiles(this.files);
				return true;
				
			case R.id.menu_create_folder:
				showEditTextDialog(R.string.create_folder, R.string.create, new OnResultListener<CharSequence>()
				{

					@Override
					public void onResult(AsyncResult<CharSequence> result)
					{
						try
						{
							String name = result.getResult().toString();
							File newFolder = new File(currentDir, name);
							if (newFolder.mkdirs())
							{
								refreshFolder();
								Toast.makeText(getActivity(), R.string.folder_created_successfully, Toast.LENGTH_SHORT).show();
								navigateTo(newFolder);
							}
						} catch (Exception e)
						{
							e.printStackTrace();
							Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_SHORT).show();
						}						
					}
				});
				return true;
				
			case R.id.menu_paste:
				try
				{
					Clipboard.getInstance().paste(currentDir);
					Toast.makeText(getActivity(), R.string.files_pasted, Toast.LENGTH_SHORT).show();
					refreshFolder();
				} catch (IOException e)
				{
					e.printStackTrace();
					Toast.makeText(getActivity(), e.getMessage(), Toast.LENGTH_SHORT).show();
				}
				return true;
				
			case R.id.menu_refresh:
				refreshFolder();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
	@Override
	public void onViewCreated(View view, final Bundle savedInstanceState)
	{
		super.onViewCreated(view, savedInstanceState);
		
		loadFileList();
		
		if (selectedFiles.isEmpty() == false)
		{
			selectFiles(selectedFiles);
		}
		
		final String directoryName;
		if (currentDir.equals(Environment.getExternalStorageDirectory()))
			directoryName = getActivity().getString(R.string.sd_card);
		else directoryName = currentDir.getName();
		getActivity().setTitle(directoryName);
		getListView().setOnItemClickListener(FolderFragment.this);
		getListView().setOnScrollListener(this);
		getListView().setOnItemLongClickListener(this);
		getListView().setMultiChoiceModeListener(this);
		getActivity().getActionBar().setSubtitle(FileUtils.getUserFriendlySdcardPath(currentDir));
		
		if (topVisibleItem <= DISTANCE_TO_HIDE_ACTIONBAR)
			setActionbarVisibility(true);
		
		// add listview header to push items below the actionbar
		LayoutInflater inflater = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View header = inflater.inflate(R.layout.list_header_actionbar_padding, getListView(), false);
		SystemBarTintManager systemBarTintManager = new SystemBarTintManager(getActivity());
		int headerHeight = systemBarTintManager.getConfig().getPixelInsetTop(true);
		header.setLayoutParams(new android.widget.AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, headerHeight));
		getListView().addHeaderView(header);
		
		// add footer
		int footerHeight = systemBarTintManager.getConfig().getPixelInsetBottom();
		if (footerHeight > 0)
		{
			View footer = inflater.inflate(R.layout.list_header_actionbar_padding, getListView(), false);
			footer.setLayoutParams(new android.widget.AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, footerHeight));
			getListView().addFooterView(footer);
		}		
		
		if (fileAdapter != null)
			setListAdapter(fileAdapter);
		
		MainActivity activity = (MainActivity) getActivity();
		activity.setLastFolder(currentDir);
	}
	
	@Override
	public void onDestroyView()
	{
		finishActionMode(true);
		listView = null;
		super.onDestroyView();
	}
	
	@Override
	public void onDestroy() 
	{
		if (loadFilesTask != null)
			loadFilesTask.cancel(true);
		super.onDestroy();
	}
	
	@Override
	public void onSaveInstanceState(Bundle outState)
	{
		super.onSaveInstanceState(outState);
		outState.putInt(EXTRA_SCROLL_POSITION, topVisibleItem);
		outState.putSerializable(EXTRA_SELECTED_FILES, selectedFiles);
	}
	
	void navigateTo(File folder)
	{		
		nextDir = folder;
		MainActivity activity = (MainActivity) getActivity();
		FolderFragment fragment = new FolderFragment();
		Bundle args = new Bundle();
		args.putString(EXTRA_DIR, folder.getAbsolutePath());
		fragment.setArguments(args);
		activity.showFragment(fragment);
	}
	
	void openFile(File file)
	{
		if (file.isDirectory())
			throw new IllegalArgumentException("File cannot be a directory!");
		
		Intent intent = FileUtils.createFileOpenIntent(file);

		try
		{
			startActivity(intent);
		}
		catch (ActivityNotFoundException e)
		{
			startActivity(Intent.createChooser(intent, getString(R.string.open_file_with_, file.getName())));
		}
	}

	@Override
	public void onItemClick(AdapterView<?> adapterView, View arg1, int position, long arg3)
	{
		Object selectedObject = adapterView.getItemAtPosition(position);
		if (selectedObject instanceof File)
		{
			if (actionMode == null)
			{
				File selectedFile = (File) selectedObject;
				if (selectedFile.isDirectory())
					navigateTo(selectedFile);
				else 
					openFile(selectedFile);
			}
			else
			{
				toggleFileSelected((File) selectedObject);
			}			
		}
	}
	
	void setActionbarVisibility(boolean visible)
	{
		if (actionMode == null || visible == true) // cannot hide CAB
			((MainActivity) getActivity()).setActionbarVisible(visible);
	}

	@Override
	public void onScroll(AbsListView view, int firstVisibleItem,
			int visibleItemCount, int totalItemCount)
	{		
		if (firstVisibleItem < this.topVisibleItem - DISTANCE_TO_HIDE_ACTIONBAR)
		{
			setActionbarVisibility(true);
			this.topVisibleItem = firstVisibleItem;
		}
		else if (firstVisibleItem > this.topVisibleItem + DISTANCE_TO_HIDE_ACTIONBAR)
		{
			setActionbarVisibility(false);
			this.topVisibleItem = firstVisibleItem;
		}
	}

	@Override
	public void onScrollStateChanged(AbsListView view, int scrollState)
	{

	}

	@Override
	public boolean onItemLongClick(AdapterView<?> arg0, View arg1, int arg2,
			long arg3)
	{
		setFileSelected((File) arg0.getItemAtPosition(arg2), true);
		return true;
	}

	@Override
	public boolean onActionItemClicked(ActionMode mode, MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.action_delete:
				new AlertDialog.Builder(getActivity())
					.setMessage(getString(R.string.delete_d_items_, selectedFiles.size()))
					.setPositiveButton(R.string.delete, new OnClickListener()
					{
						
						@Override
						public void onClick(DialogInterface dialog, int which)
						{
							int n = FileUtils.deleteFiles(selectedFiles);
							Toast.makeText(getActivity(), getString(R.string._d_files_deleted, n), Toast.LENGTH_SHORT).show();
							refreshFolder();
							finishActionMode(false);
						}
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
				return true;
				
			case R.id.action_selectAll:
				if (isEverythingSelected()) clearFileSelection();
				else selectFiles(files);
				return true;
				
			case R.id.action_copy:
				Clipboard.getInstance().addFiles(selectedFiles, FileAction.Copy);
				Toast.makeText(getActivity(), R.string.objects_copied_to_clipboard, Toast.LENGTH_SHORT).show();
				finishActionMode(false);
				return true;
				
			case R.id.action_cut:
				Clipboard clipboard = Clipboard.getInstance();
				clipboard.addFiles(selectedFiles, FileAction.Cut);
				Toast.makeText(getActivity(), R.string.objects_cut_to_clipboard, Toast.LENGTH_SHORT).show();
				finishActionMode(false);
				return true;
				
			case R.id.action_rename:
				return true;
			
			case R.id.menu_add_homescreen_icon:

				for (File file : selectedFiles)
					FileUtils.createShortcut(getActivity(), file);
				Toast.makeText(getActivity(), R.string.shortcut_created, Toast.LENGTH_SHORT).show();
				actionMode.finish();
				return true;
		}
		return false;
	}
	
	protected void refreshFolder()
	{
		loadFileList();		
	}

	void updateActionMode()
	{
		if (actionMode != null)
		{
			actionMode.invalidate();
			int count = selectedFiles.size();
			actionMode.setTitle(getString(R.string._d_objects, count));
			
			actionMode.setSubtitle(FileUtils.combineFileNames(selectedFiles));
			
			if (shareActionProvider != null)
			{
				ArrayList<Uri> fileUris = new ArrayList<Uri>(selectedFiles.size());
				
				for (File file : selectedFiles) if (file.isDirectory() == false)
				{
					fileUris.add(Uri.fromFile(file));
				}
				
				Intent shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
				shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, fileUris);
				shareIntent.setType(FileUtils.getCollectiveMimeType(selectedFiles));
				shareActionProvider.setShareIntent(shareIntent);
			}
		}
	}

	@Override
	public boolean onCreateActionMode(ActionMode mode, Menu menu)
	{
		setActionbarVisibility(true);
		getActivity().getMenuInflater().inflate(R.menu.action_file, menu);
		
		MenuItem shareMenuItem = menu.findItem(R.id.action_share);
		shareActionProvider = (ShareActionProvider) shareMenuItem.getActionProvider();
		this.preserveSelection = false;
		return true;
	}
	
	void finishSelection()
	{
		if (listView != null)
			listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
		clearFileSelection();		
	}
	
	void finishActionMode(boolean preserveSelection)
	{
		this.preserveSelection = preserveSelection;
		if (actionMode != null)
			actionMode.finish();
	}

	@Override
	public void onDestroyActionMode(ActionMode mode)
	{
		actionMode = null;
		shareActionProvider = null;
		if (preserveSelection == false)
			finishSelection();
		Log.d(LOG_TAG, "Action mode destroyed");
	}

	@Override
	public boolean onPrepareActionMode(ActionMode mode, Menu menu)
	{
		int count = selectedFiles.size();
		if (count == 1)
		{
			menu.findItem(R.id.action_rename).setVisible(true);
		}
		else
		{
			menu.findItem(R.id.action_rename).setVisible(false);
		}
		
		// show Share button if no folder was selected
		boolean allowShare = (count > 0);
		if (allowShare)
		{
			for (File file : selectedFiles) if (file.isDirectory())
			{
				allowShare = false;
				break;
			}
		}
		menu.findItem(R.id.action_share).setVisible(allowShare);
		
		return true;
	}

	@Override
	public void onItemCheckedStateChanged(ActionMode mode, int position,
			long id, boolean checked)
	{	
	}
	
	void toggleFileSelected(File file)
	{
		setFileSelected(file, !selectedFiles.contains(file));
	}
	
	void clearFileSelection()
	{
		if (listView != null)
			listView.clearChoices();
		selectedFiles.clear();
		updateActionMode();
		fileAdapter.notifyDataSetChanged();
		Log.d(LOG_TAG, "Selection cleared");
	}
	
	boolean isEverythingSelected()
	{
		return selectedFiles.size() == files.size();
	}
	
	void selectFiles(Collection<File> files)
	{
		if (files == null) return;
		
		if (actionMode == null)
		{
			listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
			actionMode = getActivity().startActionMode(this);
		}
		
		selectedFiles.addAll(files);
		updateActionMode();
		fileAdapter.notifyDataSetChanged();
	}
	
	void setFileSelected(File file, boolean selected)
	{
		if (actionMode == null)
		{
			listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
			actionMode = getActivity().startActionMode(this);
		}
		
		if (selected)
			selectedFiles.add(file);
		else 
			selectedFiles.remove(file);
		updateActionMode();
		fileAdapter.notifyDataSetChanged();
		
		if (selectedFiles.isEmpty())
			finishActionMode(false);
	}

	@Override
	public void onFileSelected(File file)
	{
		toggleFileSelected(file);		
	}
}