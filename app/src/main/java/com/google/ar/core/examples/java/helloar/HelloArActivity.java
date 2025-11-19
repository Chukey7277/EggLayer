/* full file: com/google/ar/core/examples/java/helloar/HelloArActivity.java */
package com.google.ar.core.examples.java.helloar;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.ArCoreApk.Availability;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
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
import android.view.LayoutInflater;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

import com.google.firebase.storage.FirebaseStorage;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import com.google.firebase.storage.StorageReference;
import androidx.annotation.NonNull;

public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

    private static final String TAG = HelloArActivity.class.getSimpleName();

    // Tighter, more stable depth range.
    private static final float Z_NEAR = 0.10f;
    private static final float Z_FAR  = 150f;

    private static final int CUBEMAP_RESOLUTION = 16;
    private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

    private enum Env { OUTDOOR, INDOOR }
    private static final String PREFS = "helloar_prefs";

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
    private static final double CELL_DEG = 0.01;
    private static final int FIRESTORE_FETCH_LIMIT = 120;
    private boolean previousEggsLoadedOnce = false;
    private long lastPrevLoadAtMs = 0L;
    private static final long PREV_RELOAD_MS = 60_000L;
    private final Set<String> mountedPrevDocIds =
            java.util.Collections.synchronizedSet(new HashSet<>());

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
    private final InstantPlacementSettings instantPlacementSettings = new InstantPlacementSettings();
    private VertexBuffer pointCloudVertexBuffer;
    private Mesh pointCloudMesh;
    private Shader pointCloudShader;
    private long lastPointCloudTimestamp = 0;

    private Mesh   virtualObjectMesh;
    private Shader virtualObjectShader;
    private Texture virtualObjectAlbedoTexture;
    private Shader unlitShader;
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
    private static final double MOUNT_RADIUS_M = 80.0; // or 100–150 to taste

    // Global (legacy) suppression window (kept) + per-anchor windows.
    private long noOccUntilMs = 0L;
    @Nullable private String lastPlacedDocId = null;

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


    // Approx raw height of each OBJ in its own units (Y-extent).
// If you don't know, start with 1.0f and adjust.
    private static final float STAR_RAW_HEIGHT_UNITS   = 1.6f;
    private static final float PUZZLE_RAW_HEIGHT_UNITS = 1.6f;
    // One place to tune model size in world meters-per-model-unit

    private static final float MODEL_BASE_SCALE = 0.3f; // was 0.02f → ~40% smaller
    private static final float STAR_SIZE_MULT   = 0.5f;
    private static final float PUZZLE_SIZE_MULT = 1f;   // or 1.0f if you want same as star

    private static final float[] MODEL_UPRIGHT_FIX = quatAxisAngle(1, 0, 0, 0f);
    // Extra multipliers applied only to *saaved* anchors (prevAnchors)
    private static final float SAVED_STAR_MULT   = 1.0f; // <- bump to taste
    private static final float SAVED_PUZZLE_MULT = 1.0f; // keep puzzles unchanged

    // turn on to lock the star's orientation everywhere
    private static final boolean ALWAYS_FACE_CAMERA = true;
    // Put near other enums:
    private enum ModelType { STAR, PUZZLE }
    // Per-model assets
    private Mesh[]  starMeshes,  puzzleMeshes;
    private Shader[] starShaders, puzzleShaders;
    private static final boolean SHOW_ONLY_JUST_PLACED = true;
    private boolean inPuzzleFlow = false;
    private ModelType currentPreviewModel = ModelType.STAR;
    private static final float STAR_TARGET_PX   = 65f;  // try 32–64
    private static final float PUZZLE_TARGET_PX = 52f;  // magnifier a bit smaller
    @Nullable private WrappedAnchor selectedAnchor = null;
    private static final float PICK_RADIUS_DP = 56f; // ~48dp base
    @Nullable private Pose lastCameraPose = null;

    // show scanning dialog only once for the whole Activity lifetime
    private boolean initialScanDialogShown = false;
    // fields
    private volatile boolean isSavingFlow = false;
    // Keep status dialog visible until user dismisses
    private volatile boolean statusModalPinned = false;
    // Add this field near your other tracking fields
    private final Set<String> currentSessionRecentDocIds =
            java.util.Collections.synchronizedSet(new HashSet<>());

    private volatile boolean saveAckShown = false;
    private volatile long suppressProgressUntilMs = 0L; // prevent re-open for a short window
    private final java.util.concurrent.ConcurrentHashMap<String, String[]> justSavedMeta =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Put near the other helpers
    private void runOnGl(Runnable r) {
        if (surfaceView != null) {
            surfaceView.queueEvent(() -> {
                try { r.run(); } catch (Throwable t) { Log.e(TAG, "GL task failed", t); }
            });
        } else {
            try { r.run(); } catch (Throwable t) { Log.e(TAG, "GL task failed", t); }
        }
    }
    private static final float MIN_RENDER_DISTANCE_M = 0.35f; // ~35 cm; tune to taste
    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private volatile boolean saveInFlight = false;
    private long lastSaveStartedMs = 0L;
    private View btnNearby; // FloatingActionButton or Button
    // Nearby list (independent of rendering)
    private static final double NEARBY_RADIUS_M = 500.0;   // radius for list
    private static final long   NEARBY_SCAN_MS  = 10_000L; // refresh button every 10s
    private long lastNearbyScanAt = 0L;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean internetOk = true;                // last-known internet status
    private long lastOfflineHintAt = 0L;
    private static final long OFFLINE_HINT_COOLDOWN_MS = 8000L;
    // Turn model's +X to +Z so the face points at camera. Flip sign if needed.
    private static final float[] PUZZLE_FACE_FIX = quatAxisAngle(0, 1, 0, -90f);
    // Make the PUZZLE (question mark) face the camera too.
// If it still looks sideways, try 90f or 180f here.
    private static final float PUZZLE_FACE_YAW_DEG = -90f;
    private static float yawFromQuaternion(float[] q) {
        // yaw (around +Y), same convention as yawToQuaternion()
        float siny_cosp = 2f * (q[3]*q[1] + q[0]*q[2]);
        float cosy_cosp = 1f - 2f * (q[1]*q[1] + q[2]*q[2]);
        return (float) Math.atan2(siny_cosp, cosy_cosp); // radians
    }

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
    // Add anywhere in the class (e.g., near other statics)
    private static float calculateDistanceToPlane(Pose planePose, Pose cameraPose) {
        float[] normal = planePose.getTransformedAxis(1, 1.0f); // +Y of plane
        float[] point  = planePose.getTranslation();
        float[] cam    = cameraPose.getTranslation();
        float dx = cam[0] - point[0];
        float dy = cam[1] - point[1];
        float dz = cam[2] - point[2];
        return dx*normal[0] + dy*normal[1] + dz*normal[2];
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

        // 1) Always ensure auth for real features (release + debug)
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            FirebaseAuth.getInstance().signInAnonymously()
                    .addOnSuccessListener(r -> { /* ready */ })
                    .addOnFailureListener(e ->
                            Toast.makeText(this, "Sign-in failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                    );
        }

        // 2) Debug-only probes
        if (BuildConfig.DEBUG) {
            testResolveFromApp();
            // If you want the test write only *after* auth:
            ensureAnonAuthThen(this::testFirestoreWrite);
        }

//    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
//            != PackageManager.PERMISSION_GRANTED) {
//        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_FINE_LOCATION);
//    }

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
        try {
            // Make sure the SurfaceView isn't layered above dialog windows
            surfaceView.setZOrderOnTop(false);
        } catch (Throwable ignore) {}
        displayRotationHelper = new DisplayRotationHelper(this);

        // NEW: hook up the Nearby Anchors button (FAB or Button)
        btnNearby = findViewById(R.id.fabNearby);
        if (btnNearby != null) {
            btnNearby.setOnClickListener(v -> openNearbySheet());
            btnNearby.setVisibility(View.VISIBLE); // hidden until we detect nearby anchors
        }

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
            // If any modal UI is open, do NOT consume touch — let the dialog get it.
            if (inMetadataFlow || isSavingFlow) return false;

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
                    boolean promoted = false;
                    for (WrappedAnchor p : prevAnchors) {
                        if (p.getAnchor() == old) { promoted = true; break; }
                    }
                    if (!promoted) {
                        old.detach();
                        lastStableT.remove(old);
                    }
                }
            } catch (Throwable ignore) {}
        }
        wrappedAnchors.clear();
        currentPlacedAnchor = null;
    }
    private void clearExistingPreviewAsync() {
        runOnGl(this::clearExistingPreview);
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
        startNetMonitor();
        surfaceView.onResume();
        displayRotationHelper.onResume();

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

            } catch (UnavailableArcoreNotInstalledException |
                     UnavailableUserDeclinedInstallationException e) {
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
                    if (!SHOW_ONLY_JUST_PLACED) {
                        maybeLoadPreviousEggs(earth, camGp);
                    } else {
                        purgePrevAnchors();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Immediate previous-eggs load on resume skipped", t);
        }

        boolean hasActivePlacement = (currentPlacedAnchor != null) || !wrappedAnchors.isEmpty();
        if (!inMetadataFlow && !hasActivePlacement && !initialScanDialogShown) {
            placementModeActive = false;
            readyPromptShown = false;
            uiShowProgress(
                    "Scanning",
                    "Move slowly — waiting for surfaces (mesh/grid) to appear…",
                    "Aim at well-lit, textured areas."
            );
            showPlacementCoachDialog();
            initialScanDialogShown = true; // lock it so it never shows again
        }
    }

    @Override protected void onPause() {
        super.onPause();
        stopNetMonitor();
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
                        .setDepthWrite(true);

                // --- PUZZLE (magnifier) ---
                Texture magnifierTex;
                Mesh magnifierMesh;
                try {
                    magnifierTex = Texture.createFromAsset(
                            render, "models/Image_01.png",
                            Texture.WrapMode.CLAMP_TO_EDGE,
                            Texture.ColorFormat.SRGB
                    );
                    magnifierMesh = Mesh.createFromAsset(render, "models/magnifying_glass1.obj");
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
                        .setDepthWrite(true);

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
                        magnifierTex = Texture.createFromAsset(render, "models/Image_01.png",
                                Texture.WrapMode.CLAMP_TO_EDGE, Texture.ColorFormat.SRGB);
                        magnifierMesh = Mesh.createFromAsset(render, "models/magnifying_glass1.obj");
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
            String title, String description, List<Uri> photoUris,
            @Nullable Uri audioUri, Camera cameraForSave,
            @Nullable EggCardSheet.GeoPoseSnapshot snapshot
    ) {
        long now = System.currentTimeMillis();
        if (saveInFlight || (now - lastSaveStartedMs) < 1500L) {
            Log.w(TAG, "proceedToSave ignored (already saving or double-tap)");
            return;
        }
        saveInFlight = true;
        lastSaveStartedMs = now;

        // NEW: allow progress again for this fresh flow
//        suppressProgressUntilMs = 0L;

        inMetadataFlow = false;
        isSavingFlow = true;
        placementModeActive = false;
        statusModalPinned = false;

        continueProceedToSave(title, description, photoUris, audioUri, cameraForSave, snapshot);
    }

    // --- helper with the original heavy logic (unchanged semantics) ---
    private void continueProceedToSave(
            String title,
            String description,
            List<Uri> photoUris,
            @Nullable Uri audioUri,
            Camera cameraForSave,
            @Nullable EggCardSheet.GeoPoseSnapshot snapshot
    ) {
        // ---- Stage 0: enter saving mode & show dialog immediately ----
        inMetadataFlow = false;
        isSavingFlow = true;          // <- onDrawFrame should not hide the dialog while this is true
        placementModeActive = false;  // <- prevents scan UI from dismissing the status dialog

        uiShowProgress(
                inPuzzleFlow ? "Saving (1/3)" : "Saving (1/3)",
                inPuzzleFlow ? "Saving puzzle details…" : "Saving star details…",
                null
        );

        // ---- Gather best-available geospatial info ----
        Earth earthNow = null;
        GeospatialPose camNow = null;
        GeospatialPose anchorGp = null;
        try {
            earthNow = (session != null) ? session.getEarth() : null;
            if (earthNow != null && earthNow.getTrackingState() == TrackingState.TRACKING) {
                try { camNow = earthNow.getCameraGeospatialPose(); } catch (Throwable ignore) {}
                if (currentPlacedAnchor != null) {
                    try { anchorGp = earthNow.getGeospatialPose(currentPlacedAnchor.getPose()); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable ignore) {}

        final boolean anchorOk = anchorGp != null
                && anchorGp.getHorizontalAccuracy() <= hGate()
                && !Double.isNaN(anchorGp.getVerticalAccuracy())
                && anchorGp.getVerticalAccuracy() <= vGate();

        final boolean snapOk = snapshot != null
                && snapshot.hAcc <= hGate()
                && !Double.isNaN(snapshot.vAcc)
                && snapshot.vAcc <= vGate();

        final boolean camOk = camNow != null
                && camNow.getHorizontalAccuracy() <= hGate()
                && !Double.isNaN(camNow.getVerticalAccuracy())
                && camNow.getVerticalAccuracy() <= vGate();

        Double lat = null, lng = null, alt = null, hAcc = null, vAcc = null;
        if (anchorOk) {
            lat  = anchorGp.getLatitude();  lng  = anchorGp.getLongitude();
            alt  = anchorGp.getAltitude();  hAcc = anchorGp.getHorizontalAccuracy();  vAcc = anchorGp.getVerticalAccuracy();
        } else if (snapOk) {
            lat  = snapshot.lat; lng = snapshot.lng; alt = snapshot.alt; hAcc = snapshot.hAcc; vAcc = snapshot.vAcc;
        } else if (camOk) {
            lat  = camNow.getLatitude();  lng = camNow.getLongitude();
            alt  = camNow.getAltitude();  hAcc = camNow.getHorizontalAccuracy();  vAcc = camNow.getVerticalAccuracy();
        }
        if (lat == null && anchorGp != null) {
            lat  = anchorGp.getLatitude();  lng  = anchorGp.getLongitude();
            alt  = anchorGp.getAltitude();  hAcc = anchorGp.getHorizontalAccuracy();  vAcc = anchorGp.getVerticalAccuracy();
        } else if (lat == null && camNow != null) {
            lat  = camNow.getLatitude();  lng = camNow.getLongitude();
            alt  = camNow.getAltitude();  hAcc = camNow.getHorizontalAccuracy();  vAcc = camNow.getVerticalAccuracy();
        }

        boolean goodAcc = (hAcc != null && vAcc != null &&
                hAcc <= (hGate() * 1.5f) && vAcc <= (vGate() * 1.5f));
        boolean haveLatLngAny = (lat != null && lng != null);

        double headingNow = Double.NaN;
        try {
            headingNow = goodHeadingDeg(camNow, lastGoodGeoPose, headGate());
            if (Double.isNaN(headingNow) && snapshot != null && !Double.isNaN(snapshot.heading)) {
                headingNow = snapshot.heading;
            }
        } catch (Throwable ignore) {}

        // ---- Build initial doc payload ----
        EggEntry e = new EggEntry();
        e.title = (title == null) ? "" : title;
        e.description = (description == null) ? "" : description;
        e.quiz = null;
        e.heading = headingNow;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) e.userId = user.getUid();

        if (haveLatLngAny) {
            e.geo      = new com.google.firebase.firestore.GeoPoint(lat, lng);
            e.horizAcc = hAcc;
            e.vertAcc  = vAcc;
            e.alt      = alt;
        }

        Trackable firstTrackable = (!wrappedAnchors.isEmpty()) ? wrappedAnchors.get(0).getTrackable() : null;
        e.placementType = (firstTrackable != null) ? firstTrackable.getClass().getSimpleName() : "Local";
        e.distanceFromCamera = (currentPlacedAnchor != null)
                ? distance(currentPlacedAnchor.getPose(), cameraForSave.getPose())
                : 0f;

        e.anchorType = inPuzzleFlow ? "GEO_PUZZLE" : "CLOUD";
        String cellKeyStr = (haveLatLngAny) ? cellKey(lat, lng) : null;

        final Earth earthNowF           = earthNow;
        final GeospatialPose anchorGpF  = anchorGp;
        final GeospatialPose camNowF    = camNow;
        final Double latF  = lat,  lngF = lng,  altF = alt;
        final Double hAccF = hAcc, vAccF = vAcc;
        final boolean haveLatLngAnyF = haveLatLngAny;
        final boolean goodAccF = goodAcc;
        final String cellKeyStrF = cellKeyStr;

        // ---- Stage 1: Create draft in Firestore ----
        eggRepo.createDraft(e).addOnSuccessListener(docRef -> {
            currentSessionRecentDocIds.add(docRef.getId());

            // Patch orientation + extras + provisional geo + status
            try {
                float[] q = (lastHitPose != null)
                        ? lastHitPose.getRotationQuaternion()
                        : (currentPlacedAnchor != null
                        ? currentPlacedAnchor.getPose().getRotationQuaternion()
                        : new float[]{0,0,0,1});

                Map<String, Object> orient = new HashMap<>();
                orient.put("localQx", q[0]); orient.put("localQy", q[1]);
                orient.put("localQz", q[2]); orient.put("localQw", q[3]);
                orient.put("surface", lastHitSurfaceType);
                orient.put("placementEnv", envMode.name());
                orient.put("model", inPuzzleFlow ? "puzzle" : "star");
                if (cellKeyStrF != null) orient.put("cellKey", cellKeyStrF);

                Map<String, Object> patch = new HashMap<>(orient);
                patch.put("extras", new HashMap<>(orient));
                patch.put("anchorType", inPuzzleFlow ? "GEO_PUZZLE" : "CLOUD");

                if (haveLatLngAnyF) {
                    patch.put("geo", new com.google.firebase.firestore.GeoPoint(latF, lngF));
                    if (altF  != null) patch.put("alt", altF);
                    if (hAccF != null) patch.put("horizAcc", hAccF);
                    if (vAccF != null) patch.put("vertAcc", vAccF);
                    patch.put("cloudStatus", goodAccF ? "OK" : "LOW_GEOPOSE");
                } else {
                    patch.put("cloudStatus", "NO_GEOPOSE");
                }

                FirebaseFirestore.getInstance().collection(EGGS)
                        .document(docRef.getId())
                        .set(patch, SetOptions.merge());
            } catch (Throwable t) { Log.w(TAG, "Failed to patch orientation/geo", t); }

            // Keep the saved version visible immediately (continuity)
            try {
                lastPlacedDocId = docRef.getId();

                if (!inPuzzleFlow && localAnchorForHosting != null) {
                    // STAR: keep local plane anchor for this session
                    addPersistentAnchorForSaved(
                            docRef.getId(), /*gp*/ null,
                            (lastHitPose != null ? lastHitPose.getRotationQuaternion() : null),
                            /*cloudId*/ null,
                            ModelType.STAR,
                            /*existingLocalAnchor*/ localAnchorForHosting
                    );
                } else if (inPuzzleFlow && currentPlacedAnchor != null) {
                    // PUZZLE: keep the preview anchor as the saved anchor (instant & stable)
                    addPersistentAnchorForSaved(
                            docRef.getId(),
                            /*gp*/ null,
                            (lastHitPose != null ? lastHitPose.getRotationQuaternion() : null),
                            /*cloudId*/ null,
                            ModelType.PUZZLE,
                            /*existingLocalAnchor*/ currentPlacedAnchor
                    );
                } else {
                    // Fallback (geo/terrain or preview copy)
                    addPersistentAnchorForSaved(
                            docRef.getId(),
                            anchorGpF,
                            (lastHitPose != null ? lastHitPose.getRotationQuaternion() : null),
                            /*cloudId*/ null,
                            inPuzzleFlow ? ModelType.PUZZLE : ModelType.STAR
                    );
                }

                // Remove preview
                try { clearExistingPreviewAsync(); } catch (Throwable ignore) {}
                currentPlacedAnchor = null;
            } catch (Throwable t) {
                Log.w(TAG, "Anchor setup failed", t);
            }

            // ---- Stage 2: Upload media (progress + Firestore patch INSIDE repo) ----
            uiShowProgress("Saving (2/3)", "Uploading media…", null);

            // Persist URI permissions BEFORE starting uploads
            if (photoUris != null) {
                for (Uri u : photoUris) {
                    try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignore) {}
                }
            }
            if (audioUri != null) {
                try { getContentResolver().takePersistableUriPermission(audioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignore) {}
            }

            eggRepo.uploadPhotosWithProgress(
                            HelloArActivity.this,
                            docRef.getId(),
                            photoUris,
                            audioUri,
                            line -> uiShowProgress("Saving (2/3)", (line == null ? "Uploading…" : line), null)
                    )
                    .addOnSuccessListener(v -> {
                        Toast.makeText(HelloArActivity.this, "Media uploaded ✓", Toast.LENGTH_SHORT).show();

                        // ---- Stage 3 ----
                        if (!inPuzzleFlow && localAnchorForHosting != null) {
                            // STAR: Host in cloud
                            FirebaseFirestore.getInstance().collection(EGGS).document(docRef.getId())
                                    .update("cloudStatus", "HOSTING",
                                            "cloudTtlDays", CLOUD_TTL_DAYS,
                                            "anchorType", "CLOUD");

                            uiShowProgress("Saving (3/3)", "Hosting anchor in the cloud…", "This can take a few seconds");

                            pendingEggDocId = docRef.getId();
                            lastPlacedDocId = docRef.getId();

                            // Keep rendering the LOCAL anchor; cloud is for sharing
                            startHostingCloudAnchor(localAnchorForHosting, pendingEggDocId);

                        } else {
                            // PUZZLE: finalize immediately
                            uiShowProgress("Saving (3/3)", "Finalizing…", null);

                            GeospatialPose gpForMount = null;
                            if (goodAccF) gpForMount = (anchorGpF != null) ? anchorGpF : (camNowF != null ? camNowF : null);

                            lastPlacedDocId = docRef.getId();
                            addPersistentAnchorForSaved(
                                    docRef.getId(),
                                    gpForMount,
                                    (lastHitPose != null ? lastHitPose.getRotationQuaternion() : null),
                                    null,
                                    ModelType.PUZZLE
                            );

                            try { clearExistingPreviewAsync(); } catch (Throwable ignore) {}
                            currentPlacedAnchor = null;
                            inPuzzleFlow = false;
                            placementModeActive = true; // back to placement
                            readyPromptShown = true;
                            localAnchorForHosting = null;
                            hostState = HostState.IDLE;

                            // Optional: save height-above-terrain
                            try {
                                boolean okSurface = "PLANE_HORIZONTAL".equals(lastHitSurfaceType)
                                        || "PLANE_VERTICAL".equals(lastHitSurfaceType);
                                if (earthNowF != null && haveLatLngAnyF && okSurface && goodAccF && altF != null) {
                                    saveHeightAboveTerrain(earthNowF, latF, lngF, altF, docRef.getId());
                                }
                            } catch (Throwable ignore) {}

                            // Final success (keeps dialog until user taps OK)
                            showFinalSavedMessage(ModelType.PUZZLE, haveLatLngAnyF, goodAccF);
                        }
                    })
                    .addOnFailureListener((Exception err) -> {
                        Log.e(TAG, "Media upload failed", err);
                        Toast.makeText(HelloArActivity.this, "Upload failed: " + err.getMessage(), Toast.LENGTH_LONG).show();
                        uiShowMessage("Upload failed", "Media upload failed: " + err.getMessage(), true);
                    });

        }).addOnFailureListener((Exception err) -> {
            Log.e(TAG, "save failed", err);
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

        // Cache camera pose for billboarding & culling
        Pose camPoseForCull = null;
        try { camPoseForCull = camera.getPose(); } catch (Throwable ignore) {}
        if (camPoseForCull != null) lastCameraPose = camPoseForCull;

        try { backgroundRenderer.updateDisplayGeometry(frame); } catch (Throwable t) { return; }

        if (camera.getTrackingState() == TrackingState.TRACKING
                && (depthSettings.useDepthForOcclusion() || depthSettings.depthColorVisualizationEnabled())) {
            try (Image depthImage = frame.acquireDepthImage16Bits()) {
                backgroundRenderer.updateCameraDepthTexture(depthImage);
            } catch (Throwable ignore) {}
        }

        trySetBackgroundZoom(zoomFactor);

        // === MOVED: get fresh matrices BEFORE tap handling so picking uses the current PV ===
        if (camera.getTrackingState() == TrackingState.TRACKING) {
            try {
                camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);
                camera.getViewMatrix(viewMatrix, 0);
            } catch (Throwable t) { return; }
        }

        // Handle taps (uses fresh matrices for picking)
        if (!inMetadataFlow) {
            try { handleTap(frame, camera); } catch (Throwable ignore) {}
        }

        // Draw camera background
        try {
            if (frame.getTimestamp() != 0) backgroundRenderer.drawBackground(render);
        } catch (Throwable t) {
            return;
        }

        // If not tracking, nothing else to render
        if (camera.getTrackingState() != TrackingState.TRACKING) return;

        // Scan UI hints — keep hands off while saving or while the sheet is open
        try {
            if (isSavingFlow) {
                // While saving, never touch/hide/replace the status dialog.
                // return; // intentionally not returning so hosting state can still progress
            }

            if (inMetadataFlow) {
                uiHideStatus();
            } else {
                int trackedPlanes = 0;
                for (Plane p : session.getAllTrackables(Plane.class)) {
                    if (p.getTrackingState() == TrackingState.TRACKING && p.getSubsumedBy() == null) trackedPlanes++;
                }

                if (!placementModeActive) {
                    if (trackedPlanes == 0) {
                        if (!initialScanDialogShown) {
                            uiShowProgress(
                                    "Scanning",
                                    "Move slowly — waiting for surfaces (mesh/grid) to appear…",
                                    "Aim at well-lit, textured areas.");
                            initialScanDialogShown = true;
                        } else {
                            uiHideStatus(); // don't re-show after first time
                        }
                    } else if (!readyPromptShown) {
                        readyPromptShown = true;
                        placementModeActive = true;
                        uiShowMessage("Ready", "Surfaces detected — tap OK to start placing.", true);
                    }
                } else {
                    uiHideStatus();
                }
            }
        } catch (Throwable ignore) {}

        // --- Draw plane grid as a non-occluding overlay ---
        try {
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL);
            GLES30.glPolygonOffset(3.0f, 3.0f);
            // Planes should not write to depth
            GLES30.glDepthMask(false);

            if (planeRenderer != null) {
                planeRenderer.drawPlanes(
                        render,
                        session.getAllTrackables(Plane.class),
                        camera.getDisplayOrientedPose(),
                        projectionMatrix
                );
            }
        } finally {
            GLES30.glDepthMask(true);
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        }

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
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL);
        } catch (Throwable ignore) {}

        // Prepare the virtual scene target
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);

        // Maintain anchor lists
        pruneDeadAnchors(prevAnchors);
        pruneDeadAnchors(wrappedAnchors);

        try { GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL); } catch (Throwable ignore) {}

        // === CHANGE: render saved list only if we are not in "just placed" mode ===
        if (SHOW_ONLY_JUST_PLACED) {
            // Show ALL recent placements from current session
            drawAnchorsList(prevAnchors, MODEL_BASE_SCALE, /*isSavedList=*/true, render, currentSessionRecentDocIds);
        } else {
            drawAnchorsList(prevAnchors, MODEL_BASE_SCALE, /*isSavedList=*/true, render, null);
        }
        drawAnchorsList(wrappedAnchors, MODEL_BASE_SCALE, /*isSavedList=*/false, render, null);

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

                // Determine the specific error for better user messaging
                String userMessage;
                switch (st) {
                    case ERROR_HOSTING_DATASET_PROCESSING_FAILED:
                        userMessage = "Couldn't process the visual data. Try better lighting or more textured surfaces.";
                        break;
                    case ERROR_NOT_AUTHORIZED:
                        userMessage = "Authentication failed. Please check your ARCore configuration.";
                        break;
                    case ERROR_SERVICE_UNAVAILABLE:
                        userMessage = "Cloud Anchor service is temporarily unavailable. Please try again later.";
                        break;
                    case ERROR_RESOURCE_EXHAUSTED:
                        userMessage = "Too many cloud anchor requests. Please try again later.";
                        break;
                    default:
                        userMessage = "Cloud hosting failed: " + st;
                        break;
                }

                if (pendingEggDocId != null) {
                    // Update Firestore with error status
                    FirebaseFirestore.getInstance().collection(EGGS).document(pendingEggDocId)
                            .update("cloudStatus", "ERROR", "cloudError", st.toString())
                            .addOnFailureListener(e -> Log.w(TAG, "Failed to mark cloud error", e));

                    // REMOVE THE STAR FROM VISIBLE ANCHORS
                    runOnGl(() -> {
                        try {
                            // Remove from recent placements
                            currentSessionRecentDocIds.remove(pendingEggDocId);

                            // Remove from mounted anchors
                            mountedPrevDocIds.remove(pendingEggDocId);

                            // Remove from prevAnchors list and detach anchor
                            for (WrappedAnchor w : new ArrayList<>(prevAnchors)) {
                                if (pendingEggDocId.equals(w.getDocId())) {
                                    try {
                                        if (w.getAnchor() != null) {
                                            w.getAnchor().detach();
                                            lastStableT.remove(w.getAnchor());
                                        }
                                    } catch (Throwable ignore) {}
                                    prevAnchors.remove(w);
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Error removing failed cloud anchor", t);
                        }
                    });

                    // Show error message - star will NOT be visible
                    uiShowMessage("Cloud Hosting Failed",
                            "Your star could not be hosted in the cloud. " +
                                    userMessage + " " +
                                    "Please try placing again with better lighting and surfaces.",
                            true);
                }
            } else if (st == Anchor.CloudAnchorState.SUCCESS) {
                hostState = HostState.SUCCESS;
                hostedCloudId = hostedCloudAnchor.getCloudAnchorId();

                // IMPORTANT: Don't remove the local anchor - just update Firestore
                // The local anchor should continue to be used for rendering

                // Mount the cloud-resolved version ONLY if we don't have a good local anchor
                if (hostedCloudId != null && pendingEggDocId != null) {
                    currentSessionRecentDocIds.add(pendingEggDocId);

                    // But keep using the LOCAL anchor for rendering, not the cloud one
                    // The cloud ID is just for sharing with other users
                    Log.d(TAG, "Cloud hosting successful for " + pendingEggDocId + ", but keeping local anchor for rendering");
                }

                // Patch Firestore with cloud info
                if (pendingEggDocId != null && hostedCloudId != null) {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put("cloudStatus", "SUCCESS");
                    patch.put("cloudId", hostedCloudId);
                    patch.put("cloudHostedAt", FieldValue.serverTimestamp());
                    patch.put("cloudTtlDays", CLOUD_TTL_DAYS);
                    patch.put("anchorType", "CLOUD");

                    FirebaseFirestore.getInstance().collection(EGGS)
                            .document(pendingEggDocId)
                            .update(patch)
                            .addOnSuccessListener(v -> uiShowMessage(
                                    "All set!",
                                    "Star saved and cloud hosted ✓\nOther users can now see it.\nTap OK to place again.",
                                    true))
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Failed to patch egg with cloudId", e);
                                uiShowMessage(
                                        "All set!",
                                        "Star saved locally ✓ (cloud update failed).\nTap OK to place again.",
                                        true);
                            });
                } else {
                    uiShowMessage("All set!", "Star saved locally ✓\nTap OK to place again.", true);
                }
            }
        }

        // Telemetry / HUD
        try {
            Earth earth = null;
            try { earth = session.getEarth(); } catch (Throwable ignore) {}
            // OFFLINE hint when Earth isn't tracking
            if (earth != null && earth.getTrackingState() != TrackingState.TRACKING && !hasInternetNow()) {
                long now = System.currentTimeMillis();
                if (now - lastOfflineHintAt > OFFLINE_HINT_COOLDOWN_MS) {
                    messageSnackbarHelper.showMessage(
                            this, "You’re offline — Geospatial tracking needs internet. Reconnect and try again.");
                    lastOfflineHintAt = now;
                }
            }

            boolean online = hasInternetNow();
            final String earthLine = (earth == null)
                    ? "Earth: not available" + (online ? "" : " • OFFLINE")
                    : "AR Earth: " + earth.getEarthState() + " • Tracking: " + earth.getTrackingState()
                    + (online ? "" : " • OFFLINE (check connection)");
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

                    if (!SHOW_ONLY_JUST_PLACED) {
                        maybeLoadPreviousEggs(earth, camGp);
                    }
                    maybeScanNearbyForButton();
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

    // Keep the original 4-arg API for any existing callers.
    private int drawAnchorsList(List<WrappedAnchor> list, float baseScale, boolean isSavedList, SampleRender render) {
        return drawAnchorsList(list, baseScale, isSavedList, render, /*onlyDocId=*/null);
    }

    // New core with optional filter: only draw the saved anchor whose docId == onlyDocId.
    // Replace the current drawAnchorsList method with this version:
    private int drawAnchorsList(
            List<WrappedAnchor> list,
            float baseScale,
            boolean isSavedList,
            SampleRender render,
            @Nullable Object filter // Can be String (single ID) or Set<String> (multiple IDs)
    ) {
        int drawn = 0;

        for (WrappedAnchor wrapped : list) {
            if (wrapped == null) continue;

            // Handle filtering for saved items
            if (isSavedList && filter != null) {
                String did = wrapped.getDocId();
                if (did == null) continue;

                if (filter instanceof String) {
                    // Single ID filter
                    if (!((String) filter).equals(did)) continue;
                } else if (filter instanceof Set) {
                    // Multiple IDs filter
                    if (!((Set<String>) filter).contains(did)) continue;
                }
            }

            // ... rest of the existing drawing code remains the same ...
            Anchor a = wrapped.getAnchor();
            if (a == null) continue;

            // ---- distance-based culling (near + far) ----
            if (lastCameraPose != null) {
                float d = distance(a.getPose(), lastCameraPose);

                // Allow much closer render for the just-placed saved anchor
                float minCull = MIN_RENDER_DISTANCE_M; // default 0.35m
                if (isSavedList && filter != null) {
                    String didThis = wrapped.getDocId();
                    if (didThis != null) {
                        boolean isRecent = false;
                        if (filter instanceof String) {
                            isRecent = ((String) filter).equals(didThis);
                        } else if (filter instanceof Set) {
                            isRecent = ((Set<String>) filter).contains(didThis);
                        }
                        if (isRecent) {
                            minCull = 0.08f; // ~8 cm — show it even if you're very close
                        }
                    }
                }

                if (d < minCull) continue;               // too close to the glass
                if (isSavedList && d > MOUNT_RADIUS_M) continue; // far cull for persisted list
            }

            // ... rest of the existing drawing code remains exactly the same ...
            TrackingState st = a.getTrackingState();
            if (st == TrackingState.STOPPED) {
                try { a.detach(); } catch (Throwable ignore) {}
                lastStableT.remove(a);
                continue;
            }
            boolean isPaused   = (st == TrackingState.PAUSED);
            boolean isTracking = (st == TrackingState.TRACKING);
            if (!isPaused && !isTracking) continue;

            // --- pose smoothing (translation only) ---
            Pose p = a.getPose();
            float[] t = p.getTranslation();
            float[] last = lastStableT.get(a);
            if (last != null && SMOOTHING_ALPHA < 1f) {
                for (int i = 0; i < 3; i++) {
                    t[i] = SMOOTHING_ALPHA * last[i] + (1f - SMOOTHING_ALPHA) * t[i];
                }
            }
            lastStableT.put(a, t.clone());

            // --- choose render rotation ---
            // --- choose render rotation ---
            final boolean isPuzzle = (wrapped.getModelType() == ModelType.PUZZLE);
            float[] renderQ;
            if (ALWAYS_FACE_CAMERA && lastCameraPose != null && !isPuzzle) {
                float[] camT = lastCameraPose.getTranslation();
                float yawDeg = (float) Math.toDegrees(Math.atan2(camT[0] - t[0], camT[2] - t[2]));
                renderQ = quatMul(yawToQuaternion(yawDeg), MODEL_UPRIGHT_FIX);
            } else {
                renderQ = new float[]{ p.qx(), p.qy(), p.qz(), p.qw() };
            }
            // Build model matrix
            Pose rPose = new Pose(t, renderQ);
            rPose.toMatrix(modelMatrix, 0);

            // Lift previews slightly; saved items stay on the pose
            float liftY = (isSavedList ? 0f : 0.02f) + (isPaused ? 0.03f : 0f);
            Matrix.translateM(modelMatrix, 0, 0f, liftY, 0f);

            // --- constant-pixel size scaling ---
            float[] wp   = new float[]{ t[0], t[1], t[2], 1f };
            float[] vpos = new float[4];
            Matrix.multiplyMV(vpos, 0, viewMatrix, 0, wp, 0);
            float zView = -vpos[2];
            zView = Math.max(0.05f, Math.min(50f, zView));
            float fyNdc = projectionMatrix[5];
            int   vh    = Math.max(1, viewportHeight);
            float fyPx  = 0.5f * vh * fyNdc;

            float targetPx = isPuzzle ? PUZZLE_TARGET_PX : STAR_TARGET_PX;
            float targetHeightM = (targetPx * zView) / Math.max(1e-6f, fyPx);

            float rawH   = isPuzzle ? PUZZLE_RAW_HEIGHT_UNITS : STAR_RAW_HEIGHT_UNITS;
            float typeM  = isPuzzle ? PUZZLE_SIZE_MULT        : STAR_SIZE_MULT;
            float savedM = (isSavedList ? (isPuzzle ? SAVED_PUZZLE_MULT : SAVED_STAR_MULT) : 1.0f);

            float s = (targetHeightM / Math.max(1e-6f, rawH)) * typeM * savedM * baseScale;
            Matrix.scaleM(modelMatrix, 0, s, s, s);

            // --- MVP ---
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

            // --- draw ---
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

    private void forceBackCamera(Session session) {
        // Filter for BACK camera configs
        CameraConfigFilter filter = new CameraConfigFilter(session);
        try {
            // Newer ARCore SDKs
            filter.setFacingDirection(CameraConfig.FacingDirection.BACK);
            List<CameraConfig> configs = session.getSupportedCameraConfigs(filter);
            if (!configs.isEmpty()) session.setCameraConfig(configs.get(0));
        } catch (NoSuchMethodError ignored) {
            // Older SDKs: pick a BACK config manually
            List<CameraConfig> all = session.getSupportedCameraConfigs(new CameraConfigFilter(session));
            for (CameraConfig c : all) {
                if (c.getFacingDirection() == CameraConfig.FacingDirection.BACK) {
                    session.setCameraConfig(c);
                    break;
                }
            }
        }
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

        runOnGl(() -> {
            clearExistingPreview();

            Pose p = poseAlongRay(activeRay, rayDistanceM, rayForcedYawDeg);
            Anchor a = session.createAnchor(p);
            currentPlacedAnchor = a;
            lastHitPose = p;
            lastHitSurfaceType = "GEO_RAY";

            long grace = System.currentTimeMillis() + 1500L;
            inPuzzleFlow = true;
            currentPreviewModel = ModelType.PUZZLE;

            wrappedAnchors.add(new WrappedAnchor(a, null, null, grace, ModelType.PUZZLE));
            noOccUntilMs = grace;
        });
    }
    // ====== /helpers ======

    private void purgePrevAnchors() {
        runOnGl(() -> {
            for (WrappedAnchor w : new ArrayList<>(prevAnchors)) {
                try {
                    Anchor a = w.getAnchor();
                    if (a != null) {
                        a.detach();
                        lastStableT.remove(a);
                    }
                } catch (Throwable ignore) {}
            }
            prevAnchors.clear();
            mountedPrevDocIds.clear();
        });
        previousEggsLoadedOnce = true; // disables reload logic elsewhere
    }

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
                boolean inFront = calculateDistanceToPlane(pl.getCenterPose(), camPoseNow) > 0;
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
            uiHideStatus(); // <- make sure center status isn’t on top

            new AlertDialog.Builder(HelloArActivity.this)
                    .setTitle("No surface found yet")
                    .setMessage(
                            "We couldn’t find a surface here just yet.\n\n" +
                                    "Would you like to place a Puzzle (magnifying glass) instead?\n\n" +
                                    "Heads-up: you’ll need to add at least one photo before saving."
                    )
                    .setCancelable(true)
                    .setOnCancelListener(d -> {
                        // restore placement hints cleanly if user backs out
                        placementModeActive = true;
                        readyPromptShown = true;
                    })
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

        // --- Create the visual preview anchor BEFORE opening the sheet ---
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
                ? (hostableForCloud.getHitPose().equals(pose) ? visualAnchor : hostableForCloud.createAnchor())
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
        Earth earthTmp = null; GeospatialPose anchorGp = null, camGp = null;
        try { earthTmp = session.getEarth(); } catch (Throwable ignore) {}
        if (earthTmp != null && earthTmp.getTrackingState() == TrackingState.TRACKING) {
            try { anchorGp = earthTmp.getGeospatialPose(visualAnchor.getPose()); } catch (Throwable ignore) {}
            try { camGp    = earthTmp.getCameraGeospatialPose(); } catch (Throwable ignore) {}
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

        inMetadataFlow = true;
        uiHideStatus();

        // Open metadata sheet (immediately after preview is shown)
        EggCardSheet sheet = new EggCardSheet();
        sheet.setCancelable(false);
        // Let the sheet enforce ≥1 photo for puzzles
        sheet.setRequireAtLeastOnePhoto(asPuzzle);

        if (anchorGp != null) {
            final double headingDeg = goodHeadingDeg(camGp, lastGoodGeoPose, headGate());
            final double hAccVal    = anchorGp.getHorizontalAccuracy();
            final double vAccVal    = anchorGp.getVerticalAccuracy();
            final double headAccVal = (camGp != null ? camGp.getHeadingAccuracy() : Double.NaN);

            // Your project expects 7 args (no timestamp)
            sheet.setGeoPoseSnapshot(new EggCardSheet.GeoPoseSnapshot(
                    anchorGp.getLatitude(),
                    anchorGp.getLongitude(),
                    anchorGp.getAltitude(),
                    headingDeg,
                    hAccVal,
                    vAccVal,
                    headAccVal
            ));
        }

        sheet.setListener(new EggCardSheet.Listener() {
            @Override
            public void onSave(String title, String description, List<Uri> photoUris,
                               @Nullable Uri audioUri, @Nullable EggCardSheet.GeoPoseSnapshot geoSnapshot) {
                if (saveInFlight) { Log.w(TAG, "onSave ignored (save already in flight)"); return; }
                suppressProgressUntilMs = 0L;
                proceedToSave(title, description, photoUris, audioUri, cameraForSave, geoSnapshot);
            }

            @Override
            public void onCancel() {
                // Remove preview so the user can tap elsewhere
                try { clearExistingPreviewAsync(); } catch (Throwable ignore) {}
                inPuzzleFlow = false;
                placementModeActive = true;
                readyPromptShown = true;
                localAnchorForHosting = null;
                hostState = HostState.IDLE;
                inMetadataFlow = false;
                hideStatus();
            }
        });

        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                forceHideStatus();
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

        forceBackCamera(session);

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
        if (System.currentTimeMillis() < suppressProgressUntilMs) return;
        if (!isSavingFlow && placementModeActive && !inMetadataFlow) return;
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
                    // User tapped OK / dialog dismissed
                    statusModalPinned = false;
                    isSavingFlow = false;
                    inMetadataFlow = false;

                    // Swallow any late progress calls (uploads, terrain resolve, Firestore listeners)
                    suppressProgressUntilMs = System.currentTimeMillis() + 12_000L; // was 2000L

                    // Reset placement state
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
                    readyPromptShown = true;
                    // Avoid “Scanning…” from showing again immediately
                    initialScanDialogShown = true;
                    coachLastHintMs = 0L;

                    // Nuke any existing dialog/spinner *hard*
                    forceHideStatus(); // <- dismiss + clear refs
                }
        );
    }

    private void uiShowProgress(String title, String line1, @Nullable String line2) {
        final long now = System.currentTimeMillis();
        if (statusModalPinned) return;
        // A) Hard block during cool-down (swallow any late progress after finishing a flow)
        if (now < suppressProgressUntilMs) return;

        // B) Only allow progress while actively saving OR editing (sheet open).
        //    Do NOT allow while a final message is pinned.
        if (!(isSavingFlow || inMetadataFlow)) return;

        final String t  = (title == null || title.trim().isEmpty()) ? "Working…"    : title;
        final String l1 = (line1 == null || line1.trim().isEmpty()) ? "Please wait…" : line1;
        final String l2 = (line2 == null || line2.trim().isEmpty()) ? null           : line2;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (statusModalPinned) return;
            // Guard again on the UI thread in case flags changed between threads
            if (!(isSavingFlow || inMetadataFlow)) return;
            if (System.currentTimeMillis() < suppressProgressUntilMs) return;

            ensureStatusShownNoReset();
            if (statusDlg != null) {
                statusDlg.showProgress(t, l1, l2);
            } else if (poseInfoCard != null) {
                String msg = (l2 == null) ? (t + " — " + l1) : (t + " — " + l1 + "\n" + l2);
                poseInfoCard.setVisibility(View.VISIBLE);
                poseInfoCard.setText(msg);
            }
        });
    }
    private void forceHideStatus() {
        statusModalPinned = false;
        isSavingFlow = false;
        inMetadataFlow = false;
        runOnUiThread(() -> {
            if (statusDlg != null) {
                try { statusDlg.dismissAllowingStateLoss(); } catch (Throwable ignore) {}
                statusDlg = null;
            }
            if (poseInfoCard != null) poseInfoCard.setVisibility(View.GONE);
        });
    }

// Keep your existing uiHideStatus() as-is; we call forceHideStatus() only on final OK.

    private void uiShowMessage(String title, String message, boolean okButton) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            boolean endOfFlow =
                    (isSavingFlow && okButton) ||
                            (title != null && (title.startsWith("All set")
                                    || title.startsWith("Saved")
                                    || title.startsWith("Hosting failed")
                                    || title.startsWith("Upload failed")
                                    || title.startsWith("Save failed")
                                    || title.startsWith("Not hosted")
                                    || title.startsWith("Ready")));

            try {
                if (endOfFlow) {
                    // Final message: pin the dialog AND immediately start a cool-down window
                    statusModalPinned = true;
                    isSavingFlow = false;
                    saveInFlight = false;

                    // Start the cool-down *now* so late progress calls are ignored even before OK
                    suppressProgressUntilMs = System.currentTimeMillis() + 8000L;

                    // Install the OK handler that also cleans up and hard-dismisses
                    ensureStatusShownAndRearm();
                } else {
                    ensureStatusShownNoReset();
                }

                if (statusDlg != null) {
                    String safeTitle = (title == null || title.trim().isEmpty()) ? "Message" : title;
                    String safeMsg   = (message == null) ? "" : message;
                    statusDlg.showMessage(safeTitle, safeMsg, okButton);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "uiShowMessage: dialog fragment unavailable", t);
            }

            // Fallback dialog
            new androidx.appcompat.app.AlertDialog.Builder(HelloArActivity.this)
                    .setTitle((title == null || title.trim().isEmpty()) ? "Message" : title)
                    .setMessage((message == null) ? "" : message)
                    .setOnDismissListener(d -> {
                        if (endOfFlow) {
                            statusModalPinned = false;
                            isSavingFlow = false;
                            inMetadataFlow = false;
                            saveInFlight = false;
                            placementModeActive = true;
                            readyPromptShown = true;
                            initialScanDialogShown = true;
                            coachLastHintMs = 0L;
                            suppressProgressUntilMs = System.currentTimeMillis() + 8000L; // swallow late progress
                            forceHideStatus();
                        }
                    })
                    .setPositiveButton(okButton ? "OK" : "Close", (d, w) -> {})
                    .show();
        });
    }

    private void uiHideStatus() {
        if (isSavingFlow || statusModalPinned) return; // ← add statusModalPinned guard
        runOnUiThread(this::hideStatus);
    }

    private void hideStatus() {
        runOnUiThread(() -> {
            if (statusDlg != null) {
                try { statusDlg.dismissAllowingStateLoss(); } catch (Throwable ignore) {}
                statusDlg = null;
            }
            if (poseInfoCard != null) {
                poseInfoCard.setText("");
                poseInfoCard.setVisibility(View.GONE);
            }
        });
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
        if (SHOW_ONLY_JUST_PLACED) return;
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

    private void mountPreviousEggsFromSnapshot(
            Earth earth,
            GeospatialPose camGp,
            List<DocumentSnapshot> docs,
            boolean cellFiltered
    ) {
        int added = 0; // NOTE: additions happen on the GL thread; this counter is best-effort (SHOW_LOAD_TOAST is false by default).

        if (earth == null || earth.getTrackingState() != TrackingState.TRACKING || camGp == null) {
            return;
        }

        for (DocumentSnapshot snap : docs) {
            final String docId = snap.getId();
            if (mountedPrevDocIds.contains(docId)) continue;

            // Pull fields
            Double lat = (snap.getGeoPoint("geo") != null) ? snap.getGeoPoint("geo").getLatitude() : null;
            Double lng = (snap.getGeoPoint("geo") != null) ? snap.getGeoPoint("geo").getLongitude() : null;
            Double alt = snap.getDouble("alt");
            String type = snap.getString("anchorType");
            String cloudId = snap.getString("cloudId");
            Double heading = snap.getDouble("heading");
            if (heading == null) heading = 0.0;

            // Choose STAR vs PUZZLE based on saved "model" field
            String modelField = snap.getString("model"); // "star" or "puzzle"
            final ModelType mType = (modelField != null && modelField.equalsIgnoreCase("puzzle"))
                    ? ModelType.PUZZLE : ModelType.STAR;


            // Prefer saved local rotation; else face camera heading + keep upright
            Float lqx = (snap.getDouble("localQx") != null) ? snap.getDouble("localQx").floatValue() : null;
            Float lqy = (snap.getDouble("localQy") != null) ? snap.getDouble("localQy").floatValue() : null;
            Float lqz = (snap.getDouble("localQz") != null) ? snap.getDouble("localQz").floatValue() : null;
            Float lqw = (snap.getDouble("localQw") != null) ? snap.getDouble("localQw").floatValue() : null;

            final float[] q = (lqx != null && lqy != null && lqz != null && lqw != null)
                    ? new float[]{ lqx, lqy, lqz, lqw }
                    : quatMul(yawToQuaternion(heading.floatValue()), MODEL_UPRIGHT_FIX);

            // Cloud-only fallback if no geo
            if (lat == null || lng == null) {
                if (cloudId != null && !cloudId.isEmpty()) {
                    final String fCloudId = cloudId;
                    runOnGl(() -> {
                        try {
                            if (session == null) return;
                            if (mountedPrevDocIds.contains(docId)) return;
                            Anchor a = session.resolveCloudAnchor(fCloudId);
                            long grace = System.currentTimeMillis() + 2500L;
                            prevAnchors.add(new WrappedAnchor(a, null, docId, grace, mType));
                            mountedPrevDocIds.add(docId);
                        } catch (Throwable t) {
                            Log.w(TAG, "Failed to resolve cloud-only egg " + docId, t);
                        }
                    });
                }
                continue;
            }

            // ---- Distance gate: only mount items within MOUNT_RADIUS_M of the camera ----
            if (!isWithinMeters(camGp.getLatitude(), camGp.getLongitude(), lat, lng, MOUNT_RADIUS_M)) {
                continue; // skip far-away eggs so they don't clutter the view
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

                final double flat = lat, flng = lng;
                final Double falt = alt; // may be null
                final String fCloudId = cloudId;

                if (wantsGeo && alt != null) {
                    runOnGl(() -> {
                        try {
                            if (earth.getTrackingState() != TrackingState.TRACKING) return;
                            if (mountedPrevDocIds.contains(docId)) return;
                            Anchor a = earth.createAnchor(flat, flng, falt, q[0], q[1], q[2], q[3]);
                            long grace = System.currentTimeMillis() + 2500L;
                            prevAnchors.add(new WrappedAnchor(a, null, docId, grace, mType));
                            mountedPrevDocIds.add(docId);
                        } catch (Throwable t) {
                            Log.w(TAG, "earth.createAnchor failed for " + docId, t);
                        }
                    });
                    added++;
                } else if (wantsGeo) {
                    final float approxAlt = (float) (falt != null ? falt : camGp.getAltitude());
                    runOnGl(() -> {
                        try {
                            if (earth.getTrackingState() != TrackingState.TRACKING) return;
                            earth.resolveAnchorOnTerrainAsync(
                                    flat, flng, approxAlt, q[0], q[1], q[2], q[3],
                                    (terrainAnchor, state) -> runOnGl(() -> {
                                        if (state == Anchor.TerrainAnchorState.SUCCESS && terrainAnchor != null) {
                                            try {
                                                if (mountedPrevDocIds.contains(docId)) {
                                                    try { terrainAnchor.detach(); } catch (Throwable ignore) {}
                                                    return;
                                                }
                                                long grace = System.currentTimeMillis() + 2500L;
                                                prevAnchors.add(new WrappedAnchor(terrainAnchor, null, docId, grace, mType));
                                                mountedPrevDocIds.add(docId);
                                            } catch (Throwable ignore) { }
                                        } else {
                                            if (terrainAnchor != null) {
                                                try { terrainAnchor.detach(); } catch (Throwable ignore) {}
                                            }
                                        }
                                    })
                            );
                        } catch (Throwable t) {
                            Log.w(TAG, "resolveAnchorOnTerrainAsync failed for " + docId, t);
                        }
                    });
                    added++;
                } else if (wantsCloudOnly) {
                    runOnGl(() -> {
                        try {
                            if (session == null) return;
                            if (mountedPrevDocIds.contains(docId)) return;
                            Anchor a = session.resolveCloudAnchor(fCloudId);
                            long grace = System.currentTimeMillis() + 2500L;
                            prevAnchors.add(new WrappedAnchor(a, null, docId, grace, mType));
                            mountedPrevDocIds.add(docId);
                        } catch (Throwable t) {
                            Log.w(TAG, "resolveCloudAnchor failed for " + docId, t);
                        }
                    });
                    added++;
                } else {
                    // Generic fallback: try terrain with our rotation
                    final float approxAlt = (float) (falt != null ? falt : camGp.getAltitude());
                    runOnGl(() -> {
                        try {
                            if (earth.getTrackingState() != TrackingState.TRACKING) return;
                            earth.resolveAnchorOnTerrainAsync(
                                    flat, flng, approxAlt, q[0], q[1], q[2], q[3],
                                    (terrainAnchor, state) -> runOnGl(() -> {
                                        if (state == Anchor.TerrainAnchorState.SUCCESS && terrainAnchor != null) {
                                            try {
                                                if (mountedPrevDocIds.contains(docId)) {
                                                    try { terrainAnchor.detach(); } catch (Throwable ignore) {}
                                                    return;
                                                }
                                                long grace = System.currentTimeMillis() + 2500L;
                                                prevAnchors.add(new WrappedAnchor(terrainAnchor, null, docId, grace, mType));
                                                mountedPrevDocIds.add(docId);
                                            } catch (Throwable ignore) { }
                                        } else {
                                            if (terrainAnchor != null) {
                                                try { terrainAnchor.detach(); } catch (Throwable ignore) {}
                                            }
                                        }
                                    })
                            );
                        } catch (Throwable t) {
                            Log.w(TAG, "resolveAnchorOnTerrainAsync (fallback) failed for " + docId, t);
                        }
                    });
                    added++;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to mount previous egg " + docId, t);
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

    private float pickRadiusPxFor(WrappedAnchor w) {
        float base = dpToPx(PICK_RADIUS_DP);         // ~48dp baseline
        if (w == null) return base;

        final boolean isPuzzle = (w.getModelType() == ModelType.PUZZLE);
        float targetPx = isPuzzle ? PUZZLE_TARGET_PX : STAR_TARGET_PX;
        float typeMult = isPuzzle ? PUZZLE_SIZE_MULT : STAR_SIZE_MULT;
        float savedMult = (w.getDocId() != null)
                ? (isPuzzle ? SAVED_PUZZLE_MULT : SAVED_STAR_MULT)
                : 1f;

        // Approx on-screen height you already use for sizing
        float approxOnScreenPx = targetPx * typeMult * savedMult * MODEL_BASE_SCALE;

        // Be much more generous for magnifier (lens is offset from pivot)
        float factor = isPuzzle ? 1.25f : 0.60f;
        float r = base + factor * approxOnScreenPx;

        float min = dpToPx(isPuzzle ? 120 : 36);
        float max = dpToPx(isPuzzle ? 260 : 160);
        if (r < min) r = min;
        if (r > max) r = max;
        return r;
    }

    /** Find the nearest placed anchor under the tap (both saved + preview). */
    @Nullable
    private WrappedAnchor pickAnchorAtTap(float xPx, float yPx) {
        WrappedAnchor bestRecent = null, bestOther = null;
        float bestRecentD2 = Float.MAX_VALUE, bestOtherD2 = Float.MAX_VALUE;

        List<WrappedAnchor> all = new ArrayList<>();
        all.addAll(prevAnchors);
        all.addAll(wrappedAnchors);

        for (WrappedAnchor w : all) {
            if (w == null) continue;
            Anchor a = w.getAnchor();
            if (a == null) continue;
            TrackingState st = a.getTrackingState();
            if (!(st == TrackingState.TRACKING || st == TrackingState.PAUSED)) continue;

            Pose p = a.getPose();
            float[] scr = worldToScreenPx(p.tx(), p.ty(), p.tz());
            if (scr == null) continue;

            float dx = scr[0] - xPx, dy = scr[1] - yPx;
            float d2 = dx * dx + dy * dy;
            float radius = pickRadiusPxFor(w);
            float r2 = radius * radius;

            boolean inside = d2 <= r2;
            float usedD2 = d2;

            // If miss and this is a PUZZLE (magnifier), also test a hotspot near the lens ring.
            if (!inside && w.getModelType() == ModelType.PUZZLE) {
                // Estimate distance-scaled model height (same math as your constant-pixel sizing)
                float[] wp = new float[]{ p.tx(), p.ty(), p.tz(), 1f };
                float[] vpos = new float[4];
                Matrix.multiplyMV(vpos, 0, viewMatrix, 0, wp, 0);
                float zView = -vpos[2];
                zView = Math.max(0.05f, Math.min(50f, zView));
                float fyPx = 0.5f * Math.max(1, viewportHeight) * projectionMatrix[5];
                float targetHeightM = (PUZZLE_TARGET_PX * zView) / Math.max(1e-6f, fyPx);

                // Lens center is ~40% of model height forward from pivot; tweak 0.30–0.50f if needed
                float lensOffsetM = 0.40f * targetHeightM;

                // Extract yaw from quaternion (same convention as yawToQuaternion)
                float[] q = new float[]{ p.qx(), p.qy(), p.qz(), p.qw() };
                float siny_cosp = 2f * (q[3]*q[1] + q[0]*q[2]);
                float cosy_cosp = 1f - 2f * (q[1]*q[1] + q[2]*q[2]);
                float yaw = (float) Math.atan2(siny_cosp, cosy_cosp); // radians

                // Lens hotspot world position (forward in XZ from pivot)
                float lx = p.tx() + (float) Math.sin(yaw) * lensOffsetM;
                float ly = p.ty();
                float lz = p.tz() + (float) Math.cos(yaw) * lensOffsetM;

                float[] scr2 = worldToScreenPx(lx, ly, lz);
                if (scr2 != null) {
                    float dx2 = scr2[0] - xPx, dy2 = scr2[1] - yPx;
                    float d2Lens = dx2 * dx2 + dy2 * dy2;
                    if (d2Lens <= r2) {
                        inside = true;
                        usedD2 = d2Lens; // use the better (closer) match for tie-breaking
                    }
                }
            }

            if (!inside) continue;

            // Prioritize the current user's recent placement
            if (w.getDocId() != null && currentSessionRecentDocIds.contains(w.getDocId())) {
                if (usedD2 < bestRecentD2) {
                    bestRecentD2 = usedD2;
                    bestRecent = w;
                }
            } else {
                if (usedD2 < bestOtherD2) {
                    bestOtherD2 = usedD2;
                    bestOther = w;
                }
            }
        }

        // Always prefer recent placement if available and close enough
        selectedAnchor = (bestRecent != null) ? bestRecent : bestOther;
        return selectedAnchor;
    }
    private void showFinalSavedMessage(ModelType type, boolean haveGeo, boolean goodAcc) {
        String title = "All set!";
        String body;
        if (type == ModelType.PUZZLE) {
            if (haveGeo && goodAcc) body = "Puzzle saved by location with photos ✓\nTap OK to place again.";
            else if (haveGeo)       body = "Puzzle saved by location ✓\nIt may appear slightly offset.\nTap OK to place again.";
            else                    body = "Puzzle saved ✓\nWe’ll geo-lock it when tracking improves.\nTap OK to place again.";
        } else {
            if (hostedCloudId != null) body = "Star saved and cloud hosted ✓\nOther users can now see it.\nTap OK to place again.";
            else                       body = "Star saved locally ✓\nTap OK to place again.";
        }
        uiShowMessage(title, body, true); // pinned until user taps OK
    }
    private void showAnchorInfo(WrappedAnchor w) {
        // PATCH 6b: if user tapped a preview but a saved anchor is colocated, redirect to the saved one
        WrappedAnchor resolved = w;
        try {
            if (resolved != null && (resolved.getDocId() == null || resolved.getDocId().isEmpty())) {
                Anchor a = resolved.getAnchor();
                if (a != null) {
                    Pose p = a.getPose();
                    WrappedAnchor nearSaved = findNearbyAnchor(p, 0.25f); // within ~25 cm
                    if (nearSaved != null && nearSaved.getDocId() != null) {
                        resolved = nearSaved; // redirect to saved
                    }
                }
            }
        } catch (Throwable ignore) {}

        // Use an effectively-final reference for any lambdas below
        final WrappedAnchor sel = resolved;
        final String fallbackTitle = (sel.getModelType() == ModelType.PUZZLE) ? "Puzzle" : "Star";

        // HUD confirmation
        runOnUiThread(() -> {
            if (poseInfoCard != null) {
                poseInfoCard.setVisibility(View.VISIBLE);
                poseInfoCard.setText("Selected: " + fallbackTitle + (sel.getDocId() != null ? " (saved)" : " (preview)"));
            }
        });

        // If saved, fetch details; else show preview notice
        String docId = sel.getDocId();
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
    //  GL-wrapped version:
    private void addPersistentAnchorForSaved(
            String docId,
            @Nullable GeospatialPose gp,
            @Nullable float[] localQ,
            @Nullable String cloudId,
            ModelType modelType,
            @Nullable Anchor existingLocalAnchor) { // ADD THIS PARAMETER

        runOnGl(() -> {
            try {
                if (session == null) return;

                // Skip if already mounted
                if (mountedPrevDocIds.contains(docId)) {
                    Log.d(TAG, "addPersistentAnchorForSaved: already mounted " + docId);
                    return;
                }

                Anchor a = null;
                Earth earth = null;
                try { earth = session.getEarth(); } catch (Throwable ignore) {}

                // PRIORITY 1: Clone preview anchor so clearing the preview won't detach the saved one
                if (existingLocalAnchor != null && existingLocalAnchor.getTrackingState() != TrackingState.STOPPED) {
                    try {
                        a = session.createAnchor(existingLocalAnchor.getPose()); // <-- NEW: clone, don't reuse
                        Log.d(TAG, "Cloned preview anchor for " + docId + " → persistent");
                    } catch (Throwable t) {
                        Log.w(TAG, "Clone failed; falling back to reusing preview (may vanish if preview is cleared).", t);
                        a = existingLocalAnchor;
                    }
                }
                // PRIORITY 2: Cloud anchor (only if we have cloud ID and no local anchor)
                else if (cloudId != null && !cloudId.isEmpty()) {
                    a = session.resolveCloudAnchor(cloudId);
                    Log.d(TAG, "Resolving cloud anchor for " + docId);
                }
                // PRIORITY 3: Geo placement
                else if (gp != null && earth != null && earth.getTrackingState() == TrackingState.TRACKING) {
                    float[] q = (localQ != null) ? localQ : new float[]{0,0,0,1};
                    a = earth.createAnchor(
                            gp.getLatitude(), gp.getLongitude(), gp.getAltitude(),
                            q[0], q[1], q[2], q[3]);
                    Log.d(TAG, "Creating geo anchor for " + docId);
                }
                // PRIORITY 4: Fallback to current preview
                else if (currentPlacedAnchor != null) {
                    a = session.createAnchor(currentPlacedAnchor.getPose());
                    Log.d(TAG, "Using preview anchor fallback for " + docId);
                }

                if (a != null) {
                    long grace = System.currentTimeMillis() + 2500L;
                    prevAnchors.add(new WrappedAnchor(a, null, docId, grace, modelType));
                    mountedPrevDocIds.add(docId);
                    Log.d(TAG, "Successfully mounted anchor for " + docId + " (type: " +
                            (existingLocalAnchor != null ? "LOCAL" :
                                    cloudId != null ? "CLOUD" : "GEO") + ")");
                } else {
                    Log.w(TAG, "addPersistentAnchorForSaved: no anchor created for " + docId);
                }
            } catch (Throwable t) {
                Log.w(TAG, "addPersistentAnchorForSaved failed for " + docId, t);
            }
        });
    }

    // Keep the old method for backward compatibility
    private void addPersistentAnchorForSaved(
            String docId,
            @Nullable GeospatialPose gp,
            @Nullable float[] localQ,
            @Nullable String cloudId,
            ModelType modelType) {
        addPersistentAnchorForSaved(docId, gp, localQ, cloudId, modelType, null);
    }
    private java.util.List<com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item>
    gatherNearbyAnchors() {
        java.util.List<com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item> out = new java.util.ArrayList<>();
        if (lastCameraPose == null) return out;

        for (WrappedAnchor w : new java.util.ArrayList<>(prevAnchors)) {
            if (w == null) continue;

            // Firestore doc id you saved when you created this anchor
            String docId = w.getDocId();
            if (docId == null || docId.isEmpty()) continue;

            Anchor a = w.getAnchor();
            if (a == null || a.getTrackingState() != TrackingState.TRACKING) continue;

            float distM = distance(a.getPose(), lastCameraPose);
            if (distM < MIN_RENDER_DISTANCE_M || distM > (float) MOUNT_RADIUS_M) continue;

            String model = (w.getModelType() == ModelType.PUZZLE) ? "puzzle" : "star";

            // Optional meta (only if your WrappedAnchor stores them)
            String title = (w.getTitle() != null && !w.getTitle().trim().isEmpty()) ? w.getTitle().trim() : null;
            String thumbUrl = (w.getThumbUrl() != null && !w.getThumbUrl().trim().isEmpty()) ? w.getThumbUrl().trim() : null;

            out.add(new com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item(
                    docId, model, title, distM, thumbUrl
            ));
        }

        java.util.Collections.sort(out, (a, b) -> Float.compare(a.distanceM, b.distanceM));
        return out;
    }

    private void openNearbySheet() {
        Earth earth = null;
        try { earth = (session != null) ? session.getEarth() : null; } catch (Throwable ignore) {}
        if (session == null || earth == null || earth.getTrackingState() != TrackingState.TRACKING || lastCameraPose == null) {
            runOnUiThread(() ->
                    Toast.makeText(HelloArActivity.this, "Move slowly until AR is tracking, then try again.", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        // Get local (already-mounted) items first
        final java.util.List<com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item> localItems =
                gatherNearbyAnchors();

        fetchNearbyFromFirestore(NEARBY_RADIUS_M, listFromDb -> {
            // Merge: prefer Firestore entries when IDs collide (they carry titles)
            java.util.LinkedHashMap<String, com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item> map =
                    new java.util.LinkedHashMap<>();

            for (com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item it : localItems) {
                if (it != null && it.docId != null) map.put(it.docId, it); // <-- docId
            }
            if (listFromDb != null) {
                for (com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item it : listFromDb) {
                    if (it != null && it.docId != null) map.put(it.docId, it); // overwrite with DB item
                }
            }

            final java.util.ArrayList<com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item> merged =
                    new java.util.ArrayList<>(map.values());
            java.util.Collections.sort(merged, (a, b) -> Float.compare(a.distanceM, b.distanceM));

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                new com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet()
                        .setMaxDistanceM(100f)   // requires the setter you added in NearbyAnchorsSheet
                        .setItems(merged)
                        .setListener((docId, fallbackThumb, fallbackTitle) ->
                                openEggDetails(docId, fallbackThumb, fallbackTitle))
                        .show(getSupportFragmentManager(), "nearby");
            });
        });
    }

    @Nullable private WrappedAnchor getSavedAnchorByDocId(String docId){
        for (WrappedAnchor w : new java.util.ArrayList<>(prevAnchors)) {
            if (w != null && docId.equals(w.getDocId())) return w;
        }
        return null;
    }

    private void openEggDetails(String docId,
                                @Nullable String fallbackThumb,
                                @Nullable String fallbackTitle) {
        FirebaseFirestore.getInstance()
                .collection(EGGS).document(docId).get()
                .addOnSuccessListener(doc -> runOnUiThread(() -> {
                    String title = doc.getString("title");
                    if (TextUtils.isEmpty(title)) title = !TextUtils.isEmpty(fallbackTitle) ? fallbackTitle : "Details";
                    String descr = doc.getString("description");
                    if (TextUtils.isEmpty(descr)) descr = "(no description)";

                    View content = LayoutInflater.from(HelloArActivity.this)
                            .inflate(R.layout.dialog_egg_details, null, false);
                    ImageView iv = content.findViewById(R.id.iv);
                    TextView tv  = content.findViewById(R.id.tv);
                    tv.setText(descr);

                    String photo = pickPhotoFromDoc(doc);
                    if (TextUtils.isEmpty(photo)) photo = fallbackThumb;   // optional backup
                    loadInto(iv, photo);

                    new androidx.appcompat.app.AlertDialog.Builder(HelloArActivity.this)
                            .setTitle(title)
                            .setView(content)
                            .setPositiveButton("OK", null)
                            .show();
                }))
                .addOnFailureListener(e -> runOnUiThread(() ->
                        new androidx.appcompat.app.AlertDialog.Builder(HelloArActivity.this)
                                .setTitle(TextUtils.isEmpty(fallbackTitle) ? "Details" : fallbackTitle)
                                .setMessage("Failed to load: " + e.getMessage())
                                .setPositiveButton("OK", null)
                                .show()));
    }
    private interface ItemsCallback {
        void onResult(java.util.List<com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item> items);
    }

    /** Query Firestore for anchors within radius of current geospatial camera. */
    private void fetchNearbyFromFirestore(double radiusM, ItemsCallback cb) {
        if (session == null) { cb.onResult(new java.util.ArrayList<>()); return; }
        com.google.ar.core.Earth earth = session.getEarth();
        if (earth == null || earth.getTrackingState() != com.google.ar.core.TrackingState.TRACKING) {
            cb.onResult(new java.util.ArrayList<>()); return;
        }
        com.google.ar.core.GeospatialPose camGp = earth.getCameraGeospatialPose();
        if (camGp == null) { cb.onResult(new java.util.ArrayList<>()); return; }

        final double camLat = camGp.getLatitude();
        final double camLng = camGp.getLongitude();

        com.google.firebase.firestore.FirebaseFirestore db =
                com.google.firebase.firestore.FirebaseFirestore.getInstance();

        java.util.List<String> keys = neighborKeys(camLat, camLng);

        // NEW: if no keys, go straight to fallback
        if (keys == null || keys.isEmpty()) {
            fetchFallback(db, camLat, camLng, radiusM, cb);
            return;
        }

        // NEW: Firestore whereIn() allows up to 10 values — chunk if needed
        int step = 10;
        java.util.List<com.google.android.gms.tasks.Task<com.google.firebase.firestore.QuerySnapshot>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < keys.size(); i += step) {
            java.util.List<String> part = keys.subList(i, Math.min(i + step, keys.size()));
            tasks.add(
                    db.collection(EGGS)
                            .whereIn("cellKey", part)
                            .limit(FIRESTORE_FETCH_LIMIT)
                            .get()
            );
        }

        com.google.android.gms.tasks.Tasks.whenAllSuccess(tasks)
                .addOnSuccessListener(results -> {
                    java.util.List<com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item> out = new java.util.ArrayList<>();
                    for (Object o : results) {
                        com.google.firebase.firestore.QuerySnapshot qs = (com.google.firebase.firestore.QuerySnapshot) o;
                        for (com.google.firebase.firestore.DocumentSnapshot d : qs.getDocuments()) {
                            com.google.firebase.firestore.GeoPoint gp = d.getGeoPoint("geo");
                            if (gp == null) continue;
                            if (!isWithinMeters(camLat, camLng, gp.getLatitude(), gp.getLongitude(), radiusM)) continue;

                            float distM = haversineM(camLat, camLng, gp.getLatitude(), gp.getLongitude());

                            String modelField = d.getString("model");
                            String model = (modelField != null && modelField.equalsIgnoreCase("puzzle")) ? "puzzle" : "star";

                            String title = d.getString("title");
                            if (title != null) {
                                title = title.trim();
                                if (title.isEmpty()) title = null;
                            }

                            String thumb = firstPhotoUrlFrom(d);
                            out.add(new com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item(
                                    d.getId(), model, title, distM, thumb
                            ));
                        }
                    }

                    // NEW: if we still found nothing, run fallback too
                    if (out.isEmpty()) {
                        fetchFallback(db, camLat, camLng, radiusM, cb);
                        return;
                    }

                    java.util.Collections.sort(out, (a, b) -> Float.compare(a.distanceM, b.distanceM));
                    cb.onResult(out);
                })
                .addOnFailureListener(e -> fetchFallback(db, camLat, camLng, radiusM, cb));
    }

    private static float haversineM(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (float)(R * c);
    }

    private void fetchFallback(com.google.firebase.firestore.FirebaseFirestore db,
                               double camLat, double camLng, double radiusM, ItemsCallback cb) {
        db.collection(EGGS).limit(FIRESTORE_FETCH_LIMIT).get()
                .addOnSuccessListener(qs2 -> {
                    java.util.List<com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item> out = new java.util.ArrayList<>();
                    for (com.google.firebase.firestore.DocumentSnapshot d : qs2.getDocuments()) {
                        com.google.firebase.firestore.GeoPoint gp = d.getGeoPoint("geo");
                        if (gp == null) continue;
                        if (!isWithinMeters(camLat, camLng, gp.getLatitude(), gp.getLongitude(), radiusM)) continue;

                        float distM = haversineM(camLat, camLng, gp.getLatitude(), gp.getLongitude());

                        String modelField = d.getString("model");
                        String model = (modelField != null && modelField.equalsIgnoreCase("puzzle")) ? "puzzle" : "star";

                        String title = d.getString("title");
                        if (title != null) {
                            title = title.trim();
                            if (title.isEmpty()) title = null;
                        }

                        String thumb = firstPhotoUrlFrom(d);
                        out.add(new com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item(
                                d.getId(), model, title, distM, thumb
                        ));
                    }
                    java.util.Collections.sort(out, (a,b) -> Float.compare(a.distanceM, b.distanceM));
                    cb.onResult(out);
                })
                .addOnFailureListener(err -> cb.onResult(new java.util.ArrayList<>()));
    }
    private void maybeScanNearbyForButton() {
        if (btnNearby == null) return;

        long now = System.currentTimeMillis();
        if (now - lastNearbyScanAt < NEARBY_SCAN_MS) return;
        lastNearbyScanAt = now;

        // If you're currently placing or already have any anchors in memory, keep it visible.
        if (currentPlacedAnchor != null || !wrappedAnchors.isEmpty() || !prevAnchors.isEmpty()) {
            runOnUiThread(() -> btnNearby.setVisibility(View.VISIBLE));
            return;
        }

        // If local (already-mounted) nearby items exist, show immediately.
        java.util.List<com.google.ar.core.examples.java.helloar.ui.NearbyAnchorsSheet.Item> local = gatherNearbyAnchors();
        if (local != null && !local.isEmpty()) {
            runOnUiThread(() -> btnNearby.setVisibility(View.VISIBLE));
            return;
        }

        // Else try Firestore.
        fetchNearbyFromFirestore(NEARBY_RADIUS_M, items ->
                runOnUiThread(() -> btnNearby.setVisibility((items != null && !items.isEmpty()) ? View.VISIBLE : View.GONE))
        );
    }
    @Nullable
    private static String firstPhotoUrlFrom(com.google.firebase.firestore.DocumentSnapshot d) {
        // direct single-string fields
        String[] singles = {"thumbUrl","photoThumbUrl","thumbnailUrl","imageUrl","photo","url","downloadUrl"};
        for (String k : singles) {
            String v = d.getString(k);
            if (!TextUtils.isEmpty(v)) return v;
        }

        // array of strings or maps
        Object photos = d.get("photos");
        if (photos instanceof java.util.List) {
            java.util.List<?> arr = (java.util.List<?>) photos;
            if (!arr.isEmpty()) {
                Object first = arr.get(0);
                if (first instanceof String) {
                    String s = (String) first;
                    if (!TextUtils.isEmpty(s)) return s;
                } else if (first instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String,Object> m = (java.util.Map<String,Object>) first;
                    for (String k : new String[]{"thumbUrl","url","downloadUrl","gsUrl","path"}) {
                        Object v = m.get(k);
                        if (v instanceof String && !TextUtils.isEmpty((String) v)) return (String) v;
                    }
                }
            }
        }

        Object urls = d.get("photoUrls");
        if (urls instanceof java.util.List) {
            java.util.List<?> arr = (java.util.List<?>) urls;
            if (!arr.isEmpty() && arr.get(0) instanceof String) {
                String s = (String) arr.get(0);
                if (!TextUtils.isEmpty(s)) return s;
            }
        }

        // sometimes people store a Storage path string
        String path = d.getString("photoPath");
        if (!TextUtils.isEmpty(path)) return path; // handled by loadPhotoInto()

        return null;
    }
    // --- Image loader for dialog (http, gs://, or Storage path) ---

    private void loadInto(ImageView iv, @Nullable String photo) {
        if (TextUtils.isEmpty(photo)) { iv.setVisibility(View.GONE); return; }
        iv.setVisibility(View.VISIBLE);

        try {
            if (photo.startsWith("http://") || photo.startsWith("https://")) {
                Glide.with(HelloArActivity.this)
                        .load(photo)
                        .thumbnail(0.1f)
                        .override(800)
                        .centerCrop()
                        .dontAnimate()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .placeholder(android.R.drawable.ic_menu_report_image)
                        .error(android.R.drawable.stat_notify_error)
                        .into(iv);

            } else if (photo.startsWith("gs://")) {
                FirebaseStorage.getInstance()
                        .getReferenceFromUrl(photo)
                        .getDownloadUrl()
                        .addOnSuccessListener(uri -> Glide.with(HelloArActivity.this)
                                .load(uri)
                                .thumbnail(0.1f)
                                .override(800)
                                .centerCrop()
                                .dontAnimate()
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(android.R.drawable.ic_menu_report_image)
                                .error(android.R.drawable.stat_notify_error)
                                .into(iv))
                        .addOnFailureListener(e -> iv.setVisibility(View.GONE));

            } else {
                // Treat as Firebase Storage path like "/eggs/.../photo.jpg"
                String path = photo.startsWith("/") ? photo.substring(1) : photo;
                FirebaseStorage.getInstance()
                        .getReference(path)
                        .getDownloadUrl()
                        .addOnSuccessListener(uri -> Glide.with(HelloArActivity.this)
                                .load(uri)
                                .thumbnail(0.1f)
                                .override(800)
                                .centerCrop()
                                .dontAnimate()
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .placeholder(android.R.drawable.ic_menu_report_image)
                                .error(android.R.drawable.stat_notify_error)
                                .into(iv))
                        .addOnFailureListener(e -> iv.setVisibility(View.GONE));
            }
        } catch (Throwable t) {
            iv.setVisibility(View.GONE);
        }
    }
    @Nullable
    private static String pickPhotoFromDoc(com.google.firebase.firestore.DocumentSnapshot d) {
        // common direct fields first
        for (String k : new String[]{"thumbUrl","photoThumbUrl","thumbnailUrl","imageUrl","coverUrl"}) {
            String u = d.getString(k);
            if (!TextUtils.isEmpty(u)) return u;
        }

        // photos: ["https..."] or [{thumbUrl/url/downloadUrl/storagePath/path}]
        Object photosObj = d.get("photos");
        if (photosObj instanceof java.util.List) {
            java.util.List<?> arr = (java.util.List<?>) photosObj;
            if (!arr.isEmpty()) {
                Object first = arr.get(0);
                if (first instanceof String) {
                    String s = (String) first;
                    if (!TextUtils.isEmpty(s)) return s;
                } else if (first instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) first;
                    for (String k : new String[]{"thumbUrl","url","downloadUrl","storagePath","path"}) {
                        Object s = m.get(k);
                        if (s instanceof String && !TextUtils.isEmpty((String) s)) return (String) s;
                    }
                }
            }
        }

        // photoUrls: ["https..."]
        Object photoUrls = d.get("photoUrls");
        if (photoUrls instanceof java.util.List) {
            java.util.List<?> arr = (java.util.List<?>) photoUrls;
            if (!arr.isEmpty() && arr.get(0) instanceof String) {
                String s = (String) arr.get(0);
                if (!TextUtils.isEmpty(s)) return s;
            }
        }

        // ✅ photoPaths: ["/eggs/.../photo.jpg"]  <-- your case
        Object photoPaths = d.get("photoPaths");
        if (photoPaths instanceof java.util.List) {
            java.util.List<?> arr = (java.util.List<?>) photoPaths;
            if (!arr.isEmpty() && arr.get(0) instanceof String) {
                String s = (String) arr.get(0);
                if (!TextUtils.isEmpty(s)) return s;
            }
        }
        return null;
    }
    private void startNetMonitor() {
        if (connectivityManager == null) {
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        }
        if (connectivityManager == null) return;

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                internetOk = hasValidatedActive();
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                internetOk = caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            @Override public void onLost(Network network) {
                internetOk = hasValidatedActive();
            }
            @Override public void onUnavailable() {
                internetOk = false;
            }
        };

        try {
            connectivityManager.registerNetworkCallback(req, networkCallback);
        } catch (Throwable ignore) { /* best effort */ }
        internetOk = hasValidatedActive(); // seed initial state
    }

    private void stopNetMonitor() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Throwable ignore) {}
        }
    }

    private boolean hasValidatedActive() {
        if (connectivityManager == null) return true;
        Network n = connectivityManager.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(n);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private boolean hasInternetNow() {
        // fast path + fallback check
        return internetOk || hasValidatedActive();
    }


    class WrappedAnchor {
        private final Anchor anchor;
        @Nullable private final Trackable trackable;
        @Nullable private final String docId;
        private final long noOccUntilMs;
        private final ModelType modelType;

        // NEW: optional UI metadata
        @Nullable private final String title;
        @Nullable private final String thumbUrl;

        // Existing short ctor -> forwards with null title/thumb
        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId) {
            this(anchor, trackable, docId, 0L, ModelType.STAR, null, null);
        }

        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId,
                      long noOccUntilMs) {
            this(anchor, trackable, docId, noOccUntilMs, ModelType.STAR, null, null);
        }

        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId,
                      long noOccUntilMs, ModelType modelType) {
            this(anchor, trackable, docId, noOccUntilMs, modelType, null, null);
        }

        // NEW: full ctor with title/thumb
        WrappedAnchor(Anchor anchor, @Nullable Trackable trackable, @Nullable String docId,
                      long noOccUntilMs, @Nullable ModelType modelType,
                      @Nullable String title, @Nullable String thumbUrl) {
            this.anchor = anchor;
            this.trackable = trackable;
            this.docId = docId;
            this.noOccUntilMs = noOccUntilMs;
            this.modelType = (modelType == null) ? ModelType.STAR : modelType;
            this.title = (title != null && !title.trim().isEmpty()) ? title.trim() : null;
            this.thumbUrl = (thumbUrl != null && !thumbUrl.trim().isEmpty()) ? thumbUrl.trim() : null;
        }

        Anchor getAnchor() { return anchor; }
        @Nullable Trackable getTrackable() { return trackable; }
        @Nullable String getDocId() { return docId; }
        boolean isOcclusionSuppressedAt(long nowMs) { return nowMs < noOccUntilMs; }
        ModelType getModelType() { return modelType; }

        // NEW getters
        @Nullable String getTitle() { return title; }
        @Nullable String getThumbUrl() { return thumbUrl; }
    }

    private static float[] yawToQuaternion(float yawDeg, float pitchDeg, float rollDeg) {
        return yawToQuaternion(yawDeg);
    }
}