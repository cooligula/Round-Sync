package ca.pkay.rcloneexplorer.thumbnails;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.module.AppGlideModule;

import java.io.File;

/**
 * Glide setup for thumbnails.
 *
 * <ul>
 *   <li>Thumbnails live in their own disk cache directory with a hard size cap (LRU eviction), so
 *       they can be inspected, measured and cleared independently of anything else.</li>
 *   <li>Video poster frames are loaded through {@link VideoThumbnailModelLoader}.</li>
 * </ul>
 */
@GlideModule
public final class ThumbnailGlideModule extends AppGlideModule {

    public static final String CACHE_DIR_NAME = "thumbnails";
    public static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setDiskCache(new InternalCacheDiskCacheFactory(context, CACHE_DIR_NAME, MAX_CACHE_BYTES));
    }

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        registry.append(VideoThumbnailRequest.class, Bitmap.class, new VideoThumbnailModelLoader.Factory());
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }

    /** Current size of the thumbnail cache on disk, in bytes. Does file IO: call off the main thread. */
    public static long getCacheSizeBytes(Context context) {
        return directorySize(new File(context.getCacheDir(), CACHE_DIR_NAME));
    }

    /** Empties the in-memory and on-disk thumbnail caches. Blocking: call off the main thread. */
    public static void clearCache(Context context) {
        Glide glide = Glide.get(context.getApplicationContext());
        glide.clearDiskCache();
        // clearMemory() must run on the main thread.
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(glide::clearMemory);
    }

    private static long directorySize(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        long total = 0;
        for (File child : children) {
            total += child.isDirectory() ? directorySize(child) : child.length();
        }
        return total;
    }
}
