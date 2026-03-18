package com.example.v380cam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity {

    private EditText etIp, etPort, etUsername, etPassword, etDeviceId;
    private TextView tvStatus;
    private CountDownTimer autoConnectTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etIp       = findViewById(R.id.etIp);
        etPort     = findViewById(R.id.etPort);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        etDeviceId = findViewById(R.id.etDeviceId);
        tvStatus   = findViewById(R.id.tvStatus);

        // Pre-fill with defaults — edit as needed
        etIp.setText("192.168.1.100");
        etPort.setText("8800");
        etUsername.setText("admin");
        etPassword.setText("");
        etDeviceId.setText("");

        Button btnConnect = findViewById(R.id.btnConnect);
        btnConnect.setOnClickListener(v -> { cancelAutoConnect(); connect(); });

        View.OnFocusChangeListener cancelOnFocus = (v, hasFocus) -> {
            if (hasFocus) cancelAutoConnect();
        };
        etIp.setOnFocusChangeListener(cancelOnFocus);
        etPort.setOnFocusChangeListener(cancelOnFocus);
        etUsername.setOnFocusChangeListener(cancelOnFocus);
        etPassword.setOnFocusChangeListener(cancelOnFocus);
        etDeviceId.setOnFocusChangeListener(cancelOnFocus);

        startAutoConnect();
    }

    private void startAutoConnect() {
        autoConnectTimer = new CountDownTimer(3000, 1000) {
            @Override public void onTick(long ms) {
                tvStatus.setText("Auto-connecting in " + (ms / 1000 + 1) + "s  (touch any field to cancel)");
            }
            @Override public void onFinish() {
                tvStatus.setText("");
                connect();
            }
        }.start();
    }

    private void cancelAutoConnect() {
        if (autoConnectTimer != null) {
            autoConnectTimer.cancel();
            autoConnectTimer = null;
            tvStatus.setText("");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelAutoConnect();
    }

    private void connect() {
        String ip       = etIp.getText().toString().trim();
        String port     = etPort.getText().toString().trim();
        String username = etUsername.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String deviceId = etDeviceId.getText().toString().trim();

        if (ip.isEmpty())       { tvStatus.setText("Camera IP is required.");  return; }
        if (deviceId.isEmpty()) { tvStatus.setText("Device ID is required.");  return; }
        if (port.isEmpty())     port     = "8800";
        if (username.isEmpty()) username = "admin";

        Intent intent = new Intent(this, StreamActivity.class);
        intent.putExtra("ip",       ip);
        intent.putExtra("port",     port);
        intent.putExtra("username", username);
        intent.putExtra("password", password);
        intent.putExtra("deviceId", deviceId);
        startActivity(intent);
    }
}
