package com.google.ar.core.examples.java.helloar.data;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Handles Firestore doc + Storage uploads for eggs. */
public class EggRepository {
    private static final String TAG = "EggRepository";

    private final FirebaseFirestore db;
    private final StorageReference storage;

    /** Use the project's default Firebase Storage bucket (from google-services.json). */
    public EggRepository() {
        this.db = FirebaseFirestore.getInstance();
        FirebaseStorage fs = FirebaseStorage.getInstance(); // default bucket
        this.storage = fs.getReference();

        // Helpful sanity log (remove if noisy)
        FirebaseOptions opts = FirebaseApp.getInstance().getOptions();
        Log.d(TAG, "Using default bucket: " + opts.getStorageBucket());
    }

    @Deprecated
    public EggRepository(@Nullable String unusedBucketUrl) { this(); }

    /** Create a draft Firestore document with whatever fields you already have. */
    public Task<DocumentReference> createDraft(EggEntry e) {
        Map<String, Object> doc = new HashMap<>();

        // Identity / content
        if (e.userId != null)        doc.put("userId", e.userId);
        if (e.title != null)         doc.put("title",e.title);
        if (e.description != null)   doc.put("description", e.description);

        // Geospatial
        if (e.geo != null)           doc.put("geo", e.geo);
        if (e.alt != null)           doc.put("alt", e.alt);
        if (e.heading != null)       doc.put("heading", e.heading);
        if (e.horizAcc != null)      doc.put("horizAcc", e.horizAcc);
        if (e.vertAcc != null)       doc.put("vertAcc", e.vertAcc);

        // Pose (optional)
        if (e.poseMatrix != null)    doc.put("poseMatrix", e.poseMatrix);

        // Placement metadata (depth/point/plane, augmented image tag, distance)
        if (e.refImage != null)         doc.put("refImage", e.refImage);
        if (e.placementType != null)    doc.put("placementType", e.placementType);
        if (e.distanceFromCamera != null) doc.put("distanceFromCamera", e.distanceFromCamera);

        // Persistence / anchoring mode
        if (e.anchorType != null)    doc.put("anchorType", e.anchorType);   // "CLOUD" | "GEO" | "LOCAL"
        if (e.cloudId != null)       doc.put("cloudId", e.cloudId);         // may be null at creation
        if (e.cloudTtlDays != null)  doc.put("cloudTtlDays", e.cloudTtlDays);


        // Speech + quiz (ok to be null)
        if (e.speechTranscript != null) doc.put("speechTranscript", e.speechTranscript);
        if (e.quiz != null)             doc.put("quiz", e.quiz); // List<EggEntry.QuizQuestion>

        // Media flags + timestamps
        doc.put("hasMedia", false);
        doc.put("createdAt", FieldValue.serverTimestamp());

        return db.collection("eggs").add(doc);
    }

    /**
     * Upload photos/audio to Storage and then PATCH the Firestore doc with their Storage paths.
     */
    public Task<Void> uploadMediaAndPatch(
            DocumentReference docRef,
            @Nullable List<Uri> photoUris,
            @Nullable Uri audioUri
    ) {
        final String docId = docRef.getId();
        final List<Task<String>> photoUploadTasks = new ArrayList<>();

        // Photos
        if (photoUris != null && !photoUris.isEmpty()) {
            int idx = 0;
            for (Uri uri : photoUris) {
                if (uri == null) continue;
                String path = "eggs/" + docId + "/photos/photo_" + (idx++) + fileExt(uri);
                photoUploadTasks.add(uploadOne(uri, storage.child(path)));
            }
        }

        // Audio (optional)
        Task<String> audioUploadTask = null;
        if (audioUri != null) {
            String path = "eggs/" + docId + "/audio/voice" + fileExt(audioUri);
            audioUploadTask = uploadOne(audioUri, storage.child(path));
        }

        // Wait for photos; combine with audio if present; then patch Firestore
        Task<List<String>> allPhotosTask = Tasks.whenAllSuccess(photoUploadTasks);

        if (audioUploadTask != null) {
            return Tasks.whenAllSuccess(allPhotosTask, audioUploadTask)
                    .onSuccessTask(results -> {
                        @SuppressWarnings("unchecked")
                        List<String> photoPaths = (List<String>) results.get(0);
                        String audioPath = (String) results.get(1);
                        Map<String, Object> patch = new HashMap<>();
                        patch.put("photoPaths", photoPaths);
                        patch.put("audioPath", audioPath);
                        patch.put("hasMedia", true);
                        patch.put("updatedAt", FieldValue.serverTimestamp());
                        return docRef.update(patch);
                    });
        } else {
            return allPhotosTask.onSuccessTask(photoPaths -> {
                Map<String, Object> patch = new HashMap<>();
                patch.put("photoPaths", photoPaths);
                patch.put("hasMedia", photoPaths != null && !photoPaths.isEmpty());
                patch.put("updatedAt", FieldValue.serverTimestamp());
                return docRef.update(patch);
            });
        }
    }

    /** Upload one file, return a Task<String> that resolves to its Storage path. */
    private Task<String> uploadOne(Uri localUri, StorageReference dest) {
        UploadTask task = dest.putFile(localUri);
        return task.continueWithTask(t -> {
            if (!t.isSuccessful()) throw t.getException();
            return Tasks.forResult(dest.getPath()); // e.g. "/eggs/{id}/photos/photo_0.jpg"
        }).addOnFailureListener(e -> Log.e(TAG, "Upload failed: " + dest.getPath(), e));
    }

    private static String fileExt(Uri uri) {
        String s = uri.toString();
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot >= s.length() - 6) {
            return s.substring(dot);
        }
        return ".bin";
    }

    public Task<List<EggEntry>> fetchAllEggs() {
        return db.collection("eggs").get().onSuccessTask(snap -> {
            List<EggEntry> out = new ArrayList<>();
            snap.getDocuments().forEach(d -> {
                EggEntry e = d.toObject(EggEntry.class); // hydrates nested fields if present
                if (e != null) { e.id = d.getId(); out.add(e); }
            });
            return Tasks.forResult(out);
        });
    }

    public Task<List<EggEntry>> fetchEggsNear(double lat, double lng, double radiusMeters) {
        return fetchAllEggs().onSuccessTask(list -> {
            List<EggEntry> filtered = new ArrayList<>();
            for (EggEntry e : list) {
                if (e.geo == null) continue;
                if (distanceMeters(lat, lng, e.lat(), e.lng()) <= radiusMeters) {
                    filtered.add(e);
                }
            }
            return Tasks.forResult(filtered);
        });
    }

    /** Turn a Storage *path* (e.g. "/eggs/.../photo_0.jpg") into a downloadable URL. */
    public Task<Uri> downloadUrlFromPath(String storagePath) {
        String clean = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return storage.child(clean).getDownloadUrl(); // storage is already a root reference
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2*R*Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    // ---- New helpers for anchoring ----

    /** Patch Cloud Anchor info after hosting succeeds. Also sets anchorType="CLOUD". */
    public Task<Void> patchCloudAnchor(DocumentReference docRef, String cloudId, @Nullable Integer ttlDays) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("anchorType", EggEntry.AnchorTypes.CLOUD);
        patch.put("cloudId", cloudId);
        if (ttlDays != null) patch.put("cloudTtlDays", ttlDays);
        patch.put("cloudHostedAt", FieldValue.serverTimestamp());
        return docRef.update(patch);
    }

    public Task<Void> patchCloudAnchorByDocId(String docId, String cloudId, @Nullable Integer ttlDays) {
        return patchCloudAnchor(db.collection("eggs").document(docId), cloudId, ttlDays);
    }

    /** Optional: patch geospatial accuracy/headings later if you refine them. */
    public Task<Void> patchGeospatialMeta(DocumentReference docRef,
                                          @Nullable Double horizAcc,
                                          @Nullable Double vertAcc,
                                          @Nullable Double heading) {
        Map<String, Object> patch = new HashMap<>();
        if (horizAcc != null) patch.put("horizAcc", horizAcc);
        if (vertAcc != null)  patch.put("vertAcc", vertAcc);
        if (heading != null)  patch.put("heading", heading);
        patch.put("updatedAt", FieldValue.serverTimestamp());
        return docRef.update(patch);
    }

    // ✅ Optional helper if you ever want to regenerate/patch quiz later
    public Task<Void> updateQuiz(DocumentReference docRef, @Nullable List<EggEntry.QuizQuestion> quiz) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("quiz", quiz);
        patch.put("updatedAt", FieldValue.serverTimestamp());
        return docRef.update(patch);
    }
}
