package com.google.ar.core.examples.java.helloar; // ← change if your app uses a different package

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

public class HomeActivity extends AppCompatActivity {

    private ActivityResultLauncher<String[]> permissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        // If the layout has a TextView with id instructionText, we leave its text
        // as defined in XML (do NOT override it here).
        TextView instructions = findViewById(R.id.instructionsText);
        if (instructions != null) {
            instructions.setSelected(true); // optional: enable marquee if you set it in XML
        }

        // Register permission launcher
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    if (!hasRequiredPermissions()) {
                        Toast.makeText(
                                this,
                                "Please grant Camera, Precise Location, and Microphone access.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });

        // Ask once at launch if anything is missing
        if (!hasRequiredPermissions()) requestPermissionsNow();

        // Start the creator AR screen (HelloArActivity)
        MaterialButton start = findViewById(R.id.startArTourButton);
        if (start != null) {
            // Keep the button text from XML; only wire the click.
            start.setOnClickListener(v -> {
                if (!hasRequiredPermissions()) {
                    requestPermissionsNow();
                    return;
                }
                startActivity(new Intent(this, HelloArActivity.class));
            });
        }
    }

    // ---- Permissions ----

    private boolean hasRequiredPermissions() {
        boolean ok = granted(Manifest.permission.CAMERA)
                && granted(Manifest.permission.ACCESS_FINE_LOCATION)
                && granted(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            ok = ok
                    && granted(Manifest.permission.READ_MEDIA_IMAGES)
                    && granted(Manifest.permission.READ_MEDIA_AUDIO);
        }
        return ok;
    }

    private boolean granted(@NonNull String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsNow() {
        if (Build.VERSION.SDK_INT >= 33) {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_AUDIO
            });
        } else {
            permissionLauncher.launch(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                    // If your picker needs it on old devices, add:
                    // Manifest.permission.READ_EXTERNAL_STORAGE
            });
        }
    }
}
