package com.example.v380cam;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * H.264 hardware decoder wrapping Android MediaCodec.
 * Parses Annex-B NAL units (both 3-byte and 4-byte start codes),
 * extracts SPS/PPS to configure the codec, then renders to a Surface.
 */
public class H264Decoder {

    private static final String TAG = "V380Decoder";

    public interface ErrorCallback {
        void onError(String msg);
    }

    private MediaCodec    codec;
    private boolean       configured = false;
    private boolean       started    = false;
    private Surface       surface;
    private ErrorCallback errCb;

    private byte[] pendingSps = null;
    private byte[] pendingPps = null;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public H264Decoder(Surface surface, ErrorCallback errCb) {
        this.surface = surface;
        this.errCb   = errCb;
    }

    public synchronized void decode(byte[] annexB, boolean keyframe) {
        List<byte[]> nals = splitNals(annexB);
        if (nals.isEmpty()) return;

        for (byte[] nal : nals) {
            if (nal.length == 0) continue;
            int nalType = nal[0] & 0x1F;

            if (nalType == 7) {
                pendingSps = nal;
            } else if (nalType == 8) {
                pendingPps = nal;
            } else {
                if (!configured) {
                    if (pendingSps == null || pendingPps == null) continue;
                    configure(pendingSps, pendingPps);
                }
                if (started) feedNal(nal);
            }
        }
    }

    private void configure(byte[] sps, byte[] pps) {
        try {
            codec = MediaCodec.createDecoderByType("video/avc");
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
            codec.configure(fmt, surface, null, 0);
            codec.start();
            configured = true;
            started    = true;
            Log.i(TAG, "Codec started");
            new Thread(this::drainOutput).start();
        } catch (Exception e) {
            Log.e(TAG, "Codec init failed: " + e.getMessage(), e);
            mainHandler.post(() -> errCb.onError("Codec init failed: " + e.getMessage()));
        }
    }

    private void feedNal(byte[] nal) {
        try {
            int idx = codec.dequeueInputBuffer(10000);
            if (idx < 0) return;
            ByteBuffer buf = codec.getInputBuffer(idx);
            if (buf == null) return;
            buf.clear();
            byte[] data = withStartCode(nal);
            if (data.length > buf.capacity()) {
                codec.queueInputBuffer(idx, 0, 0, 0, 0);
                return;
            }
            buf.put(data);
            codec.queueInputBuffer(idx, 0, data.length, System.nanoTime() / 1000, 0);
        } catch (Exception e) {
            Log.w(TAG, "feedNal: " + e.getMessage());
        }
    }

    private void drainOutput() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (started) {
            try {
                int idx = codec.dequeueOutputBuffer(info, 10000);
                if (idx >= 0) codec.releaseOutputBuffer(idx, true);
            } catch (Exception e) {
                break;
            }
        }
    }

    public synchronized void release() {
        started = false; configured = false;
        try {
            if (codec != null) { codec.stop(); codec.release(); codec = null; }
        } catch (Exception ignored) {}
    }

    private static List<byte[]> splitNals(byte[] data) {
        List<byte[]>  nals   = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<Integer> scLens = new ArrayList<>();

        for (int i = 0; i <= data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    starts.add(i + 4); scLens.add(4); i += 3;
                } else if (data[i + 2] == 1) {
                    starts.add(i + 3); scLens.add(3); i += 2;
                }
            }
        }

        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to   = (i + 1 < starts.size())
                    ? starts.get(i + 1) - scLens.get(i + 1)
                    : data.length;
            if (to > from) nals.add(Arrays.copyOfRange(data, from, to));
        }
        return nals;
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[4 + nal.length];
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }
}
