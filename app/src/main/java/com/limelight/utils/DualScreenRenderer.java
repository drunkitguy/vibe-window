package com.limelight.utils;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.opengles.GL10;

/**
 * Dual screen: the decoder writes one SurfaceTexture; the GLSurfaceView's single context draws
 * one crop to the GLSurfaceView and the other crop to a second EGL window surface (the
 * Presentation's SurfaceView on the other display), both from the same latched frame, 1:1.
 * Until DualScreenDetector has seen the two-window layout, the content box (at first the whole
 * video) goes to the GLSurfaceView alone, scaled to fit, and the second surface is black.
 */
public class DualScreenRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};

    // The SurfaceTexture matrix handles decoder padding (e.g. 2160 rows padded to 2176) and the flip
    private static final String VERTEX_SHADER =
            "attribute vec4 a_Position;\n" +
                    "attribute vec2 a_TexCoord;\n" +
                    "uniform mat4 u_TexMatrix;\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = a_Position;\n" +
                    "    v_TexCoord = (u_TexMatrix * vec4(a_TexCoord, 0.0, 1.0)).xy;\n" +
                    "}\n";

    // Layout detection: the video reduced to DualScreenDetector's grid, 4 cells per RGBA pixel, so
    // the readback is GRID_W * GRID_H bytes that are 0 or 255. Each cell takes one bilinear tap at
    // its centre, the mean of its inner 2x2 pixels.
    // shortcut: a cell's outer pixel ring is not sampled, so chroma bleed across a window edge
    // and the SurfaceTexture matrix's one-texel crop shrink do not light the next cell, but
    // content only 1 px wide on that ring is not seen. Sample all 16 pixels if that ever matters.
    private static final int ANALYSIS_W = DualScreenDetector.GRID_W / 4;
    private static final int ANALYSIS_H = DualScreenDetector.GRID_H;
    private static final String ANALYSIS_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                    "precision highp float;\n" +
                    "#else\n" +
                    "precision mediump float;\n" +
                    "#endif\n" +
                    "varying vec2 v_TexCoord;\n" +
                    "uniform samplerExternalOES u_Texture;\n" +
                    "uniform vec2 u_PixelX;\n" +
                    "float cell(float n) {\n" +
                    // The fragment's centre is 2 cells from its left edge, a cell's centre half a cell from its own
                    "    vec3 c = texture2D(u_Texture, v_TexCoord + (n - 1.5) * " + DualScreenDetector.CELL + ".0 * u_PixelX).rgb;\n" +
                    "    return step(" + DualScreenDetector.BLACK_THRESHOLD + ", max(c.r, max(c.g, c.b)));\n" +
                    "}\n" +
                    "void main() {\n" +
                    "    gl_FragColor = vec4(cell(0.0), cell(1.0), cell(2.0), cell(3.0));\n" +
                    "}\n";
    // The whole video upside down, so the first row read back is the top of the video
    private static final float[] ANALYSIS_TEX_COORDS = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};

    private final GLSurfaceView glSurfaceView;
    private final Stereo3DRenderer.OnSurfaceReadyListener onSurfaceReadyListener;
    private final Runnable onVideoSurfaceLost;
    private final int[] mainCrop;
    private final int[] secondCrop;
    private final DualScreenDetector detector;
    private final Runnable postCheck;
    private final FloatBuffer quadBuffer = floats(QUAD_VERTICES);
    private final FloatBuffer mainTexBuffer;
    private final FloatBuffer secondTexBuffer;
    private final FloatBuffer analysisTexBuffer = floats(ANALYSIS_TEX_COORDS);
    private final FloatBuffer fitTexBuffer = floats(new float[8]);
    private final ByteBuffer analysisCells = ByteBuffer.allocateDirect(DualScreenDetector.GRID_W * DualScreenDetector.GRID_H);
    private final AtomicInteger pendingFrames = new AtomicInteger();

    // GL thread only
    private final float[] texMatrix = new float[16];
    private int program, positionHandle, texCoordHandle, texMatrixHandle;
    private int videoTextureId;
    private SurfaceTexture videoSurfaceTexture;
    private Surface videoSurface;
    private boolean frameLatched;
    private boolean videoSurfaceHandedOut;
    private int mainWidth, mainHeight;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mainDrawSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface mainReadSurface = EGL14.EGL_NO_SURFACE;
    private Surface secondEglFor;
    // A Surface the EGL surface could not be made for; not retried every frame
    private Surface secondEglFailedFor;
    private EGLDisplay secondEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLSurface secondEglSurface = EGL14.EGL_NO_SURFACE;
    // The second surface already shows black, so it is not drawn again until the split
    private boolean secondBlack;
    // Layout detection; analysisFbo is 0 when it is not set up or has failed
    private int analysisProgram, analysisPositionHandle, analysisTexCoordHandle, analysisTexMatrixHandle, analysisPixelXHandle;
    private int analysisFbo, analysisTextureId;
    // A check is on its way
    private boolean checkPosted;
    // The view fitTexBuffer and fitRect were made for
    private DualScreenDetector.View fitFor;
    private int[] fitRect;

    // The second display's Surface and size, set from the UI thread
    private volatile Surface secondSurface;
    private volatile int secondWidth, secondHeight;

    public DualScreenRenderer(GLSurfaceView view, Stereo3DRenderer.OnSurfaceReadyListener listener,
                              Runnable onVideoSurfaceLost, int[] mainCrop, int[] secondCrop,
                              DualScreenDetector detector) {
        this.glSurfaceView = view;
        // UI thread, CHECK_INTERVAL_MS after scheduleCheck: hand the check to the GL thread
        Runnable check = this::checkLayout;
        this.postCheck = () -> view.queueEvent(check);
        this.detector = detector;
        this.onSurfaceReadyListener = listener;
        this.onVideoSurfaceLost = onVideoSurfaceLost;
        this.mainCrop = mainCrop;
        this.secondCrop = secondCrop;
        this.mainTexBuffer = floats(DualScreenLayout.texCoords(mainCrop));
        this.secondTexBuffer = floats(DualScreenLayout.texCoords(secondCrop));
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    /** UI thread: the Presentation's surface changed (null when it is destroyed). */
    public void setSecondSurface(Surface surface, int width, int height) {
        secondWidth = width;
        secondHeight = height;
        secondSurface = surface;
        if (surface != null) {
            glSurfaceView.requestRender();
            return;
        }
        // Release the EGL surface before the window goes away
        CountDownLatch released = new CountDownLatch(1);
        glSurfaceView.queueEvent(() -> {
            releaseSecondEglSurface();
            secondEglFailedFor = null;
            released.countDown();
        });
        try {
            released.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Any thread: the decoder (re)started, so layout detection starts over on the main display. */
    public void resetDetection() {
        glSurfaceView.queueEvent(() -> {
            // The end of a split is a state change like the others, so it is logged
            if (detector.reset()) {
                LimeLog.info("Dual screen: decoder restarted, main display only until the two-window layout is seen again");
            }
        });
    }

    /** Any thread; safe to call more than once. Everything is released on the GL thread. */
    public void onSurfaceDestroyed() {
        glSurfaceView.queueEvent(this::releaseGl);
    }

    private void releaseGl() {
        releaseSecondEglSurface();
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (videoSurfaceTexture != null) {
            videoSurfaceTexture.release();
            videoSurfaceTexture = null;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (videoTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] {videoTextureId}, 0);
            videoTextureId = 0;
        }
        releaseAnalysis();
        frameLatched = false;
    }

    // GL thread only; GL ignores the name 0, so this is safe at any point of the set-up
    private void releaseAnalysis() {
        GLES20.glDeleteFramebuffers(1, new int[] {analysisFbo}, 0);
        GLES20.glDeleteTextures(1, new int[] {analysisTextureId}, 0);
        GLES20.glDeleteProgram(analysisProgram);
        analysisFbo = analysisTextureId = analysisProgram = 0;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        pendingFrames.incrementAndGet();
        glSurfaceView.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        // A new context may come after eglTerminate; drop the old second surface (best effort) and rebuild it
        releaseSecondEglSurface();
        secondEglFailedFor = null;
        frameLatched = false;
        pendingFrames.set(0);

        if (videoSurfaceHandedOut) {
            // Context loss: the decoder still writes to the old Surface and cannot be moved to a new one,
            // so release the old objects and end the stream instead of staying black
            releaseGl();
            LimeLog.warning("Dual screen: GL context recreated; ending the stream");
            if (onVideoSurfaceLost != null) {
                onVideoSurfaceLost.run();
            }
            return;
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        videoTextureId = textures[0];
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        videoSurfaceTexture = new SurfaceTexture(videoTextureId);
        videoSurfaceTexture.setOnFrameAvailableListener(this);
        videoSurface = new Surface(videoSurfaceTexture);

        program = createProgram(VERTEX_SHADER, ShaderUtils.SIMPLE_FRAGMENT_SHADER);
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position");
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "u_TexMatrix");
        // Never ends a split: a context made after the first one ends the stream above
        detector.reset();
        setUpAnalysis();

        videoSurfaceHandedOut = true;
        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onStereo3DSurfaceReady(videoSurface);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mainWidth = width;
        mainHeight = height;
        // GLSurfaceView calls this after every new EGL surface, so the cached handles stay current
        eglDisplay = EGL14.eglGetCurrentDisplay();
        eglContext = EGL14.eglGetCurrentContext();
        mainDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        mainReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
        fitFor = null;
        if (width != mainCrop[2] || height != mainCrop[3]) {
            LimeLog.warning("Dual screen: main surface " + width + "x" + height + " is not 1:1 with its crop; scaling");
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        SurfaceTexture texture = videoSurfaceTexture;
        int pending = pendingFrames.getAndSet(0);
        if (texture != null && pending > 0) {
            try {
                // Drain the queue so the newest frame is latched, not the oldest of a burst
                for (int i = 0; i < pending; i++) {
                    texture.updateTexImage();
                }
                texture.getTransformMatrix(texMatrix);
                frameLatched = true;
                scheduleCheck();
            } catch (RuntimeException e) {
                // Keep drawing the last latched frame
                LimeLog.warning("Dual screen: updateTexImage failed: " + e);
            }
        }

        // One snapshot for both displays
        DualScreenDetector.View view = detector.view();

        updateSecondEglSurface();
        if (secondEglSurface != EGL14.EGL_NO_SURFACE && (view.dual || !secondBlack)) {
            if (EGL14.eglMakeCurrent(eglDisplay, secondEglSurface, secondEglSurface, eglContext)) {
                drawCrop(view.dual ? secondTexBuffer : null, 0, 0, secondWidth, secondHeight);
                EGL14.eglSwapBuffers(eglDisplay, secondEglSurface);
                secondBlack = !view.dual;
            }
            // GLSurfaceView swaps its own surface after this returns, so it must be current again
            EGL14.eglMakeCurrent(eglDisplay, mainDrawSurface, mainReadSurface, eglContext);
        }

        if (view.dual) {
            drawCrop(mainTexBuffer, 0, 0, mainWidth, mainHeight);
            return;
        }
        // The content box scaled to fit; the clear paints the bars
        clearBlack();
        if (view != fitFor) {
            fitFor = view;
            fitTexBuffer.put(DualScreenLayout.texCoords(view.main)).position(0);
            fitRect = view.rect(mainWidth, mainHeight);
        }
        // GL's y runs up
        drawCrop(fitTexBuffer, fitRect[0], mainHeight - fitRect[1] - fitRect[3], fitRect[2], fitRect[3]);
    }

    private void setUpAnalysis() {
        analysisProgram = createProgram(VERTEX_SHADER, ANALYSIS_FRAGMENT_SHADER);
        if (analysisProgram == 0) {
            analysisFailed("shader");
            return;
        }
        analysisPositionHandle = GLES20.glGetAttribLocation(analysisProgram, "a_Position");
        analysisTexCoordHandle = GLES20.glGetAttribLocation(analysisProgram, "a_TexCoord");
        analysisTexMatrixHandle = GLES20.glGetUniformLocation(analysisProgram, "u_TexMatrix");
        analysisPixelXHandle = GLES20.glGetUniformLocation(analysisProgram, "u_PixelX");

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        analysisTextureId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, analysisTextureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ANALYSIS_W, ANALYSIS_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenFramebuffers(1, ids, 0);
        analysisFbo = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, analysisTextureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            analysisFailed("framebuffer status 0x" + Integer.toHexString(status));
        }
    }

    // Without detection the stream is split always, as before; never a black screen
    private void analysisFailed(String why) {
        LimeLog.warning("Dual screen: layout detection is off (" + why + "); splitting over both displays");
        releaseAnalysis();
        detector.giveUp();
    }

    // One check at most per CHECK_INTERVAL_MS, and none for a picture that was checked and settled
    private void scheduleCheck() {
        if (analysisFbo != 0 && !checkPosted) {
            checkPosted = true;
            glSurfaceView.postDelayed(postCheck, DualScreenDetector.CHECK_INTERVAL_MS);
        }
    }

    // A queued event, not a frame: nothing is drawn or swapped unless the answer changed
    private void checkLayout() {
        checkPosted = false;
        if (analysisFbo == 0 || !frameLatched || !EGL14.eglGetCurrentContext().equals(eglContext)) {
            return;
        }
        DualScreenDetector.View before = detector.view();
        analyse();
        if (detector.pending()) {
            // A still picture brings no new frames, and a running debounce must end
            scheduleCheck();
        }
        if (detector.view() != before) {
            glSurfaceView.requestRender();
        }
    }

    // A few times per second, never per frame: one small draw and one GRID_W * GRID_H byte readback
    private void analyse() {
        // Drop an older error, so only this pass is judged
        GLES20.glGetError();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysisFbo);
        GLES20.glViewport(0, 0, ANALYSIS_W, ANALYSIS_H);
        GLES20.glUseProgram(analysisProgram);
        GLES20.glVertexAttribPointer(analysisPositionHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glVertexAttribPointer(analysisTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, analysisTexBuffer);
        GLES20.glEnableVertexAttribArray(analysisPositionHandle);
        GLES20.glEnableVertexAttribArray(analysisTexCoordHandle);
        GLES20.glUniformMatrix4fv(analysisTexMatrixHandle, 1, false, texMatrix, 0);
        // One video pixel to the right, after the texture matrix
        GLES20.glUniform2f(analysisPixelXHandle, texMatrix[0] / DualScreenLayout.VIDEO_W, texMatrix[1] / DualScreenLayout.VIDEO_W);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glReadPixels(0, 0, ANALYSIS_W, ANALYSIS_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, analysisCells);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            analysisFailed("GL error 0x" + Integer.toHexString(error));
            return;
        }
        String change = detector.check(analysisCells);
        if (change != null) {
            LimeLog.info("Dual screen: " + change);
        }
    }

    private void updateSecondEglSurface() {
        Surface wanted = secondSurface;
        if (wanted == secondEglFor || (wanted != null && wanted == secondEglFailedFor)) {
            return;
        }
        releaseSecondEglSurface();
        if (wanted == null || !wanted.isValid()) {
            return;
        }
        // Same config as the GLSurfaceView's context, so one context can draw both surfaces
        int[] configId = new int[1];
        EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONFIG_ID, configId, 0);
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, new int[] {EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE}, 0,
                configs, 0, 1, count, 0) || count[0] < 1) {
            LimeLog.warning("Dual screen: no EGL config for the second display");
            secondEglFailedFor = wanted;
            return;
        }
        EGLSurface surface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], wanted, new int[] {EGL14.EGL_NONE}, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            LimeLog.warning("Dual screen: eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            secondEglFailedFor = wanted;
            return;
        }
        secondEglDisplay = eglDisplay;
        secondEglSurface = surface;
        secondEglFor = wanted;

        // Never block the main display's frame on the second display's vsync (set once, per surface)
        if (EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            EGL14.eglSwapInterval(eglDisplay, 0);
        }
        EGL14.eglMakeCurrent(eglDisplay, mainDrawSurface, mainReadSurface, eglContext);

        if (secondWidth != secondCrop[2] || secondHeight != secondCrop[3]) {
            LimeLog.warning("Dual screen: second surface " + secondWidth + "x" + secondHeight + " is not 1:1 with its crop; scaling");
        }
    }

    // GL thread only
    private void releaseSecondEglSurface() {
        if (secondEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(secondEglDisplay, secondEglSurface);
        }
        secondEglSurface = EGL14.EGL_NO_SURFACE;
        secondEglFor = null;
        secondBlack = false;
    }

    // The whole surface, whatever the viewport
    private void clearBlack() {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    // Draws the crop into the viewport x, y, width, height; null texCoords draw black
    private void drawCrop(FloatBuffer texCoords, int x, int y, int width, int height) {
        if (texCoords == null || !frameLatched) {
            // Nothing to show or nothing decoded yet: black, not whatever the buffer held
            clearBlack();
            return;
        }
        GLES20.glViewport(x, y, width, height);
        GLES20.glUseProgram(program);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTextureId);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            LimeLog.severe("Dual screen: could not compile shader: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static int createProgram(String vertex, String fragment) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] != GLES20.GL_TRUE) {
            LimeLog.severe("Dual screen: could not link program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }
}
