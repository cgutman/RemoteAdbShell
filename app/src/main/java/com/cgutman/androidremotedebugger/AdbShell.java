package com.cgutman.androidremotedebugger;


import java.util.concurrent.atomic.AtomicBoolean;

import com.cgutman.adblib.AdbCrypto;
import com.cgutman.androidremotedebugger.console.CommandHistory;
import com.cgutman.androidremotedebugger.console.ConsoleBuffer;
import com.cgutman.androidremotedebugger.devconn.DeviceConnection;
import com.cgutman.androidremotedebugger.devconn.DeviceConnectionListener;
import com.cgutman.androidremotedebugger.service.ShellService;
import com.cgutman.androidremotedebugger.ui.Dialog;
import com.cgutman.androidremotedebugger.ui.SpinnerDialog;

import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.app.Activity;
import android.app.Service;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnKeyListener;
import android.view.View.OnLongClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;

public class AdbShell extends Activity implements DeviceConnectionListener, OnKeyListener, OnEditorActionListener {

	private TextView shellView;
	private EditText commandBox;
	private ScrollView shellScroller;
	
	private String hostName;
	private int port;
	
	private DeviceConnection connection;
	
	private Intent service;
	private ShellService.ShellServiceBinder binder;
	
	private SpinnerDialog connectWaiting;
	
	private final static String PREFS_FILE = "AdbCmdHistoryPrefs";
	private static final int MAX_COMMAND_HISTORY = 15;
	private CommandHistory commandHistory;
	
	private boolean updateGui;
	private AtomicBoolean updateQueued = new AtomicBoolean();
	private AtomicBoolean updateRequired = new AtomicBoolean();
	
	private boolean autoScrollEnabled = true;
	private boolean userScrolling = false;
	private boolean scrollViewAtBottom = true;

	private ConsoleBuffer lastConsoleBuffer;
	
	private StringBuilder commandBuffer = new StringBuilder();
	
	private static final int MENU_ID_CTRL_C = 1;
	private static final int MENU_ID_AUTOSCROLL = 2;
	private static final int MENU_ID_EXIT = 3;
	
	private ServiceConnection serviceConn = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName arg0, IBinder arg1) {
			binder = (ShellService.ShellServiceBinder)arg1;
			if (connection != null) {
				binder.removeListener(connection, AdbShell.this);
			}
			connection = AdbShell.this.connectOrLookupConnection(hostName, port);
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			binder = null;
		}
	};
	
	@Override
	public void onNewIntent(Intent shellIntent) {
		hostName = shellIntent.getStringExtra("IP");
		port = shellIntent.getIntExtra("Port", -1);
		if (hostName == null || port == -1) {
			// If we were launched with no connection info, this was probably a pending intent
			// that's attempting to bring up the current in-progress connection. If we don't
			// have an existing connection, then we can do nothing and must finish ourselves.
			if (connection == null || binder == null) {
				finish();
			}
			return;
		}
		
		setTitle("ADB Shell - "+hostName+":"+port);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			startForegroundService(service);
		}
		else {
			startService(service);
		}

		if (binder == null) {
			/* Bind the service if we're not bound already. After binding, the callback will
			 * perform the initial connection. */
			getApplicationContext().bindService(service, serviceConn, Service.BIND_AUTO_CREATE);
		}
		else {
			/* We're already bound, so do the connect or lookup immediately */
			if (connection != null) {
				binder.removeListener(connection, this);
			}
			connection = connectOrLookupConnection(hostName, port);
		}
	}
	
	private DeviceConnection startConnection(String host, int port) {
		/* Display the connection progress spinner */
		connectWaiting = SpinnerDialog.displayDialog(this, "Connecting to "+hostName+":"+port,
				"Please make sure the target device has network ADB enabled.\n\n"+
				"You may need to accept a prompt on the target device if you are connecting "+
				"to it for the first time from this device.", true);
		
		/* Create the connection object */
		DeviceConnection conn = binder.createConnection(host, port);
		
		/* Add this activity as a connection listener */
		binder.addListener(conn, this);
		
		/* Begin the async connection process */
		conn.startConnect();
		
		return conn;
	}
	
	private DeviceConnection connectOrLookupConnection(String host, int port) {
		DeviceConnection conn = binder.findConnection(host, port);
		if (conn == null) {
			/* No existing connection, so start the connection process */
			conn = startConnection(host, port);
		}
		else {
			/* Add ourselves as a new listener of this connection */
			binder.addListener(conn, this);
		}
		return conn;
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_adb_shell);
		
		/* Setup our controls */
		shellView = (TextView) findViewById(R.id.shellView);
		commandBox = (EditText) findViewById(R.id.command);
		shellScroller = (ScrollView) findViewById(R.id.shellScroller);
		
		OnLongClickListener showMenu = new OnLongClickListener() {
			@Override
			public boolean onLongClick(View view) {
				openContextMenu(commandBox);
				return true;
			}
		};
		
		final ViewTreeObserver.OnScrollChangedListener onScrollChangedListener = new
				ViewTreeObserver.OnScrollChangedListener() {

			@Override
			public void onScrollChanged() {
				View view = (View) shellScroller.getChildAt(0);
		        int diff = view.getBottom() - (shellScroller.getHeight() + shellScroller.getScrollY());
		        if (diff <= 0) {
		        	doAsyncGuiUpdate();
		        	scrollViewAtBottom = true;
		        }
		        else {
		        	scrollViewAtBottom = false;
		        }
			}
		};

		shellScroller.setOnTouchListener(new OnTouchListener() {
			@Override
			public boolean onTouch(View view, MotionEvent event) {
				ViewTreeObserver observer = AdbShell.this.shellScroller.getViewTreeObserver();
				switch (event.getActionMasked())
				{
				case MotionEvent.ACTION_MOVE:
				case MotionEvent.ACTION_DOWN:
					observer.addOnScrollChangedListener(onScrollChangedListener);
					userScrolling = true;
					break;
				case MotionEvent.ACTION_UP:
				case MotionEvent.ACTION_CANCEL:
					if (scrollViewAtBottom) {
						doAsyncGuiUpdate();
					}
					userScrolling = false;
					break;
				}
				
				/* Don't consume the event */
				return false;
			}
		});
		
		commandBox.setImeActionLabel("Run", EditorInfo.IME_ACTION_DONE);
		commandBox.setOnEditorActionListener(this);
		commandBox.setOnKeyListener(this);
		commandBox.setOnLongClickListener(showMenu);

		registerForContextMenu(commandBox);
		registerForContextMenu(shellView);
		
		/* Pull previous command history (if any) */
		commandHistory = CommandHistory.loadCommandHistoryFromPrefs(MAX_COMMAND_HISTORY, this, PREFS_FILE);
		
		service = new Intent(this, ShellService.class);

		onNewIntent(getIntent());
	}
	
	@Override
	protected void onDestroy() {
		/* Save the command history first */
		commandHistory.save();

		if (binder != null && connection != null) {
			/* Tell the service about our impending doom */
			binder.notifyDestroyingActivity(connection);
			
			/* Dissociate our activity's listener */
			binder.removeListener(connection, this);
		}
		
		/* If the connection hasn't actually finished yet,
		 * close it before terminating */
		if (connectWaiting != null) {
			AdbUtils.safeAsyncClose(connection);
		}
		
		/* Unbind from the service since we're going away */
		if (binder != null) {
			getApplicationContext().unbindService(serviceConn);
		}
		
		Dialog.closeDialogs();
		SpinnerDialog.closeDialogs();
		super.onDestroy();
	}
	
	@Override
	public void onResume() {
		/* Tell the service about our UI state change */
		if (binder != null) {
			binder.notifyResumingActivity(connection);
		}
		
		/* There might be changes we need to display */
		updateTerminalView();
		
		/* Start updating the GUI again */
		updateGui = true;
		super.onResume();
	}
	
	@Override
	public void onPause() {
		/* Tell the service about our UI state change */
		if (binder != null) {
			binder.notifyPausingActivity(connection);
		}
		
		/* Stop updating the GUI for now */
		updateGui = false;
		super.onPause();
	}

	@Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        
        if (v == commandBox) {
        	commandHistory.populateMenu(menu);
        }
        else {
        	menu.add(Menu.NONE, MENU_ID_CTRL_C, Menu.NONE, "Send Ctrl+C");
        	
        	MenuItem autoscroll = menu.add(Menu.NONE, MENU_ID_AUTOSCROLL, Menu.NONE, "Auto-scroll terminal");
        	autoscroll.setCheckable(true);
        	autoscroll.setChecked(autoScrollEnabled);
        	
        	menu.add(Menu.NONE, MENU_ID_EXIT, Menu.NONE, "Exit Terminal");
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
    	if (item.getItemId() == 0) {
    		commandBox.setText(item.getTitle());
    	}
    	else {
    		switch (item.getItemId())
    		{
    		case MENU_ID_CTRL_C:
    			if (connection != null) {
    				connection.queueBytes(new byte[]{0x03});
    				
    				/* Force scroll to the bottom */
    				scrollViewAtBottom = true;
    				doAsyncGuiUpdate();
    			}
    			break;
    			
    		case MENU_ID_AUTOSCROLL:
    			item.setChecked(!item.isChecked());
    			autoScrollEnabled = item.isChecked();
    			break;
    			
    		case MENU_ID_EXIT:
    			AdbUtils.safeAsyncClose(connection);
    			finish();
    			break;
    		}
    	}
		return true;
    }

	@Override
	public boolean onEditorAction(TextView textView, int actionId, KeyEvent event) {
		/* We always return false because we want to dismiss the keyboard */
		
		if (commandBox.getText().length() == 0 || connection == null)
			return false;
		
		if (actionId == EditorInfo.IME_ACTION_DONE)
		{
			String text = commandBox.getText().toString();
			
			/* Append the command to our command buffer (which is empty) */
			commandBuffer.append(text);
			
			/* Add the command to the previous command list */
			commandHistory.add(text);
			
			/* Append a newline since it's not included in the command itself */
			commandBuffer.append('\n');
			
			/* Send it to the device */
			connection.queueCommand(commandBuffer.toString());
			
			/* Clear the textbox and command buffer */
			commandBuffer.setLength(0);
			commandBox.setText("");
			
			/* Force scroll to the bottom */
			scrollViewAtBottom = true;
			doAsyncGuiUpdate();
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean onKey(View v, int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_ENTER) {
			/* Just call the onEditorAction function to handle this for us */
			return onEditorAction((TextView)v, EditorInfo.IME_ACTION_DONE, event);
		}
		else {
			return false;
		}
	}
	
	private void updateTerminalView() {
		if (lastConsoleBuffer != null) {
			lastConsoleBuffer.updateTextView(shellView);
		}

		if (autoScrollEnabled) {
			shellView.post(new Runnable() { 
				public void run() {
					if (scrollViewAtBottom) {
						shellScroller.smoothScrollTo(0, shellView.getBottom());
					}
				} 
			});
		}
	}

	@Override
	public void notifyConnectionEstablished(DeviceConnection devConn) {
		connectWaiting.dismiss();
		connectWaiting = null;
	}

	@Override
	public void notifyConnectionFailed(DeviceConnection devConn, Exception e) {
		connectWaiting.dismiss();
		connectWaiting = null;
		
		Dialog.displayDialog(this, "Connection Failed", e.getMessage(), true);
	}

	@Override
	public void notifyStreamFailed(DeviceConnection devConn, Exception e) {
		Dialog.displayDialog(this, "Connection Terminated", e.getMessage(), true);
	}

	@Override
	public void notifyStreamClosed(DeviceConnection devConn) {
		Dialog.displayDialog(this, "Connection Closed", "The connection was gracefully closed.", true);
	}

	@Override
	public AdbCrypto loadAdbCrypto(DeviceConnection devConn) {
		return AdbUtils.readCryptoConfig(getFilesDir());
	}

	@Override
	public boolean canReceiveData() {
		/* We just handle console updates */
		return false;
	}

	@Override
	public void receivedData(DeviceConnection devConn, byte[] data, int offset,
			int length) {
	}

	@Override
	public boolean isConsole() {
		return true;
	}
	
	private void setGuiDirty() {
		/* Remember that a GUI update is needed */
		updateRequired.set(true);
	}
	
	private void doAsyncGuiUpdate() {
		/* If no update is required, do nothing */
		if (!updateRequired.get()) {
			return;
		}
		
		/* If an update isn't already queued, fire one off */
		if (updateQueued.compareAndSet(false, true)) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					/* Wait for a few milliseconds to avoid spamming GUI updates */
					
					try {
						Thread.sleep(250);
					} catch (InterruptedException e) {
						return;
					}
					
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							/* We won't need an update again after this */
							updateRequired.set(false);
							
							/* Redraw the terminal */
							updateTerminalView();
							
							/* This update is finished */
							updateQueued.set(false);
							
							/* If someone updated the console between the time that we
							 * started redrawing and when we finished, we need to update
							 * the GUI again, otherwise the GUI update could be missed. */
							if (updateRequired.get()) {
								doAsyncGuiUpdate();
							}
						}
					});
				}
			}).start();
		}
	}

	@Override
	public void consoleUpdated(DeviceConnection devConn, ConsoleBuffer console) {
		lastConsoleBuffer = console;

		setGuiDirty();
		if (updateGui && !userScrolling && scrollViewAtBottom) {
			doAsyncGuiUpdate();
		}
	}
}
