package com.google.ar.core.examples.java.helloar;

import android.Manifest;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.net.Uri;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.ArCoreApk.Availability;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.DepthPoint;
import com.google.ar.core.Earth;
import com.google.ar.core.Frame;
import com.google.ar.core.GeospatialPose;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.StreetscapeGeometry;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;

import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.InstantPlacementSettings;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.Framebuffer;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter;
import com.google.ar.core.examples.java.helloar.data.EggEntry;
import com.google.ar.core.examples.java.helloar.data.EggRepository;
import com.google.ar.core.examples.java.helloar.ui.EggCardSheet;

import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import java.util.HashMap;
import java.util.Map;

/**
 * HelloAR egg layer:
 * - INDOOR: precise placement via Depth/Feature hits + Cloud Anchor hosting (persistence).
 * - OUTDOOR: Geospatial gating (no hosting).
 * - Instant Placement OFF; Depth occlusion ON if supported.
 * - One authoring anchor at a time.
 */
public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

    private static final String TAG = HelloArActivity.class.getSimpleName();

    private static final float Z_NEAR = 0.3f;
    private static final float Z_FAR  = 100f;

    private static final int CUBEMAP_RESOLUTION = 16;
    private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

    // -------- Environment profiles --------
    private enum Env { OUTDOOR, INDOOR }
    private static final String PREFS = "helloar_prefs";
    private static final String KEY_ENV = "env_mode";

    // Permissions
    private static final int REQ_FINE_LOCATION = 101;

    // OUTDOOR: stricter (GPS/VPS better outside)
    private static final double H_ACC_OUTDOOR = 30.0;    // meters
    private static final double V_ACC_OUTDOOR = 20.0;    // meters
    private static final double HEAD_ACC_OUTDOOR = 25.0; // degrees

    // INDOOR: looser gates
    private static final double H_ACC_INDOOR = 80.0;     // meters
    private static final double V_ACC_INDOOR = 60.0;     // meters
    private static final double HEAD_ACC_INDOOR = 60.0;  // degrees

    // Cloud Anchor TTL (days)
    private static final int CLOUD_TTL_DAYS = 1;

    // Rendering & ARCore
    private GLSurfaceView surfaceView;
    private boolean installRequested;
    private Session session;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private DisplayRotationHelper displayRotationHelper;
    @SuppressWarnings("unused")
    private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
    private TapHelper tapHelper;
    private SampleRender render;

    private PlaneRenderer planeRenderer;
    private BackgroundRenderer backgroundRenderer;
    private Framebuffer virtualSceneFramebuffer;
    private boolean hasSetTextureNames = false;

    private final DepthSettings depthSettings = new DepthSettings();
    private boolean[] depthSettingsMenuDialogCheckboxes = new boolean[2];

    private final InstantPlacementSettings instantPlacementSettings = new InstantPlacementSettings();
    private boolean[] instantPlacementSettingsMenuDialogCheckboxes = new boolean[1];

    // Point cloud
    private VertexBuffer pointCloudVertexBuffer;
    private Mesh pointCloudMesh;
    private Shader pointCloudShader;
    private long lastPointCloudTimestamp = 0;

    // Egg model (PBR with unlit fallback)
    private Mesh   virtualObjectMesh;
    private Shader virtualObjectShader;
    private Texture virtualObjectAlbedoTexture;
    private Shader unlitShader;
    private Mesh[]  eggMeshes;
    private Shader[] eggShaders;

    // IBL helpers
    private Texture dfgTexture;
    private SpecularCubemapFilter cubemapFilter;

    // Matrices
    private final float[] modelMatrix               = new float[16];
    private final float[] viewMatrix                = new float[16];
    private final float[] projectionMatrix          = new float[16];
    private final float[] modelViewMatrix           = new float[16];
    private final float[] modelViewProjectionMatrix = new float[16];

    private boolean sessionPaused = false;
    private boolean backgroundReady = false;

    // UI
    private TextView poseInfoCard;
    private TextView tvLatLng, tvLatLngAcc, tvAlt, tvAltAcc, tvHeading, tvHeadingAcc, tvAnchorState, tvEarthState;

    // Authoring anchor (single)
    private final List<WrappedAnchor> wrappedAnchors = new ArrayList<>();
    @Nullable private Anchor currentPlacedAnchor = null;

    // Geospatial caching
    @Nullable private GeospatialPose lastGoodGeoPose = null;

    private EggRepository eggRepo;

    // Environment state
    private Env envMode = Env.OUTDOOR; // default
    private SharedPreferences prefs;

    // Popup menu IDs
    private static final int GROUP_ENV = 2000;
    private static final int MENU_ENV_HEADER = 2001;
    private static final int MENU_ENV_INDOOR = 2002;
    private static final int MENU_ENV_OUTDOOR = 2003;
    private static final int MENU_DIVIDER    = 2004;

    // ---- Cloud Anchor hosting state (INDOOR only) ----
    private Anchor localAnchorForHosting;   // anchor to host
    private Anchor hostedCloudAnchor;       // anchor returned while hosting
    private String hostedCloudId;           // final cloud anchor id
    private String pendingEggDocId;         // Firestore doc to patch when hosting succeeds
    private enum HostState { IDLE, HOSTING, SUCCESS, ERROR }
    private HostState hostState = HostState.IDLE;

    private static float distance(Pose a, Pose b) {
        float dx = a.tx() - b.tx(), dy = a.ty() - b.ty(), dz = a.tz() - b.tz();
        return (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    // Convenience gates per mode
    private double hGate()  { return envMode == Env.INDOOR ? H_ACC_INDOOR  : H_ACC_OUTDOOR; }
    private double vGate()  { return envMode == Env.INDOOR ? V_ACC_INDOOR  : V_ACC_OUTDOOR; }
    private double headGate(){ return envMode == Env.INDOOR ? HEAD_ACC_INDOOR : HEAD_ACC_OUTDOOR; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureAnonAuth();

        // Fine location for Geospatial API (used mainly outdoors)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
        }

        setContentView(R.layout.activity_main);

        tvEarthState  = findViewById(R.id.tvEarthState);
        tvLatLng      = findViewById(R.id.tvLatLng);
        tvLatLngAcc   = findViewById(R.id.tvLatLngAcc);
        tvAlt         = findViewById(R.id.tvAlt);
        tvAltAcc      = findViewById(R.id.tvAltAcc);
        tvHeading     = findViewById(R.id.tvHeading);
        tvHeadingAcc  = findViewById(R.id.tvHeadingAcc);
        tvAnchorState = findViewById(R.id.tvAnchorState);
        poseInfoCard  = findViewById(R.id.poseInfoCard);

        surfaceView = findViewById(R.id.surfaceview);
        displayRotationHelper = new DisplayRotationHelper(this);

        surfaceView.setEGLContextClientVersion(3);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);

        tapHelper = new TapHelper(this);
        surfaceView.setOnTouchListener(tapHelper);

        render = new SampleRender(surfaceView, this, getAssets());
        installRequested = false;

        // Defaults for authoring
        depthSettings.onCreate(this);
        instantPlacementSettings.onCreate(this);
        depthSettings.setUseDepthForOcclusion(true);
        depthSettings.setDepthColorVisualizationEnabled(false);
        instantPlacementSettings.setInstantPlacementEnabled(false); // real hits only

        // Restore environment mode
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_ENV, Env.OUTDOOR.name());
        try { envMode = Env.valueOf(saved); } catch (Exception ignore) {}

        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(HelloArActivity.this, v);
            popup.setOnMenuItemClickListener(HelloArActivity.this::settingsMenuClick);

            Menu menu = popup.getMenu();
            menu.add(Menu.NONE, MENU_ENV_HEADER, Menu.CATEGORY_SYSTEM, "Environment mode — choose one:")
                    .setEnabled(false);
            menu.add(GROUP_ENV, MENU_ENV_INDOOR,  Menu.CATEGORY_SYSTEM + 1, "Indoor (plants/classroom)")
                    .setCheckable(true);
            menu.add(GROUP_ENV, MENU_ENV_OUTDOOR, Menu.CATEGORY_SYSTEM + 2, "Outdoor (streets/open)")
                    .setCheckable(true);
            menu.setGroupCheckable(GROUP_ENV, true, true);
            if (envMode == Env.INDOOR) menu.findItem(MENU_ENV_INDOOR).setChecked(true);
            else                       menu.findItem(MENU_ENV_OUTDOOR).setChecked(true);
            menu.add(Menu.NONE, MENU_DIVIDER, Menu.CATEGORY_SYSTEM + 3, "────────────").setEnabled(false);

            popup.inflate(R.menu.settings_menu);
            popup.show();
        });

        eggRepo = new EggRepository();
    }

    private boolean settingsMenuClick(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.depth_settings) {
            launchDepthSettingsMenuDialog();
            return true;
        } else if (id == R.id.instant_placement_settings) {
            launchInstantPlacementSettingsMenuDialog();
            return true;
        } else if (id == MENU_ENV_INDOOR) {
            setEnvironmentMode(Env.INDOOR);
            return true;
        } else if (id == MENU_ENV_OUTDOOR) {
            setEnvironmentMode(Env.OUTDOOR);
            return true;
        }
        return false;
    }

    private void setEnvironmentMode(Env newMode) {
        if (envMode == newMode) {
            Toast.makeText(this,
                    "You're already using " + (newMode == Env.INDOOR ? "Indoor" : "Outdoor") + " mode.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        envMode = newMode;
        prefs.edit().putString(KEY_ENV, envMode.name()).apply();
        if (session != null) configureSession();

        String msg = (envMode == Env.INDOOR)
                ? "Environment set to INDOOR.\nPlacement uses Depth/feature points.\nAnchor is hosted to Cloud for persistence."
                : "Environment set to OUTDOOR.\nPlacement gated by Geospatial accuracy.";
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();

        if (session == null) {
            Exception exception = null;
            String message = null;

            try {
                Availability availability = ArCoreApk.getInstance().checkAvailability(this);
                if (availability != Availability.SUPPORTED_INSTALLED) {
                    switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                        case INSTALL_REQUESTED:
                            installRequested = true;
                            return;
                        case INSTALLED:
                            break;
                    }
                }

                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
                    return;
                }

                session = new Session(this);
                configureSession(); // initial
                session.resume();
                sessionPaused = false;

            } catch (UnavailableArcoreNotInstalledException | UnavailableUserDeclinedInstallationException e) {
                message = "Please install ARCore";
                exception = e;
            } catch (UnavailableApkTooOldException e) {
                message = "Please update ARCore";
                exception = e;
            } catch (UnavailableSdkTooOldException e) {
                message = "Please update this app";
                exception = e;
            } catch (UnavailableDeviceNotCompatibleException e) {
                message = "This device does not support AR";
                exception = e;
            } catch (Exception e) {
                message = "Failed to create AR session";
                exception = e;
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }
        } else {
            try {
                configureSession();
                session.resume();
                sessionPaused = false;
            } catch (CameraNotAvailableException e) {
                messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
                session = null;
                return;
            }
        }

        surfaceView.onResume();
        displayRotationHelper.onResume();
    }

    @Override protected void onPause() {
        super.onPause();
        sessionPaused = true;
        if (session != null) session.pause();
        surfaceView.onPause();
        displayRotationHelper.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_FINE_LOCATION) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(this, "Location permission is required for geospatial features.", Toast.LENGTH_LONG).show();
            }
        }
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    @Override public void onSurfaceCreated(SampleRender render) {
        try {
            backgroundRenderer = new BackgroundRenderer(render);
            try { backgroundRenderer.setUseDepthVisualization(render, false); } catch (Throwable ignored) {}
            try {
                backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
                backgroundRenderer.setUseDepthVisualization(render, depthSettings.depthColorVisualizationEnabled());
            } catch (Exception e) { Log.w(TAG, "Applying background settings failed", e); }
            backgroundReady = true;

            planeRenderer = new PlaneRenderer(render);
            virtualSceneFramebuffer = new Framebuffer(render, 1, 1);

            cubemapFilter = new SpecularCubemapFilter(render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES);

            dfgTexture = new Texture(render, Texture.Target.TEXTURE_2D, Texture.WrapMode.CLAMP_TO_EDGE, false);
            try (InputStream is = getAssets().open("models/dfg.raw")) {
                final int sizeBytes = 64 * 64 * 2 * 2;
                byte[] bytes = new byte[sizeBytes];
                int off = 0;
                while (off < sizeBytes) {
                    int r = is.read(bytes, off, sizeBytes - off);
                    if (r <= 0) break;
                    off += r;
                }
                ByteBuffer buf = ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.nativeOrder());
                buf.put(bytes, 0, sizeBytes);
                buf.position(0);

                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.getTextureId());
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG16F, 64, 64, 0,
                        GLES30.GL_RG, GLES30.GL_HALF_FLOAT, buf);
            } catch (IOException io) {
                Log.w(TAG, "Missing DFG LUT (PBR may look off).", io);
            }

            try {
                pointCloudShader = Shader.createFromAssets(render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", null)
                        .setVec4("u_Color", new float[]{31f/255f,188f/255f,210f/255f,1f})
                        .setFloat("u_PointSize", 5f);
                pointCloudVertexBuffer = new VertexBuffer(render, 4, null);
                pointCloudMesh = new Mesh(render, Mesh.PrimitiveMode.POINTS, null, new VertexBuffer[]{pointCloudVertexBuffer});
            } catch (IOException e) {
                Log.w(TAG, "Point cloud shaders missing; disabling point cloud.", e);
                pointCloudShader = null; pointCloudMesh = null; pointCloudVertexBuffer = null;
            }

            boolean pbrReady = false;
            try {
                virtualObjectAlbedoTexture = Texture.createFromAsset(render, "models/egg_2_colour.jpg",
                        Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB);
                virtualObjectMesh = Mesh.createFromAsset(render, "models/egg_2.obj");

                Map<String, String> defs = new HashMap<>();
                defs.put("NUMBER_OF_MIPMAP_LEVELS", Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));

                virtualObjectShader = Shader.createFromAssets(
                                render, "shaders/environmental_hdr.vert", "shaders/environmental_hdr.frag", defs)
                        .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                        .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
                        .setTexture("u_DfgTexture", dfgTexture)
                        .setDepthTest(true)
                        .setDepthWrite(true);

                eggMeshes  = new Mesh[]  { virtualObjectMesh };
                eggShaders = new Shader[]{ virtualObjectShader };
                pbrReady = true;
            } catch (IOException e) {
                Log.e(TAG, "PBR assets missing — will fall back to unlit.", e);
            } catch (Throwable t) {
                Log.w(TAG, "PBR pipeline init failed — will fall back to unlit.", t);
            }

            if (!pbrReady) {
                try {
                    if (virtualObjectAlbedoTexture == null) {
                        virtualObjectAlbedoTexture = Texture.createFromAsset(render, "models/egg_2_colour.jpg",
                                Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB);
                    }
                    if (virtualObjectMesh == null) {
                        virtualObjectMesh = Mesh.createFromAsset(render, "models/egg_2.obj");
                    }
                    unlitShader = Shader.createFromAssets(render, "shaders/ar_unlit_object.vert", "shaders/ar_unlit_object.frag", null)
                            .setTexture("u_Texture", virtualObjectAlbedoTexture)
                            .setFloat("u_Opacity", 1.0f)
                            .setDepthTest(true)
                            .setDepthWrite(true);

                    eggMeshes  = new Mesh[]  { virtualObjectMesh };
                    eggShaders = new Shader[]{ unlitShader };
                } catch (IOException io) {
                    Log.e(TAG, "Unlit fallback also failed. No renderable egg.", io);
                    eggMeshes = null; eggShaders = null;
                }
            }

            try {
                GLES30.glEnable(GLES30.GL_DEPTH_TEST);
                GLES30.glDepthFunc(GLES30.GL_LEQUAL);
                GLES30.glDepthMask(true);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(TAG, "onSurfaceCreated: fatal init error", t);
        }
    }

    @Override public void onSurfaceChanged(SampleRender render, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        virtualSceneFramebuffer.resize(width, height);
    }

    @Override public void onDrawFrame(SampleRender render) {
        if (session == null || sessionPaused) return;

        if (!backgroundReady || backgroundRenderer == null) {
            Log.w(TAG, "onDrawFrame: background not ready; skipping frame.");
            return;
        }

        if (!hasSetTextureNames && backgroundRenderer.getCameraColorTexture() != null) {
            try {
                session.setCameraTextureNames(new int[]{backgroundRenderer.getCameraColorTexture().getTextureId()});
                hasSetTextureNames = true;
            } catch (Throwable t) {
                Log.e(TAG, "Failed to set camera texture names", t);
                return;
            }
        }

        try { displayRotationHelper.updateSessionIfNeeded(session); } catch (Throwable t) { Log.w(TAG, "updateSessionIfNeeded threw", t); }

        final Frame frame;
        try {
            frame = session.update();
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available during onDrawFrame", e);
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
            return;
        } catch (Throwable t) {
            Log.e(TAG, "session.update() failed", t);
            return;
        }

        final Camera camera = frame.getCamera();

        try { backgroundRenderer.updateDisplayGeometry(frame); } catch (Throwable t) { Log.e(TAG, "updateDisplayGeometry failed", t); return; }

        // Depth texture (optional for occlusion)
        if (camera.getTrackingState() == TrackingState.TRACKING
                && (depthSettings.useDepthForOcclusion() || depthSettings.depthColorVisualizationEnabled())) {
            try (Image depthImage = frame.acquireDepthImage16Bits()) {
                backgroundRenderer.updateCameraDepthTexture(depthImage);
            } catch (Throwable ignore) {}
        }

        // Tap handling (may create an anchor if we have a *real* hit)
        try { handleTap(frame, camera); } catch (Throwable t) { Log.w(TAG, "handleTap failed", t); }

        // Draw camera
        try { if (frame.getTimestamp() != 0) backgroundRenderer.drawBackground(render); } catch (Throwable t) { Log.e(TAG, "drawBackground failed", t); return; }

        if (camera.getTrackingState() != TrackingState.TRACKING) return;

        // View / Projection
        try {
            camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);
            camera.getViewMatrix(viewMatrix, 0);
        } catch (Throwable t) { Log.e(TAG, "Failed to get camera matrices", t); return; }

        // Planes
        try {
            if (planeRenderer != null) {
                planeRenderer.drawPlanes(render, session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projectionMatrix);
            }
        } catch (Throwable t) { Log.w(TAG, "Plane rendering failed", t); }

        // Point cloud
        try (com.google.ar.core.PointCloud pc = frame.acquirePointCloud()) {
            if (pointCloudShader != null && pointCloudMesh != null && pointCloudVertexBuffer != null) {
                if (pc.getTimestamp() > lastPointCloudTimestamp) {
                    pointCloudVertexBuffer.set(pc.getPoints());
                    lastPointCloudTimestamp = pc.getTimestamp();
                }
                Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
                pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
                render.draw(pointCloudMesh, pointCloudShader);
            }
        } catch (Throwable t) { Log.w(TAG, "Point cloud draw failed", t); }

        // Prepare to draw eggs
        try {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthFunc(GLES30.GL_LEQUAL);
            GLES30.glDepthMask(true);
        } catch (Throwable ignore) {}

        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);

        final float MODEL_BASE_SCALE = 0.0035f;
        int anchorsDrawn = 0;

        for (WrappedAnchor wrapped : wrappedAnchors) {
            if (wrapped == null) continue;
            Anchor a = wrapped.getAnchor();
            if (a == null || a.getTrackingState() == TrackingState.STOPPED) continue;

            a.getPose().toMatrix(modelMatrix, 0);

            // Slight lift if not on a plane/point
            float liftY = (wrapped.getTrackable() instanceof Plane || wrapped.getTrackable() instanceof Point) ? 0.0f : 0.02f;
            Matrix.translateM(modelMatrix, 0, 0f, liftY, 0f);

            // OBJ is Z-up → rotate to Y-up
            Matrix.rotateM(modelMatrix, 0, -90f, 1f, 0f, 0f);

            Matrix.scaleM(modelMatrix, 0, MODEL_BASE_SCALE, MODEL_BASE_SCALE, MODEL_BASE_SCALE);

            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

            if (eggMeshes != null && eggShaders != null && eggMeshes.length == eggShaders.length && eggMeshes.length > 0) {
                for (int i = 0; i < eggMeshes.length; i++) {
                    if (eggMeshes[i] == null || eggShaders[i] == null) continue;
                    try { eggShaders[i].setMat4("u_ModelViewProjection", modelViewProjectionMatrix); } catch (Throwable ignore) {}
                    try { eggShaders[i].setMat4("u_ModelView", modelViewMatrix); } catch (Throwable ignore) {}
                    try { eggShaders[i].setFloat("u_Opacity", 1.0f); } catch (Throwable ignore) {}
                    render.draw(eggMeshes[i], eggShaders[i], virtualSceneFramebuffer);
                    anchorsDrawn++;
                }
            }
        }

        if (anchorsDrawn == 0 && !wrappedAnchors.isEmpty()) {
            Log.w(TAG, "Anchors present but nothing drawn (maybe occluded or off-screen).");
        }

        final boolean vsReady = (backgroundRenderer != null) && backgroundRenderer.isVirtualSceneInitialized();
        if (virtualSceneFramebuffer != null && vsReady) {
            backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
        }

        // --- Cloud Anchor hosting progress (INDOOR only) ---
        if (envMode == Env.INDOOR && hostState == HostState.HOSTING && hostedCloudAnchor != null) {
            Anchor.CloudAnchorState st = hostedCloudAnchor.getCloudAnchorState();
            if (st.isError()) {
                hostState = HostState.ERROR;
                runOnUiThread(() ->
                        Toast.makeText(this, "Cloud Anchor hosting failed: " + st, Toast.LENGTH_LONG).show());
            } else if (st == Anchor.CloudAnchorState.SUCCESS) {
                hostState = HostState.SUCCESS;
                hostedCloudId = hostedCloudAnchor.getCloudAnchorId();
                if (pendingEggDocId != null && hostedCloudId != null) {
                    FirebaseFirestore.getInstance()
                            .collection("eggs")
                            .document(pendingEggDocId)
                            .update("anchorType", "CLOUD",
                                    "cloudId", hostedCloudId)
                            .addOnSuccessListener(v ->
                                    runOnUiThread(() ->
                                            Toast.makeText(this, "Anchor hosted ✔", Toast.LENGTH_SHORT).show()))
                            .addOnFailureListener(e -> Log.e(TAG, "Failed to write cloudId", e));
                }
            }
        }

        // HUD based on CAMERA geopose
        try {
            Earth earth = null;
            try { earth = session.getEarth(); } catch (Throwable ignore) {}
            final String earthLine;
            if (earth == null) {
                earthLine = "Earth: not available";
            } else {
                earthLine = "AR Earth: " + earth.getEarthState()
                        + " • Tracking: " + earth.getTrackingState()
                        + " • Mode: " + (envMode == Env.INDOOR ? "Indoor" : "Outdoor");
            }

            String latLngLine = "LAT/LNG: —";
            String latLngAcc  = "H-Accuracy: —";
            String altLine    = "ALTITUDE: —";
            String altAcc     = "V-Accuracy: —";
            String headLine   = "HEADING: —";
            String headAcc    = "Heading accuracy: —";
            String anchorLine = "Anchor: none";

            Anchor currentAnchor = (!wrappedAnchors.isEmpty() && wrappedAnchors.get(0) != null) ? wrappedAnchors.get(0).getAnchor() : null;
            if (currentAnchor != null) anchorLine = "Anchor: " + currentAnchor.getTrackingState().name();

            if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
                GeospatialPose camGp = null;
                try { camGp = earth.getCameraGeospatialPose(); } catch (Throwable ignore) {}
                if (camGp != null) {
                    double heading = camGp.getHeading();
                    if ((Double.isNaN(heading) || Math.abs(heading) < 1e-6 || camGp.getHeadingAccuracy() > headGate())
                            && lastGoodGeoPose != null) {
                        heading = lastGoodGeoPose.getHeading();
                    }

                    latLngLine = String.format(Locale.US, "LAT/LNG: %.6f°, %.6f°", camGp.getLatitude(), camGp.getLongitude());
                    latLngAcc  = String.format(Locale.US, "H-Accuracy: ±%.2f m", camGp.getHorizontalAccuracy());
                    altLine    = String.format(Locale.US, "ALTITUDE: %.2f m", camGp.getAltitude());
                    altAcc     = String.format(Locale.US, "V-Accuracy: ±%.2f m", camGp.getVerticalAccuracy());
                    headLine   = String.format(Locale.US, "HEADING: %.1f°", heading);
                    headAcc    = String.format(Locale.US, "Heading accuracy: ±%.1f°", camGp.getHeadingAccuracy());

                    if (camGp.getHorizontalAccuracy() <= hGate()) lastGoodGeoPose = camGp;
                }
            }

            final String fEarthLine  = earthLine, fAnchorLine = anchorLine;
            final String fLatLngLine = latLngLine, fLatLngAcc  = latLngAcc;
            final String fAltLine    = altLine,    fAltAcc     = altAcc;
            final String fHeadLine   = headLine,   fHeadAcc    = headAcc;

            runOnUiThread(() -> {
                if (tvEarthState  != null) tvEarthState.setText(fEarthLine);
                if (tvAnchorState != null) tvAnchorState.setText(fAnchorLine);
                if (tvLatLng      != null) tvLatLng.setText(fLatLngLine);
                if (tvLatLngAcc   != null) tvLatLngAcc.setText(fLatLngAcc);
                if (tvAlt         != null) tvAlt.setText(fAltLine);
                if (tvAltAcc      != null) tvAltAcc.setText(fAltAcc);
                if (tvHeading     != null) tvHeading.setText(fHeadLine);
                if (tvHeadingAcc  != null) tvHeadingAcc.setText(fHeadAcc);
            });
        } catch (SecurityException se) {
            Log.w(TAG, "Location permission missing for geospatial.", se);
        } catch (Throwable ignore) {}
    }

    // Handle only one tap per frame — accept ONLY real hits (Depth/Point/Plane)
    private void handleTap(Frame frame, Camera camera) {
        MotionEvent tap = tapHelper.poll();
        if (tap == null) return;

        if (camera.getTrackingState() != TrackingState.TRACKING) {
            messageSnackbarHelper.showMessage(this, "Move slowly and point at well-lit, textured surfaces.");
            return;
        }

        // Only gate on geospatial accuracy outdoors
        if (envMode == Env.OUTDOOR) {
            Earth earth = session.getEarth();
            if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
                try {
                    GeospatialPose camGp = earth.getCameraGeospatialPose();
                    if (camGp != null) {
                        double h = camGp.getHorizontalAccuracy();
                        double v = camGp.getVerticalAccuracy();
                        if ((!Double.isNaN(h) && h > hGate()) || (!Double.isNaN(v) && v > vGate())) {
                            messageSnackbarHelper.showMessage(this,
                                    "Waiting for better location accuracy… move around and try again.");
                            return;
                        }
                    }
                } catch (Throwable ignore) {}
            }
        }

        // Keep just one authoring anchor
        if (!wrappedAnchors.isEmpty()) {
            try {
                Anchor old = wrappedAnchors.get(0).getAnchor();
                if (old != null) old.detach();
            } catch (Throwable ignore) {}
            wrappedAnchors.clear();
        }

        final Pose camPoseNow = camera.getPose();

        // Hit test and keep only nearby hits for precise plant placement
        final float MAX_HIT_M = 2.0f;
        List<HitResult> raw = frame.hitTest(tap);
        List<HitResult> depths = new ArrayList<>();
        List<HitResult> points = new ArrayList<>();
        List<HitResult> planes = new ArrayList<>();
        List<HitResult> geoms  = new ArrayList<>();

        for (HitResult hit : raw) {
            Trackable t = hit.getTrackable();
            float d = distance(hit.getHitPose(), camPoseNow);
            if (d > MAX_HIT_M) continue;

            boolean isDepth = (t instanceof DepthPoint);
            boolean isGoodPoint = (t instanceof Point)
                    && ((Point) t).getOrientationMode() == OrientationMode.ESTIMATED_SURFACE_NORMAL;
            boolean isPlane = (t instanceof Plane)
                    && ((Plane) t).isPoseInPolygon(hit.getHitPose())
                    && PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camPoseNow) > 0;
            boolean isGeom = (t instanceof StreetscapeGeometry);

            if (isDepth) depths.add(hit);
            else if (isGoodPoint) points.add(hit);
            else if (isPlane) planes.add(hit);
            else if (isGeom) geoms.add(hit);
        }

        Comparator<HitResult> byDist = (a, b) ->
                Float.compare(distance(a.getHitPose(), camPoseNow), distance(b.getHitPose(), camPoseNow));
        Collections.sort(depths, byDist);
        Collections.sort(points, byDist);
        Collections.sort(planes, byDist);
        Collections.sort(geoms, byDist);

        // For plant parts, prefer depth -> feature points -> plane
        HitResult chosen = !depths.isEmpty() ? depths.get(0)
                : !points.isEmpty() ? points.get(0)
                : !planes.isEmpty() ? planes.get(0)
                : !geoms.isEmpty() ? geoms.get(0)
                : null;

        if (chosen == null) {
            messageSnackbarHelper.showMessage(this, "Looking for plant surface… move a bit closer with steady hands.");
            return;
        }

        final Anchor placedAnchor = chosen.createAnchor();
        currentPlacedAnchor = placedAnchor;
        wrappedAnchors.add(new WrappedAnchor(placedAnchor, chosen.getTrackable()));

        // Remember for indoor hosting later
        localAnchorForHosting = placedAnchor;
        hostState = HostState.IDLE;

        // Optional: geospatial snapshot for HUD & doc
        GeospatialPose anchorGp = null, camGp = null;
        Earth earth = null;
        try { earth = session.getEarth(); } catch (Throwable ignore) {}
        if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
            try { anchorGp = earth.getGeospatialPose(placedAnchor.getPose()); } catch (Throwable ignore) {}
            try { camGp    = earth.getCameraGeospatialPose(); } catch (Throwable ignore) {}
        }

        final String msg = (anchorGp != null)
                ? String.format(Locale.US, "Placed. Lat %.6f  Lng %.6f  Alt %.2f  (H ±%.1fm, V ±%.1fm)",
                anchorGp.getLatitude(), anchorGp.getLongitude(), anchorGp.getAltitude(),
                anchorGp.getHorizontalAccuracy(), anchorGp.getVerticalAccuracy())
                : "Placed. Getting precise location…";
        runOnUiThread(() -> {
            if (poseInfoCard != null) {
                poseInfoCard.setVisibility(View.VISIBLE);
                poseInfoCard.setText(msg);
            }
        });

        // Open the save sheet
        EggCardSheet sheet = new EggCardSheet();
        if (anchorGp != null) {
            double headingDeg = (camGp != null && !Double.isNaN(camGp.getHeading()) && camGp.getHeadingAccuracy() <= headGate())
                    ? camGp.getHeading() : Double.NaN;
            sheet.setGeoPoseSnapshot(new EggCardSheet.GeoPoseSnapshot(
                    anchorGp.getLatitude(),
                    anchorGp.getLongitude(),
                    anchorGp.getAltitude(),
                    headingDeg,
                    anchorGp.getHorizontalAccuracy(),
                    anchorGp.getVerticalAccuracy(),
                    (camGp != null ? camGp.getHeadingAccuracy() : Double.NaN),
                    System.currentTimeMillis()));
        }
        sheet.setListener(new EggCardSheet.Listener() {
            @Override
            public void onSave(String title, String description, String transcript,
                               List<Uri> photoUris, @Nullable Uri audioUri,
                               @Nullable EggCardSheet.GeoPoseSnapshot geoSnapshot) {

                // Outdoor: ensure accuracy before saving metadata
                GeospatialPose finalGp = null;
                Earth earthNow = null;
                try {
                    earthNow = session.getEarth();
                    if (earthNow != null && earthNow.getTrackingState() == TrackingState.TRACKING && currentPlacedAnchor != null) {
                        finalGp = earthNow.getGeospatialPose(currentPlacedAnchor.getPose());
                    }
                } catch (Throwable ignore) {}

                if (envMode == Env.OUTDOOR) {
                    double hAcc = (finalGp != null) ? finalGp.getHorizontalAccuracy() : Double.POSITIVE_INFINITY;
                    double vAcc = (finalGp != null) ? finalGp.getVerticalAccuracy() : Double.POSITIVE_INFINITY;
                    if (Double.isNaN(vAcc)) vAcc = Double.POSITIVE_INFINITY;
                    if (finalGp == null || hAcc > hGate() || vAcc > vGate()) {
                        String why = (finalGp == null)
                                ? "Location not precise enough yet for Outdoor mode."
                                : String.format(Locale.US, "Location not precise enough for Outdoor mode.\nCurrent: H ±%.1fm, V ±%.1fm.", hAcc, vAcc);
                        Toast.makeText(HelloArActivity.this, why + "\nScan a bit more and try Save again.", Toast.LENGTH_LONG).show();
                        return;
                    }
                }

                double headingNow = Double.NaN;
                try {
                    GeospatialPose camNow = (earthNow != null) ? earthNow.getCameraGeospatialPose() : null;
                    if (camNow != null && !Double.isNaN(camNow.getHeading()) && camNow.getHeadingAccuracy() <= headGate()) {
                        headingNow = camNow.getHeading();
                    }
                } catch (Throwable ignore) {}

                EggEntry e = new EggEntry();
                e.title = (title == null) ? "" : title;
                e.description = (description == null) ? "" : description;
                e.speechTranscript = (transcript == null) ? "" : transcript;

                // Quiz generation paused:
                e.quiz = null;

                e.heading = headingNow;

                if (finalGp != null) {
                    e.geo      = new com.google.firebase.firestore.GeoPoint(finalGp.getLatitude(), finalGp.getLongitude());
                    e.alt      = finalGp.getAltitude();
                    e.horizAcc = finalGp.getHorizontalAccuracy();
                    double vAcc = finalGp.getVerticalAccuracy();
                    e.vertAcc  = Double.isNaN(vAcc) ? null : vAcc;
                }

                // Simple placement metadata
                e.placementType = (currentPlacedAnchor != null && !wrappedAnchors.isEmpty())
                        ? wrappedAnchors.get(0).getTrackable().getClass().getSimpleName() : "Unknown";
                e.distanceFromCamera = (currentPlacedAnchor != null) ? distance(currentPlacedAnchor.getPose(), camera.getPose()) : 0f;

                eggRepo.createDraft(e)
                        .addOnSuccessListener(docRef -> {
                            eggRepo.uploadMediaAndPatch(docRef, photoUris, audioUri, e.speechTranscript);
                            // Start hosting this placement so you can re-find it later indoors
                            startHostingCloudAnchor(currentPlacedAnchor, docRef.getId());
                        })
                        .addOnFailureListener(err -> Log.e(TAG, "Egg save failed", err));

            }

            @Override public void onCancel() {}
        });

        if (!isFinishing() && !isDestroyed()) sheet.show(getSupportFragmentManager(), "EggCardSheet");
    }

    private void startHostingCloudAnchor(Anchor local, @Nullable String eggDocIdToPatch) {
        if (local == null || session == null) return;

        pendingEggDocId = eggDocIdToPatch;
        hostState = HostState.HOSTING;

        try {
            try {
                // Prefer the 2-arg API with TTL in days
                hostedCloudAnchor = session.hostCloudAnchor(local);
            } catch (NoSuchMethodError e) {
                // fallback: 1-arg API, if available
                hostedCloudAnchor = session.hostCloudAnchor(local);
            }
            runOnUiThread(() ->
                    Toast.makeText(this, "Hosting anchor… (24h)", Toast.LENGTH_SHORT).show());
        } catch (Throwable t) {
            hostState = HostState.ERROR;
            Log.e(TAG, "Failed to start hosting", t);
            runOnUiThread(() ->
                    Toast.makeText(this, "Failed to start hosting: " + t.getMessage(), Toast.LENGTH_LONG).show());
        }
    }
    private void launchInstantPlacementSettingsMenuDialog() {
        resetSettingsMenuDialogCheckboxes();
        Resources resources = getResources();
        new AlertDialog.Builder(this)
                .setTitle(R.string.options_title_instant_placement)
                .setMultiChoiceItems(
                        resources.getStringArray(R.array.instant_placement_options_array),
                        instantPlacementSettingsMenuDialogCheckboxes,
                        (DialogInterface dialog, int which, boolean isChecked) ->
                                instantPlacementSettingsMenuDialogCheckboxes[which] = isChecked)
                .setPositiveButton(R.string.done, (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
                .setNegativeButton(android.R.string.cancel, (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
                .show();
    }

    private void launchDepthSettingsMenuDialog() {
        resetSettingsMenuDialogCheckboxes();
        Resources resources = getResources();
        if (session != null && session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.options_title_with_depth)
                    .setMultiChoiceItems(
                            resources.getStringArray(R.array.depth_options_array),
                            depthSettingsMenuDialogCheckboxes,
                            (DialogInterface dialog, int which, boolean isChecked) ->
                                    depthSettingsMenuDialogCheckboxes[which] = isChecked)
                    .setPositiveButton(R.string.done, (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
                    .setNegativeButton(android.R.string.cancel, (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.options_title_without_depth)
                    .setPositiveButton(R.string.done, (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
                    .show();
        }
    }

    private void applySettingsMenuDialogCheckboxes() {
        depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0]);
        depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1]);
        instantPlacementSettings.setInstantPlacementEnabled(instantPlacementSettingsMenuDialogCheckboxes[0]);
        configureSession();
    }

    private void resetSettingsMenuDialogCheckboxes() {
        depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion();
        depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled();
        instantPlacementSettingsMenuDialogCheckboxes[0] = instantPlacementSettings.isInstantPlacementEnabled();
    }

    private void requestQuizForEgg(String eggDocId, String title, String description) {
        Map<String, Object> req = new HashMap<>();
        req.put("eggDocId", eggDocId);
        req.put("title", title);
        req.put("description", description);
        req.put("model", "gemini-2.5-flash");
        req.put("status", "pending");
        req.put("createdAt", FieldValue.serverTimestamp());
        FirebaseFirestore.getInstance().collection("quizRequests").add(req);
    }

    private void ensureAnonAuth() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            auth.signInAnonymously()
                    .addOnSuccessListener(r -> { /* ok */ })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Anonymous sign-in failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void configureSession() {
        if (session == null) return;
        Config config = session.getConfig();
        config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
        config.setGeospatialMode(Config.GeospatialMode.ENABLED);
        config.setStreetscapeGeometryMode(Config.StreetscapeGeometryMode.DISABLED);

        // Cloud Anchors enabled (we host indoors)
        config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);

        // Instant Placement OFF by default (authoring prefers real depth/planes)
        config.setInstantPlacementMode(
                instantPlacementSettings.isInstantPlacementEnabled()
                        ? Config.InstantPlacementMode.LOCAL_Y_UP
                        : Config.InstantPlacementMode.DISABLED);

        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
        } else {
            config.setDepthMode(Config.DepthMode.DISABLED);
        }

        try { config.setFocusMode(Config.FocusMode.AUTO); } catch (Throwable ignore) {}
        try { config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE); } catch (Throwable ignore) {}

        session.configure(config);
    }

    /** Associates an Anchor with the Trackable it came from (for small Y-lift on non-surface anchors). */
    class WrappedAnchor {
        private final Anchor anchor;
        private final Trackable trackable;

        WrappedAnchor(Anchor anchor, Trackable trackable) {
            this.anchor = anchor;
            this.trackable = trackable;
        }
        Anchor getAnchor() { return anchor; }
        Trackable getTrackable() { return trackable; }
    }
}
