package com.google.ar.core.examples.java.helloar.data;

import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.GeoPoint;
import java.util.List;

/** Firestore model for an egg entry. */
public class EggEntry {

    public String id;
    public String userId;
    public String title;
    public String description;

    // Geospatial
    public @Nullable com.google.firebase.firestore.GeoPoint geo;
    public @Nullable Double alt;
    public @Nullable Double heading;
    public @Nullable Double horizAcc;
    public @Nullable Double vertAcc;// meters (vertical accuracy)

    // Pose snapshot (4x4 matrix flattened, length 16)
    public @Nullable List<Float> poseMatrix;

//    @Nullable
//    public String quizQuestion;
//    @Nullable
//    public String quizAnswer;

    // Media stored in Firebase Storage (we keep STORAGE PATHS, not download URLs)
    public List<String> photoPaths; // e.g. /eggs/{docId}/photos/photo_0.jpg
    public String audioPath;        // e.g. /eggs/{docId}/audio/voice.m4a
    public Boolean hasMedia;

    // Timestamps (server generated)
    public Timestamp createdAt;
    public Timestamp updatedAt;
    public java.util.List<String> collectedBy;

    public String speechTranscript;


    public EggEntry() {} // Firestore needs a public no-arg constructor
    public double lat() { return geo != null ? geo.getLatitude() : 0; }
    public double lng() { return geo != null ? geo.getLongitude() : 0; }
}
