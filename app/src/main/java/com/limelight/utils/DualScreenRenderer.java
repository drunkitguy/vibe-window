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
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.opengles.GL10;

/**
 * Dual screen: the decoder writes one SurfaceTexture; the GLSurfaceView's single context draws
 * one crop to the GLSurfaceView and the other crop to a second EGL window surface (the
 * Presentation's SurfaceView on the other display), both from the same texture update, 1:1.
 */
public class DualScreenRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static final float[] QUAD_VERTICES = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};

    private final GLSurfaceView glSurfaceView;
    private final Stereo3DRenderer.OnSurfaceReadyListener onSurfaceReadyListener;
    private final int[] mainCrop;
    private final FloatBuffer quadBuffer = floats(QUAD_VERTICES);
    private final FloatBuffer mainTexBuffer;
    private final FloatBuffer secondTexBuffer;
    private final AtomicBoolean frameAvailable = new AtomicBoolean(false);

    private int program;
    private int videoTextureId;
    private volatile SurfaceTexture videoSurfaceTexture;
    private Surface videoSurface;
    private int mainWidth, mainHeight;

    // The second display's Surface, set from the UI thread; the EGL side lives on the GL thread
    private volatile Surface secondSurface;
    private Surface secondEglFor;
    private EGLDisplay secondEglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLSurface secondEglSurface = EGL14.EGL_NO_SURFACE;

    public DualScreenRenderer(GLSurfaceView view, Stereo3DRenderer.OnSurfaceReadyListener listener,
                              int[] mainCrop, int[] secondCrop) {
        this.glSurfaceView = view;
        this.onSurfaceReadyListener = listener;
        this.mainCrop = mainCrop;
        this.mainTexBuffer = floats(DualScreenLayout.texCoords(mainCrop));
        this.secondTexBuffer = floats(DualScreenLayout.texCoords(secondCrop));
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    /** UI thread: the Presentation's surface changed (null when it is destroyed). */
    public void setSecondSurface(Surface surface) {
        secondSurface = surface;
        if (surface != null) {
            glSurfaceView.requestRender();
            return;
        }
        // Release the EGL surface before the window goes away
        CountDownLatch released = new CountDownLatch(1);
        glSurfaceView.queueEvent(() -> {
            releaseSecondEglSurface();
            released.countDown();
        });
        try {
            released.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void onSurfaceDestroyed() {
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (videoSurfaceTexture != null) {
            videoSurfaceTexture.release();
            videoSurfaceTexture = null;
        }
        glSurfaceView.queueEvent(() -> {
            releaseSecondEglSurface();
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteTextures(1, new int[] {videoTextureId}, 0);
        });
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        frameAvailable.set(true);
        glSurfaceView.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        // A new context may come after eglTerminate; drop the old second surface (best effort) and rebuild it
        releaseSecondEglSurface();

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

        program = createProgram(ShaderUtils.SIMPLE_VERTEX_SHADER, ShaderUtils.SIMPLE_FRAGMENT_SHADER);

        if (onSurfaceReadyListener != null) {
            onSurfaceReadyListener.onStereo3DSurfaceReady(videoSurface);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mainWidth = width;
        mainHeight = height;
        if (width != mainCrop[2] || height != mainCrop[3]) {
            LimeLog.warning("Dual screen: main surface " + width + "x" + height + " is not 1:1 with its crop; scaling");
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        SurfaceTexture texture = videoSurfaceTexture;
        if (texture == null) {
            return;
        }
        if (frameAvailable.getAndSet(false)) {
            try {
                texture.updateTexImage();
            } catch (Exception e) {
                LimeLog.warning("Dual screen: updateTexImage failed: " + e);
                return;
            }
        }

        EGLDisplay display = EGL14.eglGetCurrentDisplay();
        EGLContext context = EGL14.eglGetCurrentContext();
        updateSecondEglSurface(display, context);
        if (secondEglSurface != EGL14.EGL_NO_SURFACE) {
            EGLSurface draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
            EGLSurface read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ);
            if (EGL14.eglMakeCurrent(display, secondEglSurface, secondEglSurface, context)) {
                // Never block the main display's frame on the second display's vsync
                EGL14.eglSwapInterval(display, 0);
                int[] size = new int[2];
                EGL14.eglQuerySurface(display, secondEglSurface, EGL14.EGL_WIDTH, size, 0);
                EGL14.eglQuerySurface(display, secondEglSurface, EGL14.EGL_HEIGHT, size, 1);
                drawCrop(secondTexBuffer, size[0], size[1]);
                EGL14.eglSwapBuffers(display, secondEglSurface);
            }
            // GLSurfaceView swaps its own surface after this returns, so it must be current again
            EGL14.eglMakeCurrent(display, draw, read, context);
        }

        drawCrop(mainTexBuffer, mainWidth, mainHeight);
    }

    private void updateSecondEglSurface(EGLDisplay display, EGLContext context) {
        Surface wanted = secondSurface;
        if (wanted == secondEglFor) {
            return;
        }
        releaseSecondEglSurface();
        if (wanted == null || !wanted.isValid()) {
            return;
        }
        // Same config as the GLSurfaceView's context, so one context can draw both surfaces
        int[] configId = new int[1];
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, configId, 0);
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, new int[] {EGL14.EGL_CONFIG_ID, configId[0], EGL14.EGL_NONE}, 0,
                configs, 0, 1, count, 0) || count[0] < 1) {
            LimeLog.warning("Dual screen: no EGL config for the second display");
            return;
        }
        EGLSurface surface = EGL14.eglCreateWindowSurface(display, configs[0], wanted, new int[] {EGL14.EGL_NONE}, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            LimeLog.warning("Dual screen: eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
            return;
        }
        secondEglDisplay = display;
        secondEglSurface = surface;
        secondEglFor = wanted;
    }

    // GL thread only
    private void releaseSecondEglSurface() {
        if (secondEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(secondEglDisplay, secondEglSurface);
        }
        secondEglSurface = EGL14.EGL_NO_SURFACE;
        secondEglFor = null;
    }

    private void drawCrop(FloatBuffer texCoords, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);

        int posHandle = GLES20.glGetAttribLocation(program, "a_Position");
        int texHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, quadBuffer);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords);
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glEnableVertexAttribArray(texHandle);

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
