/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.media.common;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Objects;

/**
 * Abstract representation of a media item metadata.
 * <p>
 * For media art, only local uris are supported so downloads can be attributed to the media app.
 * Bitmaps are not supported because they slow down the binder.
 */
public class MediaItemMetadata implements Parcelable {
    private static final String TAG = "MediaItemMetadata";

    @NonNull
    private final MediaDescriptionCompat mMediaDescription;
    @Nullable
    private final Long mQueueId;
    private final boolean mIsBrowsable;
    private final boolean mIsPlayable;
    private final String mAlbumTitle;
    private final String mArtist;


    /**
     * Creates an instance based on a {@link MediaMetadataCompat}
     */
    public MediaItemMetadata(@NonNull MediaMetadataCompat metadata) {
        this(metadata.getDescription(), null, false, false,
                metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM),
                metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
    }

    /**
     * Creates an instance based on a {@link MediaSessionCompat.QueueItem}
     */
    public MediaItemMetadata(@NonNull MediaSessionCompat.QueueItem queueItem) {
        this(queueItem.getDescription(), queueItem.getQueueId(), false, true, null, null);
    }

    /**
     * Creates an instance based on a {@link MediaBrowserCompat.MediaItem}
     */
    public MediaItemMetadata(@NonNull MediaBrowserCompat.MediaItem item) {
        this(item.getDescription(), null, item.isBrowsable(), item.isPlayable(), null, null);
    }

    /**
     * Creates an instance based on a {@link Parcel}
     */
    public MediaItemMetadata(@NonNull Parcel in) {
        mMediaDescription = (MediaDescriptionCompat) in.readValue(MediaDescriptionCompat.class.getClassLoader());
        mQueueId = in.readByte() == 0x00 ? null : in.readLong();
        mIsBrowsable = in.readByte() != 0x00;
        mIsPlayable = in.readByte() != 0x00;
        mAlbumTitle = in.readString();
        mArtist = in.readString();
    }

    @VisibleForTesting
    public MediaItemMetadata(@NonNull MediaDescriptionCompat description, @Nullable Long queueId, boolean isBrowsable,
                             boolean isPlayable, String albumTitle, String artist) {
        mMediaDescription = description;
        mQueueId = queueId;
        mIsPlayable = isPlayable;
        mIsBrowsable = isBrowsable;
        mAlbumTitle = albumTitle;
        mArtist = artist;
    }

    /**
     * @return media item id
     */
    @Nullable
    public String getId() {
        return mMediaDescription.getMediaId();
    }

    /**
     * @return media item title
     */
    @Nullable
    public CharSequence getTitle() {
        return mMediaDescription.getTitle();
    }

    /**
     * @return media item subtitle
     */
    @Nullable
    public CharSequence getSubtitle() {
        return mMediaDescription.getSubtitle();
    }

    /**
     * @return the album title for the media
     */
    @Nullable
    public String getAlbumTitle() {
        return mAlbumTitle;
    }

    /**
     * @return the artist of the media
     */
    @Nullable
    public CharSequence getArtist() {
        return mArtist;
    }

    /**
     * @return the id of this item in the session queue, or NULL if this is not a session queue
     * item.
     */
    @Nullable
    public Long getQueueId() {
        return mQueueId;
    }

    /**
     * @return a {@link Uri} referencing the artwork's bitmap.
     */
    @Nullable
    public Uri getNonEmptyArtworkUri() {
        Uri uri = mMediaDescription.getIconUri();
        return (uri != null && !TextUtils.isEmpty(uri.toString())) ? uri : null;
    }

    /**
     * @return optional extras that can include extra information about the media item to be played.
     */
    public Bundle getExtras() {
        return mMediaDescription.getExtras();
    }

    /**
     * @return boolean that indicate if media is explicit.
     */
    public boolean isExplicit() {
        Bundle extras = mMediaDescription.getExtras();
        return extras != null && extras.getLong(MediaConstants.EXTRA_IS_EXPLICIT)
                == MediaConstants.EXTRA_METADATA_ENABLED_VALUE;
    }

    /**
     * @return boolean that indicate if media is downloaded.
     */
    public boolean isDownloaded() {
        Bundle extras = mMediaDescription.getExtras();
        return extras != null && extras.getLong(MediaConstants.EXTRA_DOWNLOAD_STATUS)
                == MediaDescriptionCompat.STATUS_DOWNLOADED;
    }

    public boolean isBrowsable() {
        return mIsBrowsable;
    }

    /**
     * @return Content style hint for browsable items, if provided as an extra, or
     * 0 as default value if not provided.
     */
    public int getBrowsableContentStyleHint() {
        Bundle extras = mMediaDescription.getExtras();
        if (extras != null) {
            if (extras.containsKey(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT, 0);
            } else if (extras.containsKey(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_BROWSABLE_HINT_PRERELEASE, 0);
            }
        }
        return 0;
    }

    public boolean isPlayable() {
        return mIsPlayable;
    }

    /**
     * @return Content style hint for playable items, if provided as an extra, or
     * 0 as default value if not provided.
     */
    public int getPlayableContentStyleHint() {
        Bundle extras = mMediaDescription.getExtras();
        if (extras != null) {

            if (extras.containsKey(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT, 0);
            } else if (extras.containsKey(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE)) {
                return extras.getInt(MediaConstants.CONTENT_STYLE_PLAYABLE_HINT_PRERELEASE, 0);
            }
        }
        return 0;
    }

    /**
     * @return Content style title group this item belongs to, or null if not provided
     */
    public String getTitleGrouping() {
        Bundle extras = mMediaDescription.getExtras();
        if (extras != null) {
            if (extras.containsKey(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT)) {
                return extras.getString(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT, null);
            } else if (extras.containsKey(
                    MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT_PRERELEASE)) {
                return extras.getString(MediaConstants.CONTENT_STYLE_GROUP_TITLE_HINT_PRERELEASE,
                        null);
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MediaItemMetadata that = (MediaItemMetadata) o;
        return mIsBrowsable == that.mIsBrowsable
                && mIsPlayable == that.mIsPlayable
                && Objects.equals(getId(), that.getId())
                && Objects.equals(getTitle(), that.getTitle())
                && Objects.equals(getSubtitle(), that.getSubtitle())
                && Objects.equals(getAlbumTitle(), that.getAlbumTitle())
                && Objects.equals(getArtist(), that.getArtist())
                && Objects.equals(getNonEmptyArtworkUri(), that.getNonEmptyArtworkUri())
                && Objects.equals(mQueueId, that.mQueueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMediaDescription.getMediaId(), mQueueId, mIsBrowsable, mIsPlayable);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeValue(mMediaDescription);
        if (mQueueId == null) {
            dest.writeByte((byte) (0x00));
        } else {
            dest.writeByte((byte) (0x01));
            dest.writeLong(mQueueId);
        }
        dest.writeByte((byte) (mIsBrowsable ? 0x01 : 0x00));
        dest.writeByte((byte) (mIsPlayable ? 0x01 : 0x00));
        dest.writeString(mAlbumTitle);
        dest.writeString(mArtist);
    }

    @SuppressWarnings("unused")
    public static final Creator<MediaItemMetadata> CREATOR =
            new Creator<MediaItemMetadata>() {
                @Override
                public MediaItemMetadata createFromParcel(Parcel in) {
                    return new MediaItemMetadata(in);
                }

                @Override
                public MediaItemMetadata[] newArray(int size) {
                    return new MediaItemMetadata[size];
                }
            };

    @NonNull
    @Override
    public String toString() {
        return "[Id: "
                + mMediaDescription.getMediaId()
                + ", Queue Id: "
                + mQueueId
                + ", title: "
                + mMediaDescription.getTitle()
                + ", subtitle: "
                + mMediaDescription.getSubtitle()
                + ", album title: "
                + mAlbumTitle
                + ", artist: "
                + mArtist
                + ", album art URI: "
                + mMediaDescription.getIconUri()
                + "]";
    }
}
