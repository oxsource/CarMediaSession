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

package com.android.car.media.common.source;

import android.app.Application;
import android.car.Car;
import android.car.media.CarMediaManager;
import android.content.ComponentName;
import android.media.MediaDescription;
import android.net.Uri;
import android.os.Handler;
import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.compat.MediaCompat;
import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Contains observable data needed for displaying playback and browse UI.
 * MediaSourceViewModel is a singleton tied to the application to provide a single source of truth.
 */
public class MediaSourceViewModel extends AndroidViewModel {
    private static final String TAG = "MediaSourceViewModel";

    private static final MediaSourceViewModel[] sInstances = new MediaSourceViewModel[2];
    private final Car mCar;
    private CarMediaManager mCarMediaManager;

    // Primary media source.
    private final MutableLiveData<MediaSource> mPrimaryMediaSource = new MutableLiveData<>();

    // Browser for the primary media source and its connection state.
    private final MutableLiveData<BrowsingState> mBrowsingState = new MutableLiveData<>();

    private final Handler mHandler;

    /**
     * Factory for creating dependencies. Can be swapped out for testing.
     */
    @VisibleForTesting
    interface InputFactory {
        MediaBrowserConnector createMediaBrowserConnector(@NonNull Application application,
                                                          @NonNull MediaBrowserConnector.Callback connectedBrowserCallback);

        Car getCarApi();

        CarMediaManager getCarMediaManager(Car carApi) throws Exception;

        MediaSource getMediaSource(ComponentName componentName);
    }

    /**
     * Returns the MediaSourceViewModel singleton tied to the application.
     */
    public static MediaSourceViewModel get(@NonNull Application application) {
        if (sInstances[MediaCompat.MODE_PLAYBACK] == null) {
            sInstances[MediaCompat.MODE_PLAYBACK] = new MediaSourceViewModel(application);
        }
        return sInstances[MediaCompat.MODE_PLAYBACK];
    }

    /**
     * Create a new instance of MediaSourceViewModel
     *
     * @see AndroidViewModel
     */
    private MediaSourceViewModel(@NonNull Application application) {
        this(application, new InputFactory() {
            @Override
            public MediaBrowserConnector createMediaBrowserConnector(
                    @NonNull Application application,
                    @NonNull MediaBrowserConnector.Callback connectedBrowserCallback) {
                return new MediaBrowserConnector(application, connectedBrowserCallback);
            }

            @Override
            public Car getCarApi() {
                return Car.createCar(application);
            }

            @Override
            public CarMediaManager getCarMediaManager(Car carApi) throws Exception {
                return (CarMediaManager) carApi.getCarManager(Car.CAR_MEDIA_SERVICE);
            }

            @Override
            public MediaSource getMediaSource(ComponentName componentName) {
                return componentName == null ? null : MediaSource.create(application, componentName);
            }
        });
    }

    private final InputFactory mInputFactory;
    private final MediaBrowserConnector mBrowserConnector;
    private final MediaBrowserConnector.Callback mBrowserCallback = mBrowsingState::setValue;

    private MediaSourceViewModel(@NonNull Application application, @NonNull InputFactory inputFactory) {
        super(application);

        mInputFactory = inputFactory;
        mCar = inputFactory.getCarApi();

        mBrowserConnector = inputFactory.createMediaBrowserConnector(application, mBrowserCallback);

        mHandler = new Handler(application.getMainLooper());
        CarMediaManager.MediaSourceChangedListener mMediaSourceListener = componentName -> mHandler.post(
                () -> updateModelState(mInputFactory.getMediaSource(componentName)));

        try {
            mCarMediaManager = mInputFactory.getCarMediaManager(mCar);
            mCarMediaManager.registerMediaSourceListener(mMediaSourceListener);
            ComponentName component = mCarMediaManager.getMediaSource();
            updateModelState(mInputFactory.getMediaSource(component));
        } catch (Exception e) {
            Log.e(TAG, "MediaSourceViewModel connect exp:", e);
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mCar.disconnect();
    }

    @VisibleForTesting
    MediaBrowserConnector.Callback getBrowserCallback() {
        return mBrowserCallback;
    }

    /**
     * Returns a LiveData that emits the MediaSource that is to be browsed or displayed.
     */
    public LiveData<MediaSource> getPrimaryMediaSource() {
        return mPrimaryMediaSource;
    }

    /**
     * Updates the primary media source.
     */
    public void setPrimaryMediaSource(@NonNull MediaSource mediaSource) {
        mCarMediaManager.setMediaSource(mediaSource.getBrowseServiceComponentName());
    }

    /**
     * Returns a LiveData that emits a {@link BrowsingState}, or {@code null} if there is no media
     * source.
     */
    public LiveData<BrowsingState> getBrowsingState() {
        return mBrowsingState;
    }

    public MediaDescription getMediaDescription(ComponentName source) {
        if (null == mCarMediaManager || null == source) return null;
        return mCarMediaManager.getMediaDescription(source);
    }

    public MediaItemMetadata getMediaMetadata(ComponentName source) {
        MediaDescription desc = getMediaDescription(source);
        if (null == desc) return null;
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, desc.getMediaId())
                .putText(MediaMetadataCompat.METADATA_KEY_TITLE, desc.getTitle())
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, desc.getTitle())
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, desc.getSubtitle())
                .putText(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, desc.getDescription())
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, desc.getIconBitmap());
        Uri iconUri = desc.getIconUri();
        if (null != iconUri) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, iconUri.toString());
        }
        Uri mediaUri = desc.getMediaUri();
        if (null != mediaUri) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, mediaUri.toString());
        }
        return new MediaItemMetadata(builder.build());
    }

    public List<ComponentName> getLastMediaSources(int mode) {
        if (null == mCarMediaManager) return new ArrayList<>(0);
        return mCarMediaManager.getLastMediaSources();
    }

    private void updateModelState(MediaSource newMediaSource) {
        MediaSource oldMediaSource = mPrimaryMediaSource.getValue();

        if (Objects.equals(oldMediaSource, newMediaSource)) {
            return;
        }

        // Broadcast the new source
        mPrimaryMediaSource.setValue(newMediaSource);

        // Recompute dependent values
        if (newMediaSource != null) {
            mBrowserConnector.connectTo(newMediaSource);
        }
    }

    public MediaBrowserConnector.ConnectionStatus getConnectStatus() {
        BrowsingState state = getBrowsingState().getValue();
        MediaBrowserConnector.ConnectionStatus status = null == state ? null : state.mConnectionStatus;
        return null == status ? MediaBrowserConnector.ConnectionStatus.SUSPENDED : status;
    }

    public boolean shakeHands(boolean force) {
        MediaSource source = mPrimaryMediaSource.getValue();
        if (null == source) {
            Log.e(TAG, "ping primary media source is null.");
            return false;
        }
        MediaBrowserConnector.ConnectionStatus status = getConnectStatus();
        if (!force && MediaBrowserConnector.ConnectionStatus.SUSPENDED != status) return false;
        Log.w(TAG, "try to connect primary source");
        mBrowserConnector.connectTo(source);
        return true;
    }
}
