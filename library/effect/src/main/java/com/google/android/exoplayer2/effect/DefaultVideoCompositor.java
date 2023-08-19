/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.effect;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Assertions.checkState;
import static java.lang.Math.abs;
import static java.lang.Math.max;

import android.content.Context;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.GlObjectsProvider;
import com.google.android.exoplayer2.util.GlProgram;
import com.google.android.exoplayer2.util.GlTextureInfo;
import com.google.android.exoplayer2.util.GlUtil;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.util.VideoFrameProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A basic {@link VideoCompositor} implementation that takes in frames from exactly 2 input sources'
 * streams and combines them into one output stream.
 *
 * <p>The first {@linkplain #registerInputSource registered source} will be the primary stream,
 * which is used to determine the output frames' timestamps and dimensions.
 *
 * @deprecated com.google.android.exoplayer2 is deprecated. Please migrate to androidx.media3 (which
 *     contains the same ExoPlayer code). See <a
 *     href="https://developer.android.com/guide/topics/media/media3/getting-started/migration-guide">the
 *     migration guide</a> for more details, including a script to help with the migration.
 */
@Deprecated
public final class DefaultVideoCompositor implements VideoCompositor {
  // TODO: b/262694346 -  Flesh out this implementation by doing the following:
  //  * Use a lock to synchronize inputFrameInfos more narrowly, to reduce blocking.
  //  * If the primary stream ends, consider setting the secondary stream as the new primary stream,
  //    so that secondary stream frames aren't dropped.
  //  * Consider adding info about the timestamps for each input frame used to composite an output
  //    frame, to aid debugging and testing.

  private static final String THREAD_NAME = "Effect:DefaultVideoCompositor:GlThread";
  private static final String TAG = "DefaultVideoCompositor";
  private static final String VERTEX_SHADER_PATH = "shaders/vertex_shader_transformation_es2.glsl";
  private static final String FRAGMENT_SHADER_PATH = "shaders/fragment_shader_copy_es2.glsl";
  private static final int PRIMARY_INPUT_ID = 0;

  private final Context context;
  private final Listener listener;
  private final DefaultVideoFrameProcessor.TextureOutputListener textureOutputListener;
  private final GlObjectsProvider glObjectsProvider;
  private final VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor;

  @GuardedBy("this")
  private final List<InputSource> inputSources;

  @GuardedBy("this")
  private boolean allInputsEnded; // Whether all inputSources have signaled end of input.

  private final TexturePool outputTexturePool;
  private final Queue<Long> outputTextureTimestamps; // Synchronized with outputTexturePool.
  private final Queue<Long> syncObjects; // Synchronized with outputTexturePool.

  // Only used on the GL Thread.
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull GlProgram glProgram;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;

  /**
   * Creates an instance.
   *
   * <p>If a non-null {@code executorService} is set, the {@link ExecutorService} must be
   * {@linkplain ExecutorService#shutdown shut down} by the caller.
   */
  public DefaultVideoCompositor(
      Context context,
      GlObjectsProvider glObjectsProvider,
      @Nullable ExecutorService executorService,
      Listener listener,
      DefaultVideoFrameProcessor.TextureOutputListener textureOutputListener,
      @IntRange(from = 1) int textureOutputCapacity) {
    this.context = context;
    this.listener = listener;
    this.textureOutputListener = textureOutputListener;
    this.glObjectsProvider = glObjectsProvider;

    inputSources = new ArrayList<>();
    outputTexturePool =
        new TexturePool(/* useHighPrecisionColorComponents= */ false, textureOutputCapacity);
    outputTextureTimestamps = new ArrayDeque<>(textureOutputCapacity);
    syncObjects = new ArrayDeque<>(textureOutputCapacity);

    boolean ownsExecutor = executorService == null;
    ExecutorService instanceExecutorService =
        ownsExecutor ? Util.newSingleThreadExecutor(THREAD_NAME) : checkNotNull(executorService);
    videoFrameProcessingTaskExecutor =
        new VideoFrameProcessingTaskExecutor(
            instanceExecutorService,
            /* shouldShutdownExecutorService= */ ownsExecutor,
            listener::onError);
    videoFrameProcessingTaskExecutor.submit(this::setupGlObjects);
  }

  /**
   * {@inheritDoc}
   *
   * <p>The input source must be able to have at least two {@linkplain #queueInputTexture queued
   * textures} before one texture is {@linkplain
   * DefaultVideoFrameProcessor.ReleaseOutputTextureCallback released}.
   */
  @Override
  public synchronized int registerInputSource() {
    inputSources.add(new InputSource());
    return inputSources.size() - 1;
  }

  @Override
  public synchronized void signalEndOfInputSource(int inputId) {
    inputSources.get(inputId).isInputEnded = true;
    boolean allInputsEnded = true;
    for (int i = 0; i < inputSources.size(); i++) {
      if (!inputSources.get(i).isInputEnded) {
        allInputsEnded = false;
        break;
      }
    }

    this.allInputsEnded = allInputsEnded;
    if (inputSources.get(PRIMARY_INPUT_ID).frameInfos.isEmpty()) {
      if (inputId == PRIMARY_INPUT_ID) {
        releaseExcessFramesInAllSecondaryStreams();
      }
      if (allInputsEnded) {
        listener.onEnded();
        return;
      }
    }
    if (inputId != PRIMARY_INPUT_ID && inputSources.get(inputId).frameInfos.size() == 1) {
      // When a secondary stream ends input, composite if there was only one pending frame in the
      // stream.
      videoFrameProcessingTaskExecutor.submit(this::maybeComposite);
    }
  }

  @Override
  public synchronized void queueInputTexture(
      int inputId,
      GlTextureInfo inputTexture,
      long presentationTimeUs,
      DefaultVideoFrameProcessor.ReleaseOutputTextureCallback releaseTextureCallback) {
    InputSource inputSource = inputSources.get(inputId);
    checkState(!inputSource.isInputEnded);

    InputFrameInfo inputFrameInfo =
        new InputFrameInfo(inputTexture, presentationTimeUs, releaseTextureCallback);
    inputSource.frameInfos.add(inputFrameInfo);

    if (inputId == PRIMARY_INPUT_ID) {
      releaseExcessFramesInAllSecondaryStreams();
    } else {
      releaseExcessFramesInSecondaryStream(inputSource);
    }

    videoFrameProcessingTaskExecutor.submit(this::maybeComposite);
  }

  @Override
  public void release() {
    try {
      videoFrameProcessingTaskExecutor.release(/* releaseTask= */ this::releaseGlObjects);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private synchronized void releaseExcessFramesInAllSecondaryStreams() {
    for (int i = 0; i < inputSources.size(); i++) {
      if (i == PRIMARY_INPUT_ID) {
        continue;
      }
      releaseExcessFramesInSecondaryStream(inputSources.get(i));
    }
  }

  /**
   * Release unneeded frames from the {@link InputSource} secondary stream.
   *
   * <p>After this method returns, there should be exactly zero or one frames left with a timestamp
   * less than the primary stream's next timestamp that were present when the method execution
   * began.
   */
  private synchronized void releaseExcessFramesInSecondaryStream(InputSource secondaryInputSource) {
    InputSource primaryInputSource = inputSources.get(PRIMARY_INPUT_ID);
    // If the primary stream output is ended, all secondary frames can be released.
    if (primaryInputSource.frameInfos.isEmpty() && primaryInputSource.isInputEnded) {
      releaseFrames(
          secondaryInputSource,
          /* numberOfFramesToRelease= */ secondaryInputSource.frameInfos.size());
      return;
    }

    // Release frames until the secondary stream has 0-2 frames with presentationTimeUs before or at
    // nextTimestampToComposite.
    @Nullable InputFrameInfo nextPrimaryFrame = primaryInputSource.frameInfos.peek();
    long nextTimestampToComposite =
        nextPrimaryFrame != null ? nextPrimaryFrame.presentationTimeUs : C.TIME_UNSET;

    int numberOfSecondaryFramesBeforeOrAtNextTargetTimestamp =
        Iterables.size(
            Iterables.filter(
                secondaryInputSource.frameInfos,
                frame -> frame.presentationTimeUs <= nextTimestampToComposite));
    releaseFrames(
        secondaryInputSource,
        /* numberOfFramesToRelease= */ max(
            numberOfSecondaryFramesBeforeOrAtNextTargetTimestamp - 1, 0));
  }

  private synchronized void releaseFrames(InputSource inputSource, int numberOfFramesToRelease) {
    for (int i = 0; i < numberOfFramesToRelease; i++) {
      InputFrameInfo frameInfoToRelease = inputSource.frameInfos.remove();
      frameInfoToRelease.releaseCallback.release(frameInfoToRelease.presentationTimeUs);
    }
  }

  // Below methods must be called on the GL thread.
  private void setupGlObjects() throws GlUtil.GlException {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    placeholderEglSurface =
        glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
  }

  private synchronized void maybeComposite()
      throws VideoFrameProcessingException, GlUtil.GlException {
    ImmutableList<InputFrameInfo> framesToComposite = getFramesToComposite();
    if (framesToComposite.isEmpty()) {
      return;
    }

    ensureGlProgramConfigured();

    // TODO: b/262694346 -
    //  * Support an arbitrary number of inputs.
    //  * Allow different frame dimensions.
    InputFrameInfo inputFrame1 = framesToComposite.get(0);
    InputFrameInfo inputFrame2 = framesToComposite.get(1);
    checkState(inputFrame1.texture.width == inputFrame2.texture.width);
    checkState(inputFrame1.texture.height == inputFrame2.texture.height);
    outputTexturePool.ensureConfigured(
        glObjectsProvider, inputFrame1.texture.width, inputFrame1.texture.height);
    GlTextureInfo outputTexture = outputTexturePool.useTexture();
    long outputPresentationTimestampUs = framesToComposite.get(PRIMARY_INPUT_ID).presentationTimeUs;
    outputTextureTimestamps.add(outputPresentationTimestampUs);

    drawFrame(inputFrame1.texture, inputFrame2.texture, outputTexture);
    long syncObject = GlUtil.createGlSyncFence();
    syncObjects.add(syncObject);
    textureOutputListener.onTextureRendered(
        outputTexture,
        /* presentationTimeUs= */ outputPresentationTimestampUs,
        this::releaseOutputFrame,
        syncObject);

    InputSource primaryInputSource = inputSources.get(PRIMARY_INPUT_ID);
    releaseFrames(primaryInputSource, /* numberOfFramesToRelease= */ 1);
    releaseExcessFramesInAllSecondaryStreams();

    if (allInputsEnded && inputSources.get(PRIMARY_INPUT_ID).frameInfos.isEmpty()) {
      listener.onEnded();
    }
  }

  /**
   * Checks whether {@code inputSources} is able to composite, and if so, returns a list of {@link
   * InputFrameInfo}s that should be composited next.
   *
   * <p>The first input frame info in the list is from the the primary source. An empty list is
   * returned if {@code inputSources} cannot composite now.
   */
  private synchronized ImmutableList<InputFrameInfo> getFramesToComposite() {
    if (outputTexturePool.freeTextureCount() == 0) {
      return ImmutableList.of();
    }
    for (int inputId = 0; inputId < inputSources.size(); inputId++) {
      if (inputSources.get(inputId).frameInfos.isEmpty()) {
        return ImmutableList.of();
      }
    }
    ImmutableList.Builder<InputFrameInfo> framesToComposite = new ImmutableList.Builder<>();
    InputFrameInfo primaryFrameToComposite =
        inputSources.get(PRIMARY_INPUT_ID).frameInfos.element();
    framesToComposite.add(primaryFrameToComposite);

    for (int inputId = 0; inputId < inputSources.size(); inputId++) {
      if (inputId == PRIMARY_INPUT_ID) {
        continue;
      }
      // Select the secondary streams' frame that would be composited next. The frame selected is
      // the closest-timestamp frame from the primary stream's frame, if all secondary streams have:
      //   1. One or more frames, and the secondary stream has ended, or
      //   2. Two or more frames, and at least one frame has timestamp greater than the target
      //      timestamp.
      // The smaller timestamp is taken if two timestamps have the same distance from the primary.
      InputSource secondaryInputSource = inputSources.get(inputId);
      if (secondaryInputSource.frameInfos.size() == 1 && !secondaryInputSource.isInputEnded) {
        return ImmutableList.of();
      }

      long minTimeDiffFromPrimaryUs = Long.MAX_VALUE;
      @Nullable InputFrameInfo secondaryFrameToComposite = null;
      Iterator<InputFrameInfo> frameInfosIterator = secondaryInputSource.frameInfos.iterator();
      while (frameInfosIterator.hasNext()) {
        InputFrameInfo candidateFrame = frameInfosIterator.next();
        long candidateTimestampUs = candidateFrame.presentationTimeUs;
        long candidateAbsDistance =
            abs(candidateTimestampUs - primaryFrameToComposite.presentationTimeUs);

        if (candidateAbsDistance < minTimeDiffFromPrimaryUs) {
          minTimeDiffFromPrimaryUs = candidateAbsDistance;
          secondaryFrameToComposite = candidateFrame;
        }

        if (candidateTimestampUs > primaryFrameToComposite.presentationTimeUs
            || (!frameInfosIterator.hasNext() && secondaryInputSource.isInputEnded)) {
          framesToComposite.add(checkNotNull(secondaryFrameToComposite));
          break;
        }
      }
    }
    ImmutableList<InputFrameInfo> framesToCompositeList = framesToComposite.build();
    if (framesToCompositeList.size() != inputSources.size()) {
      return ImmutableList.of();
    }
    return framesToCompositeList;
  }

  private void releaseOutputFrame(long presentationTimeUs) {
    videoFrameProcessingTaskExecutor.submit(() -> releaseOutputFrameInternal(presentationTimeUs));
  }

  private synchronized void releaseOutputFrameInternal(long presentationTimeUs)
      throws VideoFrameProcessingException, GlUtil.GlException {
    while (outputTexturePool.freeTextureCount() < outputTexturePool.capacity()
        && checkNotNull(outputTextureTimestamps.peek()) <= presentationTimeUs) {
      outputTexturePool.freeTexture();
      outputTextureTimestamps.remove();
      GlUtil.deleteSyncObject(syncObjects.remove());
    }
    maybeComposite();
  }

  private void ensureGlProgramConfigured()
      throws VideoFrameProcessingException, GlUtil.GlException {
    if (glProgram != null) {
      return;
    }
    try {
      glProgram = new GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH);
      glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
      glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    } catch (IOException e) {
      throw new VideoFrameProcessingException(e);
    }
  }

  private void drawFrame(
      GlTextureInfo inputTexture1, GlTextureInfo inputTexture2, GlTextureInfo outputTexture)
      throws GlUtil.GlException {
    GlUtil.focusFramebufferUsingCurrentContext(
        outputTexture.fboId, outputTexture.width, outputTexture.height);
    GlUtil.clearFocusedBuffers();

    GlProgram glProgram = checkNotNull(this.glProgram);
    glProgram.use();

    // Setup for blending.
    GLES20.glEnable(GLES20.GL_BLEND);
    // Similar to:
    // dst.rgb = src.rgb * src.a + dst.rgb * (1 - src.a)
    // dst.a   = src.a           + dst.a   * (1 - src.a)
    GLES20.glBlendFuncSeparate(
        /* srcRGB= */ GLES20.GL_SRC_ALPHA,
        /* dstRGB= */ GLES20.GL_ONE_MINUS_SRC_ALPHA,
        /* srcAlpha= */ GLES20.GL_ONE,
        /* dstAlpha= */ GLES20.GL_ONE_MINUS_SRC_ALPHA);
    GlUtil.checkGlError();

    // Draw textures from back to front.
    blendOntoFocusedTexture(inputTexture2.texId);
    blendOntoFocusedTexture(inputTexture1.texId);

    GLES20.glDisable(GLES20.GL_BLEND);

    GlUtil.checkGlError();
  }

  private void blendOntoFocusedTexture(int texId) throws GlUtil.GlException {
    GlProgram glProgram = checkNotNull(this.glProgram);
    glProgram.setSamplerTexIdUniform("uTexSampler", texId, /* texUnitIndex= */ 0);
    glProgram.bindAttributesAndUniforms();

    // The four-vertex triangle strip forms a quad.
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
    GlUtil.checkGlError();
  }

  private synchronized void releaseGlObjects() {
    try {
      checkState(allInputsEnded);
      outputTexturePool.deleteAllTextures();
      GlUtil.destroyEglSurface(eglDisplay, placeholderEglSurface);
      if (glProgram != null) {
        glProgram.delete();
      }
    } catch (GlUtil.GlException e) {
      Log.e(TAG, "Error releasing GL resources", e);
    } finally {
      try {
        GlUtil.destroyEglContext(eglDisplay, eglContext);
      } catch (GlUtil.GlException e) {
        Log.e(TAG, "Error releasing GL context", e);
      }
    }
  }

  /** Holds information on an input source. */
  private static final class InputSource {
    // A queue of {link InputFrameInfo}s, inserted in order from lower to higher {@code
    // presentationTimeUs} values.
    public final Queue<InputFrameInfo> frameInfos;

    public boolean isInputEnded;

    public InputSource() {
      frameInfos = new ArrayDeque<>();
    }
  }

  /** Holds information on a frame and how to release it. */
  private static final class InputFrameInfo {
    public final GlTextureInfo texture;
    public final long presentationTimeUs;
    public final DefaultVideoFrameProcessor.ReleaseOutputTextureCallback releaseCallback;

    public InputFrameInfo(
        GlTextureInfo texture,
        long presentationTimeUs,
        DefaultVideoFrameProcessor.ReleaseOutputTextureCallback releaseCallback) {
      this.texture = texture;
      this.presentationTimeUs = presentationTimeUs;
      this.releaseCallback = releaseCallback;
    }
  }
}