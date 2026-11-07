package ca.pkay.rcloneexplorer.thumbnails;

/**
 * Glide model describing "a preview frame of this remote video".
 *
 * <p>{@link #url} is only used to fetch the data. It contains the per-session port and secret path
 * of the local rclone server, so it must never take part in cache keys. {@link #cacheKey} is the
 * stable identity (remote, path, modification time, size) and is the only thing used for equality
 * and for the on-disk cache, which makes thumbnails survive app restarts and re-listing, while an
 * edited file gets a fresh thumbnail.</p>
 */
public final class VideoThumbnailRequest {

    public final String url;
    public final String cacheKey;

    public VideoThumbnailRequest(String url, String cacheKey) {
        this.url = url;
        this.cacheKey = cacheKey;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VideoThumbnailRequest && cacheKey.equals(((VideoThumbnailRequest) o).cacheKey);
    }

    @Override
    public int hashCode() {
        return cacheKey.hashCode();
    }
}
