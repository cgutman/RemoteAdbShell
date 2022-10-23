package com.cgutman.androidremotedebugger;

import com.cgutman.adblib.AdbCrypto;
import com.cgutman.androidremotedebugger.ui.Dialog;
import com.cgutman.androidremotedebugger.ui.SpinnerDialog;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;

public class ConnectActivity extends Activity implements OnClickListener {

	private Button connectButton;
	private EditText ipField, portField;
	
	private SpinnerDialog keygenSpinner;

	private final static String PREFS_FILE = "AdbConnectPrefs";
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_connect);
		
		/* Grab our controls and setup our listeners */
		connectButton = (Button)findViewById(R.id.connect);
		ipField = (EditText)findViewById(R.id.ipAddressField);
		portField = (EditText)findViewById(R.id.portField);
		connectButton.setOnClickListener(this);
		
		/* Load last entered values */
		loadPreferences();
		
		/* If we have old RSA keys, just use them */
		AdbCrypto crypto = AdbUtils.readCryptoConfig(getFilesDir());
		if (crypto == null)
		{
			/* We need to make a new pair */
			keygenSpinner = SpinnerDialog.displayDialog(this,
					"Generating RSA Key Pair",
					"This will only be done once.",
					true);
			
			new Thread(new Runnable() {
				@Override
				public void run() {
					AdbCrypto crypto;
					
					crypto = AdbUtils.writeNewCryptoConfig(getFilesDir());
					keygenSpinner.dismiss();

					if (crypto == null)
					{
						Dialog.displayDialog(ConnectActivity.this, "Key Pair Generation Failed",
								"Unable to generate and save RSA key pair", 
								true);
						return;
					}
					
					Dialog.displayDialog(ConnectActivity.this, "New Key Pair Generated",
							"Devices running 4.2.2 will need to be plugged in to a computer the next time you connect to them",
							false);
				}
			}).start();
		}
	}
	
	@Override
	protected void onDestroy() {
		Dialog.closeDialogs();
		SpinnerDialog.closeDialogs();
		super.onDestroy();
	}

	private String ipAddress() {
		WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
		return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
	}
	
	private void loadPreferences() {
		SharedPreferences prefs = getSharedPreferences(PREFS_FILE, 0);
		ipField.setText(ipAddress());
		portField.setText(prefs.getString("Port", "5555"));
	}
	
	private void savePreferences() {
		SharedPreferences.Editor prefs = getSharedPreferences(PREFS_FILE, 0).edit();
		prefs.putString("Port", portField.getText().toString());
		prefs.apply();
	}

	@Override
	public void onClick(View view) {
		Intent shellIntent = new Intent(this, AdbShell.class);
		int port;

		shellIntent.putExtra("IP", ipField.getText().toString());
		try {
			port = Integer.parseInt(portField.getText().toString());
			if (port <= 0 || port > 65535) {
				Dialog.displayDialog(this, "Invalid Port", "The port number must be between 1 and 65535", false);
				return;
			}
			shellIntent.putExtra("Port", port);
		} catch (NumberFormatException e) {
			Dialog.displayDialog(this, "Invalid Port", "The port must be an integer", false);
			return;
		}
		
		savePreferences();
		
		startActivity(shellIntent);
	}
}
