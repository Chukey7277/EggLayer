package com.google.ar.core.examples.java.helloar;

import android.Manifest;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.Image;
import java.net.HttpURLConnection;
import android.net.Uri;
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
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HelloAR egg layer
 * - INDOOR: we ONLY host the Cloud Anchor when the user presses Save.
 * - OUTDOOR: convert to Earth anchor (accuracy-gated).
 * - Quiz generation is commented out for now (do not delete).
 */
public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

    private static final String TAG = HelloArActivity.class.getSimpleName();

    private static final float Z_NEAR = 0.3f;
    private static final float Z_FAR  = 100f;

    private static final int CUBEMAP_RESOLUTION = 16;
    private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

    private enum Env { OUTDOOR, INDOOR }
    private static final String PREFS = "helloar_prefs";
    private static final String KEY_ENV = "env_mode";

    private static final int REQ_FINE_LOCATION = 101;

    private static final double H_ACC_OUTDOOR = 8.0;
    private static final double V_ACC_OUTDOOR = 6.0;
    private static final double HEAD_ACC_OUTDOOR = 25.0;

    // Require a short continuous window of good accuracy before accepting OUTDOOR placement
    private static final long LOCALIZE_STABLE_MS = 1500L;
    private long lastAccOkayAtMs = 0L;

    private static final double H_ACC_INDOOR = 80.0;   // looser; not used to block hosting
    private static final double V_ACC_INDOOR = 60.0;
    private static final double HEAD_ACC_INDOOR = 60.0;

    private static final int CLOUD_TTL_DAYS = 1;
    private static final String EGGS = "eggs";

    // ARCore & rendering
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

    // Model
    private Mesh   virtualObjectMesh;
    private Shader virtualObjectShader;
    private Texture virtualObjectAlbedoTexture;
    private Shader unlitShader;
    private Mesh[]  eggMeshes;
    private Shader[] eggShaders;

    // IBL
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

    // Anchors
    private final List<WrappedAnchor> wrappedAnchors = new ArrayList<>();
    @Nullable private Anchor currentPlacedAnchor = null;

    // Geo cache
    @Nullable private GeospatialPose lastGoodGeoPose = null;

    private EggRepository eggRepo;

    // Environment state
    private Env envMode = Env.OUTDOOR;
    private SharedPreferences prefs;

    // settings popup ids
    private static final int GROUP_ENV = 2000;
    private static final int MENU_ENV_HEADER = 2001;
    private static final int MENU_ENV_INDOOR = 2002;
    private static final int MENU_ENV_OUTDOOR = 2003;
    private static final int MENU_DIVIDER    = 2004;

    // Cloud hosting state (INDOOR)
    private Anchor localAnchorForHosting;   // will be set on tap; hosted on Save
    private Anchor hostedCloudAnchor;       // returned by ARCore while hosting
    private String hostedCloudId;
    private String pendingEggDocId;
    private enum HostState { IDLE, HOSTING, SUCCESS, ERROR }
    private HostState hostState = HostState.IDLE;

    // ---- tiny visual stabilizer for drawn anchors (translation only)
    private static final float SMOOTHING_ALPHA = 0.65f; // 0=no smoothing, 1=freeze
    private final Map<Anchor, float[]> lastStableT = new HashMap<>();

    private static float distance(Pose a, Pose b) {
        float dx = a.tx() - b.tx(), dy = a.ty() - b.ty(), dz = a.tz() - b.tz();
        return (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    private double hGate()  { return envMode == Env.INDOOR ? H_ACC_INDOOR  : H_ACC_OUTDOOR; }
    private double vGate()  { return envMode == Env.INDOOR ? V_ACC_INDOOR  : V_ACC_OUTDOOR; }
    private double headGate(){ return envMode == Env.INDOOR ? HEAD_ACC_INDOOR : HEAD_ACC_OUTDOOR; }

    // --- Coach / scanning hints ---
    private boolean placementModeActive = false;
    private long coachLastHintMs = 0L;
    private static final long COACH_HINT_MIN_INTERVAL_MS = 2000L; // throttle UI hints
    private static final String KEY_SHOW_COACH = "show_coach_placement";

    private Pose lastHitPose;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureAnonAuthThen(this::testFirestoreWrite);
        testResolveFromApp();

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

        depthSettings.onCreate(this);
        instantPlacementSettings.onCreate(this);
        depthSettings.setUseDepthForOcclusion(true);
        depthSettings.setDepthColorVisualizationEnabled(false);
        instantPlacementSettings.setInstantPlacementEnabled(false);

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
            menu.add(GROUP_ENV, MENU_ENV_INDOOR,  Menu.CATEGORY_SYSTEM + 1, "Indoor (room/classroom)").setCheckable(true);
            menu.add(GROUP_ENV, MENU_ENV_OUTDOOR, Menu.CATEGORY_SYSTEM + 2, "Outdoor (streets/open)").setCheckable(true);
            menu.setGroupCheckable(GROUP_ENV, true, true);
            if (envMode == Env.INDOOR) menu.findItem(MENU_ENV_INDOOR).setChecked(true);
            else                       menu.findItem(MENU_ENV_OUTDOOR).setChecked(true);
            menu.add(Menu.NONE, MENU_DIVIDER, Menu.CATEGORY_SYSTEM + 3, "────────────").setEnabled(false);

            popup.inflate(R.menu.settings_menu);
            popup.show();
        });

        eggRepo = new EggRepository(getApplicationContext());
    }

    /** XML button hook: android:onClick="onStartPlacingEggsClicked" */
    public void onStartPlacingEggsClicked(View v) {
        placementModeActive = true;
        showPlacementCoachDialog();
        messageSnackbarHelper.showMessage(this,
                "Scanning surfaces… move your phone slowly and wait for the grid/points to appear.");
    }

    private void showPlacementCoachDialog() {
        boolean show = (prefs != null) ? prefs.getBoolean(KEY_SHOW_COACH, true) : true;
        if (!show) return;

        new AlertDialog.Builder(this)
                .setTitle("Start placing eggs")
                .setMessage(
                        "Move your phone slowly (C-shaped sweep) so AR can detect surfaces.\n\n" +
                                "Tips:\n• Aim at well-lit, textured areas (floors, tables, walls, trees, buildings).\n" +
                                "• Keep ~0.5–2 m from the surface.\n" +
                                "• Wait for the grid/mesh, then tap to place the egg.")
                .setPositiveButton("Got it", null)
                .setNeutralButton("Don’t show again", (d, w) -> {
                    if (prefs != null) prefs.edit().putBoolean(KEY_SHOW_COACH, false).apply();
                })
                .show();
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
            Toast.makeText(this,"You're already using " + (newMode == Env.INDOOR ? "Indoor" : "Outdoor") + " mode.",Toast.LENGTH_SHORT).show();
            return;
        }
        envMode = newMode;
        prefs.edit().putString(KEY_ENV, envMode.name()).apply();
        if (session != null) configureSession();
        String msg = (envMode == Env.INDOOR)
                ? "Environment set to INDOOR.\nCloud Anchor will host only after you press Save."
                : "Environment set to OUTDOOR.\nPlacement converts to a Geospatial anchor.";
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
                configureSession();
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
        lastAccOkayAtMs = 0L;
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

        if (camera.getTrackingState() == TrackingState.TRACKING
                && (depthSettings.useDepthForOcclusion() || depthSettings.depthColorVisualizationEnabled())) {
            try (Image depthImage = frame.acquireDepthImage16Bits()) {
                backgroundRenderer.updateCameraDepthTexture(depthImage);
            } catch (Throwable ignore) {}
        }

        try { handleTap(frame, camera); } catch (Throwable t) { Log.w(TAG, "handleTap failed", t); }

        try { if (frame.getTimestamp() != 0) backgroundRenderer.drawBackground(render); } catch (Throwable t) { Log.e(TAG, "drawBackground failed", t); return; }

        if (camera.getTrackingState() != TrackingState.TRACKING) return;

        // Live hints while scanning
        if (placementModeActive) maybeShowScanHints(frame, camera);

        try {
            camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);
            camera.getViewMatrix(viewMatrix, 0);
        } catch (Throwable t) { Log.e(TAG, "Failed to get camera matrices", t); return; }

        try {
            if (planeRenderer != null) {
                planeRenderer.drawPlanes(render, session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projectionMatrix);
            }
        } catch (Throwable t) { Log.w(TAG, "Plane rendering failed", t); }

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

            // --------- Pose smoothing (translation only) ----------
            Pose p = a.getPose();
            float[] t = p.getTranslation();
            float[] last = lastStableT.get(a);
            if (last != null && SMOOTHING_ALPHA < 1f) {
                for (int i = 0; i < 3; i++) t[i] = SMOOTHING_ALPHA * last[i] + (1 - SMOOTHING_ALPHA) * t[i];
            }
            lastStableT.put(a, t.clone());
            Pose pSmooth = new Pose(t, new float[]{p.qx(), p.qy(), p.qz(), p.qw()});
            pSmooth.toMatrix(modelMatrix, 0);
            // -----------------------------------------------------

            Trackable tr = wrapped.getTrackable();
            float liftY = (tr instanceof Plane || tr instanceof Point) ? 0.0f : 0.02f;
            Matrix.translateM(modelMatrix, 0, 0f, liftY, 0f);

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

        // Cloud Anchor state machine (only runs after Save triggers hosting)
        if (envMode == Env.INDOOR && hostState == HostState.HOSTING && hostedCloudAnchor != null) {
            Anchor.CloudAnchorState st = hostedCloudAnchor.getCloudAnchorState();
            Log.i(TAG, "CloudAnchor state: " + st);
            if (st.isError()) {
                hostState = HostState.ERROR;
                if (pendingEggDocId != null) {
                    FirebaseFirestore.getInstance().collection(EGGS).document(pendingEggDocId)
                            .update("cloudStatus", "ERROR",
                                    "cloudError", st.toString())
                            .addOnFailureListener(e -> Log.w(TAG, "Failed to mark cloud error", e));
                }
                runOnUiThread(() -> Toast.makeText(this,"Cloud Anchor hosting failed: " + st, Toast.LENGTH_LONG).show());
            } else if (st == Anchor.CloudAnchorState.SUCCESS) {
                hostState = HostState.SUCCESS;
                hostedCloudId = hostedCloudAnchor.getCloudAnchorId();
                Log.i(TAG, "CloudAnchor ID: " + hostedCloudId);
                if (pendingEggDocId != null && hostedCloudId != null) {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put("anchorType", "CLOUD");
                    patch.put("cloudStatus", "SUCCESS");
                    patch.put("cloudId", hostedCloudId);
                    patch.put("cloudHostedAt", FieldValue.serverTimestamp());
                    patch.put("cloudTtlDays", CLOUD_TTL_DAYS);

                    FirebaseFirestore.getInstance()
                            .collection(EGGS)
                            .document(pendingEggDocId)
                            .update(patch)
                            .addOnSuccessListener(v -> runOnUiThread(() ->
                                    Toast.makeText(this, "Anchor hosted ✔", Toast.LENGTH_SHORT).show()))
                            .addOnFailureListener(e -> Log.e(TAG, "Failed to patch egg with cloudId", e));
                }
            }
        }

        // HUD
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
            if (currentAnchor != null) anchorLine = "Anchor: " + currentAnchor.getTrackingState().name()
                    + (envMode == Env.OUTDOOR ? " (GEOSPATIAL)" :
                    (hostState == HostState.HOSTING ? " (HOSTING…)" :
                            (hostState == HostState.SUCCESS ? " (CLOUD✔)" : "")));

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

    // Handle tap — DO NOT start hosting here; wait for Save
    private void handleTap(Frame frame, Camera camera) {
        MotionEvent tap = tapHelper.poll();
        if (tap == null) return;

        if (camera.getTrackingState() != TrackingState.TRACKING) {
            messageSnackbarHelper.showMessage(this, "Move slowly and point at well-lit, textured surfaces.");
            return;
        }

        // ---- OUTDOOR: require a brief continuous window of good accuracy before placing
        if (envMode == Env.OUTDOOR) {
            Earth earth = session.getEarth();
            if (earth == null || earth.getTrackingState() != TrackingState.TRACKING) {
                lastAccOkayAtMs = 0L;
                messageSnackbarHelper.showMessage(this, "AR Earth not tracking yet. Move your phone slowly.");
                return;
            }
            try {
                GeospatialPose camGp = earth.getCameraGeospatialPose();
                if (camGp == null) {
                    lastAccOkayAtMs = 0L;
                    messageSnackbarHelper.showMessage(this, "Getting location… try again in a moment.");
                    return;
                }
                double h = camGp.getHorizontalAccuracy();
                double v = camGp.getVerticalAccuracy();
                boolean good = (!Double.isNaN(h) && h <= hGate()) && (!Double.isNaN(v) && v <= vGate());
                long now = System.currentTimeMillis();
                if (!good) {
                    lastAccOkayAtMs = 0L;
                    messageSnackbarHelper.showMessage(this, "Waiting for better location accuracy… move around and try again.");
                    return;
                }
                if (lastAccOkayAtMs == 0L) {
                    lastAccOkayAtMs = now;
                    messageSnackbarHelper.showMessage(this, "Accuracy looks good—hold steady…");
                    return;
                }
                if (now - lastAccOkayAtMs < LOCALIZE_STABLE_MS) {
                    messageSnackbarHelper.showMessage(this, "Locking location… keep device steady.");
                    return;
                }
            } catch (Throwable ignore) {}
        }

        // One anchor at a time
        if (!wrappedAnchors.isEmpty()) {
            try {
                Anchor old = wrappedAnchors.get(0).getAnchor();
                if (old != null) {
                    old.detach();
                    lastStableT.remove(old);
                }
            } catch (Throwable ignore) {}
            wrappedAnchors.clear();
        }

        final Pose camPoseNow = camera.getPose();

        // Loosened: allow farther hits & accept more surfaces
        final float MAX_HIT_M = 15.0f;
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
            boolean isPointAny = (t instanceof Point);
            boolean isPlane = (t instanceof Plane)
                    && ((Plane) t).isPoseInPolygon(hit.getHitPose())
                    && PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camPoseNow) > 0;
            boolean isGeom = (t instanceof StreetscapeGeometry);

            if (isDepth) depths.add(hit);
            else if (isGeom) geoms.add(hit);
            else if (isPlane) planes.add(hit);
            else if (isPointAny) points.add(hit);
        }

        Comparator<HitResult> byDist = (a, b) ->
                Float.compare(distance(a.getHitPose(), camPoseNow), distance(b.getHitPose(), camPoseNow));
        Collections.sort(depths, byDist);
        Collections.sort(points, byDist);
        Collections.sort(planes, byDist);
        Collections.sort(geoms, byDist);

        boolean isIndoor = (envMode == Env.INDOOR);

        // Outdoor visual preference: Depth (trees etc.) → StreetscapeGeometry (buildings) → Plane → Point
        HitResult chosenVisual = (envMode == Env.OUTDOOR)
                ? (!depths.isEmpty() ? depths.get(0)
                : !geoms.isEmpty() ? geoms.get(0)
                : !planes.isEmpty() ? planes.get(0)
                : !points.isEmpty() ? points.get(0) : null)
                : (!depths.isEmpty() ? depths.get(0)
                : !points.isEmpty() ? points.get(0)
                : !planes.isEmpty() ? planes.get(0)
                : !geoms.isEmpty()  ? geoms.get(0) : null);

        // Hostable candidate saved for later (on Save)
        HitResult hostable = isIndoor
                ? (!planes.isEmpty() ? planes.get(0) : (!points.isEmpty() ? points.get(0) : null))
                : null;

        if (chosenVisual == null) {
            messageSnackbarHelper.showMessage(this, "Looking for surface… move a bit closer with steady hands.");
            return;
        }

        final Anchor visualAnchor = chosenVisual.createAnchor();
        currentPlacedAnchor = visualAnchor;
        wrappedAnchors.add(new WrappedAnchor(visualAnchor, chosenVisual.getTrackable()));

        // Stop hints after first placement
        placementModeActive = false;

        // Prepare the hostable (but DO NOT start hosting yet)
        localAnchorForHosting = null;
        hostState = HostState.IDLE;
        if (isIndoor && hostable != null) {
            localAnchorForHosting = (hostable == chosenVisual) ? visualAnchor : hostable.createAnchor();
            runOnUiThread(() -> Toast.makeText(
                    HelloArActivity.this,
                    "Ready to host. Fill details and press Save to upload Cloud Anchor.",
                    Toast.LENGTH_SHORT).show());
        }

        // Optional geo snapshot for HUD
        GeospatialPose anchorGp = null, camGp = null;
        Earth earth = null;
        try { earth = session.getEarth(); } catch (Throwable ignore) {}
        if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
            try { anchorGp = earth.getGeospatialPose(visualAnchor.getPose()); } catch (Throwable ignore) {}
            try { camGp    = earth.getCameraGeospatialPose(); } catch (Throwable ignore) {}
        }

        // OUTDOOR → convert to Earth anchor (persistent lat/lng/alt)
        if (envMode == Env.OUTDOOR && earth != null && anchorGp != null) {
            boolean hOk = !Double.isNaN(anchorGp.getHorizontalAccuracy()) && anchorGp.getHorizontalAccuracy() <= hGate();
            boolean vOk = !Double.isNaN(anchorGp.getVerticalAccuracy())   && anchorGp.getVerticalAccuracy()   <= vGate();
            if (hOk && vOk) {
                double headingDeg = goodHeadingDeg(camGp, lastGoodGeoPose, headGate());
                float[] q = yawToQuaternion((float) headingDeg);

                try {
                    Anchor earthAnchor = earth.createAnchor(
                            anchorGp.getLatitude(),
                            anchorGp.getLongitude(),
                            anchorGp.getAltitude(), // this preserves tree/building top height if VPS supported
                            q[0], q[1], q[2], q[3]);

                    try { visualAnchor.detach(); } catch (Throwable ignore) {}
                    lastStableT.remove(visualAnchor);
                    wrappedAnchors.clear();
                    wrappedAnchors.add(new WrappedAnchor(earthAnchor, null));
                    currentPlacedAnchor = earthAnchor;
                    localAnchorForHosting = null;
                    hostState = HostState.IDLE;

                    Log.d(TAG, "Converted to GEOSPATIAL anchor @ lat=" +
                            anchorGp.getLatitude() + " lng=" + anchorGp.getLongitude() + " alt=" + anchorGp.getAltitude());
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to create Earth anchor; keeping local anchor.", t);
                }
            } else {
                messageSnackbarHelper.showMessage(this,
                        "Need better accuracy before saving geospatial position. Scan more around the object.");
            }
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

        // Open sheet
        EggCardSheet sheet = new EggCardSheet();
        if (anchorGp != null) {
            double headingDeg = goodHeadingDeg(camGp, lastGoodGeoPose, headGate());
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
            public void onSave(String title, String description,
                               List<Uri> photoUris, @Nullable Uri audioUri,
                               @Nullable EggCardSheet.GeoPoseSnapshot geoSnapshot) {

                // For OUTDOOR we keep the accuracy gate; INDOOR has no accuracy block
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
                    headingNow = goodHeadingDeg(camNow, lastGoodGeoPose, headGate());
                } catch (Throwable ignore) {}

                EggEntry e = new EggEntry();
                e.title = (title == null) ? "" : title;
                e.description = (description == null) ? "" : description;
                e.quiz = null; // keep field but no generation
                e.heading = headingNow;

                // Ensure the egg doc has the creator's UID
                FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                if (user != null) {
                    e.userId = user.getUid();
                }

                // Persist geospatial snapshot; only store alt if precise, else viewer will terrain-resolve it.
                if (finalGp != null) {
                    e.geo      = new com.google.firebase.firestore.GeoPoint(finalGp.getLatitude(), finalGp.getLongitude());
                    e.horizAcc = finalGp.getHorizontalAccuracy();

                    double vAcc = finalGp.getVerticalAccuracy();
                    e.vertAcc   = Double.isNaN(vAcc) ? null : vAcc;

                    e.alt      = finalGp.getAltitude();
                }

                Trackable firstTrackable = (!wrappedAnchors.isEmpty()) ? wrappedAnchors.get(0).getTrackable() : null;
                e.placementType = (firstTrackable != null) ? firstTrackable.getClass().getSimpleName() : "Earth";
                e.distanceFromCamera = (currentPlacedAnchor != null) ? distance(currentPlacedAnchor.getPose(), camera.getPose()) : 0f;
                e.anchorType = (envMode == Env.OUTDOOR) ? "GEO" : "CLOUD";

                // 1) Create egg
                eggRepo.createDraft(e)
                        .addOnSuccessListener(docRef -> {

                            Runnable continueFlow = () -> {
                                // 2) Upload media (now that server sees userId)
                                eggRepo.uploadMediaAndPatch(docRef, photoUris, audioUri)
                                        .addOnSuccessListener(v ->
                                                Toast.makeText(HelloArActivity.this, "Media uploaded ✓", Toast.LENGTH_SHORT).show())
                                        .addOnFailureListener(err -> {
                                            Log.e(TAG, "Media upload failed", err);
                                            Toast.makeText(HelloArActivity.this, "Upload failed: " + err.getMessage(),
                                                    Toast.LENGTH_LONG).show();
                                        });

                                // 3) QUIZ (optional — kept but not used)
                                enqueueQuizGenerationOnEggDoc(docRef.getId(), e.title, e.description);

                                // 4) Start Cloud hosting ONLY NOW (after Save) in INDOOR
                                if (envMode == Env.INDOOR) {
                                    if (localAnchorForHosting == null) {
                                        Toast.makeText(HelloArActivity.this,
                                                "No hostable surface selected. Tap a plane or point and Save again.",
                                                Toast.LENGTH_LONG).show();
                                        FirebaseFirestore.getInstance().collection(EGGS).document(docRef.getId())
                                                .update("cloudStatus", "NO_HOSTABLE_ANCHOR");
                                        return;
                                    }
                                    pendingEggDocId = docRef.getId();
                                    FirebaseFirestore.getInstance().collection(EGGS).document(docRef.getId())
                                            .update("cloudStatus", "HOSTING", "cloudTtlDays", CLOUD_TTL_DAYS);

                                    startHostingCloudAnchor(localAnchorForHosting, pendingEggDocId);
                                }
                            };

                            // Ensure doc is visible on server before uploading Storage
                            FirebaseFirestore.getInstance()
                                    .collection(EGGS).document(docRef.getId())
                                    .get(com.google.firebase.firestore.Source.SERVER)
                                    .addOnSuccessListener(snap -> {
                                        Log.d(TAG, "Server sees userId=" + snap.getString("userId")
                                                + " authUid=" + FirebaseAuth.getInstance().getUid());
                                        continueFlow.run();
                                    })
                                    .addOnFailureListener(err -> {
                                        Log.w(TAG, "Server read after create failed; proceeding anyway", err);
                                        continueFlow.run();
                                    });
                        })
                        .addOnFailureListener(err -> Log.e(TAG, "Egg save failed", err));
            }

            @Override public void onCancel() {}
        });

        if (!isFinishing() && !isDestroyed()) sheet.show(getSupportFragmentManager(), "EggCardSheet");
    }

    // Hosting only triggered from Save
    private void startHostingCloudAnchor(Anchor local, @Nullable String eggDocIdToPatch) {
        if (local == null || session == null) return;

        pendingEggDocId = eggDocIdToPatch;
        hostState = HostState.HOSTING;

        try {
            Anchor result = null;
            try {
                Method m = Session.class.getMethod("hostCloudAnchor", Anchor.class, int.class);
                result = (Anchor) m.invoke(session, local, CLOUD_TTL_DAYS);
                Log.i(TAG, "Hosting via hostCloudAnchor(Anchor,int) TTL=" + CLOUD_TTL_DAYS);
            } catch (NoSuchMethodException ignore1) {
                try {
                    Method m2 = Session.class.getMethod("hostCloudAnchorWithTtl", Anchor.class, int.class);
                    result = (Anchor) m2.invoke(session, local, CLOUD_TTL_DAYS);
                    Log.i(TAG, "Hosting via hostCloudAnchorWithTtl TTL=" + CLOUD_TTL_DAYS);
                } catch (NoSuchMethodException ignore2) {
                    result = session.hostCloudAnchor(local);
                    Log.w(TAG, "Hosting without TTL overload (legacy ARCore).");
                }
            }

            hostedCloudAnchor = result;
            runOnUiThread(() ->
                    Toast.makeText(this, "Hosting anchor… (~" + CLOUD_TTL_DAYS + "d)", Toast.LENGTH_SHORT).show());

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

    // QUIZ DISABLED (commented, not deleted)
    private void enqueueQuizGenerationOnEggDoc(String eggDocId, String title, String description) {
        Map<String, Object> quizReq = new HashMap<>();
        quizReq.put("status", "pending");
        quizReq.put("source", "description");
        quizReq.put("title",  title == null ? "" : title);          // optional convenience copy
        quizReq.put("description", description == null ? "" : description);
        quizReq.put("model", "gemini-2.5-flash");                    // keep in sync with CF
        quizReq.put("uid", FirebaseAuth.getInstance().getUid());
        quizReq.put("createdAt", FieldValue.serverTimestamp());

        // Prefer merge so this works whether the doc already exists or not.
        Map<String, Object> payload = new HashMap<>();
        payload.put("quizRequest", quizReq);

        FirebaseFirestore.getInstance()
                .collection(EGGS)                 // make sure EGGS == "eggs" (matches your CF trigger)
                .document(eggDocId)
                .set(payload, SetOptions.merge())
                .addOnSuccessListener(v -> Log.d(TAG, "Quiz request queued inside egg doc."))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to queue quiz request.", e));
    }

    private void ensureAnonAuthThen(Runnable onReady) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() != null) {
            onReady.run();
            return;
        }
        auth.signInAnonymously()
                .addOnSuccessListener(r -> onReady.run())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Anonymous sign-in failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
    }

    private void configureSession() {
        if (session == null) return;
        Config config = session.getConfig();
        config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
        config.setGeospatialMode(Config.GeospatialMode.ENABLED);

        // Outdoor: enable Streetscape Geometry to place on buildings where supported
        try {
            if (envMode == Env.OUTDOOR) {
                config.setStreetscapeGeometryMode(Config.StreetscapeGeometryMode.ENABLED);
            } else {
                config.setStreetscapeGeometryMode(Config.StreetscapeGeometryMode.DISABLED);
            }
        } catch (Throwable ignore) {}

        // Plane detection generous to ease hosting/placement
        try { config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL); } catch (Throwable ignore) {}

        config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);

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

    private void testResolveFromApp() {
        new Thread(() -> {
            try {
                InetAddress[] addrs = InetAddress.getAllByName("firestore.googleapis.com");
                StringBuilder sb = new StringBuilder("Resolved addresses:");
                for (InetAddress a : addrs) sb.append("\n  ").append(a.getHostAddress());
                Log.i("NetTest", sb.toString());
            } catch (Throwable t) {
                Log.e("NetTest", "Resolution failed", t);
            }

            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://firestore.googleapis.com");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                Log.i("NetTest", "HTTP GET returned code: " + code);
            } catch (Throwable t) {
                Log.e("NetTest", "HTTP reachability failed", t);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void testFirestoreWrite() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Map<String, Object> doc = new HashMap<>();
        doc.put("debug_ts", System.currentTimeMillis());
        doc.put("device", android.os.Build.MODEL);
        db.collection("debug_checks").document("last_ping")
                .set(doc)
                .addOnSuccessListener(aVoid -> Log.i("FSDebug", "Firestore write OK"))
                .addOnFailureListener(e -> {
                    Log.e("FSDebug", "Firestore write FAILED", e);
                    runOnUiThread(() ->
                            Toast.makeText(this, "Firestore write failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show()
                    );
                });
    }

    private static float[] yawToQuaternion(float yawDeg) {
        float r = (float) Math.toRadians(yawDeg);
        float s = (float) Math.sin(r * 0.5f), c = (float) Math.cos(r * 0.5f);
        return new float[]{0f, s, 0f, c};
    }

    private static double goodHeadingDeg(@Nullable GeospatialPose camPose, @Nullable GeospatialPose lastGood, double gateDeg) {
        if (camPose != null && !Double.isNaN(camPose.getHeading()) && camPose.getHeadingAccuracy() <= gateDeg) {
            return camPose.getHeading();
        }
        if (lastGood != null && !Double.isNaN(lastGood.getHeading())) return lastGood.getHeading();
        return 0.0;
    }

    private void maybeShowScanHints(Frame frame, Camera camera) {
        long now = System.currentTimeMillis();
        if (now - coachLastHintMs < COACH_HINT_MIN_INTERVAL_MS) return;

        int trackedPlanes = 0;
        for (Plane p : session.getAllTrackables(Plane.class)) {
            if (p.getTrackingState() == TrackingState.TRACKING && p.getSubsumedBy() == null) {
                trackedPlanes++;
            }
        }

        if (trackedPlanes == 0) {
            messageSnackbarHelper.showMessage(this,
                    "Still looking for surfaces… move slowly; aim at textured, well-lit areas.");
        } else {
            messageSnackbarHelper.showMessage(this,
                    "Surface found ✓ — tap on the grid/points to place an egg.");
        }
        coachLastHintMs = now;
    }

    class WrappedAnchor {
        private final Anchor anchor;
        @Nullable private final Trackable trackable;

        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable) {
            this.anchor = anchor;
            this.trackable = trackable;
        }
        Anchor getAnchor() { return anchor; }
        @Nullable Trackable getTrackable() { return trackable; }
    }
}
