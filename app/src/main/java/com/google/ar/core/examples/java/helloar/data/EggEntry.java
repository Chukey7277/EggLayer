package com.google.ar.core.examples.java.helloar.data;

import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.GeoPoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Firestore model for an egg entry (authoring). */
public class EggEntry {

    // ---------- Identity ----------
    public String id;
    public String userId;

    // ---------- Content ----------
    public String title;
    public String description;

    public @Nullable String speechTranscript;

    // ---------- Geospatial ----------
    public @Nullable GeoPoint geo;
    public @Nullable Double alt;
    public @Nullable Double heading;
    public @Nullable Double horizAcc;  // meters
    public @Nullable Double vertAcc;   // meters

    // Pose snapshot (4x4 matrix flattened, length 16)
    public @Nullable List<Float> poseMatrix;

    // ---------- Persistence / anchoring ----------
    /** One of: "CLOUD", "GEO", "LOCAL". */
    public @Nullable String anchorType;
    /** Cloud Anchor ID (present iff anchorType == "CLOUD"). */
    public @Nullable String cloudId;
    /** Days to live used when hosting. */
    public @Nullable Long cloudTtlDays;        // keep as Long for Firestore consistency
    public @Nullable Timestamp cloudHostedAt;
    public @Nullable String cloudStatus;       // HOSTING, SUCCESS, ERROR, NO_HOSTABLE_ANCHOR
    public @Nullable String cloudError;

    // ---------- Media ----------
    /** Storage paths (e.g., "eggs/{docId}/photos/photo_0.jpg") or absolute URLs. */
    public @Nullable List<String> photoPaths;
    public @Nullable String audioPath;         // Storage path or URL
    public @Nullable Boolean hasMedia;

    // Optional direct URLs (cache / convenience)
    public @Nullable String cardImageUrl;      // “hero” image URL or path
    public @Nullable String audioUrl;          // direct streamable URL

    // ---------- Quiz ----------
    public @Nullable List<QuizQuestion> quiz;

    // ---------- Timestamps ----------
    public @Nullable Timestamp createdAt;
    public @Nullable Timestamp updatedAt;

    // ---------- Collection / engagement ----------
    public @Nullable List<String> collectedBy;

    // ---------- Placement metadata ----------
    public @Nullable String refImage;
    public @Nullable String placementType;     // "DepthPoint", "Point", "Plane", …
    public @Nullable Float  distanceFromCamera;

    public EggEntry() {}

    // ---------- Convenience ----------
    public double lat() { return geo != null ? geo.getLatitude() : 0; }
    public double lng() { return geo != null ? geo.getLongitude() : 0; }

    public boolean hasQuiz() {
        if (quiz == null || quiz.isEmpty()) return false;
        for (QuizQuestion q : quiz) if (q != null && q.isPlausible()) return true;
        return false;
    }

    public boolean isCloudAnchored() {
        return "CLOUD".equalsIgnoreCase(anchorType) && cloudId != null && !cloudId.trim().isEmpty();
    }
    public boolean isGeospatial() {
        return "GEO".equalsIgnoreCase(anchorType) && geo != null;
    }

    /** Stable accessor used by viewer-side code. */
    @Nullable
    public String bestCloudId() {
        return (cloudId != null && !cloudId.trim().isEmpty()) ? cloudId.trim() : null;
    }

    /** First image to show: cardImageUrl if set; else first photo path (strips leading '/'). */
    @Nullable
    public String firstImageOrUrl() {
        if (cardImageUrl != null && !cardImageUrl.trim().isEmpty()) return cardImageUrl.trim();
        if (photoPaths != null && !photoPaths.isEmpty()) {
            String p = photoPaths.get(0);
            if (p != null) {
                p = p.trim();
                if (!p.startsWith("http") && !p.startsWith("gs://") && p.startsWith("/")) {
                    p = p.substring(1); // avoid StorageReference("/...") issue
                }
                if (!p.isEmpty()) return p;
            }
        }
        return null;
    }

    // ---------- (Optional) constants ----------
    public static final class AnchorTypes {
        public static final String CLOUD = "CLOUD";
        public static final String GEO   = "GEO";
        public static final String LOCAL = "LOCAL";
        private AnchorTypes() {}
    }

    /**
     * Parse quiz payload from Firestore:
     *  • List<Map<String,Object>> or
     *  • JSON string '[{question,options[],correctIndex}, …]'
     *  Also accepts {q, answer}.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static List<QuizQuestion> parseQuizFromFirestore(@Nullable Object raw) {
        if (raw == null) return null;

        if (raw instanceof List<?>) {
            List<?> arr = (List<?>) raw;
            List<QuizQuestion> out = new ArrayList<>();
            for (Object o : arr) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;

                QuizQuestion q = new QuizQuestion();

                Object qText = m.get("question");
                if (!(qText instanceof String)) qText = m.get("q");

                Object opts  = m.get("options");
                Object idx   = m.get("correctIndex");
                if (!(idx instanceof Number)) idx = m.get("answer");

                q.question = (qText instanceof String) ? (String) qText : null;
                q.q = q.question; // mirror

                if (opts instanceof List<?>) {
                    List<?> rawOpts = (List<?>) opts;
                    q.options = new ArrayList<>();
                    for (Object ro : rawOpts) if (ro instanceof String) q.options.add((String) ro);
                }

                if (idx instanceof Number) {
                    int i = ((Number) idx).intValue();
                    q.correctIndex = i;
                    q.answer = i; // mirror
                }

                if (q.isPlausible()) out.add(q);
            }
            return out.isEmpty() ? null : out;
        }

        if (raw instanceof String) {
            String s = (String) raw;
            try {
                JSONArray arr = new JSONArray(s);
                List<QuizQuestion> out = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;

                    QuizQuestion q = new QuizQuestion();

                    String qText = obj.optString("question", null);
                    if (qText == null || qText.isEmpty()) qText = obj.optString("q", null);
                    q.question = qText;
                    q.q = qText;

                    JSONArray ja = obj.optJSONArray("options");
                    if (ja != null) {
                        q.options = new ArrayList<>();
                        for (int j = 0; j < ja.length(); j++) {
                            String opt = ja.optString(j, null);
                            if (opt != null) q.options.add(opt);
                        }
                    }

                    int idx = obj.has("correctIndex") ? obj.optInt("correctIndex", 0)
                            : obj.optInt("answer", 0);
                    q.correctIndex = idx;
                    q.answer = idx;

                    if (q.isPlausible()) out.add(q);
                }
                return out.isEmpty() ? null : out;
            } catch (JSONException ignored) { /* fall through */ }
        }

        return null;
    }

    /** Single multiple-choice question. Supports both authoring and viewer field names. */
    public static class QuizQuestion {
        public String question;
        public List<String> options;
        public Integer correctIndex;

        // Aliases used by the viewer
        public String  q;
        public Integer answer;

        public QuizQuestion() {}

        public boolean isPlausible() {
            Integer idx = (correctIndex != null) ? correctIndex : answer;
            List<String> opts = options;
            String text = (question != null) ? question : q;
            return text != null
                    && opts != null
                    && opts.size() >= 2
                    && idx != null
                    && idx >= 0
                    && idx < opts.size();
        }
    }
}
