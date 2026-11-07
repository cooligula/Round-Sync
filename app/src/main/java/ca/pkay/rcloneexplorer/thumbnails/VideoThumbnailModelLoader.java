package ca.pkay.rcloneexplorer.thumbnails;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.IOException;
import java.util.HashMap;

import ca.pkay.rcloneexplorer.util.FLog;

/**
 * Produces a single poster frame for a video that is served by the local rclone HTTP server.
 *
 * <p>{@link MediaMetadataRetriever} reads over HTTP with range requests, so only the container
 * header and the bytes of one keyframe are transferred, not the whole file. That is what makes
 * previews of large remote videos practical. The resulting (already downscaled) bitmap is stored
 * by Glide in its resource disk cache, so the extraction happens once per file version.</p>
 */
public class VideoThumbnailModelLoader implements ModelLoader<VideoThumbnailRequest, Bitmap> {

    private static final String TAG = "VideoThumbnailLoader";

    @Nullable
    @Override
    public LoadData<Bitmap> buildLoadData(@NonNull VideoThumbnailRequest model, int width, int height, @NonNull Options options) {
        return new LoadData<>(new ObjectKey(model.cacheKey), new Fetcher(model, width, height));
    }

    @Override
    public boolean handles(@NonNull VideoThumbnailRequest model) {
        return true;
    }

    public static class Factory implements ModelLoaderFactory<VideoThumbnailRequest, Bitmap> {
        @NonNull
        @Override
        public ModelLoader<VideoThumbnailRequest, Bitmap> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new VideoThumbnailModelLoader();
        }

        @Override
        public void teardown() {
        }
    }

    private static class Fetcher implements DataFetcher<Bitmap> {

        /** Look slightly into the video: the very first frame is frequently black. */
        private static final long MAX_FRAME_OFFSET_US = 5_000_000L;

        private final VideoThumbnailRequest request;
        private final int targetWidth;
        private final int targetHeight;
        private volatile boolean cancelled;

        Fetcher(VideoThumbnailRequest request, int targetWidth, int targetHeight) {
            this.request = request;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
        }

        @Override
        public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super Bitmap> callback) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(request.url, new HashMap<String, String>());
                if (cancelled) {
                    return;
                }
                Bitmap frame = extractFrame(retriever);
                if (cancelled) {
                    return;
                }
                if (frame == null) {
                    callback.onLoadFailed(new IOException("No video frame could be decoded"));
                } else {
                    callback.onDataReady(frame);
                }
            } catch (RuntimeException e) {
                // MediaMetadataRetriever reports unsupported/unreachable media as RuntimeException.
                FLog.d(TAG, "loadData: failed for %s: %s", request.cacheKey, e.getMessage());
                callback.onLoadFailed(e);
            } finally {
                try {
                    retriever.release();
                } catch (RuntimeException ignored) {
                    // nothing to do
                }
            }
        }

        @Nullable
        private Bitmap extractFrame(MediaMetadataRetriever retriever) {
            long timeUs = 0;
            String durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMs != null) {
                try {
                    timeUs = Math.min(Long.parseLong(durationMs) * 100L, MAX_FRAME_OFFSET_US); // 10% of duration
                } catch (NumberFormatException ignored) {
                    timeUs = 0;
                }
            }

            Bitmap frame = getFrame(retriever, timeUs);
            if (frame == null && timeUs != 0) {
                frame = getFrame(retriever, 0);
            }
            return frame;
        }

        @Nullable
        private Bitmap getFrame(MediaMetadataRetriever retriever, long timeUs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && targetWidth > 0 && targetHeight > 0) {
                try {
                    int[] size = scaledSize(retriever);
                    if (size != null) {
                        Bitmap scaled = retriever.getScaledFrameAtTime(
                                timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size[0], size[1]);
                        if (scaled != null) {
                            return scaled;
                        }
                    }
                } catch (RuntimeException e) {
                    FLog.d(TAG, "getFrame: scaled decode failed, falling back: %s", e.getMessage());
                }
            }
            // Pre-O_MR1 (or fallback): full-size frame; Glide scales it down afterwards.
            return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        }

        /**
         * Computes the decode size so the frame still covers the target box (it is centre-cropped
         * afterwards) without ever upscaling. Mirrors the rotation handling of Glide's own
         * VideoDecoder, so that 4K clips are never decoded at full resolution just for a tiny icon.
         */
        @Nullable
        private int[] scaledSize(MediaMetadataRetriever retriever) {
            String w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (w == null || h == null) {
                return null;
            }
            int videoW;
            int videoH;
            try {
                videoW = Integer.parseInt(w);
                videoH = Integer.parseInt(h);
            } catch (NumberFormatException e) {
                return null;
            }
            if ("90".equals(rotation) || "270".equals(rotation)) {
                int tmp = videoW;
                videoW = videoH;
                videoH = tmp;
            }
            if (videoW <= 0 || videoH <= 0) {
                return null;
            }
            float scale = Math.max((float) targetWidth / videoW, (float) targetHeight / videoH);
            if (scale >= 1f) {
                return new int[]{videoW, videoH};
            }
            return new int[]{Math.max(1, Math.round(videoW * scale)), Math.max(1, Math.round(videoH * scale))};
        }

        @Override
        public void cleanup() {
            // Everything is released in loadData().
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @NonNull
        @Override
        public Class<Bitmap> getDataClass() {
            return Bitmap.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.REMOTE;
        }
    }
}
