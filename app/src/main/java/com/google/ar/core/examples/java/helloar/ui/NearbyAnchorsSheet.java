package com.google.ar.core.examples.java.helloar.ui;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.ar.core.examples.java.helloar.R;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class NearbyAnchorsSheet extends BottomSheetDialogFragment {

    /** Item for each nearby anchor row. */
    public static class Item {
        public final String docId;              // Firestore doc id
        public final String model;              // "star" or "puzzle"
        @Nullable public final String title;    // optional (prefer to pass from fetch)
        public final float  distanceM;
        @Nullable public final String thumbUrl; // optional thumbnail URL (http/https or gs://)

        public Item(@NonNull String docId, @NonNull String model, float distanceM) {
            this(docId, model, null, distanceM, null);
        }
        public Item(@NonNull String docId, @NonNull String model,
                    @Nullable String title, float distanceM) {
            this(docId, model, title, distanceM, null);
        }
        public Item(@NonNull String docId, @NonNull String model,
                    @Nullable String title, float distanceM,
                    @Nullable String thumbUrl) {
            this.docId = docId;
            this.model = model;
            this.title = title;
            this.distanceM = distanceM;
            this.thumbUrl = thumbUrl;
        }
    }

    /** Listener to ask host activity/fragment to show details. */
    public interface Listener {
        void onRequestDetails(@NonNull String docId, @Nullable String fallbackThumbUrl, @Nullable String fallbackTitle);
    }

    private final List<Item> items = new ArrayList<>();
    @Nullable private Listener listener;

    // NEW: max distance gate (default = unlimited)
    private float maxDistanceM = Float.POSITIVE_INFINITY;

    // NEW: setter so caller can choose e.g. 100 m
    public NearbyAnchorsSheet setMaxDistanceM(float meters) {
        this.maxDistanceM = Math.max(0f, meters);
        return this;
    }

    // UPDATED: apply distance filter (and optional sort by nearest)
    public NearbyAnchorsSheet setItems(@Nullable List<Item> list) {
        items.clear();
        if (list != null) {
            for (Item it : list) {
                if (it != null && it.distanceM >= 0f && it.distanceM <= maxDistanceM) {
                    items.add(it);
                }
            }
            // Keep nearest first (optional but nice)
            Collections.sort(items, Comparator.comparingDouble(i -> i.distanceM));
        }
        return this;
    }

    public NearbyAnchorsSheet setListener(@Nullable Listener l) {
        this.listener = l;
        return this;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.sheet_nearby_anchors, container, false);

        TextView tvEmpty = v.findViewById(R.id.tvEmpty);
        RecyclerView rv  = v.findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(new Adapter());

        if (items.isEmpty()) {
            // Show radius in the empty message if the list is filtered
            if (Float.isInfinite(maxDistanceM)) {
                tvEmpty.setText("No anchors found.");
            } else {
                tvEmpty.setText("No anchors within " + (int) maxDistanceM + " m.");
            }
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
        return v;
    }

    private class Adapter extends RecyclerView.Adapter<VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_nearby_anchor, parent, false);
            return new VH(row);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(items.get(pos)); }
        @Override public int getItemCount() { return items.size(); }
        @Override public long getItemId(int position) { return items.get(position).docId.hashCode(); }
    }

    private class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final ImageButton btnInfo;
        final ImageView ivThumb;
        @Nullable Item item;

        VH(@NonNull View itemView) {
            super(itemView);
            title    = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            btnInfo  = itemView.findViewById(R.id.btnInfo);
            ivThumb  = itemView.findViewById(R.id.thumb);

            // Whole row opens details
            itemView.setOnClickListener(v -> {
                if (listener != null && item != null) listener.onRequestDetails(item.docId, item.thumbUrl, item.title);
            });
            btnInfo.setOnClickListener(v -> {
                if (listener != null && item != null) listener.onRequestDetails(item.docId, item.thumbUrl, item.title);
            });
        }

        void bind(@NonNull Item it) {
            this.item = it;

            // Title: saved title or fallback label
            String fallback = "puzzle".equalsIgnoreCase(it.model) ? "Puzzle" : "Star";
            String showTitle = !TextUtils.isEmpty(it.title) ? it.title : fallback;
            title.setText(showTitle);

            // Subtitle: icon + pretty distance (NO docId shown)
            String dist = it.distanceM >= 1000f
                    ? String.format(Locale.US, "≈ %.1f km", it.distanceM / 1000f)
                    : String.format(Locale.US, "≈ %.0f m", it.distanceM);
            String badge = "puzzle".equalsIgnoreCase(it.model) ? "🧩" : "⭐";
            subtitle.setText(badge + "  •  " + dist);

            // Thumbnail: http/https direct OR Firebase Storage gs://
            if (!TextUtils.isEmpty(it.thumbUrl)) {
                ivThumb.setVisibility(View.VISIBLE);
                ivThumb.setImageDrawable(null); // clear any old image while loading
                ivThumb.setTag(it.docId);       // tag to avoid mismatched async loads

                if (it.thumbUrl.startsWith("gs://")) {
                    // Resolve gs:// → https download URL, then load with Glide
                    try {
                        StorageReference ref = FirebaseStorage.getInstance().getReferenceFromUrl(it.thumbUrl);
                        ref.getDownloadUrl()
                                .addOnSuccessListener(uri -> {
                                    // Make sure this ViewHolder is still bound to the same doc
                                    if (!it.docId.equals(ivThumb.getTag())) return;
                                    Glide.with(NearbyAnchorsSheet.this)
                                            .load(uri)
                                            .centerCrop()
                                            .placeholder(android.R.drawable.ic_menu_report_image)
                                            .error(android.R.drawable.stat_notify_error)
                                            .into(ivThumb);
                                })
                                .addOnFailureListener(e -> {
                                    if (!it.docId.equals(ivThumb.getTag())) return;
                                    ivThumb.setVisibility(View.GONE);
                                });
                    } catch (IllegalArgumentException ignore) {
                        // Bad gs:// URL—hide thumbnail
                        ivThumb.setVisibility(View.GONE);
                    }
                } else {
                    // http(s), content://, file:// etc.
                    Uri uri = Uri.parse(it.thumbUrl);
                    Glide.with(NearbyAnchorsSheet.this)
                            .load(uri)
                            .centerCrop()
                            .placeholder(android.R.drawable.ic_menu_report_image)
                            .error(android.R.drawable.stat_notify_error)
                            .into(ivThumb);
                }
            } else {
                ivThumb.setVisibility(View.GONE);
                ivThumb.setImageDrawable(null);
                ivThumb.setTag(null);
            }
        }
    }
}