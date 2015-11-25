package com.gelakinetic.selfie;

import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.io.IOException;

/**
 * A basic Camera preview class
 */
public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
    private SurfaceHolder mHolder;
    private Camera mCamera;
    private boolean isPreviewRunning = false;
    private Context mContext;

    public CameraPreview(Context context, Camera camera) {
        super(context);
        mCamera = camera;
        mContext = context;

        // Install a SurfaceHolder.Callback so we get notified when the
        // underlying surface is created and destroyed.
        mHolder = getHolder();
        mHolder.addCallback(this);
        // deprecated setting, but required on Android versions prior to 3.0
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
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
        Display display = ((WindowManager) mContext.getSystemService(Activity.WINDOW_SERVICE)).getDefaultDisplay();

        if (display.getRotation() == Surface.ROTATION_0) {
            parameters.setPreviewSize(h, w);
            mCamera.setDisplayOrientation(90);
        }

        if (display.getRotation() == Surface.ROTATION_90) {
            parameters.setPreviewSize(w, h);
        }

        if (display.getRotation() == Surface.ROTATION_180) {
            parameters.setPreviewSize(h, w);
        }

        if (display.getRotation() == Surface.ROTATION_270) {
            parameters.setPreviewSize(w, h);
            mCamera.setDisplayOrientation(180);
        }

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
}