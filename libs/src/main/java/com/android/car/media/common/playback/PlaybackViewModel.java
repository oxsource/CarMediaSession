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

package com.android.car.media.common.playback;

import android.app.Application;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.source.MediaBrowserConnector;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static androidx.lifecycle.Transformations.switchMap;
import static com.android.car.media.common.playback.PlaybackStateAnnotations.Actions;

/**
 * ViewModel for media playback.
 * <p>
 * Observes changes to the provided MediaController to expose playback state and metadata
 * observables.
 * <p>
 * PlaybackViewModel is a "singleton" tied to the application to provide a single source of truth.
 */
public class PlaybackViewModel extends AndroidViewModel {
    private static final String TAG = "PlaybackViewModel";

    private static final String ACTION_SET_RATING =
            "com.android.car.media.common.ACTION_SET_RATING";
    private static final String EXTRA_SET_HEART = "com.android.car.media.common.EXTRA_SET_HEART";

    private static final PlaybackViewModel[] sInstances = new PlaybackViewModel[2];

    /**
     * Returns the PlaybackViewModel "singleton" tied to the application for the given mode.
     */
    public static PlaybackViewModel get(@NonNull Application application, int mode) {
        if (sInstances[mode] == null) {
            sInstances[mode] = new PlaybackViewModel(application, mode);
        }
        return sInstances[mode];
    }

    /**
     * Possible main actions.
     */
    @IntDef({ACTION_PLAY, ACTION_STOP, ACTION_PAUSE, ACTION_DISABLED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {
    }

    /**
     * Main action is disabled. The source can't play media at this time
     */
    public static final int ACTION_DISABLED = 0;
    /**
     * Start playing
     */
    public static final int ACTION_PLAY = 1;
    /**
     * Stop playing
     */
    public static final int ACTION_STOP = 2;
    /**
     * Pause playing
     */
    public static final int ACTION_PAUSE = 3;

    /**
     * Factory for creating dependencies. Can be swapped out for testing.
     */
    @VisibleForTesting
    interface InputFactory {
        MediaControllerCompat getControllerForBrowser(@NonNull MediaBrowserCompat browser);
    }


    /**
     * Needs to be a MediaMetadata because the compat class doesn't implement equals...
     */
    private static final MediaMetadata EMPTY_MEDIA_METADATA = new MediaMetadata.Builder().build();

    private final MediaControllerCallback mMediaControllerCallback = new MediaControllerCallback();

    private final MutableLiveData<MediaItemMetadata> mMetadata = new MutableLiveData<>();

    // Filters out queue items with no description or title and converts them to MediaItemMetadata
    private final MutableLiveData<List<MediaItemMetadata>> mSanitizedQueue = new MutableLiveData<>();

    private final MutableLiveData<Boolean> mHasQueue = new MutableLiveData<>();

    private final MutableLiveData<CharSequence> mQueueTitle = new MutableLiveData<>();

    private final MutableLiveData<PlaybackController> mPlaybackControls = new MutableLiveData<>();

    private final MutableLiveData<PlaybackStateWrapper> mPlaybackStateWrapper = new MutableLiveData<>();

    private final LiveData<PlaybackProgress> mProgress = switchMap(mPlaybackStateWrapper, state -> {
        if (null != state) {
            return new ProgressLiveData(state.mState, state.getMaxProgress());
        }
        MutableLiveData<PlaybackProgress> value = new MutableLiveData<>();
        value.postValue(new PlaybackProgress(0L, 0L));
        return value;
    });

    private final InputFactory mInputFactory;

    private PlaybackViewModel(Application application, int mode) {
        this(application, MediaSourceViewModel.get(application, mode).getBrowsingState(),
                browser -> new MediaControllerCompat(application, browser.getSessionToken()));
    }

    private PlaybackViewModel(Application application,
                              LiveData<MediaBrowserConnector.BrowsingState> browsingState, InputFactory factory) {
        super(application);
        mInputFactory = factory;
        Observer<MediaBrowserConnector.BrowsingState> mMediaBrowsingObserver = mMediaControllerCallback::onMediaBrowsingStateChanged;
        browsingState.observeForever(mMediaBrowsingObserver);
    }

    /**
     * Returns a LiveData that emits a MediaItemMetadata of the current media item in the session
     * managed by the provided {@link MediaControllerCompat}.
     */
    public LiveData<MediaItemMetadata> getMetadata() {
        return mMetadata;
    }

    /**
     * Returns a LiveData that emits the current queue as MediaItemMetadatas where items without a
     * title have been filtered out.
     */
    public LiveData<List<MediaItemMetadata>> getQueue() {
        return mSanitizedQueue;
    }

    /**
     * Returns a LiveData that emits whether the MediaController has a non-empty queue
     */
    public LiveData<Boolean> hasQueue() {
        return mHasQueue;
    }

    /**
     * Returns a LiveData that emits the current queue title.
     */
    public LiveData<CharSequence> getQueueTitle() {
        return mQueueTitle;
    }

    /**
     * Returns a LiveData that emits an object for controlling the currently selected
     * MediaController.
     */
    public LiveData<PlaybackController> getPlaybackController() {
        return mPlaybackControls;
    }

    /**
     * Returns a {@link PlaybackStateWrapper} live data.
     */
    public LiveData<PlaybackStateWrapper> getPlaybackStateWrapper() {
        return mPlaybackStateWrapper;
    }

    /**
     * Returns a LiveData that emits the current playback progress, in milliseconds. This is a
     * value between 0 and {@link #getPlaybackStateWrapper#getMaxProgress()} or
     * {@link PlaybackStateCompat#PLAYBACK_POSITION_UNKNOWN} if the current position is unknown.
     * This value will update on its own periodically (less than a second) while active.
     */
    public LiveData<PlaybackProgress> getProgress() {
        return mProgress;
    }

    @VisibleForTesting
    MediaControllerCompat getMediaController() {
        return mMediaControllerCallback.mMediaController;
    }

    @VisibleForTesting
    MediaMetadataCompat getMediaMetadata() {
        return mMediaControllerCallback.mMediaMetadata;
    }

    public MediaSource getMediaSource() {
        MediaBrowserConnector.BrowsingState state = mMediaControllerCallback.mBrowsingState;
        return null == state ? null : state.mMediaSource;
    }

    public void setMediaMetadata(MediaItemMetadata data) {
        if (null == data) return;
        mMetadata.postValue(data);
    }

    private class MediaControllerCallback extends MediaControllerCompat.Callback {

        private MediaBrowserConnector.BrowsingState mBrowsingState;
        private MediaControllerCompat mMediaController;
        private MediaMetadataCompat mMediaMetadata;
        private PlaybackStateCompat mPlaybackState;


        void onMediaBrowsingStateChanged(MediaBrowserConnector.BrowsingState newBrowsingState) {
            if (Objects.equals(mBrowsingState, newBrowsingState)) {
                Log.w(TAG, "onMediaBrowsingStateChanged noop ");
                return;
            }

            // Reset the old controller if any, unregistering the callback when browsing is
            // not suspended (crashed).
            if (mMediaController != null) {
                switch (newBrowsingState.mConnectionStatus) {
                    case DISCONNECTING:
                    case REJECTED:
                    case CONNECTING:
                    case CONNECTED:
                        mMediaController.unregisterCallback(this);
                        // Fall through
                    case SUSPENDED:
                        setMediaController(null);
                }
            }

            mBrowsingState = newBrowsingState;

            if (mBrowsingState.mConnectionStatus == ConnectionStatus.CONNECTED) {
                setMediaController(mInputFactory.getControllerForBrowser(mBrowsingState.mBrowser));
            }
        }

        private void setMediaController(MediaControllerCompat mediaController) {
            mMediaMetadata = null;
            mPlaybackState = null;
            mMediaController = mediaController;
            mPlaybackControls.setValue(new PlaybackController(mediaController));

            if (mMediaController != null) {
                mMediaController.registerCallback(this);
                // The apps don't always send updates so make sure we fetch the most recent values.
                onMetadataChanged(mMediaController.getMetadata());
                onPlaybackStateChanged(mMediaController.getPlaybackState());
                onQueueChanged(mMediaController.getQueue());
                onQueueTitleChanged(mMediaController.getQueueTitle());
            } else {
                onMetadataChanged(null);
                onPlaybackStateChanged(null);
                onQueueChanged(null);
                onQueueTitleChanged(null);
            }

            updatePlaybackStatus();
        }

        @Override
        public void onSessionDestroyed() {
            Log.w(TAG, "onSessionDestroyed");
            // Bypass the unregisterCallback as the controller is dead.
            // TODO: consider keeping track of orphaned callbacks in case they are resurrected...
            setMediaController(null);
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadataCompat mmdCompat) {
            // MediaSession#setMetadata builds an empty MediaMetadata when its argument is null,
            // yet MediaMetadataCompat doesn't implement equals... so if the given mmdCompat's
            // MediaMetadata equals EMPTY_MEDIA_METADATA, set mMediaMetadata to null to keep
            // the code simpler everywhere else.
            if ((mmdCompat != null) && EMPTY_MEDIA_METADATA.equals(mmdCompat.getMediaMetadata())) {
                mMediaMetadata = null;
            } else {
                mMediaMetadata = mmdCompat;
            }
            MediaItemMetadata item =
                    (mMediaMetadata != null) ? new MediaItemMetadata(mMediaMetadata) : null;
            mMetadata.setValue(item);
            updatePlaybackStatus();
        }

        @Override
        public void onQueueTitleChanged(CharSequence title) {
            mQueueTitle.setValue(title);
        }

        @Override
        public void onQueueChanged(@Nullable List<MediaSessionCompat.QueueItem> queue) {
            List<MediaItemMetadata> filtered = queue == null ? Collections.emptyList()
                    : queue.stream()
                    .filter(item -> item != null
                            && item.getDescription() != null
                            && item.getDescription().getTitle() != null)
                    .map(MediaItemMetadata::new)
                    .collect(Collectors.toList());

            mSanitizedQueue.setValue(filtered);
            mHasQueue.setValue(filtered.size() > 1);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat playbackState) {
            mPlaybackState = playbackState;
            updatePlaybackStatus();
        }

        private void updatePlaybackStatus() {
            if (mMediaController != null && mPlaybackState != null) {
                mPlaybackStateWrapper.setValue(
                        new PlaybackStateWrapper(mMediaController, mMediaMetadata, mPlaybackState));
            } else {
                mPlaybackStateWrapper.setValue(null);
            }
        }
    }

    /**
     * Convenient extension of {@link PlaybackStateCompat}.
     */
    public static final class PlaybackStateWrapper {

        private final MediaControllerCompat mMediaController;
        @Nullable
        private final MediaMetadataCompat mMetadata;
        private final PlaybackStateCompat mState;

        PlaybackStateWrapper(@NonNull MediaControllerCompat mediaController,
                             @Nullable MediaMetadataCompat metadata, @NonNull PlaybackStateCompat state) {
            mMediaController = mediaController;
            mMetadata = metadata;
            mState = state;
        }

        /**
         * Returns true if there's enough information in the state to show a UI for it.
         */
        public boolean shouldDisplay() {
            // STATE_NONE means no content to play.
            return mState.getState() != PlaybackStateCompat.STATE_NONE && ((mMetadata != null) || (
                    getMainAction() != ACTION_DISABLED));
        }

        /**
         * Returns the main action.
         */
        @Action
        public int getMainAction() {
            @Actions long actions = mState.getActions();
            @Action int stopAction = ACTION_DISABLED;
            if ((actions & (PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE)) != 0) {
                stopAction = ACTION_PAUSE;
            } else if ((actions & PlaybackStateCompat.ACTION_STOP) != 0) {
                stopAction = ACTION_STOP;
            }

            switch (mState.getState()) {
                case PlaybackStateCompat.STATE_PLAYING:
                case PlaybackStateCompat.STATE_BUFFERING:
                case PlaybackStateCompat.STATE_CONNECTING:
                case PlaybackStateCompat.STATE_FAST_FORWARDING:
                case PlaybackStateCompat.STATE_REWINDING:
                case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
                case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
                case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                    return stopAction;
                case PlaybackStateCompat.STATE_STOPPED:
                case PlaybackStateCompat.STATE_PAUSED:
                case PlaybackStateCompat.STATE_NONE:
                case PlaybackStateCompat.STATE_ERROR:
                    return (actions & PlaybackStateCompat.ACTION_PLAY) != 0 ? ACTION_PLAY
                            : ACTION_DISABLED;
                default:
                    Log.w(TAG, String.format("Unknown PlaybackState: %d", mState.getState()));
                    return ACTION_DISABLED;
            }
        }

        /**
         * Returns the currently supported playback actions
         */
        public long getSupportedActions() {
            return mState.getActions();
        }

        /**
         * Returns the duration of the media item in milliseconds. The current position in this
         * duration can be obtained by calling {@link #getProgress()}.
         */
        public long getMaxProgress() {
            return mMetadata == null ? 0 :
                    mMetadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        }

        /**
         * Returns whether the current media source is playing a media item.
         */
        public boolean isPlaying() {
            return mState.getState() == PlaybackStateCompat.STATE_PLAYING;
        }

        /**
         * Returns whether the media source supports skipping to the next item.
         */
        public boolean isSkipNextEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0;
        }

        /**
         * Returns whether the media source supports skipping to the previous item.
         */
        public boolean isSkipPreviousEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0;
        }

        /**
         * Returns whether the media source supports seeking to a new location in the media stream.
         */
        public boolean isSeekToEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SEEK_TO) != 0;
        }

        /**
         * Returns whether the media source requires reserved space for the skip to next action.
         */
        public boolean isSkipNextReserved() {
            return mMediaController.getExtras() != null
                    && (mMediaController.getExtras().getBoolean(
                    MediaConstants.SLOT_RESERVATION_SKIP_TO_NEXT)
                    || mMediaController.getExtras().getBoolean(
                    MediaConstants.PLAYBACK_SLOT_RESERVATION_SKIP_TO_NEXT));
        }

        /**
         * Returns whether the media source requires reserved space for the skip to previous action.
         */
        public boolean iSkipPreviousReserved() {
            return mMediaController.getExtras() != null
                    && (mMediaController.getExtras().getBoolean(
                    MediaConstants.SLOT_RESERVATION_SKIP_TO_PREV)
                    || mMediaController.getExtras().getBoolean(
                    MediaConstants.PLAYBACK_SLOT_RESERVATION_SKIP_TO_PREV));
        }

        /**
         * Returns whether the media source is loading (e.g.: buffering, connecting, etc.).
         */
        public boolean isLoading() {
            int state = mState.getState();
            return state == PlaybackStateCompat.STATE_BUFFERING
                    || state == PlaybackStateCompat.STATE_CONNECTING
                    || state == PlaybackStateCompat.STATE_FAST_FORWARDING
                    || state == PlaybackStateCompat.STATE_REWINDING
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM;
        }

        /**
         * See {@link PlaybackStateCompat#getErrorMessage}.
         */
        public CharSequence getErrorMessage() {
            return mState.getErrorMessage();
        }

        /**
         * See {@link PlaybackStateCompat#getErrorCode()}.
         */
        public int getErrorCode() {
            return mState.getErrorCode();
        }

        /**
         * See {@link PlaybackStateCompat#getActiveQueueItemId}.
         */
        public long getActiveQueueItemId() {
            return mState.getActiveQueueItemId();
        }

        /**
         * See {@link PlaybackStateCompat#getState}.
         */
        @PlaybackStateCompat.State
        public int getState() {
            return mState.getState();
        }

        /**
         * See {@link PlaybackStateCompat#getExtras}.
         */
        public Bundle getExtras() {
            return mState.getExtras();
        }

        @VisibleForTesting
        PlaybackStateCompat getStateCompat() {
            return mState;
        }
    }


    /**
     * Wraps the {@link android.media.session.MediaController.TransportControls TransportControls}
     * for a {@link MediaControllerCompat} to send commands.
     */
    // TODO(arnaudberry) does this wrapping make sense since we're still null checking the wrapper?
    // Should we call action methods on the model class instead ?
    public class PlaybackController {
        private final MediaControllerCompat mMediaController;

        private PlaybackController(@Nullable MediaControllerCompat mediaController) {
            mMediaController = mediaController;
        }

        /**
         * Sends a 'play' command to the media source
         */
        public void play() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().play();
            }
        }

        /**
         * Sends a 'skip previews' command to the media source
         */
        public void skipToPrevious() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().skipToPrevious();
            }
        }

        /**
         * Sends a 'skip next' command to the media source
         */
        public void skipToNext() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().skipToNext();
            }
        }

        /**
         * Sends a 'pause' command to the media source
         */
        public void pause() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().pause();
            }
        }

        /**
         * Sends a 'stop' command to the media source
         */
        public void stop() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().stop();
            }
        }

        /**
         * Moves to a new location in the media stream
         *
         * @param pos Position to move to, in milliseconds.
         */
        public void seekTo(long pos) {
            if (mMediaController != null) {
                PlaybackStateCompat oldState = mMediaController.getPlaybackState();
                PlaybackStateCompat newState = new PlaybackStateCompat.Builder(oldState)
                        .setState(oldState.getState(), pos, oldState.getPlaybackSpeed())
                        .build();
                mMediaControllerCallback.onPlaybackStateChanged(newState);

                mMediaController.getTransportControls().seekTo(pos);
            }
        }

        /**
         * Sends a custom action to the media source
         *
         * @param action identifier of the custom action
         * @param extras additional data to send to the media source.
         */
        public void doCustomAction(String action, Bundle extras) {
            if (mMediaController == null) return;
            MediaControllerCompat.TransportControls cntrl = mMediaController.getTransportControls();

            if (ACTION_SET_RATING.equals(action)) {
                boolean setHeart = extras != null && extras.getBoolean(EXTRA_SET_HEART, false);
                cntrl.setRating(RatingCompat.newHeartRating(setHeart));
            } else {
                cntrl.sendCustomAction(action, extras);
            }
        }

        /**
         * Starts playing a given media item.
         */
        public void playItem(MediaItemMetadata item) {
            if (mMediaController != null) {
                // Do NOT pass the extras back as that's not the official API and isn't supported
                // in media2, so apps should not rely on this.
                mMediaController.getTransportControls().playFromMediaId(item.getId(), null);
            }
        }

        /**
         * Skips to a particular item in the media queue. This id is {
         * MediaItemMetadata#mQueueId} of the items obtained through {
         * PlaybackViewModel#getQueue()}.
         */
        public void skipToQueueItem(long queueId) {
            if (mMediaController != null) {
                mMediaController.getTransportControls().skipToQueueItem(queueId);
            }
        }

        /**
         * Prepares the current media source for playback.
         */
        public void prepare() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().prepare();
            }
        }
    }
}
