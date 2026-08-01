// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static androidx.media3.common.Player.REPEAT_MODE_ALL;
import static androidx.media3.common.Player.REPEAT_MODE_OFF;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource;
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

/**
 * Plays a video, optionally combining a separate video-only and audio-only source into a single
 * playback via {@link MergingMediaSource}. That merging behaviour is why this plugin is forked
 * from the upstream video_player: adaptive YouTube streams above 720p ship video and audio as
 * separate URLs, and upstream only accepts a single URI.
 *
 * <p>Most of the ExoPlayer surface in media3 is marked {@link UnstableApi}, so this class opts in
 * once here rather than annotating every call site.
 */
@OptIn(markerClass = UnstableApi.class)
final class VideoPlayer {
  private static final String TAG = "VideoPlayer";

  private static final String FORMAT_SS = "ss";
  private static final String FORMAT_DASH = "dash";
  private static final String FORMAT_HLS = "hls";
  private static final String FORMAT_OTHER = "other";

  /**
   * How much media must be buffered before playback starts, in ms.
   *
   * <p>This is the dominant cost in time-to-first-frame. ExoPlayer only reaches STATE_READY once
   * every selected track has this much buffered, and with a {@link MergingMediaSource} that means
   * the video *and* the audio stream independently. The Flutter side awaits initialize(), which
   * resolves on STATE_READY, so this value is paid before the first frame is ever shown.
   *
   * <p>media3's default is 2500ms and this fork previously used 1500ms. 250ms is enough to start
   * decoding while the loader keeps filling toward {@link #MIN_BUFFER_MS} in the background.
   */
  private static final int BUFFER_FOR_PLAYBACK_MS = 250;

  /**
   * Buffer required to resume after a rebuffer. Kept higher than {@link #BUFFER_FOR_PLAYBACK_MS}
   * so that recovering from a stall does not immediately stall again.
   */
  private static final int BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000;

  /** Steady-state buffer the loader works toward once playback has started. */
  private static final int MIN_BUFFER_MS = 15_000;

  private static final int MAX_BUFFER_MS = 50_000;

  /**
   * How often the progressive loader re-checks whether it should keep loading. The default is 1MB,
   * which at high video bitrates is a coarse unit -- the LoadControl cannot notice that enough is
   * buffered to start until a full chunk lands. 64KB makes the "can we start yet?" decision much
   * more responsive without meaningfully more overhead.
   */
  private static final int CONTINUE_LOADING_CHECK_INTERVAL_BYTES = 64 * 1024;

  /**
   * Shared across every VideoPlayer instance so connections survive between items.
   *
   * <p>An adaptive YouTube item is two URLs (video-only + audio-only) that are normally served by
   * the same googlevideo host. A shared pool lets the second request reuse the first's connection
   * -- or multiplex over it when the host negotiates HTTP/2 -- instead of paying a second DNS +
   * TCP + TLS handshake. Switching quality reuses it again rather than starting cold.
   */
  private static OkHttpClient sharedHttpClient;

  private static synchronized OkHttpClient getSharedHttpClient() {
    if (sharedHttpClient == null) {
      sharedHttpClient =
          new OkHttpClient.Builder()
              .connectionPool(new ConnectionPool(8, 5, TimeUnit.MINUTES))
              .retryOnConnectionFailure(true)
              .connectTimeout(15, TimeUnit.SECONDS)
              .readTimeout(15, TimeUnit.SECONDS)
              .build();
    }
    return sharedHttpClient;
  }

  /** Set when prepare() is called, so the first frame can be timed against it. */
  private long prepareStartedAtMs = 0;

  private boolean firstFrameReported = false;

  private ExoPlayer exoPlayer;

  private Surface surface;

  private final TextureRegistry.SurfaceTextureEntry textureEntry;

  private final QueuingEventSink eventSink = new QueuingEventSink();

  private final EventChannel eventChannel;

  private boolean isInitialized = false;

  private final VideoPlayerOptions options;

  private MediaSource videoMediaSource;
  private MediaSource audioMediaSource;

  Map<String, String> httpHeaders;

  VideoPlayer(
      Context context,
      EventChannel eventChannel,
      TextureRegistry.SurfaceTextureEntry textureEntry,
      String videoDataSource,
      String audioDataSource,
      String formatHint,
      Map<String, String> headers,
      VideoPlayerOptions options) {
    this.eventChannel = eventChannel;
    this.textureEntry = textureEntry;
    this.options = options;

    exoPlayer = new ExoPlayer.Builder(context)
            .setLoadControl(new DefaultLoadControl.Builder()
              .setBufferDurationsMs(
                      MIN_BUFFER_MS,
                      MAX_BUFFER_MS,
                      BUFFER_FOR_PLAYBACK_MS,
                      BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
              // Decide purely on buffered duration. Otherwise the byte-based
              // target also has to be satisfied, and at high video bitrates that
              // holds back the start of playback for no benefit.
              .setPrioritizeTimeOverSizeThresholds(true)
              .build())
            .setRenderersFactory(
              new DefaultRenderersFactory(context)
                // If the primary decoder fails to initialise, fall back to
                // another rather than surfacing an error.
                .setEnableDecoderFallback(true)
                // Feed MediaCodec from a separate thread. This is the default on
                // API 31+; forcing it on cuts codec-init latency on older devices
                // too, which is where the slow starts are most noticeable.
                .forceEnableMediaCodecAsynchronousQueueing())
            .build();
    exoPlayer.setForegroundMode(true);
    httpHeaders = headers;

    Uri videoUri = null;
    if (videoDataSource != null) {
      videoUri = Uri.parse(videoDataSource);
    }
    Uri audioUri = null;
    if (audioDataSource != null) {
      audioUri = Uri.parse(audioDataSource);
    }

    DataSource.Factory videoDataSourceFactory;
    DataSource.Factory audioDataSourceFactory;

    if (videoUri == null && audioUri != null) {
      audioDataSourceFactory = buildFactory(context, audioUri);
      audioMediaSource = buildMediaSource(audioUri, audioDataSourceFactory, formatHint, context);
      exoPlayer.setMediaSource(audioMediaSource);
    } else if (audioUri != null) {
      videoDataSourceFactory = buildFactory(context, videoUri);
      audioDataSourceFactory = buildFactory(context, audioUri);
      videoMediaSource = buildMediaSource(videoUri, videoDataSourceFactory, formatHint, context);
      audioMediaSource = buildMediaSource(audioUri, audioDataSourceFactory, formatHint, context);
      MergingMediaSource mergedSource = new MergingMediaSource(videoMediaSource, audioMediaSource);
      exoPlayer.setMediaSource(mergedSource);
    } else {
      videoDataSourceFactory = buildFactory(context, videoUri);
      videoMediaSource = buildMediaSource(videoUri, videoDataSourceFactory, formatHint, context);
      exoPlayer.setMediaSource(videoMediaSource);
    }

    markPrepareStart();
    exoPlayer.prepare();
    setupVideoPlayer(eventChannel, textureEntry);
  }

  void setVideoUrl(Context context, String url) {
    long currentPosition = exoPlayer.getCurrentPosition();
    exoPlayer.stop();
    videoMediaSource = buildMediaSource(Uri.parse(url), buildFactory(context, Uri.parse(url)), FORMAT_OTHER, context);
    if (audioMediaSource != null) {
      MergingMediaSource mergingMediaSource = new MergingMediaSource(videoMediaSource, audioMediaSource);
      exoPlayer.setMediaSource(mergingMediaSource);
    } else {
      exoPlayer.setMediaSource(videoMediaSource);
    }
    exoPlayer.seekTo(currentPosition);
    markPrepareStart();
    exoPlayer.prepare();
    exoPlayer.play();
  }

  void setAudioUrl(Context context, String url) {
    exoPlayer.stop();
    audioMediaSource = buildMediaSource(Uri.parse(url), buildFactory(context, Uri.parse(url)), FORMAT_OTHER, context);
    if (videoMediaSource != null) {
      MergingMediaSource mergingMediaSource = new MergingMediaSource(videoMediaSource, audioMediaSource);
      exoPlayer.setMediaSource(mergingMediaSource);
    } else {
      exoPlayer.setMediaSource(audioMediaSource);
    }
    markPrepareStart();
    exoPlayer.prepare();
    exoPlayer.play();
  }

  DataSource.Factory buildFactory(Context context, Uri uri) {
    DataSource.Factory dataSourceFactory;
    if (isHTTP(uri)) {
      // OkHttp rather than DefaultHttpDataSource: it pools connections across
      // instances and speaks HTTP/2, so the audio request can reuse (or
      // multiplex over) the connection the video request just opened. It also
      // follows redirects itself, which is what setAllowCrossProtocolRedirects
      // was doing on the old factory.
      HttpDataSource.Factory httpDataSourceFactory =
              new OkHttpDataSource.Factory(getSharedHttpClient())
                      .setUserAgent("ExoPlayer");

      if (httpHeaders != null && !httpHeaders.isEmpty()) {
        httpDataSourceFactory.setDefaultRequestProperties(httpHeaders);
      }
      dataSourceFactory = httpDataSourceFactory;
    } else {
      dataSourceFactory = new DefaultDataSource.Factory(context);
    }
    return dataSourceFactory;
  }

  private static boolean isHTTP(Uri uri) {
    if (uri == null || uri.getScheme() == null) {
      return false;
    }
    String scheme = uri.getScheme();
    return scheme.equals("http") || scheme.equals("https");
  }

  private MediaSource buildMediaSource(
      Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint, Context context) {
    int type;
    if (formatHint == null) {
      type = Util.inferContentType(uri);
    } else {
      switch (formatHint) {
        case FORMAT_SS:
          type = C.CONTENT_TYPE_SS;
          break;
        case FORMAT_DASH:
          type = C.CONTENT_TYPE_DASH;
          break;
        case FORMAT_HLS:
          type = C.CONTENT_TYPE_HLS;
          break;
        case FORMAT_OTHER:
          type = C.CONTENT_TYPE_OTHER;
          break;
        default:
          type = -1;
          break;
      }
    }
    switch (type) {
      case C.CONTENT_TYPE_SS:
        return new SsMediaSource.Factory(
                new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                new DefaultDataSource.Factory(context, mediaDataSourceFactory))
            .createMediaSource(MediaItem.fromUri(uri));
      case C.CONTENT_TYPE_DASH:
        return new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                new DefaultDataSource.Factory(context, mediaDataSourceFactory))
            .createMediaSource(MediaItem.fromUri(uri));
      case C.CONTENT_TYPE_HLS:
        return new HlsMediaSource.Factory(mediaDataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri));
      case C.CONTENT_TYPE_OTHER:
        // Every googlevideo URL lands here: the path ends in "videoplayback"
        // with no extension, so inferContentType cannot do better than OTHER.
        return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
            .setContinueLoadingCheckIntervalBytes(CONTINUE_LOADING_CHECK_INTERVAL_BYTES)
            .createMediaSource(MediaItem.fromUri(uri));
      default:
        {
          throw new IllegalStateException("Unsupported type: " + type);
        }
    }
  }

  private void setupVideoPlayer(
      EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry) {
    eventChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink sink) {
            eventSink.setDelegate(sink);
          }

          @Override
          public void onCancel(Object o) {
            eventSink.setDelegate(null);
          }
        });

    surface = new Surface(textureEntry.surfaceTexture());
    exoPlayer.setVideoSurface(surface);
    setAudioAttributes(exoPlayer, options.mixWithOthers);

    exoPlayer.addListener(
        new Listener() {
          private boolean isBuffering = false;

          public void setBuffering(boolean buffering) {
            if (isBuffering != buffering) {
              isBuffering = buffering;
              Map<String, Object> event = new HashMap<>();
              event.put("event", isBuffering ? "bufferingStart" : "bufferingEnd");
              eventSink.success(event);
            }
          }

          @Override
          public void onPlaybackStateChanged(final int playbackState) {
            if (playbackState == Player.STATE_BUFFERING) {
              setBuffering(true);
              sendBufferingUpdate();
            } else if (playbackState == Player.STATE_READY) {
              logSincePrepare("STATE_READY");
              if (!isInitialized) {
                isInitialized = true;
                sendInitialized();
              }
            } else if (playbackState == Player.STATE_ENDED) {
              Map<String, Object> event = new HashMap<>();
              event.put("event", "completed");
              eventSink.success(event);
            }

            if (playbackState != Player.STATE_BUFFERING) {
              setBuffering(false);
            }
          }

          @Override
          public void onRenderedFirstFrame() {
            if (!firstFrameReported) {
              firstFrameReported = true;
              logSincePrepare("first frame");
            }
          }

          @Override
          public void onPlayerError(final PlaybackException error) {
            setBuffering(false);
            eventSink.error("VideoError", "Video player had error " + error, null);
          }
        });
  }

  /**
   * Starts the clock for the next time-to-first-frame measurement. Enable with
   * {@code adb shell setprop log.tag.VideoPlayer DEBUG} and watch logcat to see where the time
   * before playback actually goes.
   */
  private void markPrepareStart() {
    prepareStartedAtMs = SystemClock.elapsedRealtime();
    firstFrameReported = false;
  }

  private void logSincePrepare(String stage) {
    if (prepareStartedAtMs != 0 && Log.isLoggable(TAG, Log.DEBUG)) {
      Log.d(TAG, stage + " +" + (SystemClock.elapsedRealtime() - prepareStartedAtMs) + "ms");
    }
  }

  void sendBufferingUpdate() {
    Map<String, Object> event = new HashMap<>();
    event.put("event", "bufferingUpdate");
    List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
    // iOS supports a list of buffered ranges, so here is a list with a single range.
    event.put("values", Collections.singletonList(range));
    eventSink.success(event);
  }

  private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
    exoPlayer.setAudioAttributes(
        new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
        !isMixMode);
  }

  void play() {
    exoPlayer.setPlayWhenReady(true);
  }

  void pause() {
    exoPlayer.setPlayWhenReady(false);
  }

  void setLooping(boolean value) {
    exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
  }

  void setVolume(double value) {
    float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
    exoPlayer.setVolume(bracketedValue);
  }

  void setPlaybackSpeed(double value) {
    // We do not need to consider pitch and skipSilence for now as we do not handle them and
    // therefore never diverge from the default values.
    final PlaybackParameters playbackParameters = new PlaybackParameters(((float) value));

    exoPlayer.setPlaybackParameters(playbackParameters);
  }

  void seekTo(int location) {
    exoPlayer.seekTo(location);
  }

  long getPosition() {
    return exoPlayer.getCurrentPosition();
  }

  @SuppressWarnings("SuspiciousNameCombination")
  private void sendInitialized() {
    if (isInitialized) {
      Map<String, Object> event = new HashMap<>();
      event.put("event", "initialized");
      event.put("duration", exoPlayer.getDuration());

      if (exoPlayer.getVideoFormat() != null) {
        Format videoFormat = exoPlayer.getVideoFormat();
        int width = videoFormat.width;
        int height = videoFormat.height;
        int rotationDegrees = videoFormat.rotationDegrees;
        // Switch the width/height if video was taken in portrait mode
        if (rotationDegrees == 90 || rotationDegrees == 270) {
          width = exoPlayer.getVideoFormat().height;
          height = exoPlayer.getVideoFormat().width;
        }
        event.put("width", width);
        event.put("height", height);
      }
      eventSink.success(event);
    }
  }

  void dispose() {
    if (isInitialized) {
      exoPlayer.stop();
    }
    textureEntry.release();
    eventChannel.setStreamHandler(null);
    if (surface != null) {
      surface.release();
    }
    if (exoPlayer != null) {
      exoPlayer.release();
    }
  }
}
