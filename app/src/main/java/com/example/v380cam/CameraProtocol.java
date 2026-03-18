package com.example.v380cam;

import android.util.Log;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Implements the V380 camera binary protocol.
 * Reverse-engineered from https://github.com/mzyy94/v380 (C++ source).
 *
 * Protocol phases:
 *   1. Auth     — TCP command 1167, response 1168
 *   2. Stream login — TCP command 301, response 401
 *   3. Stream start — TCP command 303
 *   4. Frame loop   — 12-byte header + chunk data
 *
 * Frame header layout (little-endian):
 *   [0]    packet class  (0x7f = media, 0x6f = keepalive)
 *   [1]    frame type    (0x00 = I-frame, 0x01 = P-frame, 0x16 = audio)
 *   [3-4]  totalFrames  (LE uint16)
 *   [5-6]  curFrame     (LE uint16)
 *   [7-8]  dataLen      (LE uint16)
 *
 * Assembled video frame layout:
 *   bytes  0-15: timestamp header (dts, pts, unknowns)
 *   bytes 16+  : raw H.264 Annex-B NAL stream
 */
public class CameraProtocol {

    private static final String TAG = "V380Proto";
    private static final int VIDEO_HEADER_SIZE = 16;

    public interface Callback {
        void onConnected();
        void onVideoFrame(byte[] nalData, boolean keyframe);
        void onAudioFrame(byte[] adpcmData);
        void onError(String msg);
    }

    private volatile boolean running = false;
    private Socket streamSocket;

    public void start(String ip, int port, String username, String password,
                      int deviceId, Callback cb) {
        running = true;
        new Thread(() -> run(ip, port, username, password, deviceId, cb)).start();
    }

    public void stop() {
        running = false;
        try { if (streamSocket != null) streamSocket.close(); } catch (Exception ignored) {}
    }

    private void run(String ip, int port, String username, String password,
                     int deviceId, Callback cb) {
        try {
            // --- PHASE 1: AUTH ---
            Log.i(TAG, "Phase1: connecting to " + ip + ":" + port);
            Socket authSock = new Socket(ip, port);
            authSock.setSoTimeout(5000);

            byte[] pw = CameraAuth.generatePassword(password);

            byte[] loginReq = new byte[256];
            ByteBuffer lb = ByteBuffer.wrap(loginReq).order(ByteOrder.LITTLE_ENDIAN);
            lb.putInt(0,  1167);
            lb.putInt(4,  1022);
            lb.put   (8,  (byte) 2);
            lb.putInt(9,  1);
            lb.putInt(13, deviceId);

            byte[] unb = username.getBytes("ISO-8859-1");
            System.arraycopy(unb, 0, loginReq, 49, Math.min(unb.length, 32));
            System.arraycopy(pw,  0, loginReq, 81, Math.min(pw.length,  32));

            OutputStream authOut = authSock.getOutputStream();
            InputStream  authIn  = authSock.getInputStream();
            authOut.write(loginReq);
            authOut.flush();

            byte[] loginResp = recvExact(authIn, 256);
            authSock.close();

            if (loginResp == null) { cb.onError("Auth: no response"); return; }

            ByteBuffer rb = ByteBuffer.wrap(loginResp).order(ByteOrder.LITTLE_ENDIAN);
            int respCmd     = rb.getInt(0);
            int loginResult = rb.getInt(4);
            int authTicket  = rb.getInt(13);

            Log.i(TAG, "Phase1: cmd=" + respCmd + " result=" + loginResult
                    + " ticket=0x" + Integer.toHexString(authTicket));

            if (respCmd == 1168) {
                if      (loginResult == 1011) { cb.onError("Invalid username"); return; }
                else if (loginResult == 1012) { cb.onError("Invalid password"); return; }
                else if (loginResult == 1018) { cb.onError("Invalid device ID"); return; }
            }

            // --- PHASE 2: STREAM LOGIN ---
            streamSocket = new Socket(ip, port);
            streamSocket.setTcpNoDelay(true);
            streamSocket.setSoTimeout(5000);

            byte[] streamReq = new byte[256];
            ByteBuffer sb2 = ByteBuffer.wrap(streamReq).order(ByteOrder.LITTLE_ENDIAN);
            sb2.putInt  (0,  301);
            sb2.putInt  (4,  deviceId);
            sb2.putInt  (8,  0);
            sb2.putShort(12, (short) 20);
            sb2.putInt  (14, authTicket);
            sb2.putInt  (18, 0);
            sb2.putInt  (22, 4097);
            sb2.putInt  (26, 1);

            OutputStream streamOut = streamSocket.getOutputStream();
            InputStream  streamIn  = streamSocket.getInputStream();
            streamOut.write(streamReq);
            streamOut.flush();

            // Camera sends a short response (not always 412 bytes) — accept >= 16
            byte[] streamResp = recvAtLeast(streamIn, 16, 412);
            if (streamResp == null) { cb.onError("Stream login: no response"); return; }

            ByteBuffer sr = ByteBuffer.wrap(streamResp).order(ByteOrder.LITTLE_ENDIAN);
            int srCmd = sr.getInt(0);
            int srV21 = sr.getInt(4);
            Log.i(TAG, "Phase2: cmd=" + srCmd + " v21=" + srV21);

            if (srCmd != 401) { cb.onError("Stream login: expected 401, got " + srCmd); return; }

            // --- PHASE 3: STREAM START ---
            byte[] startReq = new byte[256];
            ByteBuffer stb = ByteBuffer.wrap(startReq).order(ByteOrder.LITTLE_ENDIAN);
            stb.putInt(0, 303);
            stb.putInt(4, srV21);
            streamOut.write(startReq);
            streamOut.flush();

            streamSocket.setSoTimeout(10000);
            cb.onConnected();

            // --- FRAME LOOP ---
            byte[] vbuf = new byte[0];
            byte[] abuf = new byte[0];
            int    vtotal = 0, vprev = -1;
            int    atotal = 0, aprev = -1;
            boolean vKeyframe = false;

            while (running) {
                byte[] hdr = recvExact(streamIn, 12);
                if (hdr == null) break;

                int pktClass    = hdr[0] & 0xFF;
                int frameType   = hdr[1] & 0xFF;
                int totalFrames = ((hdr[4] & 0xFF) << 8) | (hdr[3] & 0xFF);
                int curFrame    = ((hdr[6] & 0xFF) << 8) | (hdr[5] & 0xFF);
                int dataLen     = ((hdr[8] & 0xFF) << 8) | (hdr[7] & 0xFF);

                if (pktClass == 0x6f) continue;
                if (pktClass != 0x7f) continue;
                if (dataLen == 0 || dataLen > 65535 || totalFrames == 0) continue;

                byte[] chunk = recvExact(streamIn, dataLen);
                if (chunk == null) break;

                if (frameType == 0x00 || frameType == 0x01) {
                    if (curFrame == 0) {
                        vbuf = new byte[0]; vtotal = totalFrames;
                        vprev = -1; vKeyframe = (frameType == 0x00);
                    }
                    if (vtotal == 0 || curFrame != vprev + 1) { vtotal = 0; continue; }
                    vprev = curFrame;
                    vbuf  = concat(vbuf, chunk);

                    if (curFrame == vtotal - 1 && vbuf.length > VIDEO_HEADER_SIZE) {
                        byte[] nal = new byte[vbuf.length - VIDEO_HEADER_SIZE];
                        System.arraycopy(vbuf, VIDEO_HEADER_SIZE, nal, 0, nal.length);
                        cb.onVideoFrame(nal, vKeyframe);
                        vbuf = new byte[0];
                    }

                } else if (frameType == 0x16) {
                    if (curFrame == 0) {
                        abuf = new byte[0]; atotal = totalFrames; aprev = -1;
                    }
                    if (atotal == 0 || curFrame != aprev + 1) { atotal = 0; continue; }
                    aprev = curFrame;
                    abuf  = concat(abuf, chunk);

                    if (curFrame == atotal - 1 && abuf.length > 4) {
                        byte[] adpcm = new byte[abuf.length - 4];
                        System.arraycopy(abuf, 4, adpcm, 0, adpcm.length);
                        cb.onAudioFrame(adpcm);
                        abuf = new byte[0];
                    }
                }
            }

            cb.onError("Stream ended");

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Log.e(TAG, "Exception: " + msg, e);
            cb.onError(msg);
        }
    }

    private static byte[] recvAtLeast(InputStream in, int minBytes, int maxBytes) throws Exception {
        byte[] buf = new byte[maxBytes];
        int n = in.read(buf, 0, maxBytes);
        if (n < minBytes) return null;
        byte[] result = new byte[n];
        System.arraycopy(buf, 0, result, 0, n);
        return result;
    }

    private static byte[] recvExact(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(buf, read, n - read);
            if (r < 0) return null;
            read += r;
        }
        return buf;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0,        a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
