package com.gelakinetic.selfr;

import android.content.Context;
import android.hardware.Camera;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

/**
 * A basic Camera preview class
 */
@SuppressWarnings("deprecation")
public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
    private SurfaceHolder mHolder;
    private Camera mCamera;
    private boolean isPreviewRunning = false;

    private List<Camera.Size> mSupportedPreviewSizes;
    private Camera.Size mPreviewSize;

    public CameraPreview(Context context) {
        super(context);
    }

    public CameraPreview(Context context, @NotNull Camera camera) {
        super(context);
        mCamera = camera;

        mSupportedPreviewSizes = mCamera.getParameters().getSupportedPreviewSizes();

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = getHolder();
        mHolder.addCallback(this);
    }

    public void surfaceCreated(SurfaceHolder holder) {
        // The Surface has been created, now tell the camera where to draw the preview.
        try {
            mCamera.setPreviewDisplay(holder);
            mCamera.startPreview();
        } catch (IOException e) {
            /* Eat it */
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        // empty. Take care of releasing the Camera preview in your activity.
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (isPreviewRunning) {
            mCamera.stopPreview();
        }

        Camera.Parameters parameters = mCamera.getParameters();
        parameters.setPreviewSize(w, h);
        mCamera.setDisplayOrientation(90);

        try {
            mCamera.setParameters(parameters);
        } catch (RuntimeException e) {
            /* eat it? */
        }

        try {
            mCamera.setPreviewDisplay(mHolder);
            mCamera.startPreview();
            isPreviewRunning = true;
        } catch (Exception e) {
            /* Eat it */
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        final int height = resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec);

        if (mSupportedPreviewSizes != null) {
            mPreviewSize = getOptimalPreviewSize(mSupportedPreviewSizes, width, height);

            if (mPreviewSize != null) {
                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
                mCamera.setParameters(parameters);
            }
        }

        if (mPreviewSize != null) {
            float ratio;
            if (mPreviewSize.height >= mPreviewSize.width) {
                ratio = (float) mPreviewSize.height / (float) mPreviewSize.width;
            } else {
                ratio = (float) mPreviewSize.width / (float) mPreviewSize.height;
            }

            setMeasuredDimension(width, (int) (width * ratio));
        }
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {

        double targetRatio = Math.max(h, w) / Math.min(h, w);

        if (sizes == null)
            return null;

        Camera.Size optimalSize = null;
        double minRatioDiff = Double.MAX_VALUE;
        double minSizeDiff = Double.MAX_VALUE;

        for (Camera.Size size : sizes) {
            double ratio = Math.max(size.height, size.width) / Math.min(size.height, size.width);
            if (Math.abs(ratio - targetRatio) <= minRatioDiff) {
                if (Math.abs(Math.max(size.height, size.width) - Math.max(h, w)) < minSizeDiff) {
                    optimalSize = size;
                    minRatioDiff = Math.abs(ratio - targetRatio);
                    minSizeDiff = Math.abs(Math.max(size.height, size.width) - Math.max(h, w));
                }
            }
        }

        return optimalSize;
    }
}