package com.gelakinetic.selfie;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("deprecation")
public class CameraActivity extends AppCompatActivity {

    /* Constants */
    private static final int UI_ANIMATION_DELAY = 300;
    private static final int PERMISSION_REQUEST_CODE = 162;

    /* UI Objects */
    private FrameLayout mContentView;
    private FrameLayout mFlashView;
    private TextView mNoStickWarningView;
    private View mControlsView;

    /* State objects */
    private boolean mControlsVisible;
    private int mCameraType = Camera.CameraInfo.CAMERA_FACING_FRONT;
    private String mFlashMode = Camera.Parameters.FLASH_MODE_OFF;
    private boolean mHardwareFlashSupported = false;
    private boolean mDebounce = false;
    private int mDeviceRotation = 0;
    private float mOldBrightness;

    /* Hardware interface objects */
    private HeadsetStateReceiver mHeadsetStateReceiver;
    private AudioCapturer mAudioCapturer;
    private Camera mCamera;
    private CameraPreview mCameraPreview;
    private OrientationEventListener mOrientationEventListener;

    /* Handler and Runnables */
    private Handler mHandler;
    private final Runnable mHideRunnable = new Runnable() {
        /**
         * TODO
         */
        @Override
        public void run() {
            hide();
        }
    };

    private final Runnable mHidePart2Runnable = new Runnable() {
        /**
         * TODO
         */
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };

    private final Runnable mShowRunnable = new Runnable() {
        /**
         * TODO
         */
        @Override
        public void run() {
            // Delayed display of UI elements
            mControlsView.setVisibility(View.VISIBLE);
        }
    };

    private Camera.PictureCallback mPicture = new Camera.PictureCallback() {

        /**
         * TODO
         * @param data
         * @param camera
         */
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {

            /* Freeze the picture on the screen */
            mCamera.stopPreview();

            /* Clear the flash screen, if there is no hardware flash
             * and the front facing camera was used
             */
            if (!mHardwareFlashSupported &&
                    mCameraType == Camera.CameraInfo.CAMERA_FACING_FRONT &&
                    mFlashMode.equals(Camera.Parameters.FLASH_MODE_ON)) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        /* Restore the brightness */
                        WindowManager.LayoutParams layout = getWindow().getAttributes();
                        layout.screenBrightness = mOldBrightness;
                        getWindow().setAttributes(layout);

                        /* Hide the "flash" view */
                        mFlashView.setVisibility(View.GONE);
                    }
                });
            }

            /* Get a file to write the picture to */
            File pictureFile = getOutputImageFile();
            if (pictureFile == null) {
                return;
            }

            try {
                /* Save the image */
                FileOutputStream fos = new FileOutputStream(pictureFile);
                fos.write(data);
                fos.close();

                /* Notify the media scanner so it displays in teh gallery */
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(pictureFile)));
            } catch (IOException e) {
                /* Eat it */
            }

            /* Restart the preview later */
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mCamera.startPreview();
                }
            }, 1000);
        }
    };

    private Runnable takePictureRunnable = new Runnable() {
        /**
         * TODO
         */
        @Override
        public void run() {
            try {
                mCamera.takePicture(null, null, mPicture);
                mDebounce = true;
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mDebounce = false;
                    }
                }, 3000);
            } catch (RuntimeException e) {
                /* That didn't work... */
            }
        }
    };

    /**
     * A safe way to get an instance of the Camera object. Also sets up picture size & focus type
     *
     * @param cameraType Camera.CameraInfo.CAMERA_FACING_FRONT or Camera.CameraInfo.CAMERA_FACING_BACK
     * @return A Camera object if it was created, or null
     */
    @Nullable
    public static Camera getCameraInstance(int cameraType) {
        Camera camera = null;
        try {
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            for (int camIdx = 0; camIdx < Camera.getNumberOfCameras(); camIdx++) {
                Camera.getCameraInfo(camIdx, cameraInfo);
                if (cameraInfo.facing == cameraType) {
                    try {
                        /* Open the camera, get default parameters */
                        camera = Camera.open(camIdx);
                        Camera.Parameters parameters = camera.getParameters();

                        /* Set the image to native resolution */
                        List<Camera.Size> sizes = parameters.getSupportedPictureSizes();
                        Camera.Size nativeSize = null;
                        int maxHeight = Integer.MIN_VALUE;
                        for (Camera.Size size : sizes) {
                            if (size.height > maxHeight) {
                                maxHeight = size.height;
                                nativeSize = size;
                            }
                        }
                        if (nativeSize != null) {
                            parameters.setPictureSize(nativeSize.width, nativeSize.height);
                        }

                        /* Set autofocus, if we can */
                        List<String> focusModes = parameters.getSupportedFocusModes();
                        if (focusModes != null &&
                                focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                        }

                        /* Set the parameters */
                        camera.setParameters(parameters);

                    } catch (RuntimeException e) {
                        /* Eat it */
                    }
                }
            }
        } catch (Exception e) {
            // Camera is not available (in use or does not exist)
        }
        return camera; // returns null if camera is unavailable
    }

    /**
     * TODO
     *
     * @param savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);

        mControlsVisible = true;
        mControlsView = findViewById(R.id.fullscreen_content_controls);
        mContentView = (FrameLayout) findViewById(R.id.fullscreen_content);
        mFlashView = (FrameLayout) findViewById(R.id.flash_view);
        mNoStickWarningView = (TextView) findViewById(R.id.no_stick_text);

        mHandler = new Handler();

        // Set up the user interaction to manually show or hide the system UI.
        mContentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });

        Toolbar mToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
    }

    /**
     * TODO
     */
    @Override
    protected void onResume() {
        super.onResume();

        /* Check to see what permissions we're granted */
        ArrayList<String> requestedPermissions = new ArrayList<>(3);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestedPermissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestedPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestedPermissions.add(Manifest.permission.RECORD_AUDIO);
        }

        /* If we don't have all of the permissions */
        if (requestedPermissions.size() > 0) {
            String permissionStrings[] = new String[requestedPermissions.size()];
            requestedPermissions.toArray(permissionStrings);
            /* Request the permissions */
            ActivityCompat.requestPermissions(this,
                    permissionStrings,
                    PERMISSION_REQUEST_CODE);
        } else {
            /* Otherwise, fire everything up */
            /* Set up the audio capture */
            mAudioCapturer = AudioCapturer.getInstance(new IAudioReceiver() {
                /**
                 * TODO
                 * @param tempBuf
                 */
                @Override
                public void capturedAudioReceived(short[] tempBuf) {

                    if (mDebounce) {
                        return;
                    }
                    if (findMax(tempBuf) > 32000) {
                        if (mCamera != null) {
                            /* Set rotation */
                            Camera.Parameters parameters = mCamera.getParameters();
                            parameters.setRotation(mDeviceRotation);
                            mCamera.setParameters(parameters);

                            if (!mHardwareFlashSupported &&
                                    mCameraType == Camera.CameraInfo.CAMERA_FACING_FRONT &&
                                    mFlashMode.equals(Camera.Parameters.FLASH_MODE_ON)) {
                                /* No hardware flash & front camera, draw the screen bright white */
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        /* Save the old brightness, set current to max */
                                        WindowManager.LayoutParams layout = getWindow().getAttributes();
                                        mOldBrightness = layout.screenBrightness;
                                        layout.screenBrightness = 1F;
                                        getWindow().setAttributes(layout);
                                        /* Show the "flash" screen */
                                        mFlashView.setVisibility(View.VISIBLE);
                                        /* Take a picture, after letting the "flash" settle */
                                        mHandler.postDelayed(takePictureRunnable, 2000);
                                    }
                                });
                            } else {
                                /* Take a picture immediately */
                                takePictureRunnable.run();
                            }
                        }
                    }
                }

                /**
                 * TODO
                 * @param array
                 * @return
                 */
                short findMax(short[] array) {
                    short max = Short.MIN_VALUE;
                    for (short element : array) {
                        if (element > max) {
                            max = element;
                        }
                    }
                    return max;
                }
            });

            /* Set up the camera */
            mCamera = getCameraInstance(mCameraType);
            mCameraPreview = new CameraPreview(this, mCamera);
            mContentView.addView(mCameraPreview);

            /* Register the headset state receiver */
            mHeadsetStateReceiver = new HeadsetStateReceiver();
            registerReceiver(mHeadsetStateReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));

            /* Set up the accelerometer */
            mOrientationEventListener = new OrientationEventListener(CameraActivity.this, SensorManager.SENSOR_DELAY_NORMAL) {
                /**
                 * TODO
                 * @param orientation
                 */
                @Override
                public void onOrientationChanged(int orientation) {
                    /* If the orientation is unknown, don't bother */
                    if (orientation == ORIENTATION_UNKNOWN) {
                        return;
                    }

                    /* Clamp rotation to nearest 90 degree wedge */
                    int rotation = 0;
                    Camera.CameraInfo info = new Camera.CameraInfo();
                    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        if (315 <= orientation || orientation < 45) {
                            rotation = 270;
                        } else if (45 <= orientation && orientation < 135) {
                            rotation = 180;
                        } else if (135 <= orientation && orientation < 225) {
                            rotation = 90;
                        } else if (225 <= orientation && orientation < 315) {
                            rotation = 0;
                        }
                    } else {
                        if (315 <= orientation || orientation < 45) {
                            rotation = 90;
                        } else if (45 <= orientation && orientation < 135) {
                            rotation = 180;
                        } else if (135 <= orientation && orientation < 225) {
                            rotation = 270;
                        } else if (225 <= orientation && orientation < 315) {
                            rotation = 0;
                        }
                    }

                    mDeviceRotation = rotation;
                }
            };
            if (mOrientationEventListener.canDetectOrientation()) {
                mOrientationEventListener.enable();
            }

            /* Trigger the initial hide() shortly after the activity has been
             * created, to briefly hint to the user that UI controls
             * are available.
             */
            delayedHide(100);
        }
    }

    /**
     * TODO
     */
    @Override
    protected void onPause() {
        super.onPause();

        /* Clean up the camera & preview */
        if (mCameraPreview != null) {
            mContentView.removeView(mCameraPreview);
            mCameraPreview = null;
        }
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

        /* Clean up the audio */
        if (mAudioCapturer != null) {
            mAudioCapturer.stop();
            mAudioCapturer = null;
        }

        /* Clean up the receiver */
        if (mHeadsetStateReceiver != null) {
            unregisterReceiver(mHeadsetStateReceiver);
            mHeadsetStateReceiver = null;
        }

        /* Clean up the accelerometer */
        if (mOrientationEventListener != null) {
            mOrientationEventListener.disable();
            mOrientationEventListener = null;
        }
    }

    /**
     * TODO
     *
     * @param requestCode
     * @param permissions
     * @param grantResults
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        boolean allPermissionsGranted = true;
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int i = 0; i < grantResults.length; i++) {
                switch (permissions[i]) {
                    case Manifest.permission.CAMERA:
                    case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                    case Manifest.permission.RECORD_AUDIO: {
                        if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                            allPermissionsGranted = false;
                        }
                        break;
                    }
                    default: {
                        /* Some unknown permission */
                    }
                }
            }
        }
        if (!allPermissionsGranted) {
            Toast.makeText(this, getString(R.string.permission_failure), Toast.LENGTH_LONG).show();
            this.finish();
        }
    }

    /**
     * TODO
     */
    private void toggle() {
        if (mControlsVisible) {
            hide();
        } else {
            show();
        }
    }

    /**
     * TODO
     */
    private void hide() {
        // Hide UI first
        mControlsView.setVisibility(View.GONE);
        mControlsVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHandler.removeCallbacks(mShowRunnable);
        mHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in [delay] milliseconds, canceling any
     * previously scheduled calls.
     *
     * @param delayMillis How long to delay before hiding the UI
     */
    private void delayedHide(int delayMillis) {
        mHandler.removeCallbacks(mHideRunnable);
        mHandler.postDelayed(mHideRunnable, delayMillis);
    }

    /**
     * TODO
     */
    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mControlsVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHandler.removeCallbacks(mHidePart2Runnable);
        mHandler.postDelayed(mShowRunnable, UI_ANIMATION_DELAY);

        /* Hide the UI in 5 seconds, should be enough for a button press */
        delayedHide(5000);
    }

    /**
     * TODO
     *
     * @param menu
     * @return
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.camera_menu, menu);
        return true;
    }

    /**
     * TODO
     *
     * @param item
     * @return
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.photo_library:
                hide();
                Intent i = new Intent(Intent.ACTION_VIEW,
                        android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI);
                startActivity(i);
                return true;
            case R.id.camera_switch: {
                /* Switch from one camera type to the other, adjust the icon as necessary */
                switch (mCameraType) {
                    case Camera.CameraInfo.CAMERA_FACING_FRONT: {
                        mCameraType = Camera.CameraInfo.CAMERA_FACING_BACK;
                        item.setIcon(R.drawable.ic_camera_rear_white_24dp);
                        break;
                    }
                    case Camera.CameraInfo.CAMERA_FACING_BACK: {
                        mCameraType = Camera.CameraInfo.CAMERA_FACING_FRONT;
                        item.setIcon(R.drawable.ic_camera_front_white_24dp);
                        break;
                    }
                }

                /* Remove old camera & preview */
                mContentView.removeView(mCameraPreview);
                mCamera.stopPreview();
                mCamera.release();

                /* Make a new camera & preview */
                mCamera = getCameraInstance(mCameraType);
                mCameraPreview = new CameraPreview(this, mCamera);
                mContentView.addView(mCameraPreview);

                /* Make sure the flash parameter is correct */
                setFlashParameter();

                /* Hide the UI */
                delayedHide(2500);

                return true;
            }
            case R.id.flash_setting: {

                /* Change the flash mode */
                switch (mFlashMode) {
                    case Camera.Parameters.FLASH_MODE_OFF: {
                        mFlashMode = Camera.Parameters.FLASH_MODE_ON;
                        item.setIcon(R.drawable.ic_flash_on_white_24dp);
                        break;
                    }
                    case Camera.Parameters.FLASH_MODE_ON: {
                        mFlashMode = Camera.Parameters.FLASH_MODE_OFF;
                        item.setIcon(R.drawable.ic_flash_off_white_24dp);
                        break;
                    }
                }
                /* Then set the parameter */
                setFlashParameter();

                /* Hide the UI */
                delayedHide(2500);

                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Create a File for saving an image or video
     */
    @Nullable
    private File getOutputImageFile() {
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), getString(R.string.app_name));
        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                return null;
            }
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return new File(mediaStorageDir.getPath() + File.separator +
                "IMG_" + timeStamp + ".jpg");
    }

    /**
     * TODO
     */
    private void setFlashParameter() {
        /* If the camera supports flash, set the parameter */
        Camera.Parameters parameters = mCamera.getParameters();
        List<String> flashModes = parameters.getSupportedFlashModes();
        if (flashModes != null &&
                flashModes.contains(Camera.Parameters.FLASH_MODE_OFF) &&
                flashModes.contains(Camera.Parameters.FLASH_MODE_OFF)) {
            switch (mFlashMode) {
                case Camera.Parameters.FLASH_MODE_OFF: {
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    break;
                }
                case Camera.Parameters.FLASH_MODE_ON: {
                    parameters.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
                    break;
                }
            }
            mCamera.setParameters(parameters);
            mHardwareFlashSupported = true;
        } else {
            mHardwareFlashSupported = false;
        }
    }

    private class HeadsetStateReceiver extends BroadcastReceiver {
        /**
         * TODO
         *
         * @param context
         * @param intent
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getExtras().getInt("state");
            int microphone = intent.getExtras().getInt("microphone");
            if (state == 1 && microphone == 1) {
                mNoStickWarningView.setVisibility(View.GONE);
                mAudioCapturer.start();
            } else {
                mNoStickWarningView.setVisibility(View.VISIBLE);
                mAudioCapturer.stop();
            }
        }
    }
}