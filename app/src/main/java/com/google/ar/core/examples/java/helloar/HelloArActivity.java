/* full file: com/google/ar/core/examples/java/helloar/HelloArActivity.java */
package com.google.ar.core.examples.java.helloar;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.Image;
import android.net.Uri;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
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
import com.google.ar.core.InstantPlacementPoint;
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
import com.google.ar.core.examples.java.helloar.ui.CenterStatusDialogFragment;
import com.google.ar.core.examples.java.helloar.ui.EggCardSheet;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SetOptions;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

    private static final String TAG = HelloArActivity.class.getSimpleName();

    // Tighter, more stable depth range.
    private static final float Z_NEAR = 0.10f;
    private static final float Z_FAR  = 80f;

    private static final int CUBEMAP_RESOLUTION = 16;
    private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

    private enum Env { OUTDOOR, INDOOR }
    private static final String PREFS = "helloar_prefs";
    private static final String KEY_ENV = "env_mode";

    private static final int REQ_FINE_LOCATION = 101;

    private static final double H_ACC_OUTDOOR = 8.0;
    private static final double V_ACC_OUTDOOR = 6.0;
    private static final double HEAD_ACC_OUTDOOR = 15.0;

    private static final long LOCALIZE_STABLE_MS = 900L;
    private long lastAccOkayAtMs = 0L;

    private static final double H_ACC_INDOOR = 80.0;
    private static final double V_ACC_INDOOR = 60.0;
    private static final double HEAD_ACC_INDOOR = 60.0;

    private static final int CLOUD_TTL_DAYS = 365;
    private static final String EGGS = "eggs";

    private static final int NEARBY_RADIUS_M = 150;
    private static final double CELL_DEG = 0.01;
    private static final int FIRESTORE_FETCH_LIMIT = 120;
    private boolean previousEggsLoadedOnce = false;
    private long lastPrevLoadAtMs = 0L;
    private static final long PREV_RELOAD_MS = 60_000L;
    private final Set<String> mountedPrevDocIds = new HashSet<>();

    private static final boolean ENABLE_QUIZ = false;

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

    // Kept the class around, but we’ll force-disable it everywhere.
    private final InstantPlacementSettings instantPlacementSettings = new InstantPlacementSettings();
    private boolean[] instantPlacementSettingsMenuDialogCheckboxes = new boolean[1];

    private VertexBuffer pointCloudVertexBuffer;
    private Mesh pointCloudMesh;
    private Shader pointCloudShader;
    private long lastPointCloudTimestamp = 0;

    private Mesh   virtualObjectMesh;
    private Shader virtualObjectShader;
    private Texture virtualObjectAlbedoTexture;
    private Shader unlitShader;
    private Mesh[]  eggMeshes;
    private Shader[] eggShaders;

    private Texture dfgTexture;
    private SpecularCubemapFilter cubemapFilter;

    private final float[] modelMatrix               = new float[16];
    private final float[] viewMatrix                = new float[16];
    private final float[] projectionMatrix          = new float[16];
    private final float[] modelViewMatrix           = new float[16];
    private final float[] modelViewProjectionMatrix = new float[16];

    private boolean sessionPaused = false;
    private boolean backgroundReady = false;

    private TextView poseInfoCard;
    private TextView tvLatLng, tvLatLngAcc, tvAlt, tvAltAcc, tvHeading, tvHeadingAcc, tvAnchorState, tvEarthState;

    private final List<WrappedAnchor> wrappedAnchors = new CopyOnWriteArrayList<>();
    private final List<WrappedAnchor> prevAnchors    = new CopyOnWriteArrayList<>();
    @Nullable private Anchor currentPlacedAnchor = null;

    @Nullable private GeospatialPose lastGoodGeoPose = null;

    private EggRepository eggRepo;

    private Env envMode = Env.OUTDOOR; // default mode (settings removed)
    private SharedPreferences prefs;

    private static final int GROUP_ENV = 2000;
    private static final int MENU_ENV_HEADER = 2001;
    private static final int MENU_ENV_INDOOR = 2002;
    private static final int MENU_ENV_OUTDOOR = 2003;
    private static final int MENU_DIVIDER    = 2004;

    private Anchor localAnchorForHosting;   // set for BOTH indoor and outdoor now
    private Anchor hostedCloudAnchor;
    private String hostedCloudId;
    private String pendingEggDocId;
    private enum HostState { IDLE, HOSTING, SUCCESS, ERROR }
    private HostState hostState = HostState.IDLE;

    private static final float SMOOTHING_ALPHA = 0.65f;
    private final Map<Anchor, float[]> lastStableT = new HashMap<>();

    private Pose   lastHitPose;
    private String lastHitSurfaceType = "UNKNOWN";

    private CenterStatusDialogFragment statusDlg;

    private boolean readyPromptShown = false;
    private boolean placementModeActive = false;
    private long coachLastHintMs = 0L;
    private static final long COACH_HINT_MIN_INTERVAL_MS = 2000L;
    private static final String KEY_SHOW_COACH = "show_coach_placement";

    // Pinch-to-zoom (camera) and ray distance.
    private ScaleGestureDetector scaleDetector;
    private float zoomFactor = 1.0f;
    private static final float ZOOM_MIN = 1.0f, ZOOM_MAX = 4.0f;

    // ===== Ray placement state (pinch-to-push + buttons) =====
    private boolean rayAdjustActive = false;
    @Nullable private Ray activeRay = null;
    private float rayDistanceM = 60f;
    private static final float RAY_MIN = 0.5f, RAY_MAX = 120f;
    private static final float RAY_STEP = 0.5f; // per-button nudge

    // Top-left UI controls for ray mode
    private View rayControlsContainer;
    private Button btnRayMinus, btnRayPlus;
    // =========================================================

    // Global (legacy) suppression window (kept) + per-anchor windows.
    private long noOccUntilMs = 0L;

    private static float distance(Pose a, Pose b) {
        float dx = a.tx() - b.tx(), dy = a.ty() - b.ty(), dz = a.tz() - b.tz();
        return (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    private double hGate()   { return envMode == Env.INDOOR ? H_ACC_INDOOR  : H_ACC_OUTDOOR; }
    private double vGate()   { return envMode == Env.INDOOR ? V_ACC_INDOOR  : V_ACC_OUTDOOR; }
    private double headGate(){ return envMode == Env.INDOOR ? HEAD_ACC_INDOOR : HEAD_ACC_OUTDOOR; }

    private static final double MIN_LOAD_METERS = 0.8;

    private boolean inMetadataFlow = false;

    // === Textures ===
    private Texture[] starTextures;
    private int currentTexIndex = 0;
    private final String[] TEX_FILES = {
            "models/Image_0.png",
            "models/Image_1.png",
            "models/Image_2.png"
    };
    private final java.util.Random rng = new java.util.Random();

    private void saveHeightAboveTerrain(
            Earth earth,
            double lat,
            double lng,
            double hitAlt,
            String eggId
    ) {
        earth.resolveAnchorOnTerrainAsync(
                lat, lng, (float) hitAlt, 0, 0, 0, 1,
                (terrainAnchor, state) -> {
                    try {
                        if (state == Anchor.TerrainAnchorState.SUCCESS) {
                            GeospatialPose tPose = earth.getGeospatialPose(terrainAnchor.getPose());
                            if (tPose != null) {
                                double terrainAlt = tPose.getAltitude();
                                double hat = hitAlt - terrainAlt;

                                Map<String, Object> m = new HashMap<>();
                                m.put("heightAboveTerrain", hat);

                                FirebaseFirestore.getInstance()
                                        .collection(EGGS).document(eggId)
                                        .set(m, SetOptions.merge());

                                Log.d(TAG, "Saved heightAboveTerrain=" + hat);
                            }
                        } else {
                            Log.w(TAG, "Terrain resolve " + state + " — skipping hat.");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "hat patch failed", t);
                    } finally {
                        if (terrainAnchor != null) {
                            try { terrainAnchor.detach(); } catch (Throwable ignore) {}
                        }
                    }
                });
    }

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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);


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

        // NEW: hook up the ray controls (they may be null if XML not added yet)
        rayControlsContainer = findViewById(R.id.rayControlsContainer);
        btnRayMinus = findViewById(R.id.btnRayMinus);
        btnRayPlus  = findViewById(R.id.btnRayPlus);
        if (btnRayMinus != null) {
            btnRayMinus.setOnClickListener(v -> nudgeRayDistance(-RAY_STEP));
            btnRayMinus.setOnLongClickListener(v -> { nudgeRayDistance(-5f); return true; });
        }
        if (btnRayPlus != null) {
            btnRayPlus.setOnClickListener(v -> nudgeRayDistance(+RAY_STEP));
            btnRayPlus.setOnLongClickListener(v -> { nudgeRayDistance(+5f); return true; });
        }
        setRayControlsVisible(false);

        surfaceView.setEGLContextClientVersion(3);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);

        tapHelper = new TapHelper(this);

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float s = detector.getScaleFactor();

                if (rayAdjustActive && activeRay != null) {
                    rayDistanceM *= s;
                    if (rayDistanceM < RAY_MIN) rayDistanceM = RAY_MIN;
                    if (rayDistanceM > RAY_MAX) rayDistanceM = RAY_MAX;
                    refreshRayPreview();
                    updateRayDistanceHud();
                    return true;
                }

                zoomFactor *= s;
                if (zoomFactor < ZOOM_MIN) zoomFactor = ZOOM_MIN;
                if (zoomFactor > ZOOM_MAX) zoomFactor = ZOOM_MAX;
                trySetBackgroundZoom(zoomFactor);
                return true;
            }
        });
        surfaceView.setOnTouchListener((v, e) -> {
            scaleDetector.onTouchEvent(e);
            if (e.getPointerCount() > 1 || scaleDetector.isInProgress()) return true;
            return tapHelper.onTouch(v, e);
        });

        render = new SampleRender(surfaceView, this, getAssets());
        installRequested = false;

        depthSettings.onCreate(this);
        instantPlacementSettings.onCreate(this);

        depthSettings.setUseDepthForOcclusion(false);
        depthSettings.setDepthColorVisualizationEnabled(false);

        instantPlacementSettings.setInstantPlacementEnabled(false);

        eggRepo = new EggRepository(getApplicationContext());
    }

    private void setRayControlsVisible(boolean visible) {
        if (rayControlsContainer == null) return;
        runOnUiThread(() -> rayControlsContainer.setVisibility(visible ? View.VISIBLE : View.GONE));
    }

    private void nudgeRayDistance(float delta) {
        if (!rayAdjustActive || activeRay == null) return;
        rayDistanceM += delta;
        if (rayDistanceM < RAY_MIN) rayDistanceM = RAY_MIN;
        if (rayDistanceM > RAY_MAX) rayDistanceM = RAY_MAX;
        refreshRayPreview();
        updateRayDistanceHud();
    }

    private void updateRayDistanceHud() {
        runOnUiThread(() -> {
            if (poseInfoCard != null) {
                poseInfoCard.setVisibility(View.VISIBLE);
                poseInfoCard.setText(String.format(Locale.US, "Distance: %.1f m  (pinch or ±)"));
            }
        });
    }

    @Override protected void onDestroy() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }
    @Override
    protected void onResume() {
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

        boolean hasActivePlacement = (currentPlacedAnchor != null) || !wrappedAnchors.isEmpty();
        if (!inMetadataFlow && !hasActivePlacement) {
            placementModeActive = false;
            readyPromptShown = false;
            uiShowProgress(
                    "Scanning",
                    "Move slowly — waiting for surfaces (mesh/grid) to appear…",
                    "Aim at well-lit, textured areas."
            );
            showPlacementCoachDialog();
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

        // Exit/hide ray controls if backgrounded
        rayAdjustActive = false;
        activeRay = null;
        setRayControlsVisible(false);
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

    // ========= RENDERER =========

    @Override public void onSurfaceCreated(SampleRender render) {
        try {
            backgroundRenderer = new BackgroundRenderer(render);
            try {
                backgroundRenderer.setUseDepthVisualization(render, depthSettings.depthColorVisualizationEnabled());
                backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
            } catch (Exception e) { Log.w(TAG, "Applying background settings failed", e); }
            backgroundReady = true;

            trySetBackgroundZoom(zoomFactor);

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
                String[] texFiles = new String[] {
                        "models/Image_0.png",
                        "models/Image_1.png",
                        "models/Image_2.png"
                };
                this.starTextures = new Texture[texFiles.length];
                for (int i = 0; i < texFiles.length; i++) {
                    this.starTextures[i] = Texture.createFromAsset(
                            render, texFiles[i],
                            Texture.WrapMode.CLAMP_TO_EDGE,
                            Texture.ColorFormat.SRGB
                    );
                }
                int pick = rng.nextInt(this.starTextures.length);
                virtualObjectAlbedoTexture = this.starTextures[pick];

                virtualObjectMesh = Mesh.createFromAsset(render, "models/star.obj");

                Map<String, String> defs = new HashMap<>();
                defs.put("NUMBER_OF_MIPMAP_LEVELS", Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));

                virtualObjectShader = Shader.createFromAssets(
                                render, "shaders/environmental_hdr.vert", "shaders/environmental_hdr.frag", defs)
                        .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                        .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
                        .setTexture("u_DfgTexture", dfgTexture)
                        .setDepthTest(true)
                        .setDepthWrite(false);

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
                        virtualObjectAlbedoTexture = Texture.createFromAsset(
                                render, "models/Image_0.png",
                                Texture.WrapMode.CLAMP_TO_EDGE,
                                Texture.ColorFormat.SRGB
                        );
                    }
                    if (virtualObjectMesh == null) {
                        virtualObjectMesh = Mesh.createFromAsset(render, "models/star.obj");
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
                GLES30.glDisable(GLES30.GL_CULL_FACE);
                GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
                GLES30.glPolygonOffset(1.0f, 1.0f);
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
        if (!backgroundReady || backgroundRenderer == null) return;

        if (!hasSetTextureNames && backgroundRenderer.getCameraColorTexture() != null) {
            try {
                session.setCameraTextureNames(new int[]{backgroundRenderer.getCameraColorTexture().getTextureId()});
                hasSetTextureNames = true;
            } catch (Throwable t) {
                Log.e(TAG, "Failed to set camera texture names", t);
                return;
            }
        }

        try { displayRotationHelper.updateSessionIfNeeded(session); } catch (Throwable t) { }

        final Frame frame;
        try {
            frame = session.update();
        } catch (CameraNotAvailableException e) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
            return;
        } catch (Throwable t) { return; }

        final Camera camera = frame.getCamera();

        try { backgroundRenderer.updateDisplayGeometry(frame); } catch (Throwable t) { return; }

        if (camera.getTrackingState() == TrackingState.TRACKING
                && (depthSettings.useDepthForOcclusion() || depthSettings.depthColorVisualizationEnabled())) {
            try (Image depthImage = frame.acquireDepthImage16Bits()) {
                backgroundRenderer.updateCameraDepthTexture(depthImage);
            } catch (Throwable ignore) {}
        }

        trySetBackgroundZoom(zoomFactor);

        if (placementModeActive) {
            try { handleTap(frame, camera); } catch (Throwable t) { }
        } else {
            tapHelper.poll();
        }

        try { if (frame.getTimestamp() != 0) backgroundRenderer.drawBackground(render); } catch (Throwable t) { return; }
        if (camera.getTrackingState() != TrackingState.TRACKING) return;

        try {
            int trackedPlanes = 0;
            for (Plane p : session.getAllTrackables(Plane.class)) {
                if (p.getTrackingState() == TrackingState.TRACKING && p.getSubsumedBy() == null) trackedPlanes++;
            }

            if (!placementModeActive) {
                if (trackedPlanes == 0) {
                    readyPromptShown = false;
                    uiShowProgress(
                            "Scanning",
                            "Move slowly — waiting for surfaces (mesh/grid) to appear…",
                            "Aim at well-lit, textured areas."
                    );
                } else if (!readyPromptShown) {
                    readyPromptShown = true;
                    placementModeActive = true;
                    uiShowMessage(
                            "Ready",
                            "Surfaces detected — tap OK to start placing.\n(Then tap on the mesh/grid.)",
                            true);
                }
            } else {
                uiHideStatus();
            }
        } catch (Throwable ignore) {}

        try {
            camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);
            camera.getViewMatrix(viewMatrix, 0);
        } catch (Throwable t) { return; }

        try {
            if (planeRenderer != null) {
                planeRenderer.drawPlanes(render, session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projectionMatrix);
            }
        } catch (Throwable t) { }

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
        } catch (Throwable t) { }

        try {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthFunc(GLES30.GL_LEQUAL);
            GLES30.glDepthMask(true);
        } catch (Throwable ignore) {}

        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);

        pruneDeadAnchors(prevAnchors);
        pruneDeadAnchors(wrappedAnchors);

        boolean anyPaused = hasPausedAnchors(prevAnchors) || hasPausedAnchors(wrappedAnchors);
        boolean anyPerAnchorNoOcc = hasActiveNoOcc(prevAnchors) || hasActiveNoOcc(wrappedAnchors);
        boolean suppressOcclusion = anyPaused || anyPerAnchorNoOcc || (System.currentTimeMillis() < noOccUntilMs);
        try {
            backgroundRenderer.setUseOcclusion(render, suppressOcclusion ? false : depthSettings.useDepthForOcclusion());
        } catch (Throwable ignore) {}

        final float MODEL_BASE_SCALE = 0.02f;

        drawAnchorsList(prevAnchors, MODEL_BASE_SCALE, render);
        drawAnchorsList(wrappedAnchors, MODEL_BASE_SCALE, render);

        final boolean vsReady = (backgroundRenderer != null) && backgroundRenderer.isVirtualSceneInitialized();
        if (virtualSceneFramebuffer != null && vsReady) {
            backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
        }

        if (hostState == HostState.HOSTING && hostedCloudAnchor != null) {
            Anchor.CloudAnchorState st = hostedCloudAnchor.getCloudAnchorState();
            Log.i(TAG, "CloudAnchor state: " + st);

            if (st.isError()) {
                hostState = HostState.ERROR;
                if (pendingEggDocId != null) {
                    FirebaseFirestore.getInstance().collection(EGGS).document(pendingEggDocId)
                            .update("cloudStatus", "ERROR", "cloudError", st.toString())
                            .addOnFailureListener(e -> Log.w(TAG, "Failed to mark cloud error", e));
                }
                uiShowMessage("Hosting failed", "Cloud Anchor hosting failed: " + st, true);
            } else if (st == Anchor.CloudAnchorState.SUCCESS) {
                hostState = HostState.SUCCESS;
                hostedCloudId = hostedCloudAnchor.getCloudAnchorId();

                if (pendingEggDocId != null && hostedCloudId != null) {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put("cloudStatus", "SUCCESS");
                    patch.put("cloudId", hostedCloudId);
                    patch.put("cloudHostedAt", FieldValue.serverTimestamp());
                    patch.put("cloudTtlDays", CLOUD_TTL_DAYS);

                    FirebaseFirestore.getInstance()
                            .collection(EGGS)
                            .document(pendingEggDocId)
                            .update(patch)
                            .addOnSuccessListener(v -> {
                                uiShowMessage(
                                        "All set!",
                                        "Egg saved and cloud hosted ✓\nTap OK to place again.",
                                        true
                                );
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Failed to patch egg with cloudId", e);
                                uiShowMessage(
                                        "All set!",
                                        "Egg saved and hosted ✓ (but metadata patch failed).\nTap OK to place again.",
                                        true
                                );
                            });
                } else {
                    uiShowMessage(
                            "All set!",
                            "Egg saved and hosted ✓\nTap OK to place again.",
                            true
                    );
                }
            }
        }

        try {
            Earth earth = null;
            try { earth = session.getEarth(); } catch (Throwable ignore) {}
            final String earthLine;
            if (earth == null) {
                earthLine = "Earth: not available";
            } else {
                earthLine = "AR Earth: " + earth.getEarthState()
                        + " • Tracking: " + earth.getTrackingState();
//                        + " • Mode: " + (envMode == Env.INDOOR ? "Indoor" : "Outdoor");
            }

            String latLngLine = "LAT/LNG: —";
            String latLngAcc  = "H-Accuracy: —";
            String altLine    = "ALTITUDE: —";
            String altAcc     = "V-Accuracy: —";
            String headLine   = "HEADING: —";
            String headAcc    = "Heading accuracy: —";
            String anchorLine = "Anchor: none";

            Anchor currentAnchor = (!wrappedAnchors.isEmpty() && wrappedAnchors.get(0) != null) ? wrappedAnchors.get(0).getAnchor() : null;
            if (currentAnchor != null) {
                String suffix;
                if (hostState == HostState.HOSTING) suffix = " (CLOUD…)";
                else if (hostState == HostState.SUCCESS) suffix = " (CLOUD✔)";
                else if (envMode == Env.OUTDOOR) suffix = " (LOCAL)";
                else suffix = " (LOCAL)";
                anchorLine = "Anchor: " + currentAnchor.getTrackingState().name() + suffix;
            }

            GeospatialPose camGp = null;
            if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
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

                    maybeLoadPreviousEggs(earth, camGp);
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

    private void trySetBackgroundZoom(float z) {
        try {
            Method m = backgroundRenderer.getClass().getMethod("setZoom", float.class);
            m.invoke(backgroundRenderer, z);
        } catch (Throwable ignore) {}
    }

    private void pruneDeadAnchors(List<WrappedAnchor> list) {
        for (WrappedAnchor w : new ArrayList<>(list)) {
            if (w == null) continue;
            Anchor a = w.getAnchor();
            if (a == null) {
                list.remove(w);
                continue;
            }
            if (a.getTrackingState() == TrackingState.STOPPED) {
                try { a.detach(); } catch (Throwable ignore) {}
                lastStableT.remove(a);
                list.remove(w);
            }
        }
    }

    private int drawAnchorsList(List<WrappedAnchor> list, float baseScale, SampleRender render) {
        int drawn = 0;
        for (WrappedAnchor wrapped : list) {
            if (wrapped == null) continue;
            Anchor a = wrapped.getAnchor();
            if (a == null) continue;

            TrackingState st = a.getTrackingState();
            if (st == TrackingState.STOPPED) {
                try { a.detach(); } catch (Throwable ignore) {}
                lastStableT.remove(a);
                list.remove(wrapped);
                continue;
            }
            boolean isPaused   = (st == TrackingState.PAUSED);
            boolean isTracking = (st == TrackingState.TRACKING);
            if (!isPaused && !isTracking) continue;

            Pose p = a.getPose();
            float[] t = p.getTranslation();
            float[] last = lastStableT.get(a);
            if (last != null && SMOOTHING_ALPHA < 1f) {
                for (int i = 0; i < 3; i++) t[i] = SMOOTHING_ALPHA * last[i] + (1 - SMOOTHING_ALPHA) * t[i];
            }
            lastStableT.put(a, t.clone());
            Pose pSmooth = new Pose(t, new float[]{p.qx(), p.qy(), p.qz(), p.qw()});
            pSmooth.toMatrix(modelMatrix, 0);

            float liftY = 0.03f;
            if (isPaused) liftY += 0.03f;
            Matrix.translateM(modelMatrix, 0, 0f, liftY, 0f);

            Matrix.scaleM(modelMatrix, 0, baseScale, baseScale, baseScale);

            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

            if (eggMeshes != null && eggShaders != null && eggMeshes.length == eggShaders.length && eggMeshes.length > 0) {
                for (int i = 0; i < eggMeshes.length; i++) {
                    if (eggMeshes[i] == null || eggShaders[i] == null) continue;
                    try { eggShaders[i].setMat4("u_ModelViewProjection", modelViewProjectionMatrix); } catch (Throwable ignore) {}
                    try { eggShaders[i].setMat4("u_ModelView", modelViewMatrix); } catch (Throwable ignore) {}

                    if (isPaused) {
                        try { eggShaders[i].setDepthTest(false); } catch (Throwable ignore) {}
                        try { eggShaders[i].setFloat("u_Opacity", 0.85f); } catch (Throwable ignore) {}
                        render.draw(eggMeshes[i], eggShaders[i], virtualSceneFramebuffer);
                        try { eggShaders[i].setDepthTest(true); } catch (Throwable ignore) {}
                        try { eggShaders[i].setFloat("u_Opacity", 1.0f); } catch (Throwable ignore) {}
                    } else {
                        try { eggShaders[i].setFloat("u_Opacity", 1.0f); } catch (Throwable ignore) {}
                        render.draw(eggMeshes[i], eggShaders[i], virtualSceneFramebuffer);
                    }
                }
                drawn++;
            }
        }
        return drawn;
    }

    private boolean hasPausedAnchors(List<WrappedAnchor> list) {
        for (WrappedAnchor w : list) {
            if (w == null) continue;
            Anchor a = w.getAnchor();
            if (a == null) continue;
            if (a.getTrackingState() == TrackingState.PAUSED) return true;
        }
        return false;
    }

    private boolean hasActiveNoOcc(List<WrappedAnchor> list) {
        long now = System.currentTimeMillis();
        for (WrappedAnchor w : list) {
            if (w != null && w.isOcclusionSuppressedAt(now)) return true;
        }
        return false;
    }

    // Kept (no longer used for hosting since IP is disabled)
    private static boolean isInstantPlacementResolved(Trackable tr) {
        try {
            if (tr instanceof InstantPlacementPoint) {
                InstantPlacementPoint ipp = (InstantPlacementPoint) tr;
                return ipp.getTrackingMethod() == InstantPlacementPoint.TrackingMethod.FULL_TRACKING;
            }
        } catch (Throwable ignore) {}
        return false;
    }

    // ====== Ray holder + helpers ======
    private static class Ray {
        final float[] origin = new float[3];
        final float[] dir    = new float[3];
    }

    private Ray screenTapToWorldRay(Camera camera, float xPx, float yPx, int vw, int vh) {
        float[] proj = new float[16], view = new float[16], vp = new float[16], invVP = new float[16];
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);
        camera.getViewMatrix(viewMatrix, 0);
        System.arraycopy(projectionMatrix, 0, proj, 0, 16);
        System.arraycopy(viewMatrix, 0, view, 0, 16);
        android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        android.opengl.Matrix.invertM(invVP, 0, vp, 0);

        float nx =  (2f * xPx) / vw - 1f;
        float ny =  1f - (2f * yPx) / vh;

        float[] nearN = {nx, ny, -1f, 1f};
        float[] farN  = {nx, ny,  1f, 1f};
        float[] nearW = new float[4], farW = new float[4];
        android.opengl.Matrix.multiplyMV(nearW, 0, invVP, 0, nearN, 0);
        android.opengl.Matrix.multiplyMV(farW,  0, invVP, 0, farN,  0);
        for (int i=0;i<3;i++){ nearW[i]/=nearW[3]; farW[i]/=farW[3]; }

        float[] invView = new float[16];
        android.opengl.Matrix.invertM(invView, 0, view, 0);
        Ray r = new Ray();
        r.origin[0] = invView[12]; r.origin[1] = invView[13]; r.origin[2] = invView[14];
        float dx = farW[0]-nearW[0], dy = farW[1]-nearW[1], dz = farW[2]-nearW[2];
        float len = (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
        r.dir[0] = dx/len; r.dir[1] = dy/len; r.dir[2] = dz/len;
        return r;
    }

    private Pose poseAlongRay(Ray r, float distanceMeters, @Nullable Float forcedYawDeg) {
        float px = r.origin[0] + r.dir[0]*distanceMeters;
        float py = r.origin[1] + r.dir[1]*distanceMeters;
        float pz = r.origin[2] + r.dir[2]*distanceMeters;

        float[] q;
        if (forcedYawDeg != null) {
            q = yawToQuaternion(forcedYawDeg);
        } else {
            double yaw = Math.atan2(-r.dir[0], -r.dir[2]) * 180.0/Math.PI;
            q = yawToQuaternion((float)yaw);
        }
        return new Pose(new float[]{px,py,pz}, q);
    }

    /** Re-create the preview anchor at the current ray/distance, cleanly swapping the old one. */
    private void refreshRayPreview() {
        if (session == null || activeRay == null) return;
        Anchor old = currentPlacedAnchor;
        Pose p = poseAlongRay(activeRay, rayDistanceM, null);
        Anchor a = session.createAnchor(p);
        currentPlacedAnchor = a;
        lastHitPose = p;
        lastHitSurfaceType = "GEO_RAY";

        wrappedAnchors.clear();
        long grace = System.currentTimeMillis() + 1500L;
        wrappedAnchors.add(new WrappedAnchor(a, null, null, grace));
        noOccUntilMs = grace;

        if (old != null) {
            try { old.detach(); } catch (Throwable ignore) {}
        }
    }
    // ====== /helpers ======

    private void handleTap(Frame frame, Camera camera) {
        MotionEvent tap = tapHelper.poll();
        if (tap == null) return;

        if (camera.getTrackingState() != TrackingState.TRACKING) {
            messageSnackbarHelper.showMessage(this, "Move slowly and point at well-lit, textured surfaces.");
            return;
        }

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

        final float MAX_HIT_M = 40.0f;
        List<HitResult> raw = frame.hitTest(tap);
        List<HitResult> depths      = new ArrayList<>();
        List<HitResult> pointsSurf  = new ArrayList<>();
        List<HitResult> pointsAny   = new ArrayList<>();
        List<HitResult> planesVert  = new ArrayList<>();
        List<HitResult> planesHoriz = new ArrayList<>();
        List<HitResult> geoms       = new ArrayList<>();

        for (HitResult hit : raw) {
            Trackable tr = hit.getTrackable();
            float d = distance(hit.getHitPose(), camPoseNow);
            if (d > MAX_HIT_M) continue;

            boolean isDepth = (tr instanceof DepthPoint);
            boolean isPoint = (tr instanceof Point);
            boolean isPlane = (tr instanceof Plane)
                    && ((Plane) tr).isPoseInPolygon(hit.getHitPose())
                    && PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camPoseNow) > 0;
            boolean isGeom = (tr instanceof StreetscapeGeometry);

            if (isDepth) {
                depths.add(hit);
            } else if (isPlane) {
                Plane pl = (Plane) tr;
                if (pl.getType() == Plane.Type.VERTICAL) planesVert.add(hit);
                else planesHoriz.add(hit);
            } else if (isPoint) {
                Point p = (Point) tr;
                if (p.getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                    pointsSurf.add(hit);
                } else {
                    pointsAny.add(hit);
                }
            } else if (isGeom) {
                geoms.add(hit);
            }
        }

        Comparator<HitResult> byDist = (a, b) ->
                Float.compare(distance(a.getHitPose(), camPoseNow), distance(b.getHitPose(), camPoseNow));
        Collections.sort(depths, byDist);
        Collections.sort(pointsSurf, byDist);
        Collections.sort(pointsAny, byDist);
        Collections.sort(planesVert, byDist);
        Collections.sort(planesHoriz, byDist);
        Collections.sort(geoms, byDist);

        // Choose visual anchor (what user sees immediately)
        HitResult chosenVisual = (envMode == Env.OUTDOOR)
                ? (!depths.isEmpty() ? depths.get(0)
                : !geoms.isEmpty() ? geoms.get(0)
                : !planesVert.isEmpty() ? planesVert.get(0)
                : !planesHoriz.isEmpty() ? planesHoriz.get(0)
                : !pointsSurf.isEmpty() ? pointsSurf.get(0)
                : !pointsAny.isEmpty()  ? pointsAny.get(0)
                : null)
                : (!planesVert.isEmpty() ? planesVert.get(0)
                : !planesHoriz.isEmpty() ? planesHoriz.get(0)
                : !pointsSurf.isEmpty() ? pointsSurf.get(0)
                : !depths.isEmpty() ? depths.get(0)
                : !pointsAny.isEmpty() ? pointsAny.get(0)
                : !geoms.isEmpty() ? geoms.get(0)
                : null);

        // Hostable: prefer vertical plane → horizontal plane → surface-normal point
        HitResult hostable = null;
        if (!planesVert.isEmpty()) {
            hostable = planesVert.get(0);
        } else if (!planesHoriz.isEmpty()) {
            hostable = planesHoriz.get(0);
        } else if (!pointsSurf.isEmpty()) {
            hostable = pointsSurf.get(0);
        }

        // GEO Ray fallback when nothing was hit at all
        if (chosenVisual == null) {
            int vw = surfaceView.getWidth(), vh = surfaceView.getHeight();
            activeRay = screenTapToWorldRay(camera, tap.getX(), tap.getY(), vw, vh);
            rayDistanceM = 60f; // start
            rayAdjustActive = true;

            refreshRayPreview();
            setRayControlsVisible(true);

            placementModeActive = false;
            messageSnackbarHelper.showMessage(this, "Placed (geospatial ray). Pinch or use ±, then Save.");
            updateRayDistanceHud();

            Earth earth = null;
            GeospatialPose gp = null, camGp = null;
            try { earth = session.getEarth(); } catch (Throwable ignore) {}
            if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
                try { gp = earth.getGeospatialPose(currentPlacedAnchor.getPose()); } catch (Throwable ignore) {}
                try { camGp = earth.getCameraGeospatialPose(); } catch (Throwable ignore) {}
            }

            final String hud = (gp != null)
                    ? String.format(Locale.US, "Placed. Lat %.6f  Lng %.6f  Alt %.2f  (H ±%.1fm, V ±%.1fm)",
                    gp.getLatitude(), gp.getLongitude(), gp.getAltitude(),
                    gp.getHorizontalAccuracy(), gp.getVerticalAccuracy())
                    : "Placed. Getting precise location…";
            runOnUiThread(() -> {
                if (poseInfoCard != null) {
                    poseInfoCard.setVisibility(View.VISIBLE);
                    poseInfoCard.setText(hud);
                }
            });

            final EggCardSheet sheet = new EggCardSheet();
            if (gp != null) {
                double headingDeg = goodHeadingDeg(camGp, lastGoodGeoPose, headGate());
                sheet.setGeoPoseSnapshot(new EggCardSheet.GeoPoseSnapshot(
                        gp.getLatitude(),
                        gp.getLongitude(),
                        gp.getAltitude(),
                        headingDeg,
                        gp.getHorizontalAccuracy(),
                        gp.getVerticalAccuracy(),
                        (camGp != null ? camGp.getHeadingAccuracy() : Double.NaN),
                        System.currentTimeMillis()));
            }

            inMetadataFlow = true;
            final Camera cameraForSave = camera;
            sheet.setListener(new EggCardSheet.Listener() {
                @Override
                public void onSave(String title, String description, List<Uri> photoUris, @Nullable Uri audioUri,
                                   @Nullable EggCardSheet.GeoPoseSnapshot geoSnapshot) {
                    rayAdjustActive = false;
                    setRayControlsVisible(false);
                    inMetadataFlow = false;
                    placementModeActive = false;
                    uiShowProgress("Saving", "Saving the egg…", null);
                    // fall-through to save pipeline (handled in the normal onSave path below)
                }
                @Override
                public void onCancel() {
                    rayAdjustActive = false;
                    setRayControlsVisible(false);
                    inMetadataFlow = false;
                    placementModeActive = true;
                    readyPromptShown = true;
                    hideStatus();
                }
            });

            if (!isFinishing() && !isDestroyed()) {
                sheet.show(getSupportFragmentManager(), "EggCardSheet");
            }
            return;
        }

        // Normal hit path
        lastHitPose = chosenVisual.getHitPose();
        lastHitSurfaceType =
                (chosenVisual.getTrackable() instanceof DepthPoint) ? "DEPTH" :
                        (chosenVisual.getTrackable() instanceof StreetscapeGeometry) ? "STREETSCAPE" :
                                (chosenVisual.getTrackable() instanceof Plane)
                                        ? (((Plane) chosenVisual.getTrackable()).getType() == Plane.Type.VERTICAL ? "PLANE_VERTICAL" : "PLANE_HORIZONTAL")
                                        : (chosenVisual.getTrackable() instanceof Point) ? "POINT"
                                        : "UNKNOWN";

        final long grace = System.currentTimeMillis() + 2500L;
        final Anchor visualAnchor = chosenVisual.createAnchor();
        currentPlacedAnchor = visualAnchor;
        messageSnackbarHelper.showMessage(this, "Placed — fill details and press Save.");
        wrappedAnchors.add(new WrappedAnchor(visualAnchor, chosenVisual.getTrackable(), null, grace));

        noOccUntilMs = grace;
        placementModeActive = false;

        // Ensure ray mode off + hide buttons
        rayAdjustActive = false;
        activeRay = null;
        setRayControlsVisible(false);

        localAnchorForHosting = null;
        hostState = HostState.IDLE;

        if (hostable != null) {
            Trackable tr = hostable.getTrackable();
            boolean ok =
                    (tr instanceof Plane) ||
                            (tr instanceof Point && isSurfaceNormalPoint((Point) tr));

            if (ok) {
                localAnchorForHosting = (hostable == chosenVisual) ? visualAnchor : hostable.createAnchor();
                runOnUiThread(() -> Toast.makeText(
                        HelloArActivity.this,
                        "Ready to host Cloud Anchor on Save.",
                        Toast.LENGTH_SHORT).show());
            } else {
                localAnchorForHosting = null;
                runOnUiThread(() -> Toast.makeText(
                        HelloArActivity.this,
                        "No hostable surface yet — you can still save by location on the next screen.",
                        Toast.LENGTH_SHORT).show());
            }
        }

        GeospatialPose anchorGp = null, camGp = null;
        Earth earth = null;
        try { earth = session.getEarth(); } catch (Throwable ignore) {}
        if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
            try { anchorGp = earth.getGeospatialPose(visualAnchor.getPose()); } catch (Throwable ignore) {}
            try { camGp    = earth.getCameraGeospatialPose(); } catch (Throwable ignore) {}
        }

        final String hud = (anchorGp != null)
                ? String.format(Locale.US, "Placed. Lat %.6f  Lng %.6f  Alt %.2f  (H ±%.1fm, V ±%.1fm)",
                anchorGp.getLatitude(), anchorGp.getLongitude(), anchorGp.getAltitude(),
                anchorGp.getHorizontalAccuracy(), anchorGp.getVerticalAccuracy())
                : "Placed. Getting precise location…";
        runOnUiThread(() -> {
            if (poseInfoCard != null) {
                poseInfoCard.setVisibility(View.VISIBLE);
                poseInfoCard.setText(hud);
            }
        });

        final EggCardSheet sheet = new EggCardSheet();
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

        inMetadataFlow = true;
        final Camera cameraForSave = camera;

        sheet.setListener(new EggCardSheet.Listener() {
            @Override
            public void onSave(String title, String description,
                               List<Uri> photoUris, @Nullable Uri audioUri,
                               @Nullable EggCardSheet.GeoPoseSnapshot geoSnapshot) {

                // Wrap the existing save pipeline so we can optionally confirm first
                Runnable doActualSave = () -> {
                    inMetadataFlow = false;
                    placementModeActive = false;

                    uiShowProgress("Saving", "Saving the egg…", null);

                    GeospatialPose finalGp = null;
                    Earth earthNow = null;
                    try {
                        earthNow = session.getEarth();
                        if (earthNow != null && earthNow.getTrackingState() == TrackingState.TRACKING && currentPlacedAnchor != null) {
                            finalGp = earthNow.getGeospatialPose(currentPlacedAnchor.getPose());
                        }
                    } catch (Throwable ignore) {}

                    double headingNow = Double.NaN;
                    try {
                        GeospatialPose camNow = (earthNow != null) ? earthNow.getCameraGeospatialPose() : null;
                        headingNow = goodHeadingDeg(camNow, lastGoodGeoPose, headGate());
                    } catch (Throwable ignore) {}

                    EggEntry e = new EggEntry();
                    e.title = (title == null) ? "" : title;
                    e.description = (description == null) ? "" : description;
                    e.quiz = null;
                    e.heading = headingNow;

                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    if (user != null) e.userId = user.getUid();

                    if (finalGp != null) {
                        e.geo      = new com.google.firebase.firestore.GeoPoint(finalGp.getLatitude(), finalGp.getLongitude());
                        e.horizAcc = finalGp.getHorizontalAccuracy();
                        double vAcc = finalGp.getVerticalAccuracy();
                        e.vertAcc   = Double.isNaN(vAcc) ? null : vAcc;
                        e.alt      = finalGp.getAltitude();
                    }

                    Trackable firstTrackable = (!wrappedAnchors.isEmpty()) ? wrappedAnchors.get(0).getTrackable() : null;
                    e.placementType = (firstTrackable != null) ? firstTrackable.getClass().getSimpleName() : "Local";
                    e.distanceFromCamera = (currentPlacedAnchor != null)
                            ? distance(currentPlacedAnchor.getPose(), cameraForSave.getPose())
                            : 0f;

                    e.anchorType = "CLOUD";

                    String cellKey = (finalGp != null) ? cellKey(finalGp.getLatitude(), finalGp.getLongitude()) : null;

                    final Earth earthNowF = earthNow;
                    final GeospatialPose finalGpF = finalGp;
                    final com.google.firebase.firestore.GeoPoint geoF = e.geo;

                    eggRepo.createDraft(e)
                            .addOnSuccessListener(docRef -> {

                                Runnable continueFlow = () -> {
                                    try {
                                        float[] q = (lastHitPose != null)
                                                ? lastHitPose.getRotationQuaternion()
                                                : (currentPlacedAnchor != null ? currentPlacedAnchor.getPose().getRotationQuaternion()
                                                : new float[]{0,0,0,1});

                                        Map<String, Object> orient = new HashMap<>();
                                        orient.put("localQx", q[0]);
                                        orient.put("localQy", q[1]);
                                        orient.put("localQz", q[2]);
                                        orient.put("localQw", q[3]);
                                        orient.put("surface", lastHitSurfaceType);
                                        orient.put("placementEnv", envMode.name());
                                        if (cellKey != null) orient.put("cellKey", cellKey);

                                        Map<String, Object> patch = new HashMap<>(orient);
                                        patch.put("extras", new HashMap<>(orient));

                                        FirebaseFirestore.getInstance()
                                                .collection(EGGS).document(docRef.getId())
                                                .set(patch, SetOptions.merge());
                                    } catch (Throwable t) {
                                        Log.w(TAG, "Failed to patch local quaternion", t);
                                    }

                                    uiShowProgress("Saving", "Uploading media…", null);

                                    try {
                                        if (earthNowF != null && finalGpF != null && geoF != null) {
                                            saveHeightAboveTerrain(
                                                    earthNowF,
                                                    geoF.getLatitude(),
                                                    geoF.getLongitude(),
                                                    finalGpF.getAltitude(),
                                                    docRef.getId()
                                            );
                                        }
                                    } catch (Throwable t) {
                                        Log.w(TAG, "resolveAnchorOnTerrainAsync failed", t);
                                    }

                                    if (photoUris != null) {
                                        for (Uri u : photoUris) {
                                            try { getContentResolver().takePersistableUriPermission(
                                                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignore) {}
                                        }
                                    }
                                    if (audioUri != null) {
                                        try { getContentResolver().takePersistableUriPermission(
                                                audioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignore) {}
                                    }

                                    eggRepo.uploadMediaAndPatch(docRef, photoUris, audioUri)
                                            .addOnSuccessListener(v -> {
                                                Toast.makeText(HelloArActivity.this, "Media uploaded ✓", Toast.LENGTH_SHORT).show();

                                                if (localAnchorForHosting != null) {
                                                    FirebaseFirestore.getInstance().collection(EGGS).document(docRef.getId())
                                                            .update("cloudStatus", "HOSTING", "cloudTtlDays", CLOUD_TTL_DAYS,
                                                                    "anchorType", (envMode == Env.OUTDOOR ? "GEO+CLOUD" : "CLOUD"));
                                                    uiShowProgress("Saving", "Hosting anchor in the cloud…", "This can take a few seconds");
                                                    pendingEggDocId = docRef.getId();
                                                    startHostingCloudAnchor(localAnchorForHosting, pendingEggDocId);
                                                } else {
                                                    if (finalGpF != null) {
                                                        FirebaseFirestore.getInstance().collection(EGGS).document(docRef.getId())
                                                                .update("cloudStatus", "NONE",
                                                                        "anchorType", "GEO")
                                                                .addOnSuccessListener(v2 -> uiShowMessage(
                                                                        "Saved (geospatial)",
                                                                        "No hostable surface. Saved by location (lat/lng/alt + height).",
                                                                        true))
                                                                .addOnFailureListener(err -> uiShowMessage(
                                                                        "Save (geospatial) failed",
                                                                        "Could not mark as geospatial: " + err.getMessage(),
                                                                        true));
                                                    } else {
                                                        uiShowMessage("Need location",
                                                                "Couldn’t get geospatial pose. Move until AR Earth is tracking, then Save again.",
                                                                true);
                                                        FirebaseFirestore.getInstance().collection(EGGS).document(docRef.getId())
                                                                .update("cloudStatus", "NO_GEOPOSE");
                                                    }
                                                }
                                            })
                                            .addOnFailureListener(err -> {
                                                Log.e(TAG, "Media upload failed", err);
                                                Toast.makeText(HelloArActivity.this, "Upload failed: " + err.getMessage(),
                                                        Toast.LENGTH_LONG).show();
                                                uiShowMessage("Upload failed", "Media upload failed: " + err.getMessage(), true);
                                            });

                                    if (ENABLE_QUIZ) {
                                        enqueueQuizGenerationOnEggDoc(docRef.getId(), e.title, e.description);
                                    }
                                };

                                FirebaseFirestore.getInstance()
                                        .collection(EGGS).document(docRef.getId())
                                        .get(com.google.firebase.firestore.Source.SERVER)
                                        .addOnSuccessListener(snap -> continueFlow.run())
                                        .addOnFailureListener(err -> continueFlow.run());
                            })
                            .addOnFailureListener(err -> {
                                Log.e(TAG, "Egg save failed", err);
                                uiShowMessage("Save failed", "Could not save egg: " + err.getMessage(), true);
                            });
                };

                // If there is no hostable surface, confirm with the user
                if (localAnchorForHosting == null) {
                    new AlertDialog.Builder(HelloArActivity.this)
                            .setTitle("No hostable surface found")
                            .setMessage(
                                    "We can’t lock to a plane/point right now.\n\n" +
                                            "• Still save: Save by location (lat/lng/alt + height), so it shows by geospatial pose.\n" +
                                            "• Wait: Keep scanning until meshes/grids appear, then try again.")
                            .setPositiveButton("Still save", (d, w) -> doActualSave.run())
                            .setNegativeButton("Wait for meshes/grids", (d, w) -> {
                                // Back to camera & keep scanning
                                inMetadataFlow = false;
                                placementModeActive = true;
                                readyPromptShown = false;
                                uiShowProgress("Scanning",
                                        "Move slowly — waiting for surfaces (mesh/grid) to appear…",
                                        "Aim at well-lit, textured areas.");
                            })
                            .show();
                    return;
                }

                // Otherwise (hostable present), proceed immediately
                doActualSave.run();
            }

            @Override public void onCancel() {
                inMetadataFlow = false;
                placementModeActive = true;
                readyPromptShown = true;
                hideStatus();
            }
        });

        if (!isFinishing() && !isDestroyed()) {
            sheet.show(getSupportFragmentManager(), "EggCardSheet");
        }
    }

    private void startHostingCloudAnchor(Anchor local, @Nullable String eggDocIdToPatch) {
        if (local == null || session == null) return;

        pendingEggDocId = eggDocIdToPatch;
        hostState = HostState.HOSTING;

        try {
            Anchor result = null;
            try {
                Method m = Session.class.getMethod("hostCloudAnchor", Anchor.class, int.class);
                result = (Anchor) m.invoke(session, local, CLOUD_TTL_DAYS);
            } catch (NoSuchMethodException ignore1) {
                try {
                    Method m2 = Session.class.getMethod("hostCloudAnchorWithTtl", Anchor.class, int.class);
                    result = (Anchor) m2.invoke(session, local, CLOUD_TTL_DAYS);
                } catch (NoSuchMethodException ignore2) {
                    result = session.hostCloudAnchor(local);
                }
            }

            hostedCloudAnchor = result;
            runOnUiThread(() ->
                    Toast.makeText(this, "Hosting anchor… (~" + CLOUD_TTL_DAYS + "d)", Toast.LENGTH_SHORT).show());

        } catch (Throwable t) {
            hostState = HostState.ERROR;
            uiShowMessage("Hosting failed", "Failed to start hosting: " + t.getMessage(), true);
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
        instantPlacementSettings.setInstantPlacementEnabled(false);
        configureSession();
    }

    private void resetSettingsMenuDialogCheckboxes() {
        depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion();
        depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled();
        instantPlacementSettingsMenuDialogCheckboxes[0] = false;
    }

    private void enqueueQuizGenerationOnEggDoc(String eggDocId, String title, String description) {
        Map<String, Object> quizReq = new HashMap<>();
        quizReq.put("status", "pending");
        quizReq.put("source", "description");
        quizReq.put("title",  title == null ? "" : title);
        quizReq.put("description", description == null ? "" : description);
        quizReq.put("model", "gemini-2.5-flash");
        quizReq.put("uid", FirebaseAuth.getInstance().getUid());
        quizReq.put("createdAt", FieldValue.serverTimestamp());

        Map<String, Object> payload = new HashMap<>();
        payload.put("quizRequest", quizReq);

        FirebaseFirestore.getInstance()
                .collection(EGGS)
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
    /** One-time coach dialog shown before the first placement session. */
    private void showPlacementCoachDialog() {
        if (isFinishing() || isDestroyed()) return;

        // Default to showing once unless the user opted out.
        boolean shouldShow = (prefs == null) || prefs.getBoolean(KEY_SHOW_COACH, true);
        if (!shouldShow) return;

        new AlertDialog.Builder(this)
                .setTitle("How to place ")
                .setMessage(
                        "• Move slowly; aim at well-lit, textured areas until you see a mesh/grid.\n" +
                                "• Tap on the grid/points to place.\n" +
                                "• If nothing is hittable, tap once to place along a geospatial ray, " +
                                "then pinch or use the ± buttons to adjust distance.\n" +
                                "• Press Save to upload, optionally hosting a Cloud Anchor."
                )
                .setPositiveButton("Got it", (d, w) -> {
                    if (prefs != null) prefs.edit().putBoolean(KEY_SHOW_COACH, false).apply();
                })
                .setNegativeButton("Show again next time", (d, w) -> {
                    if (prefs != null) prefs.edit().putBoolean(KEY_SHOW_COACH, true).apply();
                })
                .show();
    }


    private void configureSession() {
        if (session == null) return;

        Config config = session.getConfig();

        config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
        config.setGeospatialMode(Config.GeospatialMode.ENABLED);

        try {
            config.setStreetscapeGeometryMode(
                    envMode == Env.OUTDOOR
                            ? Config.StreetscapeGeometryMode.ENABLED
                            : Config.StreetscapeGeometryMode.DISABLED);
        } catch (Throwable ignore) {}

        try { config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL); } catch (Throwable ignore) {}

        config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);

        config.setInstantPlacementMode(Config.InstantPlacementMode.DISABLED);

        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
        } else {
            config.setDepthMode(Config.DepthMode.DISABLED);
        }

        try { config.setFocusMode(Config.FocusMode.AUTO); } catch (Throwable ignore) {}
        try { config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE); } catch (Throwable ignore) {}

        session.configure(config);
    }

    private static boolean isSurfaceNormalPoint(Point p) {
        return p != null && p.getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL;
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

    private static float[] yawToQuaternionZ(float yawDeg) {
        float r = (float) Math.toRadians(yawDeg);
        float s = (float) Math.sin(r * 0.5f), c = (float) Math.cos(r * 0.5f);
        return new float[]{0f, 0f, s, c};
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

    private void ensureStatusShownNoReset() {
        statusDlg = CenterStatusDialogFragment.showOnce(
                getSupportFragmentManager(),
                () -> {
                    hideStatus();
                }
        );
    }

    private void ensureStatusShownAndRearm() {
        statusDlg = CenterStatusDialogFragment.showOnce(
                getSupportFragmentManager(),
                () -> {
                    try {
                        if (currentPlacedAnchor != null) {
                            currentPlacedAnchor.detach();
                            lastStableT.remove(currentPlacedAnchor);
                        }
                    } catch (Throwable ignore) {}
                    currentPlacedAnchor = null;
                    try { wrappedAnchors.clear(); } catch (Throwable ignore) {}
                    localAnchorForHosting = null;
                    hostState = HostState.IDLE;

                    placementModeActive = true;
                    coachLastHintMs = 0L;

                    hideStatus();
                }
        );
    }

    private void uiShowProgress(String title, String line1, @Nullable String line2) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            ensureStatusShownNoReset();
            if (statusDlg != null) statusDlg.showProgress(title, line1, line2);
        });
    }

    private void uiShowMessage(String title, String message, boolean okButton) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            boolean endOfFlow = title != null && (title.startsWith("All set") || title.startsWith("Not hosted") || title.startsWith("Hosting failed") || title.startsWith("Ready") || title.startsWith("Saved (geospatial)") || title.startsWith("Save (geospatial) failed") || title.startsWith("Need location"));
            if (endOfFlow) ensureStatusShownAndRearm();
            else ensureStatusShownNoReset();

            if (statusDlg != null) statusDlg.showMessage(title, message, okButton);
        });
    }

    private void uiHideStatus() { runOnUiThread(this::hideStatus); }

    private void hideStatus() {
        if (statusDlg != null) try { statusDlg.dismissAllowingStateLoss(); } catch (Throwable ignore) {}
        statusDlg = null;
    }

    private static String cellKey(double lat, double lng) {
        long cy = (long) Math.floor(lat / CELL_DEG);
        long cx = (long) Math.floor(lng / CELL_DEG);
        return cy + "_" + cx;
    }

    private static List<String> neighborKeys(double lat, double lng) {
        long cy = (long) Math.floor(lat / CELL_DEG);
        long cx = (long) Math.floor(lng / CELL_DEG);
        List<String> keys = new ArrayList<>(9);
        for (long dy = -1; dy <= 1; dy++) {
            for (long dx = -1; dx <= 1; dx++) {
                keys.add((cy + dy) + "_" + (cx + dx));
            }
        }
        return keys;
    }

    private void maybeLoadPreviousEggs(Earth earth, GeospatialPose camGp) {
        long now = System.currentTimeMillis();
        if (previousEggsLoadedOnce && (now - lastPrevLoadAtMs) < PREV_RELOAD_MS) return;

        lastPrevLoadAtMs = now;
        List<String> keys = neighborKeys(camGp.getLatitude(), camGp.getLongitude());
        try {
            Query q = FirebaseFirestore.getInstance()
                    .collection(EGGS)
                    .whereIn("cellKey", keys)
                    .limit(FIRESTORE_FETCH_LIMIT);

            q.get().addOnSuccessListener(qs -> mountPreviousEggsFromSnapshot(earth, camGp, qs.getDocuments(), true))
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "cellKey query failed, falling back to full fetch", e);
                        fallbackPrevFetch(earth, camGp);
                    });
        } catch (Throwable t) {
            Log.w(TAG, "cellKey query not supported, falling back", t);
            fallbackPrevFetch(earth, camGp);
        }
    }

    private void fallbackPrevFetch(Earth earth, GeospatialPose camGp) {
        FirebaseFirestore.getInstance()
                .collection(EGGS)
                .limit(FIRESTORE_FETCH_LIMIT)
                .get()
                .addOnSuccessListener(qs -> mountPreviousEggsFromSnapshot(earth, camGp, qs.getDocuments(), false))
                .addOnFailureListener(e -> Log.w(TAG, "Previous eggs fetch failed", e));
    }

    private void setStarTextureIndex(int idx) {
        if (starTextures == null || starTextures.length == 0) return;
        currentTexIndex = Math.floorMod(idx, starTextures.length);
        Texture t = starTextures[currentTexIndex];
        if (virtualObjectShader != null) virtualObjectShader.setTexture("u_AlbedoTexture", t);
        if (unlitShader != null)         unlitShader.setTexture("u_Texture", t);
        virtualObjectAlbedoTexture = t;
    }

    private void mountPreviousEggsFromSnapshot(Earth earth, GeospatialPose camGp, List<DocumentSnapshot> docs, boolean cellFiltered) {
        int added = 0;
        final long grace = System.currentTimeMillis() + 2500L;
        for (DocumentSnapshot snap : docs) {
            if (mountedPrevDocIds.contains(snap.getId())) continue;

            Double lat = (snap.getGeoPoint("geo") != null) ? snap.getGeoPoint("geo").getLatitude() : null;
            Double lng = (snap.getGeoPoint("geo") != null) ? snap.getGeoPoint("geo").getLongitude() : null;
            Double alt = snap.getDouble("alt");
            String type = snap.getString("anchorType");
            String cloudId = snap.getString("cloudId");
            Double heading = snap.getDouble("heading");
            if (heading == null) heading = 0.0;

            if (lat == null || lng == null) {
                if (cloudId != null && !cloudId.isEmpty()) {
                    try {
                        Anchor a = session.resolveCloudAnchor(cloudId);
                        prevAnchors.add(new WrappedAnchor(a, null, snap.getId(), grace));
                        mountedPrevDocIds.add(snap.getId());
                        added++;
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to resolve cloud-only egg " + snap.getId(), t);
                    }
                }
                continue;
            }

            if (isWithinMeters(camGp.getLatitude(), camGp.getLongitude(), lat, lng, MIN_LOAD_METERS)) {
                continue;
            }

            try {
                // NEW: Prefer GEO for "GEO" and "GEO+CLOUD"; only use cloud when type == "CLOUD"
                boolean wantsCloudOnly = (cloudId != null && !cloudId.isEmpty()) &&
                        (type != null && type.equalsIgnoreCase("CLOUD"));
                boolean wantsGeo = (type != null && (type.equalsIgnoreCase("GEO") || type.equalsIgnoreCase("GEO+CLOUD")));

                if (wantsGeo && alt != null) {
                    float[] q = yawToQuaternion(heading.floatValue());
                    Anchor a = earth.createAnchor(lat, lng, alt, q[0], q[1], q[2], q[3]);
                    prevAnchors.add(new WrappedAnchor(a, null, snap.getId(), grace));
                    mountedPrevDocIds.add(snap.getId());
                    added++;
                } else if (wantsGeo) {
                    earth.resolveAnchorOnTerrainAsync(
                            lat, lng, /*approxAlt*/ (float)(alt != null ? alt : camGp.getAltitude()),
                            0,0,0,1,
                            (terrainAnchor, state) -> {
                                if (state == Anchor.TerrainAnchorState.SUCCESS) {
                                    try {
                                        prevAnchors.add(new WrappedAnchor(terrainAnchor, null, snap.getId(), System.currentTimeMillis()+2500L));
                                        mountedPrevDocIds.add(snap.getId());
                                    } catch (Throwable ignore) {}
                                } else {
                                    if (terrainAnchor != null) terrainAnchor.detach();
                                }
                            });
                } else if (wantsCloudOnly) {
                    Anchor a = session.resolveCloudAnchor(cloudId);
                    prevAnchors.add(new WrappedAnchor(a, null, snap.getId(), grace));
                    mountedPrevDocIds.add(snap.getId());
                    added++;
                } else {
                    // Fallback: try terrain if nothing else matches
                    earth.resolveAnchorOnTerrainAsync(
                            lat, lng, /*approxAlt*/ (float)(alt != null ? alt : camGp.getAltitude()),
                            0,0,0,1,
                            (terrainAnchor, state) -> {
                                if (state == Anchor.TerrainAnchorState.SUCCESS) {
                                    try {
                                        prevAnchors.add(new WrappedAnchor(terrainAnchor, null, snap.getId(), System.currentTimeMillis()+2500L));
                                        mountedPrevDocIds.add(snap.getId());
                                    } catch (Throwable ignore) {}
                                } else {
                                    if (terrainAnchor != null) terrainAnchor.detach();
                                }
                            });
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to mount previous egg " + snap.getId(), t);
            }
        }
        if (added > 0) {
            final int addedCount = added;
            runOnUiThread(() ->
                    Toast.makeText(HelloArActivity.this,
                            "Loaded " + addedCount + " nearby egg(s).",
                            Toast.LENGTH_SHORT).show());
        }

        previousEggsLoadedOnce = true;
    }

    private static boolean isWithinMeters(double lat1, double lon1, double lat2, double lon2, double meters) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return (R * c) <= meters;
    }

    class WrappedAnchor {
        private final Anchor anchor;
        @Nullable private final Trackable trackable;
        @Nullable private final String docId;
        private final long noOccUntilMs; // per-anchor occlusion grace

        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId) {
            this(anchor, trackable, docId, 0L);
        }
        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId, long noOccUntilMs) {
            this.anchor = anchor;
            this.trackable = trackable;
            this.docId = docId;
            this.noOccUntilMs = noOccUntilMs;
        }
        Anchor getAnchor() { return anchor; }
        @Nullable Trackable getTrackable() { return trackable; }
        @Nullable String getDocId() { return docId; }
        boolean isOcclusionSuppressedAt(long nowMs) { return nowMs < noOccUntilMs; }
    }

    private static float[] yawToQuaternion(float yawDeg, float pitchDeg, float rollDeg) {
        return yawToQuaternion(yawDeg);
    }
}
