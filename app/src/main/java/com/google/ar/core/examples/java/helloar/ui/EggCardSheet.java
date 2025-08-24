package com.google.ar.core.examples.java.helloar.ui;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
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

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.ar.core.examples.java.helloar.R;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import android.view.ViewParent;


/** Bottom sheet to capture egg details + media (+ optional GeoPose snapshot). */
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

    // Includes transcript in the callback
    public interface Listener {
        void onSave(String title,
                    String description,
                    String transcript,
                    List<Uri> photoUris,
                    @Nullable Uri audioUri,
                    @Nullable GeoPoseSnapshot geoPose);
        void onCancel();
    }

    private @Nullable Listener listener;
    public void setListener(@Nullable Listener l) { this.listener = l; }

    private @Nullable GeoPoseSnapshot geoPoseSnapshot;
    public void setGeoPoseSnapshot(@Nullable GeoPoseSnapshot snapshot) { this.geoPoseSnapshot = snapshot; }

    // ---------- UI ----------
    private TextInputEditText titleInput, descInput;
    private TextInputEditText transcriptInput;           // editable paragraph
    private Button addPhotosBtn, recordAudioBtn, cancelBtn, saveBtn;
    private TextView photoHint, photoEmpty, recordingTimer;
    private LinearLayout mediaPreviewContainer, playbackGroup;
    private View photoStrip;
    private Chip recordingChip;
    private Button playPauseBtn, reRecordBtn, deleteAudioBtn;

    // ---------- Photo state ----------
    private final ArrayList<Uri> pickedPhotoUris = new ArrayList<>();
    private @Nullable Uri cameraTempPhotoUri = null;

    // ---------- Voice-note recording (file) ----------
    private @Nullable MediaRecorder mediaRecorder = null;
    private @Nullable MediaPlayer mediaPlayer = null;
    private boolean isAudioRecording = false;
    private boolean isPlaying = false;
    private @Nullable File audioFile = null;
    private @Nullable Uri audioUri = null;

    // ---------- Live transcription (no file) ----------
    private @Nullable SpeechRecognizer speechRecognizer = null;
    private Intent speechRecognizerIntent;
    private boolean userIsHolding = false;
    private boolean recognizerActive = false;
    private final StringBuilder transcriptBuffer = new StringBuilder(); // committed text
    private String lastFinal = ""; // last finalized segment to reduce duplicates

    // ---------- Timers / handlers ----------
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private long recordStartTime = 0L;

    // touch helpers
    private int touchSlop;
    private float downX, downY;
    private boolean movedOutside;
    private final Rect tmpRect = new Rect();

    // ---------- Activity Result Launchers ----------
    private ActivityResultLauncher<String> requestCameraPermission;
    private ActivityResultLauncher<String> requestMicPermissionTranscribe;
    private ActivityResultLauncher<String> requestMicPermissionAudio;
    private ActivityResultLauncher<String> requestReadImagesPermission;
    private ActivityResultLauncher<Uri>    takePictureLauncher;
    private ActivityResultLauncher<String> pickMultipleImagesLauncher;

    @Override public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        registerLaunchers();
        initSpeechRecognizer();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.bottomsheet_egg_card, container, false);

        // Inputs
        titleInput  = root.findViewById(R.id.titleInput);
        descInput   = root.findViewById(R.id.descInput);
        transcriptInput = root.findViewById(R.id.transcriptionInput); // must exist in layout

        // Photos
        addPhotosBtn           = root.findViewById(R.id.addPhotosBtn);
        photoHint              = root.findViewById(R.id.photoHint);
        photoEmpty             = root.findViewById(R.id.photoEmpty);
        mediaPreviewContainer  = root.findViewById(R.id.mediaPreviewContainer);
        photoStrip             = root.findViewById(R.id.photoStrip);

        // Audio / transcript UI
        recordAudioBtn  = root.findViewById(R.id.recordAudioBtn); // HOLD to live-transcribe
        playbackGroup   = root.findViewById(R.id.playbackGroup);  // shows when an audio file exists
        playPauseBtn    = root.findViewById(R.id.playPauseBtn);
        reRecordBtn     = root.findViewById(R.id.reRecordBtn);    // tap to record/stop voice-note file
        deleteAudioBtn  = root.findViewById(R.id.deleteAudioBtn);
        recordingChip   = root.findViewById(R.id.recordingChip);
        recordingTimer  = root.findViewById(R.id.recordingTimer);

        // Actions
        cancelBtn = root.findViewById(R.id.cancelBtn);
        saveBtn   = root.findViewById(R.id.saveBtn);

        // Photos
        addPhotosBtn.setOnClickListener(v -> showPhotoOptions());

        // ---------- Robust HOLD-TO-SPEAK gesture ----------
        recordAudioBtn.setLongClickable(false); // avoid long-click conflicts
        recordAudioBtn.setHapticFeedbackEnabled(true);
        recordAudioBtn.setOnTouchListener((v, event) -> {
            // Always try to keep ScrollView from intercepting while we're holding.
            final ViewParent parent = v.getParent();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    // If a voice-note file recording is running, don't start live transcript
                    if (isAudioRecording) {
                        Toast.makeText(requireContext(), "Stop voice-note recording first", Toast.LENGTH_SHORT).show();
                        return true;
                    }

                    // Check permission first — if not granted, request and abort this press.
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestMicPermissionTranscribe.launch(Manifest.permission.RECORD_AUDIO);
                        return true;
                    }

                    movedOutside = false;
                    downX = event.getRawX();
                    downY = event.getRawY();

                    v.getGlobalVisibleRect(tmpRect);
                    // Give some wiggle room beyond the button
                    tmpRect.inset(-Math.max(100, touchSlop * 2), -Math.max(100, touchSlop * 2));

                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    v.setPressed(true);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

                    beginHoldToTranscribe();
                    return true;
                }

                case MotionEvent.ACTION_MOVE: {
                    // if we never started holding (e.g., permission dialog), ignore.
                    if (!userIsHolding) return true;

                    // Keep parent from hijacking during move
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);

                    // If pointer moved too far or exited expanded bounds, cancel
                    float dx = Math.abs(event.getRawX() - downX);
                    float dy = Math.abs(event.getRawY() - downY);
                    // refresh rect each move in case of layout changes
                    v.getGlobalVisibleRect(tmpRect);
                    tmpRect.inset(-Math.max(100, touchSlop * 2), -Math.max(100, touchSlop * 2));

                    if (dx > touchSlop * 2 || dy > touchSlop * 2 || !tmpRect.contains((int) event.getRawX(), (int) event.getRawY())) {
                        if (!movedOutside) {
                            movedOutside = true;
                            endHoldToTranscribe(true);
                            v.setPressed(false);
                            Toast.makeText(requireContext(), "Listening cancelled", Toast.LENGTH_SHORT).show();
                            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                        }
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP: {
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                    // Only finish if we were actually holding and not already cancelled
                    if (userIsHolding && !movedOutside) {
                        endHoldToTranscribe(false);
                    }
                    v.setPressed(false);
                    return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                    if (userIsHolding) {
                        endHoldToTranscribe(true);
                    }
                    v.setPressed(false);
                    return true;
                }
            }
            return false;
        });

        // Tap to record/stop voice-note (file)
        reRecordBtn.setOnClickListener(v -> {
            if (userIsHolding) {
                Toast.makeText(requireContext(), "Release the hold-to-speak first", Toast.LENGTH_SHORT).show();
                return;
            }
            if (isAudioRecording) {
                stopAudioRecordingAndSave();
            } else {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestMicPermissionAudio.launch(Manifest.permission.RECORD_AUDIO);
                } else {
                    startAudioRecording();
                }
            }
        });

        playPauseBtn.setOnClickListener(v -> togglePlayback());

        deleteAudioBtn.setOnClickListener(v -> {
            stopPlaybackIfNeeded();
            clearAudioSelection();
            Toast.makeText(requireContext(), "Audio removed", Toast.LENGTH_SHORT).show();
        });

        cancelBtn.setOnClickListener(v -> {
            if (listener != null) listener.onCancel();
            dismissAllowingStateLoss();
        });

        saveBtn.setOnClickListener(v -> {
            if (listener != null) {
                String title = safeText(titleInput);
                String desc  = safeText(descInput);
                String transcript = safeText(transcriptInput);
                listener.onSave(title, desc, transcript, new ArrayList<>(pickedPhotoUris), audioUri, geoPoseSnapshot);
            }
            dismissAllowingStateLoss();
        });

        refreshPhotoUI();
        refreshAudioUI();
        return root;
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        endHoldToTranscribe(true);
        stopAndReleaseRecorder();
        stopPlaybackIfNeeded();
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }

    // ---------- Photo helpers ----------
    private void registerLaunchers() {
        requestCameraPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) launchCameraNow(); }
        );

        requestMicPermissionTranscribe = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        // Ask user to press-and-hold again (safer than auto-starting unexpectedly)
                        Toast.makeText(requireContext(), "Mic granted. Press and HOLD to speak.", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        requestMicPermissionAudio = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) startAudioRecording(); }
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
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                requestReadImagesPermission.launch(Manifest.permission.READ_MEDIA_IMAGES);
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
        photoEmpty.setVisibility(hasPhotos ? View.GONE : View.VISIBLE);
        photoStrip.setVisibility(hasPhotos ? View.VISIBLE : View.GONE);

        if (hasPhotos) {
            photoHint.setText(pickedPhotoUris.size() + " selected • Add more");
            addPhotosBtn.setText("Add more");
        } else {
            photoHint.setText("Take a photo or pick up to 5");
            addPhotosBtn.setText("Add photos");
        }

        mediaPreviewContainer.removeAllViews();
        final int size = (int) dp(64);
        final int pad  = (int) dp(6);
        for (Uri u : pickedPhotoUris) {
            ImageView iv = new ImageView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(pad, pad, pad, pad);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setImageURI(u);
            mediaPreviewContainer.addView(iv);
        }
    }

    // ---------- Live transcription ----------
    private void beginHoldToTranscribe() {
        userIsHolding = true;

        // Show current committed buffer immediately (keep existing edits)
        runOnUi(() -> transcriptInput.setText(transcriptBuffer.toString()));
        runOnUi(() -> transcriptInput.setSelection(transcriptInput.getText().length()));

        recordingChip.setVisibility(View.VISIBLE);
        recordingTimer.setVisibility(View.VISIBLE);
        recordAudioBtn.setText("Release to Stop");
        recordStartTime = System.currentTimeMillis();
        uiHandler.post(transcribeTimerRunnable);

        startLiveRecognition();
    }

    private void endHoldToTranscribe(boolean cancel) {
        if (!userIsHolding) return;
        userIsHolding = false;

        stopLiveRecognition(cancel);

        recordingChip.setVisibility(View.GONE);
        recordingTimer.setVisibility(View.GONE);
        uiHandler.removeCallbacks(transcribeTimerRunnable);
        recordAudioBtn.setText("Hold to Speak");
        lastFinal = "";
    }

    private final Runnable transcribeTimerRunnable = new Runnable() {
        @Override public void run() {
            if (!userIsHolding) return;
            long elapsedSec = (System.currentTimeMillis() - recordStartTime) / 1000;
            recordingTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", elapsedSec / 60, elapsedSec % 60));
            uiHandler.postDelayed(this, 500);
        }
    };

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "Speech recognition not available on this device", Toast.LENGTH_LONG).show();
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override public void onError(int error) {
                recognizerActive = false;
                if (userIsHolding) {
                    // Restart quickly to maintain continuous dictation
                    uiHandler.postDelayed(EggCardSheet.this::startLiveRecognition, 250);
                }
            }

            @Override public void onResults(Bundle results) {
                List<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String utterance = normalize(matches.get(0));
                    commitFinal(utterance);
                }
                recognizerActive = false;
                if (userIsHolding) startLiveRecognition();
            }

            @Override public void onPartialResults(Bundle partialResults) {
                List<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (!userIsHolding) return;
                if (partial != null && !partial.isEmpty()) {
                    String p = normalize(partial.get(0));
                    // Show committed + live tail
                    String preview = transcriptBuffer.toString();
                    if (preview.length() > 0 && !preview.endsWith(" ")) preview += " ";
                    preview += p;
                    final String display = preview.trim();
                    runOnUi(() -> {
                        transcriptInput.setText(display);
                        transcriptInput.setSelection(display.length());
                    });
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });

        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, requireContext().getPackageName());
        // Tweak these to reduce dropouts:
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200);
        // speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
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

    private void stopLiveRecognition(boolean cancel) {
        if (speechRecognizer == null) return;
        try { if (cancel) speechRecognizer.cancel(); else speechRecognizer.stopListening(); }
        catch (Exception ignored) {}
        recognizerActive = false;
    }

    private void commitFinal(String utterance) {
        // Avoid duplicating the same final across restarts
        if (utterance.equalsIgnoreCase(lastFinal)) return;
        if (transcriptBuffer.length() > 0 && !transcriptBuffer.toString().endsWith(" ")) transcriptBuffer.append(' ');
        transcriptBuffer.append(utterance);
        lastFinal = utterance;

        if (userIsHolding) {
            final String display = transcriptBuffer.toString();
            runOnUi(() -> {
                transcriptInput.setText(display);
                transcriptInput.setSelection(display.length());
            });
        }
    }

    private static String normalize(String s) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        return s;
    }

    // ---------- Voice-note audio recording (file) ----------
    private void startAudioRecording() {
        if (isAudioRecording) return;

        try {
            audioFile = createTempAudioFile(requireContext());
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();

            isAudioRecording = true;
            recordStartTime = System.currentTimeMillis();

            recordingChip.setVisibility(View.VISIBLE);
            recordingTimer.setVisibility(View.VISIBLE);
            reRecordBtn.setText("Stop Recording");
            uiHandler.post(audioTimerRunnable);

        } catch (Exception e) {
            Toast.makeText(getContext(), "Recording failed", Toast.LENGTH_SHORT).show();
            stopAndReleaseRecorder();
            isAudioRecording = false;
            reRecordBtn.setText("Record");
        }
    }

    private final Runnable audioTimerRunnable = new Runnable() {
        @Override public void run() {
            if (!isAudioRecording) return;
            long elapsedSec = (System.currentTimeMillis() - recordStartTime) / 1000;
            recordingTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", elapsedSec / 60, elapsedSec % 60));
            uiHandler.postDelayed(this, 500);
        }
    };

    private void stopAudioRecordingAndSave() {
        if (!isAudioRecording) return;
        try { if (mediaRecorder != null) mediaRecorder.stop(); } catch (RuntimeException ignored) {}
        finally { stopAndReleaseRecorder(); }

        isAudioRecording = false;
        uiHandler.removeCallbacks(audioTimerRunnable);
        recordingChip.setVisibility(View.GONE);
        recordingTimer.setVisibility(View.GONE);
        reRecordBtn.setText("Record");

        if (audioFile != null && audioFile.exists()) {
            audioUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    audioFile
            );
            Toast.makeText(getContext(), "Recording saved", Toast.LENGTH_SHORT).show();
        }
        refreshAudioUI();
    }

    private void stopAndReleaseRecorder() {
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
    }

    private void refreshAudioUI() {
        boolean hasAudio = (audioUri != null);
        playbackGroup.setVisibility(hasAudio ? View.VISIBLE : View.GONE);
        playPauseBtn.setText(isPlaying ? "Pause" : "Play");
    }

    private void togglePlayback() {
        if (audioUri == null) return;
        if (isPlaying) {
            stopPlaybackIfNeeded();
            playPauseBtn.setText("Play");
        } else {
            try {
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setDataSource(requireContext(), audioUri);
                mediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    playPauseBtn.setText("Play");
                });
                mediaPlayer.prepare();
                mediaPlayer.start();
                isPlaying = true;
                playPauseBtn.setText("Pause");
            } catch (Exception e) {
                stopPlaybackIfNeeded();
                Toast.makeText(requireContext(), "Playback failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopPlaybackIfNeeded() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPlaying = false;
        if (playPauseBtn != null) playPauseBtn.setText("Play");
    }

    private void clearAudioSelection() {
        stopPlaybackIfNeeded();
        if (audioFile != null && audioFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            audioFile.delete();
        }
        audioUri = null;
        audioFile = null;
        recordingTimer.setText("00:00");
        recordingTimer.setVisibility(View.GONE);
        playbackGroup.setVisibility(View.GONE);
        reRecordBtn.setText("Record");
    }

    // ---------- Files & utils ----------
    private static File createTempImageFile(@NonNull Context ctx) throws IOException {
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File(ctx.getExternalFilesDir(null), "tempimg");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return File.createTempFile("IMG_" + time + "_", ".jpg", dir);
    }

    private static File createTempAudioFile(@NonNull Context ctx) throws IOException {
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = new File(ctx.getExternalFilesDir(null), "tempaudio");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return File.createTempFile("AUD_" + time + "_", ".m4a", dir);
    }

    private static String safeText(@Nullable TextInputEditText et) {
        if (et == null || et.getText() == null) return "";
        String s = et.getText().toString();
        return TextUtils.isEmpty(s) ? "" : s.trim();
    }

    private void runOnUi(Runnable r) {
        if (isAdded()) requireActivity().runOnUiThread(r);
    }

    private float dp(float v) {
        return v * requireContext().getResources().getDisplayMetrics().density;
    }
}
