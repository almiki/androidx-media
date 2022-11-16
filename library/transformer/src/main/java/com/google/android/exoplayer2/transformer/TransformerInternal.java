/*
 * Copyright 2022 The Android Open Source Project
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

package com.google.android.exoplayer2.transformer;

import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_AVAILABLE;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_NO_TRANSFORMATION;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE;
import static com.google.android.exoplayer2.transformer.Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static java.lang.Math.min;

import android.content.Context;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.mp4.SlowMotionData;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.DebugViewProvider;
import com.google.android.exoplayer2.util.Effect;
import com.google.android.exoplayer2.util.FrameProcessor;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/* package */ final class TransformerInternal {

  public interface Listener {

    void onTransformationCompleted(TransformationResult transformationResult);

    void onTransformationError(TransformationException exception);
  }

  private final Context context;
  @Nullable private final String outputPath;
  @Nullable private final ParcelFileDescriptor outputParcelFileDescriptor;
  private final TransformationRequest transformationRequest;
  private final ImmutableList<AudioProcessor> audioProcessors;
  private final ImmutableList<Effect> videoEffects;
  private final Codec.DecoderFactory decoderFactory;
  private final Codec.EncoderFactory encoderFactory;
  private final FrameProcessor.Factory frameProcessorFactory;
  private final Listener listener;
  private final DebugViewProvider debugViewProvider;
  private final Handler handler;
  private final ExoPlayerAssetLoader exoPlayerAssetLoader;
  private final MuxerWrapper muxerWrapper;
  private final List<SamplePipeline> samplePipelines;

  private @Transformer.ProgressState int progressState;
  private long durationMs;
  private boolean released;

  public TransformerInternal(
      Context context,
      MediaItem mediaItem,
      @Nullable String outputPath,
      @Nullable ParcelFileDescriptor outputParcelFileDescriptor,
      TransformationRequest transformationRequest,
      ImmutableList<AudioProcessor> audioProcessors,
      ImmutableList<Effect> videoEffects,
      boolean removeAudio,
      boolean removeVideo,
      MediaSource.Factory mediaSourceFactory,
      Codec.DecoderFactory decoderFactory,
      Codec.EncoderFactory encoderFactory,
      FrameProcessor.Factory frameProcessorFactory,
      Muxer.Factory muxerFactory,
      Listener listener,
      FallbackListener fallbackListener,
      DebugViewProvider debugViewProvider,
      Clock clock) {
    this.context = context;
    this.outputPath = outputPath;
    this.outputParcelFileDescriptor = outputParcelFileDescriptor;
    this.transformationRequest = transformationRequest;
    this.audioProcessors = audioProcessors;
    this.videoEffects = videoEffects;
    this.decoderFactory = decoderFactory;
    this.encoderFactory = encoderFactory;
    this.frameProcessorFactory = frameProcessorFactory;
    this.listener = listener;
    this.debugViewProvider = debugViewProvider;
    handler = Util.createHandlerForCurrentLooper();
    AssetLoaderListener assetLoaderListener = new AssetLoaderListener(mediaItem, fallbackListener);
    muxerWrapper =
        new MuxerWrapper(
            outputPath,
            outputParcelFileDescriptor,
            muxerFactory,
            /* errorConsumer= */ assetLoaderListener::onError);
    exoPlayerAssetLoader =
        new ExoPlayerAssetLoader(
            context,
            mediaItem,
            removeAudio,
            removeVideo,
            mediaSourceFactory,
            assetLoaderListener,
            clock);
    samplePipelines = new ArrayList<>(/* initialCapacity= */ 2);
    progressState = PROGRESS_STATE_WAITING_FOR_AVAILABILITY;
  }

  public void start() {
    exoPlayerAssetLoader.start();
  }

  public @Transformer.ProgressState int getProgress(ProgressHolder progressHolder) {
    if (progressState == PROGRESS_STATE_AVAILABLE) {
      long positionMs = getCurrentPositionMs();
      progressHolder.progress = min((int) (positionMs * 100 / durationMs), 99);
    }
    return progressState;
  }

  /**
   * Releases the resources.
   *
   * @param forCancellation Whether the reason for releasing the resources is the transformation
   *     cancellation.
   * @throws TransformationException If the muxer is in the wrong state and {@code forCancellation}
   *     is false.
   */
  public void release(boolean forCancellation) throws TransformationException {
    if (released) {
      return;
    }
    samplePipelines.clear();
    progressState = PROGRESS_STATE_NO_TRANSFORMATION;
    released = true;
    exoPlayerAssetLoader.release();
    try {
      muxerWrapper.release(forCancellation);
    } catch (Muxer.MuxerException e) {
      throw TransformationException.createForMuxer(
          e, TransformationException.ERROR_CODE_MUXING_FAILED);
    }
  }

  private long getCurrentPositionMs() {
    if (samplePipelines.isEmpty()) {
      return 0;
    }
    long positionMsSum = 0;
    for (int i = 0; i < samplePipelines.size(); i++) {
      positionMsSum += samplePipelines.get(i).getCurrentPositionMs();
    }
    return positionMsSum / samplePipelines.size();
  }

  /**
   * Returns the current size in bytes of the current/latest output file, or {@link C#LENGTH_UNSET}
   * if unavailable.
   */
  private long getCurrentOutputFileCurrentSizeBytes() {
    long fileSize = C.LENGTH_UNSET;

    if (outputPath != null) {
      fileSize = new File(outputPath).length();
    } else if (outputParcelFileDescriptor != null) {
      fileSize = outputParcelFileDescriptor.getStatSize();
    }

    if (fileSize <= 0) {
      fileSize = C.LENGTH_UNSET;
    }

    return fileSize;
  }

  private class AssetLoaderListener implements ExoPlayerAssetLoader.Listener {

    private final MediaItem mediaItem;
    private final FallbackListener fallbackListener;

    private volatile boolean trackRegistered;

    public AssetLoaderListener(MediaItem mediaItem, FallbackListener fallbackListener) {
      this.mediaItem = mediaItem;
      this.fallbackListener = fallbackListener;
    }

    @Override
    public void onDurationMs(long durationMs) {
      // Make progress permanently unavailable if the duration is unknown, so that it doesn't jump
      // to a high value at the end of the transformation if the duration is set once the media is
      // entirely loaded.
      progressState =
          durationMs <= 0 || durationMs == C.TIME_UNSET
              ? PROGRESS_STATE_UNAVAILABLE
              : PROGRESS_STATE_AVAILABLE;
      TransformerInternal.this.durationMs = durationMs;
    }

    @Override
    public void onTrackRegistered() {
      trackRegistered = true;
      muxerWrapper.registerTrack();
      fallbackListener.registerTrack();
    }

    @Override
    public void onAllTracksRegistered() {
      if (!trackRegistered) {
        onError(new IllegalStateException("The output does not contain any tracks."));
      }
    }

    @Override
    public SamplePipeline onTrackAdded(
        Format format, long streamStartPositionUs, long streamOffsetUs)
        throws TransformationException {
      SamplePipeline samplePipeline =
          getSamplePipeline(format, streamStartPositionUs, streamOffsetUs);
      samplePipelines.add(samplePipeline);
      return samplePipeline;
    }

    @Override
    public void onError(Exception e) {
      TransformationException transformationException;
      if (e instanceof TransformationException) {
        transformationException = (TransformationException) e;
      } else if (e instanceof PlaybackException) {
        transformationException =
            TransformationException.createForPlaybackException((PlaybackException) e);
      } else {
        transformationException = TransformationException.createForUnexpected(e);
      }
      handleTransformationEnded(transformationException);
    }

    @Override
    public void onEnded() {
      handleTransformationEnded(/* transformationException= */ null);
    }

    private SamplePipeline getSamplePipeline(
        Format inputFormat, long streamStartPositionUs, long streamOffsetUs)
        throws TransformationException {
      if (MimeTypes.isAudio(inputFormat.sampleMimeType) && shouldTranscodeAudio(inputFormat)) {
        return new AudioTranscodingSamplePipeline(
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            audioProcessors,
            decoderFactory,
            encoderFactory,
            muxerWrapper,
            fallbackListener);
      } else if (MimeTypes.isVideo(inputFormat.sampleMimeType)
          && shouldTranscodeVideo(inputFormat, streamStartPositionUs, streamOffsetUs)) {
        return new VideoTranscodingSamplePipeline(
            context,
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            videoEffects,
            frameProcessorFactory,
            decoderFactory,
            encoderFactory,
            muxerWrapper,
            fallbackListener,
            this::onError,
            debugViewProvider);
      } else {
        return new PassthroughSamplePipeline(
            inputFormat,
            streamStartPositionUs,
            streamOffsetUs,
            transformationRequest,
            muxerWrapper,
            fallbackListener);
      }
    }

    private boolean shouldTranscodeAudio(Format inputFormat) {
      if (encoderFactory.audioNeedsEncoding()) {
        return true;
      }
      if (transformationRequest.audioMimeType != null
          && !transformationRequest.audioMimeType.equals(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.audioMimeType == null
          && !muxerWrapper.supportsSampleMimeType(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.flattenForSlowMotion && isSlowMotion(inputFormat)) {
        return true;
      }
      if (!audioProcessors.isEmpty()) {
        return true;
      }
      return false;
    }

    private boolean isSlowMotion(Format format) {
      @Nullable Metadata metadata = format.metadata;
      if (metadata == null) {
        return false;
      }
      for (int i = 0; i < metadata.length(); i++) {
        if (metadata.get(i) instanceof SlowMotionData) {
          return true;
        }
      }
      return false;
    }

    private boolean shouldTranscodeVideo(
        Format inputFormat, long streamStartPositionUs, long streamOffsetUs) {
      if ((streamStartPositionUs - streamOffsetUs) != 0
          && !mediaItem.clippingConfiguration.startsAtKeyFrame) {
        return true;
      }
      if (encoderFactory.videoNeedsEncoding()) {
        return true;
      }
      if (transformationRequest.hdrMode != TransformationRequest.HDR_MODE_KEEP_HDR) {
        return true;
      }
      if (transformationRequest.videoMimeType != null
          && !transformationRequest.videoMimeType.equals(inputFormat.sampleMimeType)) {
        return true;
      }
      if (transformationRequest.videoMimeType == null
          && !muxerWrapper.supportsSampleMimeType(inputFormat.sampleMimeType)) {
        return true;
      }
      if (inputFormat.pixelWidthHeightRatio != 1f) {
        return true;
      }
      if (transformationRequest.rotationDegrees != 0f) {
        return true;
      }
      if (transformationRequest.scaleX != 1f) {
        return true;
      }
      if (transformationRequest.scaleY != 1f) {
        return true;
      }
      // The decoder rotates encoded frames for display by inputFormat.rotationDegrees.
      int decodedHeight =
          (inputFormat.rotationDegrees % 180 == 0) ? inputFormat.height : inputFormat.width;
      if (transformationRequest.outputHeight != C.LENGTH_UNSET
          && transformationRequest.outputHeight != decodedHeight) {
        return true;
      }
      if (!videoEffects.isEmpty()) {
        return true;
      }
      return false;
    }

    private void handleTransformationEnded(
        @Nullable TransformationException transformationException) {
      Util.postOrRun(
          handler,
          () -> {
            @Nullable TransformationException releaseException = null;
            try {
              release(/* forCancellation= */ false);
            } catch (TransformationException e) {
              releaseException = e;
            } catch (RuntimeException e) {
              releaseException = TransformationException.createForUnexpected(e);
            }
            TransformationException exception = transformationException;
            if (exception == null) {
              // We only report the exception caused by releasing the resources if there is no other
              // exception. It is more intuitive to call the error callback only once and reporting
              // the exception caused by releasing the resources can be confusing if it is a
              // consequence of the first exception.
              exception = releaseException;
            }

            if (exception != null) {
              listener.onTransformationError(exception);
            } else {
              TransformationResult result =
                  new TransformationResult.Builder()
                      .setDurationMs(checkNotNull(muxerWrapper).getDurationMs())
                      .setAverageAudioBitrate(
                          muxerWrapper.getTrackAverageBitrate(C.TRACK_TYPE_AUDIO))
                      .setAverageVideoBitrate(
                          muxerWrapper.getTrackAverageBitrate(C.TRACK_TYPE_VIDEO))
                      .setVideoFrameCount(muxerWrapper.getTrackSampleCount(C.TRACK_TYPE_VIDEO))
                      .setFileSizeBytes(getCurrentOutputFileCurrentSizeBytes())
                      .build();
              listener.onTransformationCompleted(result);
            }
          });
    }
  }
}
