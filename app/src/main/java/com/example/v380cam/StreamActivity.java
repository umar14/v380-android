package com.example.v380cam;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class StreamActivity extends Activity implements SurfaceHolder.Callback {

    private SurfaceView    surfaceView;
    private TextView       tvStatus;
    private CameraProtocol protocol;
    private H264Decoder    decoder;

    private String ip, username, password;
    private int    port, deviceId;

    private final Handler  handler    = new Handler(Looper.getMainLooper());
    private final Runnable hideStatus = () -> tvStatus.setVisibility(View.GONE);
    private static final int STATUS_TIMEOUT_MS = 3000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_stream);

        ip       = getIntent().getStringExtra("ip");
        port     = Integer.parseInt(getIntent().getStringExtra("port"));
        username = getIntent().getStringExtra("username");
        password = getIntent().getStringExtra("password");
        deviceId = Integer.parseInt(getIntent().getStringExtra("deviceId"));

        surfaceView = findViewById(R.id.surfaceView);
        tvStatus    = findViewById(R.id.tvStatus);
        surfaceView.getHolder().addCallback(this);

        // Tap surface to toggle status overlay
        surfaceView.setOnClickListener(v -> {
            handler.removeCallbacks(hideStatus);
            if (tvStatus.getVisibility() == View.VISIBLE) {
                tvStatus.setVisibility(View.GONE);
            } else {
                tvStatus.setVisibility(View.VISIBLE);
                handler.postDelayed(hideStatus, STATUS_TIMEOUT_MS);
            }
        });
    }

    private void showStatus(String msg) {
        handler.post(() -> {
            tvStatus.setText(msg);
            tvStatus.setVisibility(View.VISIBLE);
            handler.removeCallbacks(hideStatus);
            handler.postDelayed(hideStatus, STATUS_TIMEOUT_MS);
        });
    }

    private void startStream() {
        if (protocol != null) return;
        showStatus("Connecting to " + ip + "...");

        decoder  = new H264Decoder(surfaceView.getHolder().getSurface(),
                msg -> showStatus("Error: " + msg));

        protocol = new CameraProtocol();
        protocol.start(ip, port, username, password, deviceId,
                new CameraProtocol.Callback() {
                    @Override public void onConnected()                            { showStatus("Connected"); }
                    @Override public void onVideoFrame(byte[] d, boolean kf)      { decoder.decode(d, kf); }
                    @Override public void onAudioFrame(byte[] d)                  {}
                    @Override public void onError(String msg)                     { showStatus(msg); }
                });
    }

    private void stopStream() {
        if (protocol != null) { protocol.stop(); protocol = null; }
        if (decoder  != null) { decoder.release();  decoder  = null; }
    }

    @Override protected void onPause()   { super.onPause();   stopStream(); }
    @Override protected void onResume()  {
        super.onResume();
        if (surfaceView.getHolder().getSurface() != null
                && surfaceView.getHolder().getSurface().isValid()) {
            startStream();
        }
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(hideStatus);
        stopStream();
    }

    @Override public void surfaceCreated(SurfaceHolder h)                     { startStream(); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int v) {}
    @Override public void surfaceDestroyed(SurfaceHolder h)                   { stopStream(); }
}
