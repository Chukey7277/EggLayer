package com.google.ar.core.examples.java.helloar.data;

import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.GeoPoint;

import java.util.List;

/** Firestore model for an egg entry (authoring). */
public class EggEntry {

    public String id;
    public String userId;
    public String title;
    public String description;

    // Geospatial
    public @Nullable GeoPoint geo;
    public @Nullable Double alt;
    public @Nullable Double heading;
    public @Nullable Double horizAcc;  // meters (horizontal accuracy)
    public @Nullable Double vertAcc;   // meters (vertical accuracy)

    // Pose snapshot (4x4 matrix flattened, length 16)
    public @Nullable List<Float> poseMatrix;

    // Media (Storage paths or absolute URLs)
    public @Nullable List<String> photoPaths;   // e.g., /eggs/{docId}/photos/photo_0.jpg
    public @Nullable String audioPath;          // e.g., /eggs/{docId}/audio/voice.m4a
    public @Nullable Boolean hasMedia;

    // Optional direct URLs (used by EggHunter convenience / caching)
    public @Nullable String cardImageUrl;       // optional “hero” image URL or path
    public @Nullable String audioUrl;           // optional direct streamable URL

    // Quiz generated from transcript (0..n)
    public @Nullable List<QuizQuestion> quiz;

    // Timestamps (server generated)
    public @Nullable Timestamp createdAt;
    public @Nullable Timestamp updatedAt;

    public @Nullable List<String> collectedBy;

    // Speech-to-text captured when authoring
    public @Nullable String speechTranscript;

    public EggEntry() {} // Firestore needs a public no-arg constructor

    public double lat() { return geo != null ? geo.getLatitude() : 0; }
    public double lng() { return geo != null ? geo.getLongitude() : 0; }
    public boolean hasQuiz() { return quiz != null && !quiz.isEmpty(); }

    /** Single multiple-choice question. */
    public static class QuizQuestion {
        public String q;                 // question text (e.g., “Paris is the capital of ____.”)
        public List<String> options;     // choices (size >= 2)
        public Integer answer;           // index into options

        public QuizQuestion() {}         // Firestore
    }
}
