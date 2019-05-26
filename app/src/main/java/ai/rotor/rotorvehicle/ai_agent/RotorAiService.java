package ai.rotor.rotorvehicle.ai_agent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.widget.ImageView;

import java.nio.ByteBuffer;

import ai.rotor.rotorvehicle.rotor_ctl.RotorCtlService;
import timber.log.Timber;

import static ai.rotor.rotorvehicle.RotorUtils.*;

public class RotorAiService implements Runnable {
    private RotorCamera mCamera;
    private Handler mCameraHandler;
    private Handler mUiHandler;
    private ImageView mImageView;
    private HandlerThread mCameraThread;
    private Context mMainContext;
    private RotorCtlService mRotorCtlService;
    private boolean mIsAuto;

    public RotorAiService(Context context, ImageView imageView, RotorCtlService rotorCtlService) {
        Timber.d("Creating RotorAiService");
        this.mMainContext = context;
        this.mImageView = imageView;
        this.mUiHandler = new Handler(mMainContext.getMainLooper());
        this.mRotorCtlService = rotorCtlService;
        this.mIsAuto = false;

        if (mMainContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Timber.d("Unable to use camera. Permission not granted.");
        }

        // Create handler threads
        mCameraThread = new HandlerThread("CameraBackground");
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());

        // Instantiate RotorCamera object
        mCamera = RotorCamera.getInstance();
    }

    public void startAutoMode() {

        Timber.d("Starting autonomous mode");
        mRotorCtlService.setState(RotorCtlService.State.AUTONOMOUS);

        mCamera.startCapturing();

        mIsAuto = true;

    }

    public void stopAutoMode() {

        Timber.d("Stopping autonomous mode");
        mRotorCtlService.setState(RotorCtlService.State.HOMED);

        mCamera.stopCapturing();

        mIsAuto = false;
    }


    public boolean isAutoMode() {
        return mIsAuto;
    }


    private Context getMainContext() { return mMainContext; }


    @Override
    public void run() {

        if (mMainContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Timber.d("No permission");
        }

        // Start RotorCamera session
        mCamera.initializeCamera(mMainContext, mCameraHandler, mOnImageAvailableListener);

    }


    private ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image jpgImage = reader.acquireLatestImage();
            ByteBuffer imgBuffer = jpgImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[imgBuffer.remaining()];
            imgBuffer.get(bytes);

            Bitmap imgBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);

            Matrix matrix = new Matrix();
            matrix.postRotate(90);

            Bitmap resizedBitmap = Bitmap.createScaledBitmap(imgBitmap, IMAGE_HEIGHT, IMAGE_WIDTH, false);
            final Bitmap rotatedBitmap = Bitmap.createBitmap(resizedBitmap, 0, 0, resizedBitmap.getWidth(), resizedBitmap.getHeight(), matrix, true);

            // Display the image on the imageView
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mImageView.setImageBitmap(rotatedBitmap);
                }
            });

            jpgImage.close();
        }
    };

    private void runOnUiThread(Runnable r) {
        mUiHandler.post(r);
    }

    public void stop() {
        mCamera.shutDown();
    }
}
