/*
 * Copyright 2016 Mario Guggenberger <mg@protyposis.net>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.protyposis.android.spectaculumdemo;

import android.media.MediaCodec;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.widget.MediaController;
import android.widget.Toast;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecSelector;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.extractor.ExtractorSampleSource;
import com.google.android.exoplayer.upstream.Allocator;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.PlayerControl;

import net.protyposis.android.spectaculum.InputSurfaceHolder;
import net.protyposis.android.spectaculum.SpectaculumView;

import java.io.IOException;

/**
 * Created by Mario on 17.08.2016.
 */
public class ExoPlayerActivity extends SpectaculumDemoBaseActivity implements
        InputSurfaceHolder.Callback {

    private static final String TAG = ExoPlayerActivity.class.getSimpleName();

    private SpectaculumView mVideoView;
    private ExoPlayer mExoPlayer;
    private PlayerControl mExoPlayerControl;

    private Uri mVideoUri;
    private int mVideoPosition;
    private boolean mVideoPlaying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.activity_exoplayer);
        super.onCreate(savedInstanceState);

        mVideoView = (SpectaculumView) findViewById(R.id.spectaculum);
        mVideoView.getInputHolder().addCallback(this);

        initMediaController(new ExoPlayerControl());

        // Init video playback state (will eventually be overwritten by saved instance state)
        mVideoUri = getIntent().getData();
        mVideoPosition = 0;
        mVideoPlaying = false;
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mVideoUri = savedInstanceState.getParcelable("uri");
        mVideoPosition = savedInstanceState.getInt("position");
        mVideoPlaying = savedInstanceState.getBoolean("playing");
    }

    @Override
    public void surfaceCreated(InputSurfaceHolder holder) {
        try {
            initPlayer();
        } catch (IOException e) {
            Log.e(TAG, "error initializing player", e);
        }
    }

    @Override
    public void surfaceDestroyed(InputSurfaceHolder holder) {
        // Stop playback so no video frames get written to the now invalid surface causing an exception
        mExoPlayer.stop();
    }

    private void initPlayer() throws IOException {
        setMediaUri(mVideoUri);
        final boolean[] waitingForFirstOnPrepared = {true};

        // Set up an ExoPlayer
        // This looks really messy but serves the purpose
        mExoPlayer = ExoPlayer.Factory.newInstance(2);
        int BUFFER_SEGMENT_SIZE = 64 * 1024;
        int BUFFER_SEGMENT_COUNT = 256;
        Allocator allocator = new DefaultAllocator(BUFFER_SEGMENT_SIZE);
        DataSource dataSource = new DefaultUriDataSource(this, "Spectaculum-Demo");
        ExtractorSampleSource sampleSource = new ExtractorSampleSource(
                mVideoUri, dataSource, allocator, BUFFER_SEGMENT_COUNT * BUFFER_SEGMENT_SIZE);
        MediaCodecVideoTrackRenderer.EventListener videoEventListener = new MediaCodecVideoTrackRenderer.EventListener() {
            @Override
            public void onDroppedFrames(int count, long elapsed) {}

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                mVideoView.updateResolution(width, height);
            }

            @Override
            public void onDrawnToSurface(Surface surface) {}

            @Override
            public void onDecoderInitializationError(MediaCodecTrackRenderer.DecoderInitializationException e) {}

            @Override
            public void onCryptoError(MediaCodec.CryptoException e) {}

            @Override
            public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs, long initializationDurationMs) {}
        };
        MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(
                this, sampleSource, MediaCodecSelector.DEFAULT,
                MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 1000, new Handler(), videoEventListener, 10);
        MediaCodecAudioTrackRenderer audioRenderer = new MediaCodecAudioTrackRenderer(
                sampleSource, MediaCodecSelector.DEFAULT);
        mExoPlayer.prepare(videoRenderer, audioRenderer);
        mExoPlayer.sendMessage(videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE,
                mVideoView.getInputHolder().getSurface());
        mExoPlayerControl = new PlayerControl(mExoPlayer);
        mExoPlayer.addListener(new ExoPlayer.Listener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if(playbackState == ExoPlayer.STATE_READY && waitingForFirstOnPrepared[0]) {
                    hideProgressIndicator();
                    getMediaControllerWidget().setEnabled(true);

                    /* Because we want to replicate MediaPlayer's OnPreparedListener that is only
                     * fired once when the player is ready, we set this value to false to not
                     * execute this block again. */
                    waitingForFirstOnPrepared[0] = false;
                }
            }

            @Override
            public void onPlayWhenReadyCommitted() {}

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                Toast.makeText(ExoPlayerActivity.this,
                        "Cannot play the video, see logcat for the detailed exception",
                        Toast.LENGTH_LONG).show();
                hideProgressIndicator();
                getMediaControllerWidget().setEnabled(false);
            }
        });
        mExoPlayer.seekTo(mVideoPosition > 0 ? mVideoPosition : 0);
        if (mVideoPlaying) {
            mExoPlayerControl.start();
        }

        mVideoView.setOnFrameCapturedCallback(new Utils.OnFrameCapturedCallback(this, "spectaculum-exoplayerview"));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mVideoView != null) {
            mVideoPosition = (int)mExoPlayer.getCurrentPosition();
            mVideoPlaying = mExoPlayerControl.isPlaying();
            // the uri is stored in the base activity
            outState.putInt("position", mVideoPosition);
            outState.putBoolean("playing", mVideoPlaying);
        }
    }

    @Override
    protected void onStop() {
        mExoPlayer.release();
        super.onStop();
    }

    private class ExoPlayerControl implements MediaController.MediaPlayerControl {
        @Override
        public void start() {
            if (mExoPlayer != null) mExoPlayerControl.start();
        }

        @Override
        public void pause() {
            if (mExoPlayer != null) mExoPlayerControl.pause();
        }

        @Override
        public int getDuration() {
            return mExoPlayer != null ? (int)mExoPlayer.getDuration() : 0;
        }

        @Override
        public int getCurrentPosition() {
            return mExoPlayer != null ? (int)mExoPlayer.getCurrentPosition() : 0;
        }

        @Override
        public void seekTo(int pos) {
            if (mExoPlayer != null) mExoPlayer.seekTo(pos);
        }

        @Override
        public boolean isPlaying() {
            return mExoPlayer != null && mExoPlayerControl.isPlaying();
        }

        @Override
        public int getBufferPercentage() {
            return 0;
        }

        @Override
        public boolean canPause() {
            return true;
        }

        @Override
        public boolean canSeekBackward() {
            return true;
        }

        @Override
        public boolean canSeekForward() {
            return true;
        }

        @Override
        public int getAudioSessionId() {
            return mExoPlayer != null ? mExoPlayerControl.getAudioSessionId() : 0;
        }
    };
}
