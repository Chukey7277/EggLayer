package com.google.ar.core.examples.java.helloar.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Handles Firestore doc + Storage uploads for eggs. */
public class EggRepository {
    private static final String TAG = "EggRepository";

    private final FirebaseFirestore db;
    private final StorageReference storage;
    private final ContentResolver resolver;
    private final Context appContext;

    /** Use Firebase App’s *configured* bucket. Pass Application Context! */
    public EggRepository(Context appContext) {
        this.appContext = appContext.getApplicationContext();
        this.db = FirebaseFirestore.getInstance();
        this.resolver = this.appContext.getContentResolver();

        FirebaseOptions opts = FirebaseApp.getInstance().getOptions();
        Log.d("FB", "projectId=" + opts.getProjectId() + "  storageBucket=" + opts.getStorageBucket());
        String bucket = opts.getStorageBucket(); // e.g. egglayer1-468718.appspot.com
        StorageReference ref;
        if (bucket == null || bucket.trim().isEmpty()) {
            Log.w(TAG, "Storage bucket missing in FirebaseOptions; falling back to default Storage instance.");
            ref = FirebaseStorage.getInstance().getReference();
        } else {
            // Force exact bucket to avoid 403s from hitting the wrong project/bucket.
            ref = FirebaseStorage.getInstance("gs://" + bucket).getReference();
        }
        this.storage = ref;
        Log.d(TAG, "Using Firebase project: " + opts.getProjectId() + "  bucket: " + (bucket == null ? "<default>" : bucket));
    }

    /** Legacy: only works if a default FirebaseApp is already initialized. */
    @Deprecated
    public EggRepository() {
        this(FirebaseApp.getInstance().getApplicationContext());
    }

    @Deprecated
    public EggRepository(@Nullable String unusedBucketUrl) {
        this(FirebaseApp.getInstance().getApplicationContext());
    }

    /** Create a draft Firestore document with whatever fields you already have. */
    public Task<DocumentReference> createDraft(EggEntry e) {
        Map<String, Object> doc = new HashMap<>();
        if (e.userId != null)        doc.put("userId", e.userId);
        if (e.title != null)         doc.put("title", e.title);
        if (e.description != null)   doc.put("description", e.description);

        // Geospatial / pose
        if (e.geo != null)           doc.put("geo", e.geo);
        if (e.alt != null)           doc.put("alt", e.alt);
        if (e.heading != null)       doc.put("heading", e.heading);
        if (e.horizAcc != null)      doc.put("horizAcc", e.horizAcc);
        if (e.vertAcc != null)       doc.put("vertAcc", e.vertAcc);
        if (e.poseMatrix != null)    doc.put("poseMatrix", e.poseMatrix);

        // Placement metadata
        if (e.refImage != null)            doc.put("refImage", e.refImage);
        if (e.placementType != null)       doc.put("placementType", e.placementType);
        if (e.distanceFromCamera != null)  doc.put("distanceFromCamera", e.distanceFromCamera);

        // Anchoring
        if (e.anchorType != null)    doc.put("anchorType", e.anchorType);
        if (e.cloudId != null)       doc.put("cloudId", e.cloudId);
        if (e.cloudTtlDays != null)  doc.put("cloudTtlDays", e.cloudTtlDays);

        // Content extras
        if (e.speechTranscript != null) doc.put("speechTranscript", e.speechTranscript);
        if (e.quiz != null)             doc.put("quiz", e.quiz);

        doc.put("hasMedia", false);
        doc.put("createdAt", FieldValue.serverTimestamp());
        return db.collection("eggs").add(doc);
    }

    /**
     * Upload photos/audio to Storage and then PATCH the Firestore doc with their Storage paths.
     * Returns a Task that completes when Firestore is updated.
     */
    public Task<Void> uploadMediaAndPatch(
            DocumentReference docRef,
            @Nullable List<Uri> photoUris,
            @Nullable Uri audioUri
    ) {
        final String docId = docRef.getId();

        // ---- Photos ----
        List<Task<String>> photoUploads = new ArrayList<>();
        if (photoUris != null) {
            int i = 0;
            for (Uri u : photoUris) {
                if (u == null) continue;
                FileInfo info = fileNameAndMime(u, /*fallbackBase*/ "photo_" + (i++));
                String path = "eggs/" + docId + "/photos/" + info.fileNameWithExt;
                photoUploads.add(uploadOne(u, storage.child(path), info.mime));
            }
        }
        Task<List<String>> photosTask = Tasks.whenAllSuccess(photoUploads);

        // ---- Audio (optional) ----
        final Task<String> audioTask;
        if (audioUri != null) {
            FileInfo a = fileNameAndMime(audioUri, "voice");
            String path = "eggs/" + docId + "/audio/" + a.fileNameWithExt;
            audioTask = uploadOne(audioUri, storage.child(path), a.mime);
        } else {
            audioTask = Tasks.forResult(null);
        }

        // ---- Patch once everything we attempted has finished successfully ----
        return photosTask
                .onSuccessTask(photoPaths -> audioTask.onSuccessTask(audioPath -> {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put("photoPaths", photoPaths);
                    if (audioPath != null) patch.put("audioPath", audioPath);
                    patch.put("hasMedia", (photoPaths != null && !photoPaths.isEmpty()) || audioPath != null);
                    patch.put("updatedAt", FieldValue.serverTimestamp());
                    Log.d(TAG, "Patching egg " + docId + " with media: photos=" +
                            (photoPaths != null ? photoPaths.size() : 0) + " audio=" + (audioPath != null));
                    return docRef.update(patch);
                }))
                .addOnFailureListener(e -> Log.e(TAG, "uploadMediaAndPatch failed for " + docId, e));
    }

    /** Upload one file; returns the *Storage path* (e.g., "/eggs/{id}/photos/x.jpg"). */
    private Task<String> uploadOne(Uri localUri, StorageReference dest, @Nullable String contentMime) {
        // Copy ANY content:// stream to a local cache file → upload as file:// (bulletproof).
        Uri safeUri = coerceToUploadableUri(localUri);

        StorageMetadata md = (contentMime == null)
                ? new StorageMetadata.Builder().build()
                : new StorageMetadata.Builder().setContentType(contentMime).build();

        Log.d(TAG, "Uploading → " + dest.getPath() + "  mime=" + contentMime + "  uri=" + safeUri);

        UploadTask task = dest.putFile(safeUri, md);
        return task
                .addOnProgressListener(s -> Log.d(TAG, "… " + dest.getPath() + " " +
                        (int) (100f * s.getBytesTransferred() / Math.max(1, s.getTotalByteCount())) + "%"))
                .continueWithTask(t -> {
                    if (!t.isSuccessful()) {
                        Exception ex = t.getException();
                        Log.e(TAG, "Upload failed for " + dest.getPath() + " from " + safeUri, ex);
                        throw (ex != null ? ex : new RuntimeException("upload failed"));
                    }
                    // We store the *path* in Firestore; viewer will resolve it to a download URL.
                    return Tasks.forResult(dest.getPath());
                });
    }

    /**
     * Ensure the given Uri is safe for Firebase Storage putFile().
     * - file:// → return as-is
     * - content:// → copy to app cache and return a file:// Uri
     * - anything else → best-effort return original
     */
    private Uri coerceToUploadableUri(Uri src) {
        try {
            final String scheme = src.getScheme();
            if ("file".equalsIgnoreCase(scheme)) return src;

            if ("content".equalsIgnoreCase(scheme)) {
                FileInfo info = fileNameAndMime(src, "upload");
                File outDir = new File(appContext.getCacheDir(), "egg_uploads");
                //noinspection ResultOfMethodCallIgnored
                outDir.mkdirs();

                // Ensure unique file
                String base = stripExt(info.fileNameWithExt);
                String ext = getExt(info.fileNameWithExt);
                File out = File.createTempFile(base + "_", "." + (ext != null ? ext : "bin"), outDir);

                try (InputStream in = resolver.openInputStream(src);
                     OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while (in != null && (r = in.read(buf)) != -1) os.write(buf, 0, r);
                }
                return Uri.fromFile(out);
            }
        } catch (Exception e) {
            Log.w(TAG, "coerceToUploadableUri: fallback to original for " + src, e);
        }
        return src;
    }

    private static String stripExt(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }

    @Nullable
    private static String getExt(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(d + 1) : null;
    }

    /** Guess a good file name + extension and MIME for a given Uri. */
    private static class FileInfo { final String fileNameWithExt; final String mime; FileInfo(String n, String m){fileNameWithExt=n; mime=m;} }

    private FileInfo fileNameAndMime(Uri uri, String fallbackBase) {
        String mime = resolver.getType(uri);
        String ext = null;

        if (mime != null) {
            ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        }

        if (ext == null) {
            // try from uri string
            String s = uri.toString();
            int dot = s.lastIndexOf('.');
            if (dot >= 0 && dot >= s.length() - 6) ext = s.substring(dot + 1);
        }

        String lcExt = ext == null ? "" : ext.toLowerCase();

        // If we don't have a useful MIME, infer a *specific* one accepted by rules (image/* or audio/*)
        if (mime == null || "application/octet-stream".equals(mime)) {
            switch (lcExt) {
                case "jpg":
                case "jpeg": mime = "image/jpeg"; break;
                case "png":  mime = "image/png";  break;
                case "webp": mime = "image/webp"; break;
                case "heic": mime = "image/heic"; break;
                case "heif": mime = "image/heif"; break;
                case "m4a":
                case "mp4":  mime = "audio/mp4";  break;
                case "aac":  mime = "audio/aac";  break;
                case "3gp":  mime = "audio/3gpp"; break;
                default:
                    // fall back to image if we're clearly uploading a photo
                    mime = fallbackBase.startsWith("photo_") ? "image/jpeg" : "application/octet-stream";
            }
        }

        if (lcExt.isEmpty()) {
            if ("image/jpeg".equals(mime)) ext = "jpg";
            else if ("image/png".equals(mime)) ext = "png";
            else if ("image/webp".equals(mime)) ext = "webp";
            else if ("image/heic".equals(mime)) ext = "heic";
            else if ("image/heif".equals(mime)) ext = "heif";
            else if ("audio/mp4".equals(mime)) ext = "m4a";
            else if ("audio/aac".equals(mime)) ext = "aac";
            else if ("audio/3gpp".equals(mime)) ext = "3gp";
            else ext = "bin";
        }

        // try to keep a readable base name if available
        String base = fallbackBase;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = resolver.query(uri, new String[]{"_display_name"}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String dn = c.getString(0);
                    if (dn != null && dn.contains(".")) dn = dn.substring(0, dn.lastIndexOf('.'));
                    if (dn != null && !dn.trim().isEmpty()) base = dn.trim();
                }
            } catch (Exception ignore) {}
        }

        // sanitize base
        base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new FileInfo(base + "." + ext, mime);
    }

    // ----- (fetchers kept the same) -----

    public Task<List<EggEntry>> fetchAllEggs() {
        return db.collection("eggs").get().onSuccessTask(snap -> {
            List<EggEntry> out = new ArrayList<>();
            snap.getDocuments().forEach(d -> {
                EggEntry e = d.toObject(EggEntry.class);
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
                if (distanceMeters(lat, lng, e.lat(), e.lng()) <= radiusMeters) filtered.add(e);
            }
            return Tasks.forResult(filtered);
        });
    }

    /** Turn a Storage *path* (e.g. "/eggs/.../photo_0.jpg") into a downloadable URL. */
    public Task<Uri> downloadUrlFromPath(String storagePath) {
        String clean = storagePath.startsWith("/") ? storagePath.substring(1) : storagePath;
        return storage.child(clean).getDownloadUrl();
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

    // ---- Anchoring helpers unchanged ----
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

    public Task<Void> updateQuiz(DocumentReference docRef, @Nullable List<EggEntry.QuizQuestion> quiz) {
        Map<String, Object> patch = new HashMap<>();
        patch.put("quiz", quiz);
        patch.put("updatedAt", FieldValue.serverTimestamp());
        return docRef.update(patch);
    }
}
