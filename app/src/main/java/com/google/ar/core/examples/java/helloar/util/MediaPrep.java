package com.google.ar.core.examples.java.helloar.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Downscales + fixes orientation; returns file:// Uri to a temp JPEG. */
public final class MediaPrep {
    private MediaPrep() {}

    public static Uri prepareImageForUpload(Context ctx, Uri src, int maxDimPx, int jpegQuality) throws IOException {
        final ContentResolver resolver = ctx.getContentResolver();

        // 1) Read EXIF orientation (best-effort; may be undefined)
        int orientation = ExifInterface.ORIENTATION_UNDEFINED;
        try (InputStream exifIn = resolver.openInputStream(src)) {
            if (exifIn != null) {
                ExifInterface exif = new ExifInterface(exifIn);
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            }
        }

        // 2) Decode bounds to choose inSampleSize
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = resolver.openInputStream(src)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }

        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = calcInSampleSize(largest, maxDimPx);

        // 3) Decode bitmap with sampling
        Bitmap bmp;
        try (InputStream is = resolver.openInputStream(src)) {
            bmp = BitmapFactory.decodeStream(is, null, opts);
        }
        if (bmp == null) throw new IOException("Bitmap decode failed for: " + src);

        // 4) Correct rotation
        int rotateDeg = exifToDegrees(orientation);
        if (rotateDeg != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotateDeg);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }

        // 5) Scale to maxDimPx while preserving aspect (if needed)
        int w = bmp.getWidth(), h = bmp.getHeight();
        int maxDim = Math.max(1, maxDimPx);
        float scale = Math.min(1f, (float) maxDim / Math.max(w, h));
        if (scale < 0.999f) {
            int nw = Math.max(1, Math.round(w * scale));
            int nh = Math.max(1, Math.round(h * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }

        // 6) Write to cache as JPEG
        File outDir = new File(ctx.getCacheDir(), "egg_prepped");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File out = File.createTempFile("img_", ".jpg", outDir);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, clampQuality(jpegQuality), fos);
        } finally {
            bmp.recycle();
        }
        return Uri.fromFile(out);
    }

    private static int clampQuality(int q) { return Math.max(60, Math.min(100, q)); }

    /** Choose a power-of-two sample so the decoded largest dim is at most ~2× target (for speed+quality). */
    private static int calcInSampleSize(int largestDim, int targetMax) {
        if (largestDim <= 0 || targetMax <= 0) return 1;
        int ss = 1;
        while ((largestDim / ss) > (targetMax * 2)) ss *= 2;
        return Math.max(1, ss);
    }

    private static int exifToDegrees(int exif) {
        switch (exif) {
            case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }
}