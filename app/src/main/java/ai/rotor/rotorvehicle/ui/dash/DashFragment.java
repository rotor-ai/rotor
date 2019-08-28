package ai.rotor.rotorvehicle.ui.dash;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Rational;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraX;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewConfig;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProviders;

import ai.rotor.rotorvehicle.R;
import ai.rotor.rotorvehicle.databinding.DashFragmentBinding;
import butterknife.BindView;
import butterknife.ButterKnife;
import timber.log.Timber;

import static ai.rotor.rotorvehicle.RotorUtils.IMAGE_HEIGHT;
import static ai.rotor.rotorvehicle.RotorUtils.IMAGE_WIDTH;
import static androidx.core.content.ContextCompat.checkSelfPermission;


public class DashFragment extends Fragment implements LifecycleOwner {

    private DashViewModel mViewModel;

    private final int REQUEST_CAMERA_ACCESS = 101;

    private DashFragmentBinding binding;

    static DashFragment newInstance() {
        return new DashFragment();
    }

    @BindView(R.id.frontCamView)
    TextureView frontCamPreview;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dash_fragment, container, false);
        ButterKnife.bind(this, view);
        binding = DataBindingUtil.bind(view);

        Timber.plant(new Timber.DebugTree());

        return view;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mViewModel = ViewModelProviders.of(this).get(DashViewModel.class);
        binding.setViewModel(mViewModel);
        setupCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode){
            case REQUEST_CAMERA_ACCESS:
                if (hasCameraPermission()) {
                    setupCamera();
                }
                break;
        }
    }

    private void setupCamera() {
        if (!hasCameraPermission()){
            requestCameraAccess();
        }
        else {
            frontCamPreview.post(this::actuallySetupCamera);
            frontCamPreview.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> setCameraPreviewTransform());
        }
    }

    private void actuallySetupCamera() {
        //setup the camera
        Preview preview = new Preview(cameraPreviewConfig());

        preview.setOnPreviewOutputUpdateListener(output -> {
            refreshCameraPreview(output);
            setCameraPreviewTransform();
        });
        CameraX.unbindAll();
        CameraX.bindToLifecycle(this, preview);
    }

    private PreviewConfig cameraPreviewConfig() {
        int wid = getFrontCamDisplayMetrics().widthPixels;
        int height = getFrontCamDisplayMetrics().heightPixels;
        Timber.d(">>>>>wid: " + wid + " height: " + height);
        return new PreviewConfig.Builder()
                .setTargetAspectRatio(new Rational(wid, height))
                .setTargetRotation(frontCamPreview.getDisplay().getRotation())
                .build();
    }

    private void refreshCameraPreview(Preview.PreviewOutput output) {
        ViewGroup parentView = (ViewGroup) frontCamPreview.getParent();
        parentView.removeView(frontCamPreview);
        parentView.addView(frontCamPreview, 0);
        frontCamPreview.setSurfaceTexture(output.getSurfaceTexture());
    }

    private void setCameraPreviewTransform() {
        int rotation = frontCamPreview.getDisplay().getRotation() * 90;
        float x = frontCamPreview.getWidth() / 2f;
        float y = frontCamPreview.getHeight() / 2f;

        Matrix matrix = new Matrix();
        matrix.postRotate(-rotation, x, y);
        frontCamPreview.setTransform(matrix);
    }

    private DisplayMetrics getFrontCamDisplayMetrics() {
        DisplayMetrics result = new DisplayMetrics();
        frontCamPreview.getDisplay().getMetrics(result);
        return result;
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(this.getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraAccess() {
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_ACCESS);
    }

}
