/* full file: com/google/ar/core/examples/java/helloar/ui/CenterStatusDialogFragment.java */
package com.google.ar.core.examples.java.helloar.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.ar.core.examples.java.helloar.R;

public class CenterStatusDialogFragment extends DialogFragment {

    public interface Listener { void onOk(); }

    /** Stable fragment tag for find/show. */
    public static final String TAG = "center_status";

    // Views
    @Nullable private TextView titleTv, msgTv, subTv;
    @Nullable private ProgressBar progress;
    @Nullable private Button okBtn;

    // External callback
    @Nullable private Listener listener;

    // Cached UI state (safe to call API before views exist)
    private String pendingTitle = "";
    private String pendingMessage = "";
    @Nullable private String pendingSub = null;
    private boolean pendingShowOk = false;
    private boolean pendingShowProgress = true;

    public CenterStatusDialogFragment() {
        setCancelable(false);
    }

    /** Factory for API compatibility. */
    public static @NonNull CenterStatusDialogFragment newInstance() {
        CenterStatusDialogFragment f = new CenterStatusDialogFragment();
        f.setCancelable(false);
        return f;
    }

    /** Idempotent helper to avoid multiple dialog windows at once. */
    public static @NonNull CenterStatusDialogFragment showOnce(
            @NonNull FragmentManager fm,
            @Nullable Listener listener
    ) {
        CenterStatusDialogFragment existing =
                (CenterStatusDialogFragment) fm.findFragmentByTag(TAG);

        if (existing != null && existing.getDialog() != null && existing.getDialog().isShowing()) {
            existing.setListener(listener);
            return existing;
        }

        CenterStatusDialogFragment f = newInstance();
        f.setListener(listener);
        f.show(fm, TAG);
        return f;
    }

    public void setListener(@Nullable Listener l) { listener = l; }

    @NonNull @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Dialog d = new Dialog(requireContext(), android.R.style.Theme_DeviceDefault_Light_Dialog);
        View v = LayoutInflater.from(d.getContext()).inflate(R.layout.dialog_center_status, null, false);

        titleTv  = v.findViewById(R.id.title);
        msgTv    = v.findViewById(R.id.message);
        subTv    = v.findViewById(R.id.sub);
        progress = v.findViewById(R.id.progress);
        okBtn    = v.findViewById(R.id.ok);

        if (okBtn != null) {
            okBtn.setOnClickListener(vw -> {
                if (listener != null) listener.onOk();
                dismissAllowingStateLoss();
            });
        }

        d.setContentView(v);
        d.setCancelable(false);
        setCancelable(false);

        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.5f);
        }

        applyPendingState();
        return d;
    }

    @Override public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d != null) {
            Window w = d.getWindow();
            if (w != null) {
                w.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
                w.setGravity(Gravity.CENTER);
            }
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        titleTv = null;
        msgTv = null;
        subTv = null;
        progress = null;
        okBtn = null;
    }

    /** Show a spinning state: title + message (+ optional subline), hides OK button. */
    public void showProgress(@NonNull String title, @NonNull String message, @Nullable String sub) {
        pendingTitle = title;
        pendingMessage = message;
        pendingSub = sub;
        pendingShowProgress = true;
        pendingShowOk = false;
        runOnUiThread(this::applyPendingState);
    }

    /** Show a message: title + message; show/hide OK button; hides spinner. */
    public void showMessage(@NonNull String title, @NonNull String message, boolean showOk) {
        pendingTitle = title;
        pendingMessage = message;
        pendingSub = null;
        pendingShowProgress = false;
        pendingShowOk = showOk;
        runOnUiThread(this::applyPendingState);
    }

    // --- Helpers ---

    private void applyPendingState() {
        if (titleTv != null) titleTv.setText(pendingTitle);
        if (msgTv != null) msgTv.setText(pendingMessage);

        if (subTv != null) {
            if (pendingSub != null && !pendingSub.isEmpty()) {
                subTv.setText(pendingSub);
                subTv.setVisibility(View.VISIBLE);
            } else {
                subTv.setVisibility(View.GONE);
            }
        }

        if (progress != null) {
            progress.setVisibility(pendingShowProgress ? View.VISIBLE : View.GONE);
        }

        if (okBtn != null) {
            okBtn.setVisibility(pendingShowOk ? View.VISIBLE : View.GONE);
        }
    }

    private void runOnUiThread(@NonNull Runnable r) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(r);
    }
}
