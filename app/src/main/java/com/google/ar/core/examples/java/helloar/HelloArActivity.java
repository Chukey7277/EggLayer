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
    private static final float Z_FAR  = 150f;

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
    private static final boolean SHOW_LOAD_TOAST = false;

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
    // Cached GL viewport size; never read surfaceView size on the GL thread.
    private int viewportWidth  = 1;
    private int viewportHeight = 1;
    @Nullable private Float rayForcedYawDeg = null;  // yaw to face camera in ray mode

    // === Textures ===
    private Texture[] starTextures;
    private int currentTexIndex = 0;
    private final String[] TEX_FILES = {
            "models/Image_0.png"
    };
    private final java.util.Random rng = new java.util.Random();

    // Desired physical size in meters
    private static final float STAR_WORLD_HEIGHT_M   = 0.10f; // 25 cm
    private static final float PUZZLE_WORLD_HEIGHT_M = 0.30f; // 18 cm

    // Approx raw height of each OBJ in its own units (Y-extent).
// If you don't know, start with 1.0f and adjust.
    private static final float STAR_RAW_HEIGHT_UNITS   = 1.0f;
    private static final float PUZZLE_RAW_HEIGHT_UNITS = 1.0f;
    // One place to tune model size in world meters-per-model-unit

    private static final float MODEL_BASE_SCALE = 0.25f; // was 0.02f → ~40% smaller
    private static final float STAR_SIZE_MULT   = 0.5f;
    private static final float PUZZLE_SIZE_MULT = 2f;   // or 1.0f if you want same as star

    private static final float[] MODEL_UPRIGHT_FIX = quatAxisAngle(1, 0, 0, 0f);
    // Extra multipliers applied only to *saved* anchors (prevAnchors)
    private static final float SAVED_STAR_MULT   = 1.6f; // <- bump to taste
    private static final float SAVED_PUZZLE_MULT = 1.0f; // keep puzzles unchanged

    // turn on to lock the star's orientation everywhere
    private static final boolean ALWAYS_FACE_CAMERA = true;
    // Put near other enums:
    private enum ModelType { STAR, PUZZLE }
    // Per-model assets
    private Mesh[]  starMeshes,  puzzleMeshes;
    private Shader[] starShaders, puzzleShaders;

    // Optional: separate textures (in case you want different look)
    private Texture puzzleTexture;


    private boolean inPuzzleFlow = false;
    private ModelType currentPreviewModel = ModelType.STAR;
    private static final float STAR_TARGET_PX   = 24f;  // try 32–64
    private static final float PUZZLE_TARGET_PX = 22f;  // magnifier a bit smaller
    @Nullable private WrappedAnchor selectedAnchor = null;
    private static final float PICK_RADIUS_PX = 96f; // tap distance threshold in screen pixels



    // If you prefer a single global orientation (not facing camera), use this instead:
    private static final float[] FIXED_WORLD_Q = quatMul(yawToQuaternion(0f), MODEL_UPRIGHT_FIX);

    // Build a pose that is always upright and yawed to face the camera (billboard at placement time).
    private Pose makeBillboardPose(Pose at, Camera camera) {
        float[] t = at.getTranslation();
        float[] c = camera.getPose().getTranslation();
        float yawDeg = (float) Math.toDegrees(Math.atan2(c[0] - t[0], c[2] - t[2]));
        float[] q = quatMul(yawToQuaternion(yawDeg), MODEL_UPRIGHT_FIX);
        return new Pose(new float[]{t[0], t[1], t[2]}, q);
    }

    @Nullable
    private WrappedAnchor findNearbyAnchor(Pose p, float meters) {
        float[] t = p.getTranslation();
        // Check already-mounted stars (prev) and any live preview(s) (wrapped)
        for (WrappedAnchor w : prevAnchors) {
            Anchor a = (w != null) ? w.getAnchor() : null;
            if (a == null) continue;
            Pose ap = a.getPose();
            float dx = ap.tx() - t[0], dy = ap.ty() - t[1], dz = ap.tz() - t[2];
            if (Math.sqrt(dx*dx + dy*dy + dz*dz) <= meters) return w;
        }
        for (WrappedAnchor w : wrappedAnchors) {
            Anchor a = (w != null) ? w.getAnchor() : null;
            if (a == null) continue;
            Pose ap = a.getPose();
            float dx = ap.tx() - t[0], dy = ap.ty() - t[1], dz = ap.tz() - t[2];
            if (Math.sqrt(dx*dx + dy*dy + dz*dz) <= meters) return w;
        }
        return null;
    }

    // 2D (horizontal) duplicate check — more forgiving across floors/walls
    @Nullable
    private WrappedAnchor findNearbyAnchor2D(Pose p, float meters) {
        float[] t = p.getTranslation();
        for (WrappedAnchor w : prevAnchors) {
            Anchor a = (w != null) ? w.getAnchor() : null;
            if (a == null) continue;
            Pose ap = a.getPose();
            float dx = ap.tx() - t[0], dz = ap.tz() - t[2];
            if (Math.hypot(dx, dz) <= meters) return w;
        }
        for (WrappedAnchor w : wrappedAnchors) {
            Anchor a = (w != null) ? w.getAnchor() : null;
            if (a == null) continue;
            Pose ap = a.getPose();
            float dx = ap.tx() - t[0], dz = ap.tz() - t[2];
            if (Math.hypot(dx, dz) <= meters) return w;
        }
        return null;
    }

    /** Add the saved star immediately to the persistent list so it stays visible after Save. */


    private void saveHeightAboveTerrain(
            Earth earth,
            double lat,
            double lng,
            double hitAlt,   // altitude of our placed anchor (WGS84)
            String eggId
    ) {
        earth.resolveAnchorOnTerrainAsync(
                lat, lng, (float) hitAlt, 0, 0, 0, 1,
                (terrainAnchor, state) -> {
                    try {
                        if (state == Anchor.TerrainAnchorState.SUCCESS && terrainAnchor != null) {
                            GeospatialPose tp = earth.getGeospatialPose(terrainAnchor.getPose());
                            if (tp == null) { Log.w(TAG, "Terrain pose null"); return; }

                            double terrainAlt = tp.getAltitude();   // also WGS84
                            double hat = hitAlt - terrainAlt;

                            // Discard obviously wrong values (indoor/DEPTH or poor geo)
                            // Keep within [-0.5, 10] meters; tune as you like.
                            if (Double.isNaN(hat) || hat < -0.5 || hat > 10.0) {
                                Log.w(TAG, "Discarding unrealistic HAT=" + hat +
                                        " (hitAlt=" + hitAlt + ", terrainAlt=" + terrainAlt + ")");
                                return;
                            }

                            Map<String, Object> m = new HashMap<>();
                            m.put("heightAboveTerrain", hat);

                            FirebaseFirestore.getInstance()
                                    .collection(EGGS).document(eggId)
                                    .set(m, SetOptions.merge());

                            Log.d(TAG, "Saved heightAboveTerrain=" + hat);
                        } else {
                            Log.w(TAG, "Terrain resolve " + state + " — skipping HAT.");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "HAT patch failed", t);
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
                poseInfoCard.setText(String.format(Locale.US, "Distance: %.1f m  (pinch)", rayDistanceM));
            }
        });
    }

    /** Helper: cleanly remove any existing preview before creating a new one. */
    private void clearExistingPreview() {
        for (WrappedAnchor w : new ArrayList<>(wrappedAnchors)) {
            try {
                Anchor old = w.getAnchor();
                if (old != null) {
                    old.detach();
                    lastStableT.remove(old);
                }
            } catch (Throwable ignore) {}
        }
        wrappedAnchors.clear();
        currentPlacedAnchor = null;
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

        // --- Force a (re)load window for previous anchors on resume ---
        previousEggsLoadedOnce = false;   // allow loading again
        lastPrevLoadAtMs = 0L;            // don't wait for PREV_RELOAD_MS

        // Try to load immediately if AR Earth is already tracking; otherwise onDrawFrame() will do it.
        try {
            Earth earth = (session != null) ? session.getEarth() : null;
            if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
                GeospatialPose camGp = earth.getCameraGeospatialPose();
                if (camGp != null) {
                    maybeLoadPreviousEggs(earth, camGp); // uses the improved empty-results fallback
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Immediate previous-eggs load on resume skipped", t);
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
        rayForcedYawDeg = null;
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

    @Override
    public void onSurfaceCreated(SampleRender render) {
        try {
            // ==== camera background / depth / occlusion ====
            backgroundRenderer = new BackgroundRenderer(render);
            try {
                backgroundRenderer.setUseDepthVisualization(render, depthSettings.depthColorVisualizationEnabled());
                backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
            } catch (Exception e) {
                Log.w(TAG, "Applying background settings failed", e);
            }
            backgroundReady = true;
            trySetBackgroundZoom(zoomFactor);

            // Plane grid & virtual scene target
            planeRenderer = new PlaneRenderer(render);
            virtualSceneFramebuffer = new Framebuffer(render, 1, 1);

            // IBL / PBR helpers
            cubemapFilter = new SpecularCubemapFilter(
                    render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES);

            // DFG LUT
            dfgTexture = new Texture(render, Texture.Target.TEXTURE_2D,
                    Texture.WrapMode.CLAMP_TO_EDGE, false);
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

            // Point cloud (optional)
            try {
                pointCloudShader = Shader.createFromAssets(
                                render, "shaders/point_cloud.vert", "shaders/point_cloud.frag", null)
                        .setVec4("u_Color", new float[]{31f/255f,188f/255f,210f/255f,1f})
                        .setFloat("u_PointSize", 5f);
                pointCloudVertexBuffer = new VertexBuffer(render, 4, null);
                pointCloudMesh = new Mesh(render, Mesh.PrimitiveMode.POINTS, null,
                        new VertexBuffer[]{pointCloudVertexBuffer});
            } catch (IOException e) {
                Log.w(TAG, "Point cloud shaders missing; disabling point cloud.", e);
                pointCloudShader = null; pointCloudMesh = null; pointCloudVertexBuffer = null;
            }

            // ==== MODELS ====
            boolean pbrReady = false;
            try {
                // --- STAR (textures optional list) ---
                this.starTextures = new Texture[TEX_FILES.length];
                for (int i = 0; i < TEX_FILES.length; i++) {
                    this.starTextures[i] = Texture.createFromAsset(
                            render, TEX_FILES[i],
                            Texture.WrapMode.CLAMP_TO_EDGE,
                            Texture.ColorFormat.SRGB
                    );
                }
                int pick = rng.nextInt(this.starTextures.length);
                virtualObjectAlbedoTexture = this.starTextures[pick];

                Mesh starMesh = Mesh.createFromAsset(render, "models/star.obj");

                Map<String, String> defs = new HashMap<>();
                defs.put("NUMBER_OF_MIPMAP_LEVELS",
                        Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));

                Shader starPbr = Shader.createFromAssets(
                                render, "shaders/environmental_hdr.vert", "shaders/environmental_hdr.frag", defs)
                        .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
                        .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
                        .setTexture("u_DfgTexture", dfgTexture)
                        .setDepthTest(true)
                        .setDepthWrite(false);

                // --- PUZZLE (magnifier) ---
                Texture magnifierTex;
                Mesh magnifierMesh;
                try {
                    magnifierTex = Texture.createFromAsset(
                            render, "models/magnifying_glass1.png",
                            Texture.WrapMode.CLAMP_TO_EDGE,
                            Texture.ColorFormat.SRGB
                    );
                    magnifierMesh = Mesh.createFromAsset(render, "models/magnifying_glass.obj");
                } catch (IOException miss) {
                    Log.w(TAG, "Puzzle assets missing; falling back to STAR visuals.", miss);
                    magnifierTex  = virtualObjectAlbedoTexture;
                    magnifierMesh = starMesh;
                }
                Shader puzzlePbr = Shader.createFromAssets(
                                render, "shaders/environmental_hdr.vert", "shaders/environmental_hdr.frag", defs)
                        .setTexture("u_AlbedoTexture", magnifierTex)
                        .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
                        .setTexture("u_DfgTexture", dfgTexture)
                        .setDepthTest(true)
                        .setDepthWrite(false);

                // Per-model arrays used by the draw loop
                starMeshes    = new Mesh[]  { starMesh };
                starShaders   = new Shader[]{ starPbr };
                puzzleMeshes  = new Mesh[]  { magnifierMesh };
                puzzleShaders = new Shader[]{ puzzlePbr };

                // Legacy (kept if other code references them)
                virtualObjectMesh   = starMesh;
                virtualObjectShader = starPbr;

                // Bind chosen STAR texture across pipelines
                setStarTextureIndex(pick);

                pbrReady = true;
            } catch (IOException e) {
                Log.e(TAG, "PBR assets missing — will fall back to unlit.", e);
            } catch (Throwable t) {
                Log.w(TAG, "PBR pipeline init failed — will fall back to unlit.", t);
            }

            // ==== Unlit fallback for both STAR and PUZZLE ====
            if (!pbrReady) {
                try {
                    Texture starTex = (virtualObjectAlbedoTexture != null) ? virtualObjectAlbedoTexture
                            : Texture.createFromAsset(
                            render, "models/Image_0.png",
                            Texture.WrapMode.CLAMP_TO_EDGE,
                            Texture.ColorFormat.SRGB
                    );
                    Mesh starMesh = (virtualObjectMesh != null) ? virtualObjectMesh
                            : Mesh.createFromAsset(render, "models/star.obj");

                    Shader starUnlit = Shader.createFromAssets(
                                    render, "shaders/ar_unlit_object.vert", "shaders/ar_unlit_object.frag", null)
                            .setTexture("u_Texture", starTex)
                            .setFloat("u_Opacity", 1.0f)
                            .setDepthTest(true)
                            .setDepthWrite(true);

                    Texture magnifierTex;
                    Mesh magnifierMesh;
                    try {
                        magnifierTex = Texture.createFromAsset(render, "models/magnifying_glass1.png",
                                Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB);
                        magnifierMesh = Mesh.createFromAsset(render, "models/magnifying_glass.obj");
                    } catch (IOException miss) {
                        Log.w(TAG, "Puzzle assets missing in unlit path; using STAR assets.", miss);
                        magnifierTex  = starTex;
                        magnifierMesh = starMesh;
                    }

                    Shader puzzleUnlit = Shader.createFromAssets(
                                    render, "shaders/ar_unlit_object.vert", "shaders/ar_unlit_object.frag", null)
                            .setTexture("u_Texture", magnifierTex)
                            .setFloat("u_Opacity", 1.0f)
                            .setDepthTest(true)
                            .setDepthWrite(true);

                    starMeshes    = new Mesh[]  { starMesh };
                    starShaders   = new Shader[]{ starUnlit };
                    puzzleMeshes  = new Mesh[]  { magnifierMesh };
                    puzzleShaders = new Shader[]{ puzzleUnlit };

                    unlitShader = starUnlit;
                    virtualObjectMesh = starMesh;

                    setStarTextureIndex(0);
                } catch (IOException io) {
                    Log.e(TAG, "Unlit fallback also failed. No renderable models.", io);
                    starMeshes = puzzleMeshes = null;
                    starShaders = puzzleShaders = null;
                }
            }

            // GL state hygiene
            try {
                GLES30.glEnable(GLES30.GL_DEPTH_TEST);
                GLES30.glDepthFunc(GLES30.GL_LEQUAL);
                GLES30.glDepthMask(true);
                GLES30.glDisable(GLES30.GL_CULL_FACE);
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.e(TAG, "onSurfaceCreated: fatal init error", t);
        }
    }
    private void proceedToSave(
            String title,
            String description,
            List<Uri> photoUris,
            @Nullable Uri audioUri,
            Camera cameraForSave
    ) {
        inMetadataFlow = false;
        placementModeActive = false;
        uiShowProgress("Saving", inPuzzleFlow ? "Saving the puzzle…" : "Saving the star…", null);

        GeospatialPose finalGp = null;
        Earth earthNow = null;
        try {
            earthNow = session.getEarth();
            if (earthNow != null
                    && earthNow.getTrackingState() == TrackingState.TRACKING
                    && currentPlacedAnchor != null) {
                finalGp = earthNow.getGeospatialPose(currentPlacedAnchor.getPose());
            }
        } catch (Throwable ignore) {}

        double headingNow = Double.NaN;
        try {
            GeospatialPose camNow = (earthNow != null) ? earthNow.getCameraGeospatialPose() : null;
            headingNow = goodHeadingDeg(camNow, lastGoodGeoPose, headGate());
        } catch (Throwable ignore) {}

        // Build the document model
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
            e.vertAcc  = Double.isNaN(vAcc) ? null : vAcc;
            e.alt      = finalGp.getAltitude();
        }

        Trackable firstTrackable = (!wrappedAnchors.isEmpty()) ? wrappedAnchors.get(0).getTrackable() : null;
        e.placementType = (firstTrackable != null) ? firstTrackable.getClass().getSimpleName() : "Local";
        e.distanceFromCamera = (currentPlacedAnchor != null)
                ? distance(currentPlacedAnchor.getPose(), cameraForSave.getPose())
                : 0f;

        // Anchor type decided by flow (initial value; we may patch later)
        e.anchorType = inPuzzleFlow ? "GEO_PUZZLE" : "CLOUD";

        String cellKeyStr = (finalGp != null) ? cellKey(finalGp.getLatitude(), finalGp.getLongitude()) : null;
        final Earth earthNowF = earthNow;
        final GeospatialPose finalGpF = finalGp;

        eggRepo.createDraft(e).addOnSuccessListener(docRef -> {

            // Patch orientation + extras
            try {
                float[] q = (lastHitPose != null)
                        ? lastHitPose.getRotationQuaternion()
                        : (currentPlacedAnchor != null
                        ? currentPlacedAnchor.getPose().getRotationQuaternion()
                        : new float[]{0,0,0,1});

                Map<String, Object> orient = new HashMap<>();
                orient.put("localQx", q[0]);
                orient.put("localQy", q[1]);
                orient.put("localQz", q[2]);
                orient.put("localQw", q[3]);
                orient.put("surface", lastHitSurfaceType);
                orient.put("placementEnv", envMode.name());
                if (cellKeyStr != null) orient.put("cellKey", cellKeyStr);
                orient.put("model", inPuzzleFlow ? "puzzle" : "star");

                Map<String, Object> patch = new HashMap<>(orient);
                patch.put("extras", new HashMap<>(orient));

                FirebaseFirestore.getInstance().collection(EGGS)
                        .document(docRef.getId())
                        .set(patch, SetOptions.merge());
            } catch (Throwable t) {
                Log.w(TAG, "Failed to patch local quaternion", t);
            }

            uiShowProgress("Saving", "Uploading media…", null);

            // --- Only compute HAT when it makes sense (planes + decent accuracy) ---
            try {
                boolean okSurface =
                        "PLANE_HORIZONTAL".equals(lastHitSurfaceType) ||
                                "PLANE_VERTICAL".equals(lastHitSurfaceType);
                boolean okAcc = (finalGpF != null) &&
                        finalGpF.getHorizontalAccuracy() <= hGate() &&
                        !Double.isNaN(finalGpF.getVerticalAccuracy()) &&
                        finalGpF.getVerticalAccuracy() <= vGate();

                if (earthNowF != null && finalGpF != null && e.geo != null && okSurface && okAcc) {
                    saveHeightAboveTerrain(
                            earthNowF,
                            e.geo.getLatitude(), e.geo.getLongitude(),
                            finalGpF.getAltitude(),
                            docRef.getId()
                    );
                } else {
                    Log.d(TAG, "Skipping HAT (surface=" + lastHitSurfaceType +
                            ", hAcc=" + (finalGpF != null ? finalGpF.getHorizontalAccuracy() : -1) +
                            ", vAcc=" + (finalGpF != null ? finalGpF.getVerticalAccuracy() : -1) + ")");
                }
            } catch (Throwable t) { Log.w(TAG, "resolveAnchorOnTerrainAsync skipped", t); }

            // Persist URI permissions
            if (photoUris != null) {
                for (Uri u : photoUris) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                                u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignore) {}
                }
            }
            if (audioUri != null) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            audioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignore) {}
            }

            eggRepo.uploadMediaAndPatch(docRef, photoUris, audioUri)
                    .addOnSuccessListener(v -> {
                        Toast.makeText(HelloArActivity.this, "Media uploaded ✓", Toast.LENGTH_SHORT).show();

                        if (!inPuzzleFlow && localAnchorForHosting != null) {
                            // STAR path → host cloud
                            FirebaseFirestore.getInstance().collection(EGGS).document(docRef.getId())
                                    .update("cloudStatus", "HOSTING",
                                            "cloudTtlDays", CLOUD_TTL_DAYS,
                                            "anchorType", (envMode == Env.OUTDOOR ? "GEO+CLOUD" : "CLOUD"));
                            uiShowProgress("Saving", "Hosting anchor in the cloud…", "This can take a few seconds");
                            pendingEggDocId = docRef.getId();

                            // Keep the item visible by mounting the persisted (saved) STAR
                            addPersistentAnchorForSaved(
                                    docRef.getId(), finalGpF,
                                    (lastHitPose != null ? lastHitPose.getRotationQuaternion() : null),
                                    null, ModelType.STAR);

                            // Start hosting using the dedicated local anchor
                            startHostingCloudAnchor(localAnchorForHosting, pendingEggDocId);

                        } else {
                            // PUZZLE path → no hosting, save geospatial + show magnifier
                            if (finalGpF != null) {
                                FirebaseFirestore.getInstance().collection(EGGS).document(docRef.getId())
                                        .update("cloudStatus", "NONE", "anchorType", "GEO_PUZZLE")
                                        .addOnSuccessListener(v2 -> {
                                            // Mount the persisted (saved) PUZZLE
                                            addPersistentAnchorForSaved(
                                                    docRef.getId(), finalGpF,
                                                    (lastHitPose != null ? lastHitPose.getRotationQuaternion() : null),
                                                    null, ModelType.PUZZLE);

                                            // Remove the temporary preview so taps select the saved one
                                            try { clearExistingPreview(); } catch (Throwable ignore) {}
                                            currentPlacedAnchor = null;
                                            inPuzzleFlow = false;
                                            placementModeActive = true;
                                            readyPromptShown = true;
                                            localAnchorForHosting = null;
                                            hostState = HostState.IDLE;

                                            // End-of-flow title resets the center dialog state
                                            uiShowMessage("All set!",
                                                    "Saved by location with photos ✓ (no cloud host).", true);
                                        })
                                        .addOnFailureListener(err ->
                                                uiShowMessage("Save failed",
                                                        "Could not mark as puzzle: " + err.getMessage(), true));
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
                        Toast.makeText(HelloArActivity.this,
                                "Upload failed: " + err.getMessage(), Toast.LENGTH_LONG).show();
                        uiShowMessage("Upload failed", "Media upload failed: " + err.getMessage(), true);
                    });

        }).addOnFailureListener(err -> {
            Log.e(TAG, "Egg save failed", err);
            uiShowMessage("Save failed", "Could not save: " + err.getMessage(), true);
        });
    }


    @Override
    public void onSurfaceChanged(SampleRender render, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        virtualSceneFramebuffer.resize(width, height);
        // NEW: cache viewport size for use in drawAnchorsList (surfaceView.getHeight() is 0 on GL thread)
        viewportWidth  = Math.max(1, width);
        viewportHeight = Math.max(1, height);
    }

    @Override
    public void onDrawFrame(SampleRender render) {
        if (session == null || sessionPaused) return;
        if (!backgroundReady || backgroundRenderer == null) return;

        if (!hasSetTextureNames && backgroundRenderer.getCameraColorTexture() != null) {
            try {
                session.setCameraTextureNames(
                        new int[]{ backgroundRenderer.getCameraColorTexture().getTextureId() });
                hasSetTextureNames = true;
            } catch (Throwable t) {
                Log.e(TAG, "Failed to set camera texture names", t);
                return;
            }
        }

        try { displayRotationHelper.updateSessionIfNeeded(session); } catch (Throwable ignore) {}

        final Frame frame;
        try {
            frame = session.update();
        } catch (CameraNotAvailableException e) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
            return;
        } catch (Throwable t) {
            return;
        }

        final Camera camera = frame.getCamera();

        try { backgroundRenderer.updateDisplayGeometry(frame); } catch (Throwable t) { return; }

        if (camera.getTrackingState() == TrackingState.TRACKING
                && (depthSettings.useDepthForOcclusion() || depthSettings.depthColorVisualizationEnabled())) {
            try (Image depthImage = frame.acquireDepthImage16Bits()) {
                backgroundRenderer.updateCameraDepthTexture(depthImage);
            } catch (Throwable ignore) {}
        }

        trySetBackgroundZoom(zoomFactor);

        // Do not react to taps when metadata sheet is open
        if (!inMetadataFlow) {
            try { handleTap(frame, camera); } catch (Throwable ignore) {}
        }

        try {
            if (frame.getTimestamp() != 0) backgroundRenderer.drawBackground(render);
        } catch (Throwable t) {
            return;
        }
        if (camera.getTrackingState() != TrackingState.TRACKING) return;

        // Scan UI hints — SUPPRESS while the metadata sheet is on screen
        try {
            if (inMetadataFlow) {
                // Ensure any previous coach/progress dialog is hidden while user types
                uiHideStatus();
            } else {
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
                                "Aim at well-lit, textured areas.");
                    } else if (!readyPromptShown) {
                        readyPromptShown = true;
                        placementModeActive = true;
                        uiShowMessage(
                                "Ready",
                                "Surfaces detected — tap OK to start placing.",
                                true);
                    }
                } else {
                    uiHideStatus();
                }
            }
        } catch (Throwable ignore) {}

        // Camera matrices
        try {
            camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);
            camera.getViewMatrix(viewMatrix, 0);
        } catch (Throwable t) { return; }

        // --- Draw plane grid with polygon offset ON (so it doesn't z-fight with anchors) ---
        try { GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); } catch (Throwable ignore) {}
        try { GLES30.glPolygonOffset(1.0f, 1.0f); } catch (Throwable ignore) {}
        try {
            if (planeRenderer != null) {
                planeRenderer.drawPlanes(
                        render,
                        session.getAllTrackables(Plane.class),
                        camera.getDisplayOrientedPose(),
                        projectionMatrix
                );
            }
        } catch (Throwable ignore) {}
        // Turn OFF polygon offset before drawing any virtual objects
        try { GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL); } catch (Throwable ignore) {}

        // Point cloud (optional)
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
        } catch (Throwable ignore) {}

        // Ensure sane depth state for our content
        try {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthFunc(GLES30.GL_LEQUAL);
            GLES30.glDepthMask(true);
            // Safety: polygon offset must be OFF for anchors
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        } catch (Throwable ignore) {}

        // Prepare the virtual scene target
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);

        // Maintain anchor lists
        pruneDeadAnchors(prevAnchors);
        pruneDeadAnchors(wrappedAnchors);

        // Occlusion toggle (avoid depth occlusion during PAUSED anchors/grace windows)
        boolean anyPaused = hasPausedAnchors(prevAnchors) || hasPausedAnchors(wrappedAnchors);
        boolean anyPerAnchorNoOcc = hasActiveNoOcc(prevAnchors) || hasActiveNoOcc(wrappedAnchors);
        boolean suppressOcclusion = anyPaused || anyPerAnchorNoOcc || (System.currentTimeMillis() < noOccUntilMs);
        try {
            backgroundRenderer.setUseOcclusion(
                    render,
                    suppressOcclusion ? false : depthSettings.useDepthForOcclusion()
            );
        } catch (Throwable ignore) {}

        // Extra safety: make sure polygon offset is OFF before drawing models
        try { GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL); } catch (Throwable ignore) {}
        drawAnchorsList(prevAnchors,    MODEL_BASE_SCALE, /*isSavedList=*/true,  render);
        drawAnchorsList(wrappedAnchors, MODEL_BASE_SCALE, /*isSavedList=*/false, render);

        // Composite virtual scene
        final boolean vsReady = backgroundRenderer != null && backgroundRenderer.isVirtualSceneInitialized();
        if (virtualSceneFramebuffer != null && vsReady) {
            backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
        }

        // Cloud Anchor state machine
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

                    FirebaseFirestore.getInstance().collection(EGGS)
                            .document(pendingEggDocId)
                            .update(patch)
                            .addOnSuccessListener(v -> uiShowMessage(
                                    "All set!",
                                    "Egg saved and cloud hosted ✓\nTap OK to place again.",
                                    true))
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Failed to patch egg with cloudId", e);
                                uiShowMessage(
                                        "All set!",
                                        "Egg saved and hosted ✓ (but metadata patch failed).\nTap OK to place again.",
                                        true);
                            });
                } else {
                    uiShowMessage("All set!", "Egg saved and hosted ✓\nTap OK to place again.", true);
                }
            }
        }

        // Telemetry / HUD
        try {
            Earth earth = null;
            try { earth = session.getEarth(); } catch (Throwable ignore) {}
            final String earthLine = (earth == null)
                    ? "Earth: not available"
                    : "AR Earth: " + earth.getEarthState() + " • Tracking: " + earth.getTrackingState();

            String latLngLine = "LAT/LNG: —";
            String latLngAcc  = "H-Accuracy: —";
            String altLine    = "ALTITUDE: —";
            String altAcc     = "V-Accuracy: —";
            String headLine   = "HEADING: —";
            String headAcc    = "Heading accuracy: —";
            String anchorLine = "Anchor: none";

            Anchor currentAnchor = (!wrappedAnchors.isEmpty() && wrappedAnchors.get(0) != null)
                    ? wrappedAnchors.get(0).getAnchor() : null;
            if (currentAnchor != null) {
                String suffix;
                if (hostState == HostState.HOSTING)      suffix = " (CLOUD…)";
                else if (hostState == HostState.SUCCESS) suffix = " (CLOUD✔)";
                else if (envMode == Env.OUTDOOR)         suffix = " (LOCAL)";
                else                                     suffix = " (LOCAL)";
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

                    latLngLine = String.format(Locale.US, "LAT/LNG: %.6f°, %.6f°",
                            camGp.getLatitude(), camGp.getLongitude());
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

    private int drawAnchorsList(List<WrappedAnchor> list, float baseScale, boolean isSavedList, SampleRender render) {
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
                for (int i = 0; i < 3; i++) {
                    t[i] = SMOOTHING_ALPHA * last[i] + (1f - SMOOTHING_ALPHA) * t[i];
                }
            }
            lastStableT.put(a, t.clone());
            Pose pSmooth = new Pose(t, new float[]{p.qx(), p.qy(), p.qz(), p.qw()});
            pSmooth.toMatrix(modelMatrix, 0);

            float liftY = 0.03f + (isPaused ? 0.03f : 0f);
            Matrix.translateM(modelMatrix, 0, 0f, liftY, 0f);

            final boolean isPuzzle = (wrapped.getModelType() == ModelType.PUZZLE);
            float targetHeightM  = isPuzzle ? PUZZLE_WORLD_HEIGHT_M   : STAR_WORLD_HEIGHT_M;
            float rawHeightUnits = isPuzzle ? PUZZLE_RAW_HEIGHT_UNITS : STAR_RAW_HEIGHT_UNITS;
            float typeMult       = isPuzzle ? PUZZLE_SIZE_MULT        : STAR_SIZE_MULT;

            // NEW: bigger only for saved stars (not previews)
            float savedListMult  = 1.0f;
            if (isSavedList) {
                savedListMult = isPuzzle ? SAVED_PUZZLE_MULT : SAVED_STAR_MULT;
            }

            float s = (targetHeightM / Math.max(1e-6f, rawHeightUnits)) * typeMult * savedListMult * baseScale;
            Matrix.scaleM(modelMatrix, 0, s, s, s);

            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

            Mesh[]   meshes  = isPuzzle ? puzzleMeshes  : starMeshes;
            Shader[] shaders = isPuzzle ? puzzleShaders : starShaders;

            if (meshes != null && shaders != null && meshes.length == shaders.length && meshes.length > 0) {
                for (int i = 0; i < meshes.length; i++) {
                    if (meshes[i] == null || shaders[i] == null) continue;
                    try { shaders[i].setMat4("u_ModelViewProjection", modelViewProjectionMatrix); } catch (Throwable ignore) {}
                    try { shaders[i].setMat4("u_ModelView",           modelViewMatrix);           } catch (Throwable ignore) {}
                    try { shaders[i].setMat4("u_Model",               modelMatrix);               } catch (Throwable ignore) {}

                    if (isPaused) {
                        try { shaders[i].setDepthTest(false); } catch (Throwable ignore) {}
                        try { shaders[i].setFloat("u_Opacity", 0.85f); } catch (Throwable ignore) {}
                        render.draw(meshes[i], shaders[i], virtualSceneFramebuffer);
                        try { shaders[i].setDepthTest(true); } catch (Throwable ignore) {}
                        try { shaders[i].setFloat("u_Opacity", 1.0f); } catch (Throwable ignore) {}
                    } else {
                        try { shaders[i].setFloat("u_Opacity", 1.0f); } catch (Throwable ignore) {}
                        render.draw(meshes[i], shaders[i], virtualSceneFramebuffer);
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

        float[] yawQ = yawToQuaternion(
                forcedYawDeg != null ? forcedYawDeg : (float) (Math.atan2(-r.dir[0], -r.dir[2]) * 180.0/Math.PI)
        );
        // NEW: apply upright fix so the model isn't flat when ray-placed
        float[] q = quatMul(yawQ, MODEL_UPRIGHT_FIX);
        return new Pose(new float[]{px,py,pz}, q);
    }

    /** Re-create the preview anchor at the current ray/distance, cleanly swapping the old one. */
    private void refreshRayPreview() {
        if (session == null || activeRay == null) return;

        clearExistingPreview();

        Pose p = poseAlongRay(activeRay, rayDistanceM, rayForcedYawDeg);
        Anchor a = session.createAnchor(p);
        currentPlacedAnchor = a;
        lastHitPose = p;
        lastHitSurfaceType = "GEO_RAY";

        long grace = System.currentTimeMillis() + 1500L;

        // Ray preview is for Puzzle only
        inPuzzleFlow = true;
        currentPreviewModel = ModelType.PUZZLE;

        // IMPORTANT: use the 5-arg ctor so the model type is PUZZLE
        wrappedAnchors.add(new WrappedAnchor(a, null, null, grace, ModelType.PUZZLE));
        noOccUntilMs = grace;
    }
    // ====== /helpers ======

    private void handleTap(Frame frame, Camera camera) {
        MotionEvent tap = tapHelper.poll();
        if (tap == null) return;

        if (camera.getTrackingState() != TrackingState.TRACKING) {
            messageSnackbarHelper.showMessage(this, "Move slowly and point at well-lit, textured surfaces.");
            return;
        }
        WrappedAnchor hitAnchor = pickAnchorAtTap(tap.getX(), tap.getY());
        if (hitAnchor != null) {
            showAnchorInfo(hitAnchor);
            return; // don’t place a new one on top of a selection tap
        }

        final Pose camPoseNow = camera.getPose();
        final float MAX_HIT_M = 40.0f;

        // 1) Hit test & bucketize
        List<HitResult> raw = frame.hitTest(tap);
        List<HitResult> planesVert  = new ArrayList<>();
        List<HitResult> planesHoriz = new ArrayList<>();
        List<HitResult> depths      = new ArrayList<>();
        List<HitResult> geoms       = new ArrayList<>();
        List<HitResult> pointsSurf  = new ArrayList<>();
        List<HitResult> pointsAny   = new ArrayList<>();

        for (HitResult hit : raw) {
            if (distance(hit.getHitPose(), camPoseNow) > MAX_HIT_M) continue;

            Trackable tr = hit.getTrackable();
            if (tr instanceof Plane) {
                Plane pl = (Plane) tr;
                boolean inPoly  = pl.isPoseInPolygon(hit.getHitPose());
                boolean inFront = PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camPoseNow) > 0;
                if (inPoly && inFront) {
                    if (pl.getType() == Plane.Type.VERTICAL) planesVert.add(hit);
                    else planesHoriz.add(hit);
                }
            } else if (tr instanceof DepthPoint) {
                depths.add(hit);
            } else if (tr instanceof StreetscapeGeometry) {
                geoms.add(hit);
            } else if (tr instanceof Point) {
                Point p = (Point) tr;
                if (p.getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) pointsSurf.add(hit);
                else pointsAny.add(hit);
            }
        }

        Comparator<HitResult> byDist = (a, b) ->
                Float.compare(distance(a.getHitPose(), camPoseNow), distance(b.getHitPose(), camPoseNow));
        Collections.sort(planesVert,  byDist);
        Collections.sort(planesHoriz, byDist);
        Collections.sort(depths,      byDist);
        Collections.sort(geoms,       byDist);
        Collections.sort(pointsSurf,  byDist);
        Collections.sort(pointsAny,   byDist);

        // 2) Hostable = planes only (mesh/grid)
        HitResult hostable =
                !planesVert.isEmpty()  ? planesVert.get(0)
                        : !planesHoriz.isEmpty() ? planesHoriz.get(0) : null;

        // 3) A *non-plane* visual hit (depth/points/geometries)
        HitResult nonPlaneHit =
                !depths.isEmpty()     ? depths.get(0)
                        : !geoms.isEmpty()      ? geoms.get(0)
                        : !pointsAny.isEmpty()  ? pointsAny.get(0)
                        : !pointsSurf.isEmpty() ? pointsSurf.get(0) : null;

        // ---------- STAR on a plane (hostable) ----------
        if (hostable != null) {
            Pose corrected = makeBillboardPose(hostable.getHitPose(), camera);
            // Optional duplicate logic (kept simple: always place)
            placeAndOpenSheet(false, corrected, hostable.getTrackable(), hostable, camera);
            return;
        }

        // ---------- Not on meshes/grids → ASK FIRST, then (optionally) place PUZZLE ----------
        if (nonPlaneHit != null) {
            final Pose corrected = makeBillboardPose(nonPlaneHit.getHitPose(), camera);
            final Trackable tr = nonPlaneHit.getTrackable();

            promptUndetectableSurface(() -> {
                // On Continue → place magnifying glass and open the sheet (≥1 photo enforced there)
                placeAndOpenSheet(true, corrected, tr, null, camera);
            });
            return;
        }

        // ---------- No hits at all → geo-ray fallback → ASK FIRST, then place PUZZLE ahead ----------
        {
            int vw = surfaceView.getWidth(), vh = surfaceView.getHeight();
            Ray r = screenTapToWorldRay(camera, tap.getX(), tap.getY(), vw, vh);

            float yawDeg = (float) Math.toDegrees(Math.atan2(-r.dir[0], -r.dir[2]));
            final Pose candidate = poseAlongRay(r, 6f, yawDeg);

            promptUndetectableSurface(() -> {
                placeAndOpenSheet(true, candidate, null, null, camera);
            });
        }
    }
    /** Show a dialog before placing a PUZZLE on non-mesh taps. */
    private void promptUndetectableSurface(Runnable onContinue) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            new AlertDialog.Builder(HelloArActivity.this)
                    .setTitle("Undetectable surface")
                    .setMessage(
                            "We couldn’t detect a plane here.\n\n" +
                                    "Do you want to place a Puzzle (magnifying glass) by location instead?\n" +
                                    "Note: you’ll need to add at least one photo before saving."
                    )
                    .setPositiveButton("Continue", (d, w) -> {
                        try { onContinue.run(); } catch (Throwable ignore) {}
                    })
                    .setNegativeButton("No", null)
                    .show();
        });
    }
    private void placeAndOpenSheet(
            boolean asPuzzle,
            Pose pose,
            @Nullable Trackable trackable,
            @Nullable HitResult hostableForCloud,
            Camera cameraForSave
    ) {
        if (session == null) return;

        // Clear any existing preview first so we don't stack duplicates
        clearExistingPreview();

        // Remember pose & surface for save-time orientation / metadata
        lastHitPose = pose;
        if (trackable instanceof Plane) {
            Plane pl = (Plane) trackable;
            lastHitSurfaceType = (pl.getType() == Plane.Type.VERTICAL) ? "PLANE_VERTICAL" : "PLANE_HORIZONTAL";
        } else if (trackable instanceof DepthPoint) {
            lastHitSurfaceType = "DEPTH";
        } else if (trackable instanceof StreetscapeGeometry) {
            lastHitSurfaceType = "STREETSCAPE";
        } else if (trackable instanceof Point) {
            lastHitSurfaceType = "POINT";
        } else {
            lastHitSurfaceType = "GEO_RAY";
        }

        // --- Create the visual preview anchor IMMEDIATELY (before opening the sheet) ---
        long grace = System.currentTimeMillis() + 2500L;
        Anchor visualAnchor = session.createAnchor(pose);
        currentPlacedAnchor = visualAnchor;

        // Flow flags + preview model (STAR on meshes, PUZZLE on non-mesh)
        inPuzzleFlow = asPuzzle;
        currentPreviewModel = asPuzzle ? ModelType.PUZZLE : ModelType.STAR;

        // Keep this preview visible and suppress occlusion briefly
        wrappedAnchors.add(new WrappedAnchor(
                visualAnchor,
                trackable,
                /*docId*/ null,
                grace,
                asPuzzle ? ModelType.PUZZLE : ModelType.STAR
        ));
        noOccUntilMs = grace;
        placementModeActive = false;

        // Ensure ray mode is off
        rayAdjustActive = false;
        activeRay = null;
        setRayControlsVisible(false);
        rayForcedYawDeg = null;

        // If STAR and we have a hostable surface, remember the local anchor for Cloud hosting
        localAnchorForHosting = (!asPuzzle && hostableForCloud != null)
                ? ((hostableForCloud.getHitPose().equals(pose)) ? visualAnchor : hostableForCloud.createAnchor())
                : null;
        hostState = HostState.IDLE;

        // Small toast hint
        runOnUiThread(() -> Toast.makeText(
                HelloArActivity.this,
                asPuzzle ? "Puzzle placed — add details (≥1 photo) and Save."
                        : (localAnchorForHosting != null
                        ? "Placed — ready to host Cloud Anchor on Save."
                        : "Placed — no hostable surface yet, you can still Save by location."),
                Toast.LENGTH_SHORT).show()
        );

        // Compose HUD with geospatial info if available
        Earth earth = null; GeospatialPose anchorGp = null, camGp = null;
        try { earth = session.getEarth(); } catch (Throwable ignore) {}
        if (earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
            try { anchorGp = earth.getGeospatialPose(visualAnchor.getPose()); } catch (Throwable ignore) {}
            try { camGp    = earth.getCameraGeospatialPose(); } catch (Throwable ignore) {}
        }
        final String hud = (anchorGp != null)
                ? String.format(Locale.US, "%s. Lat %.6f  Lng %.6f  Alt %.2f  (H ±%.1fm, V ±%.1fm)",
                (asPuzzle ? "Puzzle preview" : "Placed"),
                anchorGp.getLatitude(), anchorGp.getLongitude(), anchorGp.getAltitude(),
                anchorGp.getHorizontalAccuracy(), anchorGp.getVerticalAccuracy())
                : (asPuzzle ? "Puzzle preview. Getting precise location…" : "Placed. Getting precise location…");
        runOnUiThread(() -> {
            if (poseInfoCard != null) {
                poseInfoCard.setVisibility(View.VISIBLE);
                poseInfoCard.setText(hud);
            }
        });

        // Open metadata sheet (immediately after preview is shown)
        EggCardSheet sheet = new EggCardSheet();
        sheet.setCancelable(false);

        // NEW: let the sheet enforce the photo requirement (so the sheet stays open and preserves inputs)
        sheet.setRequireAtLeastOnePhoto(asPuzzle);

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
                    System.currentTimeMillis()
            ));
        }

        sheet.setListener(new EggCardSheet.Listener() {
            @Override
            public void onSave(String title, String description, List<Uri> photoUris,
                               @Nullable Uri audioUri, @Nullable EggCardSheet.GeoPoseSnapshot geoSnapshot) {
                // Validation (including ≥1 photo for puzzles) is handled inside the sheet.
                // If we got here, the form is valid. Proceed to save without re-opening the sheet.
                proceedToSave(title, description, photoUris, audioUri, cameraForSave);
            }

            @Override
            public void onCancel() {
                // User changed their mind: remove preview so they can tap elsewhere
                try { clearExistingPreview(); } catch (Throwable ignore) {}
                inPuzzleFlow = false;
                placementModeActive = true;
                readyPromptShown = true;
                localAnchorForHosting = null;
                hostState = HostState.IDLE;
                hideStatus();
            }
        });

        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                sheet.show(getSupportFragmentManager(), "EggCardSheet");
            }
        });
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
    // Make the model stand nicely on floors and behave like a sticker on walls.
    // Make the model stand nicely on floors and behave like a sticker on walls.
    private float[] correctionForSurface(Trackable tr, Pose hitPose, Camera camera) {
        final float[] NO = new float[]{0,0,0,1};

        if (tr instanceof Plane) {
            Plane pl = (Plane) tr;
            if (pl.getType() == Plane.Type.VERTICAL) {
                // Face camera (yaw) AND keep model upright
                float[] t = hitPose.getTranslation();
                float[] c = camera.getPose().getTranslation();
                double yawDeg = Math.toDegrees(Math.atan2(c[0] - t[0], c[2] - t[2]));
                return quatMul(yawToQuaternion((float) yawDeg), MODEL_UPRIGHT_FIX);
            } else {
                // Horizontal: just make the model stand up
                return MODEL_UPRIGHT_FIX;
            }
        } else if (tr instanceof DepthPoint) {
            // Depth-only hits: treat like horizontal so it doesn't lie down
            return MODEL_UPRIGHT_FIX;
        } else if (tr instanceof Point) {
            // Treat surface-normal points like horizontal
            return MODEL_UPRIGHT_FIX;
        } else if (tr instanceof StreetscapeGeometry) {
            float[] t = hitPose.getTranslation();
            float[] c = camera.getPose().getTranslation();
            double yawDeg = Math.toDegrees(Math.atan2(c[0] - t[0], c[2] - t[2]));
            return quatMul(yawToQuaternion((float) yawDeg), MODEL_UPRIGHT_FIX);
        }
        return NO;
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

    // ----- Quaternion utilities (ADD ONCE) -----
    private static float[] quatMul(float[] a, float[] b) {
        // a ∘ b
        return new float[] {
                a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
                a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
                a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
                a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
        };
    }

    private static float[] quatAxisAngle(float ax, float ay, float az, float deg) {
        float rad = (float) Math.toRadians(deg);
        float s = (float) Math.sin(rad * 0.5f), c = (float) Math.cos(rad * 0.5f);
        float len = (float) Math.sqrt(ax*ax + ay*ay + az*az);
        if (len < 1e-6f) return new float[]{0,0,0,1};
        ax /= len; ay /= len; az /= len;
        return new float[]{ ax*s, ay*s, az*s, c };
    }

    private static Pose poseWithExtraRotation(Pose base, float[] extraQ) {
        float[] q = quatMul(base.getRotationQuaternion(), extraQ);
        return new Pose(base.getTranslation(), q);
    }
// -------------------------------------------

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

        // Require valid Earth tracking + pose
        if (earth == null || earth.getTrackingState() != TrackingState.TRACKING || camGp == null) return;

        lastPrevLoadAtMs = now;
        List<String> keys = neighborKeys(camGp.getLatitude(), camGp.getLongitude());

        try {
            Query q = FirebaseFirestore.getInstance()
                    .collection(EGGS)
                    .whereIn("cellKey", keys)
                    .limit(FIRESTORE_FETCH_LIMIT);

            q.get().addOnSuccessListener(qs -> {
                List<DocumentSnapshot> docs = (qs != null) ? qs.getDocuments() : null;
                if (docs == null || docs.isEmpty()) {
                    Log.d(TAG, "cellKey query returned 0 docs; falling back to unfiltered fetch.");
                    fallbackPrevFetch(earth, camGp);
                    return;
                }
                mountPreviousEggsFromSnapshot(earth, camGp, docs, true);
            }).addOnFailureListener(e -> {
                Log.w(TAG, "cellKey query failed, falling back to full fetch", e);
                fallbackPrevFetch(earth, camGp);
            });
        } catch (Throwable t) {
            Log.w(TAG, "cellKey query not supported or failed to build; falling back", t);
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

        // Bind to whichever STAR pipeline is active
        if (virtualObjectShader != null) {           // PBR path
            try { virtualObjectShader.setTexture("u_AlbedoTexture", t); } catch (Throwable ignore) {}
        }
        if (unlitShader != null) {                    // Unlit fallback
            try { unlitShader.setTexture("u_Texture", t); } catch (Throwable ignore) {}
        }

        // Also update any per-model STAR shaders array
        if (starShaders != null) {
            for (Shader s : starShaders) {
                if (s == null) continue;
                try { s.setTexture("u_AlbedoTexture", t); } catch (Throwable ignore) {}
                try { s.setTexture("u_Texture", t); } catch (Throwable ignore) {}
            }
        }

        virtualObjectAlbedoTexture = t;
    }

    private void mountPreviousEggsFromSnapshot(Earth earth, GeospatialPose camGp,
                                               List<DocumentSnapshot> docs, boolean cellFiltered) {
        int added = 0;
        final long grace = System.currentTimeMillis() + 2500L;

        for (DocumentSnapshot snap : docs) {
            if (mountedPrevDocIds.contains(snap.getId())) continue;

            // Pull fields
            Double lat = (snap.getGeoPoint("geo") != null) ? snap.getGeoPoint("geo").getLatitude() : null;
            Double lng = (snap.getGeoPoint("geo") != null) ? snap.getGeoPoint("geo").getLongitude() : null;
            Double alt = snap.getDouble("alt");
            String type = snap.getString("anchorType");
            String cloudId = snap.getString("cloudId");
            Double heading = snap.getDouble("heading");
            if (heading == null) heading = 0.0;

            // NEW: choose STAR vs PUZZLE based on saved "model" field
            String modelField = snap.getString("model"); // "star" or "puzzle"
            ModelType mType = (modelField != null && modelField.equalsIgnoreCase("puzzle"))
                    ? ModelType.PUZZLE : ModelType.STAR;

            // Prefer saved local rotation; else face camera heading + keep upright
            Float lqx = (snap.getDouble("localQx") != null) ? snap.getDouble("localQx").floatValue() : null;
            Float lqy = (snap.getDouble("localQy") != null) ? snap.getDouble("localQy").floatValue() : null;
            Float lqz = (snap.getDouble("localQz") != null) ? snap.getDouble("localQz").floatValue() : null;
            Float lqw = (snap.getDouble("localQw") != null) ? snap.getDouble("localQw").floatValue() : null;

            float[] q = (lqx != null && lqy != null && lqz != null && lqw != null)
                    ? new float[]{ lqx, lqy, lqz, lqw }
                    : quatMul(yawToQuaternion(heading.floatValue()), MODEL_UPRIGHT_FIX);

            // Cloud-only fallback if no geo
            if (lat == null || lng == null) {
                if (cloudId != null && !cloudId.isEmpty()) {
                    try {
                        Anchor a = session.resolveCloudAnchor(cloudId);
                        prevAnchors.add(new WrappedAnchor(a, null, snap.getId(), grace, mType));
                        mountedPrevDocIds.add(snap.getId());
                        added++;
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to resolve cloud-only egg " + snap.getId(), t);
                    }
                }
                continue;
            }

            // Don't mount right at the camera position
            if (isWithinMeters(camGp.getLatitude(), camGp.getLongitude(), lat, lng, MIN_LOAD_METERS)) {
                continue;
            }

            try {
                boolean wantsCloudOnly = (cloudId != null && !cloudId.isEmpty()) &&
                        (type != null && type.equalsIgnoreCase("CLOUD"));
                boolean wantsGeo = (type != null && (type.equalsIgnoreCase("GEO")
                        || type.equalsIgnoreCase("GEO+CLOUD")
                        || type.equalsIgnoreCase("GEO_PUZZLE")));

                if (wantsGeo && alt != null) {
                    Anchor a = earth.createAnchor(lat, lng, alt, q[0], q[1], q[2], q[3]);
                    prevAnchors.add(new WrappedAnchor(a, null, snap.getId(), grace, mType));
                    mountedPrevDocIds.add(snap.getId());
                    added++;
                } else if (wantsGeo) {
                    final float approxAlt = (float) (alt != null ? alt : camGp.getAltitude());
                    earth.resolveAnchorOnTerrainAsync(
                            lat, lng, approxAlt, q[0], q[1], q[2], q[3],
                            (terrainAnchor, state) -> {
                                if (state == Anchor.TerrainAnchorState.SUCCESS) {
                                    try {
                                        prevAnchors.add(new WrappedAnchor(terrainAnchor, null, snap.getId(),
                                                System.currentTimeMillis() + 2500L, mType));
                                        mountedPrevDocIds.add(snap.getId());
                                    } catch (Throwable ignore) { }
                                } else {
                                    if (terrainAnchor != null) terrainAnchor.detach();
                                }
                            });
                } else if (wantsCloudOnly) {
                    Anchor a = session.resolveCloudAnchor(cloudId);
                    prevAnchors.add(new WrappedAnchor(a, null, snap.getId(), grace, mType));
                    mountedPrevDocIds.add(snap.getId());
                    added++;
                } else {
                    // Generic fallback: try terrain with our rotation
                    final float approxAlt = (float) (alt != null ? alt : camGp.getAltitude());
                    earth.resolveAnchorOnTerrainAsync(
                            lat, lng, approxAlt, q[0], q[1], q[2], q[3],
                            (terrainAnchor, state) -> {
                                if (state == Anchor.TerrainAnchorState.SUCCESS) {
                                    try {
                                        prevAnchors.add(new WrappedAnchor(terrainAnchor, null, snap.getId(),
                                                System.currentTimeMillis() + 2500L, mType));
                                        mountedPrevDocIds.add(snap.getId());
                                    } catch (Throwable ignore) { }
                                } else {
                                    if (terrainAnchor != null) terrainAnchor.detach();
                                }
                            });
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to mount previous egg " + snap.getId(), t);
            }
        }

        if (SHOW_LOAD_TOAST && added > 0) {
            final int addedCount = added;
            runOnUiThread(() ->
                    Toast.makeText(HelloArActivity.this,
                            "Loaded " + addedCount + " nearby item(s).", Toast.LENGTH_SHORT).show());
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
    /** Project a world-space point to screen pixels. Returns {xPx, yPx, wClip}, or null if behind camera. */
    @Nullable
    private float[] worldToScreenPx(float wx, float wy, float wz) {
        float[] world = new float[]{wx, wy, wz, 1f};
        float[] viewPos = new float[4];
        Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, world, 0);

        // In the ARCore OpenGL camera, points in front typically have view z <= 0
        if (viewPos[2] > 0f) return null; // behind camera

        float[] clip = new float[4];
        Matrix.multiplyMV(clip, 0, projectionMatrix, 0, viewPos, 0);
        float w = clip[3];
        if (Math.abs(w) < 1e-6f) return null;

        float ndcX = clip[0] / w;
        float ndcY = clip[1] / w;

        float sx = ((ndcX + 1f) * 0.5f) * viewportWidth;
        float sy = ((1f - ndcY) * 0.5f) * viewportHeight;
        return new float[]{sx, sy, w};
    }

    /** Find the nearest placed anchor under the tap (both saved + preview). */
    @Nullable
    private WrappedAnchor pickAnchorAtTap(float xPx, float yPx) {
        WrappedAnchor best = null;
        float bestD2 = Float.MAX_VALUE;

        // Check saved first so they are preferred if stacked
        List<WrappedAnchor> all = new ArrayList<>();
        all.addAll(prevAnchors);
        all.addAll(wrappedAnchors);

        for (WrappedAnchor w : all) {
            if (w == null) continue;
            Anchor a = w.getAnchor();
            if (a == null || a.getTrackingState() != TrackingState.TRACKING) continue;

            Pose p = a.getPose();
            float[] scr = worldToScreenPx(p.tx(), p.ty(), p.tz());
            if (scr == null) continue;

            float dx = scr[0] - xPx, dy = scr[1] - yPx;
            float d2 = dx*dx + dy*dy;
            if (d2 <= PICK_RADIUS_PX * PICK_RADIUS_PX && d2 < bestD2) {
                bestD2 = d2;
                best   = w;
            }
        }
        selectedAnchor = best;
        return best;
    }
    private void showAnchorInfo(WrappedAnchor w) {
        final String fallbackTitle = (w.getModelType() == ModelType.PUZZLE) ? "Puzzle" : "Star";

        // Update the small HUD line as a quick visual confirmation
        runOnUiThread(() -> {
            if (poseInfoCard != null) {
                poseInfoCard.setVisibility(View.VISIBLE);
                poseInfoCard.setText("Selected: " + fallbackTitle + (w.getDocId() != null ? " (saved)" : " (preview)"));
            }
        });

        // If it's a saved egg, fetch its metadata; otherwise show a simple dialog
        String docId = w.getDocId();
        if (docId == null || docId.isEmpty()) {
            runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(HelloArActivity.this)
                    .setTitle(fallbackTitle)
                    .setMessage("Preview (not saved yet).")
                    .setPositiveButton("OK", null)
                    .show());
            return;
        }

        FirebaseFirestore.getInstance().collection(EGGS).document(docId).get()
                .addOnSuccessListener(doc -> runOnUiThread(() -> {
                    String t = doc.getString("title");
                    String d = doc.getString("description");
                    new androidx.appcompat.app.AlertDialog.Builder(HelloArActivity.this)
                            .setTitle((t != null && !t.isEmpty()) ? t : fallbackTitle)
                            .setMessage((d != null && !d.isEmpty()) ? d : "(no description)")
                            .setPositiveButton("OK", null)
                            .show();
                }))
                .addOnFailureListener(e -> runOnUiThread(() -> new androidx.appcompat.app.AlertDialog.Builder(HelloArActivity.this)
                        .setTitle(fallbackTitle)
                        .setMessage("Failed to load details: " + e.getMessage())
                        .setPositiveButton("OK", null)
                        .show()));
    }

    // Canonical 5-arg version (lets us choose STAR vs PUZZLE)
    private void addPersistentAnchorForSaved(
            String docId,
            @Nullable GeospatialPose gp,
            @Nullable float[] localQ,
            @Nullable String cloudId,
            ModelType modelType) {
        try {
            if (session == null) return;

            Anchor a = null;
            Earth earth = null;
            try { earth = session.getEarth(); } catch (Throwable ignore) {}

            if (gp != null && earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
                float[] q = (localQ != null) ? localQ : new float[]{0,0,0,1};
                a = earth.createAnchor(
                        gp.getLatitude(), gp.getLongitude(), gp.getAltitude(),
                        q[0], q[1], q[2], q[3]);
            } else if (cloudId != null && !cloudId.isEmpty()) {
                a = session.resolveCloudAnchor(cloudId);
            } else if (currentPlacedAnchor != null) {
                a = session.createAnchor(currentPlacedAnchor.getPose());
            }

            if (a != null) {
                long grace = System.currentTimeMillis() + 2500L;
                prevAnchors.add(new WrappedAnchor(a, null, docId, grace, modelType));
                mountedPrevDocIds.add(docId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "addPersistentAnchorForSaved failed", t);
        }
    }


    class WrappedAnchor {
        private final Anchor anchor;
        @Nullable private final Trackable trackable;
        @Nullable private final String docId;
        private final long noOccUntilMs;
        private final ModelType modelType;

        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId) {
            this(anchor, trackable, docId, 0L, ModelType.STAR);
        }
        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId,
                      long noOccUntilMs) {
            this(anchor, trackable, docId, noOccUntilMs, ModelType.STAR);
        }
        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId,
                      long noOccUntilMs, ModelType modelType) {
            this.anchor = anchor;
            this.trackable = trackable;
            this.docId = docId;
            this.noOccUntilMs = noOccUntilMs;
            this.modelType = (modelType == null) ? ModelType.STAR : modelType;
        }

        Anchor getAnchor() { return anchor; }
        @Nullable Trackable getTrackable() { return trackable; }
        @Nullable String getDocId() { return docId; }
        boolean isOcclusionSuppressedAt(long nowMs) { return nowMs < noOccUntilMs; }
        ModelType getModelType() { return modelType; }
    }

    private static float[] yawToQuaternion(float yawDeg, float pitchDeg, float rollDeg) {
        return yawToQuaternion(yawDeg);
    }
}