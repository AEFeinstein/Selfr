/**
 * Copyright 2015 Adam Feinstein
 * <p/>
 * This file is part of Selfr.
 * <p/>
 * Selfr is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p/>
 * Selfr is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with Selfr.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.gelakinetic.selfr;

import android.content.Context;
import android.hardware.Camera;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

@SuppressWarnings("deprecation")
public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
    private SurfaceHolder mHolder;
    private Camera mCamera;
    private boolean isPreviewRunning = false;

    /**
     * Constructor which fixes a lint warning
     *
     * @param context The context for this UI element
     */
    public CameraPreview(Context context) {
        super(context);
    }

    /**
     * Constructor that should be used. Uses the given Camera for the preview
     *
     * @param context The context for this UI element
     * @param camera  The Camera to use for this preview
     */
    public CameraPreview(Context context, @NotNull Camera camera) {
        super(context);

        /* Set the camera and get a list of supported preview sizes */
        mCamera = camera;

        /* Install a SurfaceHolder.Callback so we get notified when the underlying
         * surface is created and destroyed.
         */
        mHolder = getHolder();
        mHolder.addCallback(this);
    }

    /**
     * This is called immediately after the surface is first created. Implementations of this should
     * start up whatever rendering code they desire. Note that only one thread can ever draw into a
     * Surface, so you should not draw into the Surface here if your normal rendering will be in
     * another thread.
     *
     * @param holder The SurfaceHolder whose surface is being created.
     */
    public void surfaceCreated(SurfaceHolder holder) {
        /* The Surface has been created, now tell the camera where to draw the preview. */
        try {
            mCamera.setPreviewDisplay(holder);
            mCamera.startPreview();
        } catch (IOException e) {
            /* Eat it */
        }
    }

    /**
     * This is called immediately before a surface is being destroyed. After returning from this
     * call, you should no longer try to access this surface. If you have a rendering thread that
     * directly accesses the surface, you must ensure that thread is no longer touching the Surface
     * before returning from this function.
     *
     * @param holder The SurfaceHolder whose surface is being destroyed.
     */
    public void surfaceDestroyed(SurfaceHolder holder) {
        /* Empty. Resources are released in CameraActivity.onPause() or
         * CameraActivity.switchCamera()
         */
    }

    /**
     * This is called immediately after any structural changes (format or size) have been made to
     * the surface. You should at this point update the imagery in the surface. This method is
     * always called at least once, after surfaceCreated.
     *
     * @param holder The SurfaceHolder whose surface has changed.
     * @param format The new PixelFormat of the surface.
     * @param width  The width of the surface, in pixels
     * @param height The height of the surface, in pixels
     */
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        /* Stop the preview during modification */
        if (isPreviewRunning) {
            mCamera.stopPreview();
        }

        /* Rotate to device orientation (locked in portrait) */
        mCamera.setDisplayOrientation(90);

        /* Set the preview size to the surface size */
        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setPreviewSize(width, height);
        try {
            mCamera.setParameters(parameters);
        } catch (RuntimeException e) {
            /* eat it? */
        }

        /* Restart the preview */
        try {
            mCamera.setPreviewDisplay(mHolder);
            mCamera.startPreview();
            isPreviewRunning = true;
        } catch (Exception e) {
            /* Eat it */
        }
    }

    /**
     * Measure the view and its content to determine the measured width and the measured height.
     * This method is invoked by measure(int, int) and should be overridden by subclasses to provide
     * accurate and efficient measurement of their contents.
     *
     * @param widthMeasureSpec  horizontal space requirements as imposed by the parent. The
     *                          requirements are encoded with View.MeasureSpec.
     * @param heightMeasureSpec vertical space requirements as imposed by the parent. The
     *                          requirements are encoded with View.MeasureSpec
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        /* Resolve actual sizes */
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);

        /* Get supported preview sizes */
        List<Camera.Size> supportedPreviewSizes =
                mCamera.getParameters().getSupportedPreviewSizes();
        Camera.Size previewSize = null;
        if (supportedPreviewSizes != null) {
            /* Find the optimal preview size */
            previewSize = getOptimalPreviewSize(supportedPreviewSizes, width, height);

            /* Set the camera with the preview size */
            if (previewSize != null) {
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewSize(previewSize.width, previewSize.height);
                mCamera.setParameters(parameters);
            }
        }

        if (previewSize != null) {
            /* Set this view with the preview size */
            float ratio;
            if (previewSize.height >= previewSize.width) {
                ratio = (float) previewSize.height / (float) previewSize.width;
            } else {
                ratio = (float) previewSize.width / (float) previewSize.height;
            }
            setMeasuredDimension(width, (int) (width * ratio));
        } else {
            setMeasuredDimension(width, height);
        }
    }

    /**
     * Given a list of preview sizes, and the screen size, find the largest preview which most
     * closely matches the aspect ratio of the screen
     *
     * @param sizes        A list of supported preview sizes
     * @param screenWidth  The width of the screen
     * @param screenHeight The height of the screen
     * @return The preview size which is best for this screen
     */
    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int screenWidth,
                                              int screenHeight) {
        /* No preview sizes? Don't bother */
        if (sizes == null) {
            return null;
        }

        /* This is the aspect ratio of the screen */
        double targetRatio = Math.max(screenHeight, screenWidth) /
                Math.min(screenHeight, screenWidth);

        /* Set up search variables */
        Camera.Size optimalSize = null;
        double minRatioDiff = Double.MAX_VALUE;
        double minSizeDiff = Double.MAX_VALUE;
        /* Iterate through all sizes */
        for (Camera.Size size : sizes) {
            /* Find the closest aspect ratio match */
            double ratio = Math.max(size.height, size.width) / Math.min(size.height, size.width);
            if (Math.abs(ratio - targetRatio) <= minRatioDiff) {
                /* Find the largest of the aspect ratio matches */
                if (Math.abs(Math.max(size.height, size.width) - Math.max(screenHeight, screenWidth)) < minSizeDiff) {
                    optimalSize = size;
                    minRatioDiff = Math.abs(ratio - targetRatio);
                    minSizeDiff = Math.abs(Math.max(size.height, size.width) - Math.max(screenHeight, screenWidth));
                }
            }
        }

        return optimalSize;
    }
}