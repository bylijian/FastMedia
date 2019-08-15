package com.bylijian.fastmedia;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;

import com.bylijian.fastmedia.fragment.CameraPreviewFragment;


public class CameraActivity extends AppCompatActivity {
    private CameraPreviewFragment cameraPreviewFragment;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        if (savedInstanceState == null) {
            cameraPreviewFragment = new CameraPreviewFragment();
            getSupportFragmentManager().beginTransaction().replace(R.id.container, cameraPreviewFragment).commit();
        }
    }

}
