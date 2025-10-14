package com.google.ar.core.examples.java.helloar.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.ar.core.examples.java.helloar.R;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Bottom sheet to capture egg details + photos (no audio, no reference section). */
public class EggCardSheet extends BottomSheetDialogFragment {

    // ---------- GeoPose snapshot ----------
    public static class GeoPoseSnapshot implements Serializable {
        public double latitude, longitude, altitude, heading;
        public double horizontalAccuracy, verticalAccuracy, headingAccuracy;
        public long timestampMillis;

        public GeoPoseSnapshot() {}
        public GeoPoseSnapshot(
                double latitude, double longitude, double altitude,
                double heading, double horizontalAccuracy,
                double verticalAccuracy, double headingAccuracy,
                long timestampMillis) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.heading = heading;
            this.horizontalAccuracy = horizontalAccuracy;
            this.verticalAccuracy = verticalAccuracy;
            this.headingAccuracy = headingAccuracy;
            this.timestampMillis = timestampMillis;
        }
    }

    // Listener
    public interface Listener {
        void onSave(String title,
                    String description,
                    List<Uri> photoUris,
                    @Nullable Uri audioUri, // always null here
                    @Nullable GeoPoseSnapshot geoPose);
        void onCancel();
    }

    private @Nullable Listener listener;
    public void setListener(@Nullable Listener l) { this.listener = l; }

    private @Nullable GeoPoseSnapshot geoPoseSnapshot;
    public void setGeoPoseSnapshot(@Nullable GeoPoseSnapshot snapshot) { this.geoPoseSnapshot = snapshot; }

    // ---------- UI ----------
    private TextInputLayout titleLayout, descLayout;
    private TextInputEditText titleInput, descInput;

    private Button addPhotosBtn, cancelBtn, saveBtn;
    private TextView photoHint, photoEmpty;
    private LinearLayout mediaPreviewContainer;

    // ---------- Photos ----------
    private final ArrayList<Uri> pickedPhotoUris = new ArrayList<>();
    private @Nullable Uri cameraTempPhotoUri = null;

    // If true, enforce at least one photo before enabling Save (set from activity)
    private boolean requireAtLeastOnePhoto = false;
    public void setRequireAtLeastOnePhoto(boolean require) {
        this.requireAtLeastOnePhoto = require;
        if (getView() != null) refreshPhotoUI(); // update hint immediately if already created
    }
    // Backwards-compat alias (some calling sites may still use the old name)
    public void setRequireReferencePhoto(boolean require) { setRequireAtLeastOnePhoto(require); }

    // ---------- STT: tap (one-shot) + hold (live) ----------
    private @Nullable SpeechRecognizer speechRecognizer = null;
    private Intent speechRecognizerIntent;
    private boolean recognizerActive = false;

    private enum VoiceTarget { TITLE, DESC }
    @Nullable private VoiceTarget currentHoldTarget = null;
    @Nullable private TextInputEditText currentHoldInput = null;
    @Nullable private VoiceTarget pendingTapTarget = null;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Rect holdBounds = new Rect();
    private final Handler holdHandler = new Handler(Looper.getMainLooper());
    private boolean outOfBounds = false;

    // Timer shown as helper text on the active TextInputLayout
    private long timerStartedAt = 0L;
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (currentHoldTarget == null) return;
            long s = Math.max(0, (System.currentTimeMillis() - timerStartedAt) / 1000);
            String mmss = String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60);
            getLayoutForTarget(currentHoldTarget).setHelperText("Listening… " + mmss);
            uiHandler.postDelayed(this, 500);
        }
    };

    private String baseTextBeforeHold = "";
    private String committedTextDuringHold = "";

    // ---------- Activity Result Launchers ----------
    private ActivityResultLauncher<String> requestCameraPermission;
    private ActivityResultLauncher<String> requestMicPermission;
    private ActivityResultLauncher<String> requestReadImagesPermission;
    private ActivityResultLauncher<Uri>    takePictureLauncher;
    private ActivityResultLauncher<String> pickMultipleImagesLauncher;
    private ActivityResultLauncher<Intent> sttLauncher; // one-shot STT (tap)

    @Override public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        registerLaunchers();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.bottomsheet_egg_card, container, false);

        titleLayout = root.findViewById(R.id.titleLayout);
        descLayout  = root.findViewById(R.id.descLayout);
        titleInput  = root.findViewById(R.id.titleInput);
        descInput   = root.findViewById(R.id.descInput);

        addPhotosBtn          = root.findViewById(R.id.addPhotosBtn);
        photoHint             = root.findViewById(R.id.photoHint);
        photoEmpty            = root.findViewById(R.id.photoEmpty);
        mediaPreviewContainer = root.findViewById(R.id.mediaPreviewContainer);

        cancelBtn = root.findViewById(R.id.cancelBtn);
        saveBtn   = root.findViewById(R.id.saveBtn);

        // Wire the small side-mics (tap = one-shot; long-press = live)
        wireFieldMic(titleLayout, titleInput, VoiceTarget.TITLE);
        wireFieldMic(descLayout,  descInput,  VoiceTarget.DESC);

        addPhotosBtn.setOnClickListener(v -> showPhotoOptions());
        cancelBtn.setOnClickListener(v -> { if (listener != null) listener.onCancel(); dismissAllowingStateLoss(); });

        // Live enable/disable of Save as user types / adds photos
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateSaveEnabled(); }
            @Override public void afterTextChanged(Editable s) {}
        };
        titleInput.addTextChangedListener(watcher);
        descInput.addTextChangedListener(watcher);

        saveBtn.setOnClickListener(v -> {
            String title = safeText(titleInput);
            String desc  = safeText(descInput);

            titleLayout.setError(null);
            descLayout.setError(null);

            boolean hasError = false;
            if (title.isEmpty()) { titleLayout.setError("Title is required"); titleInput.requestFocus(); hasError = true; }
            if (desc.isEmpty())  { descLayout.setError("Description is required"); if (!hasError) descInput.requestFocus(); hasError = true; }
            if (hasError) return;

            // Require ≥1 photo only when the flag is on (Puzzle / non-mesh flow)
            if (requireAtLeastOnePhoto && pickedPhotoUris.isEmpty()) {
                Toast.makeText(requireContext(),
                        "Please add at least one photo.", Toast.LENGTH_LONG).show();
                return;
            }

            if (listener != null) {
                listener.onSave(
                        title,
                        desc,
                        new ArrayList<>(pickedPhotoUris),
                        /* audio */ null,
                        geoPoseSnapshot
                );
            }
            dismissAllowingStateLoss();
        });

        initSpeechRecognizer();
        refreshPhotoUI();
        updateSaveEnabled();
        return root;
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        stopLiveIfNeeded(true);
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }

    // --------------------------- Enable/disable Save ---------------------------
    private void updateSaveEnabled() {
        boolean okTitle = !safeText(titleInput).isEmpty();
        boolean okDesc  = !safeText(descInput).isEmpty();
        boolean okPhoto = !requireAtLeastOnePhoto || !pickedPhotoUris.isEmpty();
        boolean enabled = okTitle && okDesc && okPhoto;
        saveBtn.setEnabled(enabled);
        saveBtn.setAlpha(enabled ? 1f : 0.5f);
    }

    // --------------------------- Mic: tap + hold ---------------------------
    private void wireFieldMic(TextInputLayout layout, TextInputEditText input, VoiceTarget target) {
        layout.post(() -> {
            // Find end icon (mic) view
            int endIconId = layout.getResources()
                    .getIdentifier("text_input_end_icon", "id", "com.google.android.material");
            final View endIcon = endIconId != 0 ? layout.findViewById(endIconId) : null;

            // Tap = one-shot
            layout.setEndIconOnClickListener(v -> startTapSTT(target));

            // Long-press = live dictation
            layout.setEndIconOnLongClickListener(v -> {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO);
                    return true;
                }
                if (endIcon != null) {
                    endIcon.getGlobalVisibleRect(holdBounds);
                    int margin = (int) dp(96);
                    holdBounds.inset(-margin, -margin);
                }
                outOfBounds = false;
                baseTextBeforeHold = safeText(input);
                committedTextDuringHold = baseTextBeforeHold;

                setParentsDisallowIntercept(v, true);
                beginLiveFor(target, input);
                return true;
            });

            // Drag-to-cancel if end icon exists
            if (endIcon != null) {
                endIcon.setOnTouchListener((v, ev) -> {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_MOVE: {
                            if (currentHoldTarget != null) {
                                boolean inside = holdBounds.contains((int) ev.getRawX(), (int) ev.getRawY());
                                if (!inside && !outOfBounds) {
                                    outOfBounds = true;
                                    holdHandler.postDelayed(() -> {
                                        if (outOfBounds && currentHoldTarget != null) {
                                            stopLiveIfNeeded(true);
                                            Toast.makeText(requireContext(), "Cancelled", Toast.LENGTH_SHORT).show();
                                        }
                                    }, 250);
                                } else if (inside && outOfBounds) {
                                    outOfBounds = false;
                                    holdHandler.removeCallbacksAndMessages(null);
                                }
                            }
                            break;
                        }
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL: {
                            holdHandler.removeCallbacksAndMessages(null);
                            if (currentHoldTarget != null) {
                                boolean insideUp = holdBounds.contains((int) ev.getRawX(), (int) ev.getRawY());
                                stopLiveIfNeeded(!insideUp);
                                setParentsDisallowIntercept(v, false);
                            }
                            break;
                        }
                    }
                    return false;
                });
            }
        });
    }

    private void startTapSTT(VoiceTarget target) {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT,
                target == VoiceTarget.TITLE ? "Speak the title" : "Speak the description");
        try {
            pendingTapTarget = target;
            sttLauncher.launch(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "Speech recognition not available", Toast.LENGTH_LONG).show();
        }
    }

    private void beginLiveFor(VoiceTarget target, TextInputEditText input) {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "Speech recognition not available", Toast.LENGTH_LONG).show();
            return;
        }
        currentHoldTarget = target;
        currentHoldInput = input;

        // UI: helper text timer
        timerStartedAt = System.currentTimeMillis();
        getLayoutForTarget(target).setHelperText("Listening… 00:00");
        uiHandler.postDelayed(timerTick, 300);

        if (speechRecognizer == null) initSpeechRecognizer();
        startLiveRecognition();
    }

    private void stopLiveIfNeeded(boolean cancel) {
        if (currentHoldTarget == null) return;

        try {
            if (speechRecognizer != null) {
                if (cancel) speechRecognizer.cancel(); else speechRecognizer.stopListening();
            }
        } catch (Exception ignored) {}
        recognizerActive = false;

        uiHandler.removeCallbacks(timerTick);
        getLayoutForTarget(currentHoldTarget).setHelperText(null);

        if (cancel && currentHoldInput != null) {
            currentHoldInput.setText(baseTextBeforeHold);
            if (currentHoldInput.getText() != null)
                currentHoldInput.setSelection(currentHoldInput.getText().length());
        }

        currentHoldTarget = null;
        currentHoldInput = null;
    }

    private TextInputLayout getLayoutForTarget(VoiceTarget t) {
        return (t == VoiceTarget.TITLE) ? titleLayout : descLayout;
    }

    // --------------------------- SpeechRecognizer (live) ---------------------------
    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override public void onError(int error) {
                recognizerActive = false;
                if (currentHoldTarget != null) uiHandler.postDelayed(EggCardSheet.this::startLiveRecognition, 200);
            }

            @Override public void onResults(Bundle results) {
                List<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (currentHoldInput != null && list != null && !list.isEmpty()) {
                    String out = TextUtils.isEmpty(committedTextDuringHold) ? list.get(0)
                            : committedTextDuringHold + " " + list.get(0);
                    currentHoldInput.setText(out);
                    currentHoldInput.setSelection(out.length());
                    committedTextDuringHold = out;
                }
                recognizerActive = false;
                if (currentHoldTarget != null) startLiveRecognition();
            }

            @Override public void onPartialResults(Bundle partialResults) {
                if (currentHoldInput == null) return;
                List<String> list = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list == null || list.isEmpty()) return;
                String preview = committedTextDuringHold;
                if (!TextUtils.isEmpty(preview)) preview += " ";
                preview += list.get(0);
                currentHoldInput.setText(preview);
                currentHoldInput.setSelection(preview.length());
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, requireContext().getPackageName());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000);
    }

    private void startLiveRecognition() {
        if (speechRecognizer == null || recognizerActive) return;
        try {
            recognizerActive = true;
            speechRecognizer.startListening(speechRecognizerIntent);
        } catch (Exception e) {
            recognizerActive = false;
        }
    }

    // --------------------------- Photos ---------------------------
    private void registerLaunchers() {
        requestCameraPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) launchCameraNow(); }
        );

        requestMicPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (!granted) {
                        Toast.makeText(requireContext(), "Microphone permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        requestReadImagesPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) pickFromGalleryNow(); }
        );

        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraTempPhotoUri != null) {
                        pickedPhotoUris.add(cameraTempPhotoUri);
                        Toast.makeText(requireContext(), "Photo added ✓", Toast.LENGTH_SHORT).show();
                        refreshPhotoUI();
                    }
                    cameraTempPhotoUri = null;
                }
        );

        pickMultipleImagesLauncher = registerForActivityResult(
                new ActivityResultContracts.GetMultipleContents(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        int before = pickedPhotoUris.size();
                        for (Uri u : uris) {
                            if (pickedPhotoUris.size() >= 5) break;
                            pickedPhotoUris.add(u);
                        }
                        int added = pickedPhotoUris.size() - before;
                        if (added > 0) {
                            Toast.makeText(requireContext(), added + " photo(s) added ✓", Toast.LENGTH_SHORT).show();
                            refreshPhotoUI();
                        }
                    }
                }
        );

        // one-shot (tap) STT result sink
        sttLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                res -> {
                    if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null) return;
                    ArrayList<String> list = res.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    if (list == null || list.isEmpty() || pendingTapTarget == null) return;
                    String text = list.get(0);
                    if (pendingTapTarget == VoiceTarget.TITLE) {
                        titleInput.setText(text);
                        titleInput.setSelection(text.length());
                    } else {
                        String cur = safeText(descInput);
                        String merged = cur.isEmpty() ? text : (cur.endsWith(" ") ? cur + text : cur + " " + text);
                        descInput.setText(merged);
                        descInput.setSelection(merged.length());
                    }
                    pendingTapTarget = null;
                }
        );
    }

    private void showPhotoOptions() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Add photos")
                .setItems(new CharSequence[]{"Take photo", "Pick from gallery"}, (d, which) -> {
                    if (which == 0) ensureCameraThenLaunch(); else pickFromGallery();
                }).show();
    }

    private void ensureCameraThenLaunch() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        } else {
            launchCameraNow();
        }
    }

    private void launchCameraNow() {
        try {
            File img = createTempImageFile(requireContext());
            cameraTempPhotoUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    img
            );
            takePictureLauncher.launch(cameraTempPhotoUri);
        } catch (IOException e) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "egg_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraTempPhotoUri = requireContext().getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (cameraTempPhotoUri != null) takePictureLauncher.launch(cameraTempPhotoUri);
        }
    }

    private void pickFromGallery() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestReadImagesPermission.launch(Manifest.permission.READ_MEDIA_IMAGES);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestReadImagesPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
                return;
            }
        }
        pickFromGalleryNow();
    }

    private void pickFromGalleryNow() {
        pickMultipleImagesLauncher.launch("image/*");
    }

    private void refreshPhotoUI() {
        boolean hasPhotos = !pickedPhotoUris.isEmpty();

        // Update hint + empty label
        photoEmpty.setVisibility(hasPhotos ? View.GONE : View.VISIBLE);
        if (hasPhotos) {
            photoHint.setText(pickedPhotoUris.size() + " selected • Add more");
            addPhotosBtn.setText("Add more");
        } else {
            photoHint.setText(requireAtLeastOnePhoto
                    ? "Required: add at least one photo"
                    : "Take a photo or pick up to 5");
            addPhotosBtn.setText("Add photos");
        }

        // Rebuild preview strip
        mediaPreviewContainer.removeAllViews();
        final int size = (int) dp(64);
        final int pad  = (int) dp(6);

        for (int i = 0; i < pickedPhotoUris.size(); i++) {
            Uri u = pickedPhotoUris.get(i);

            ImageView iv = new ImageView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(pad, pad, pad, pad);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

            Glide.with(this)
                    .load(u)
                    .placeholder(android.R.drawable.ic_menu_report_image)
                    .error(android.R.drawable.ic_menu_report_image)
                    .into(iv);

            mediaPreviewContainer.addView(iv);
        }

        updateSaveEnabled();
    }

    // --------------------------- Utils ---------------------------
    private static File createTempImageFile(@NonNull Context ctx) throws IOException {
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File(ctx.getExternalFilesDir(null), "tempimg");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return File.createTempFile("IMG_" + time + "_", ".jpg", dir);
    }

    private static String safeText(@Nullable TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        String s = et.getText().toString();
        return TextUtils.isEmpty(s) ? "" : s.trim();
    }

    private float dp(float v) {
        return v * requireContext().getResources().getDisplayMetrics().density;
    }

    private void setParentsDisallowIntercept(View v, boolean disallow) {
        ViewParent p = v.getParent();
        while (p != null) {
            p.requestDisallowInterceptTouchEvent(disallow);
            p = p.getParent();
        }
    }
}