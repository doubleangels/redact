package com.doubleangels.redact.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.doubleangels.redact.R;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

/**
 * Brief messages about what just happened ("Copied", "Saved", "No app can open this"), shown as a
 * Material snackbar above the floating buttons, or the bottom navigation when none show. With no
 * screen to show it on (the app is closing or in the background) it falls back to a toast, which
 * Android draws over whatever is on screen.
 */
public final class Notice {

    /** The last message shown, snackbar or toast, for tests to check. */
    @Nullable
    private static CharSequence lastShown;

    private Notice() {
    }

    @androidx.annotation.VisibleForTesting
    @Nullable
    public static String lastShownForTests() {
        return lastShown != null ? lastShown.toString() : null;
    }

    @androidx.annotation.VisibleForTesting
    public static void resetForTests() {
        lastShown = null;
    }

    public static void show(@Nullable View view, @NonNull CharSequence text, boolean longer) {
        if (view == null) {
            return;
        }
        lastShown = text;
        if (!view.isAttachedToWindow()) {
            Toast.makeText(view.getContext().getApplicationContext(), text,
                    longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }
        Snackbar bar = Snackbar.make(view, text, longer ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_SHORT);
        View anchor = anchorFor(view.getRootView());
        if (anchor != null) {
            bar.setAnchorView(anchor);
        }
        bar.show();
    }

    public static void show(@NonNull Context context, @StringRes int text) {
        show(context, context.getString(text), false);
    }

    public static void show(@NonNull Context context, @StringRes int text, boolean longer) {
        show(context, context.getString(text), longer);
    }

    /** For code that only has a context: the activity's screen when it has one, else a toast. */
    public static void show(@NonNull Context context, @NonNull CharSequence text, boolean longer) {
        Activity activity = activityOf(context);
        View content = activity != null && !activity.isFinishing()
                ? activity.findViewById(android.R.id.content) : null;
        if (content != null && content.isAttachedToWindow()) {
            show(content, text, longer);
        } else {
            lastShown = text;
            Toast.makeText(context.getApplicationContext(), text,
                    longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The view a snackbar sits above: the highest floating button showing, so it clears a stack of
     * them (Share above Clean, say), else the bottom navigation.
     */
    @Nullable
    static View anchorFor(@NonNull View root) {
        View best = highestFab(root, null);
        return best != null ? best : bottomBar(root);
    }

    /**
     * The bottom navigation bar, or null when the tabs are in a side rail (wide windows), which a
     * snackbar must not be anchored to: it would jump to the rail's top.
     */
    @Nullable
    static View bottomBar(@NonNull View root) {
        View nav = root.findViewById(R.id.bottomNavigation);
        return nav instanceof com.google.android.material.navigationrail.NavigationRailView ? null : nav;
    }

    @Nullable
    private static View highestFab(@NonNull View view, @Nullable View best) {
        if ((view instanceof FloatingActionButton || view instanceof ExtendedFloatingActionButton)
                && view.isShown()) {
            int[] at = new int[2];
            view.getLocationInWindow(at);
            if (best == null) {
                return view;
            }
            int[] bestAt = new int[2];
            best.getLocationInWindow(bestAt);
            return at[1] < bestAt[1] ? view : best;
        }
        if (view instanceof ViewGroup group && view.getVisibility() == View.VISIBLE) {
            for (int i = 0; i < group.getChildCount(); i++) {
                best = highestFab(group.getChildAt(i), best);
            }
        }
        return best;
    }

    @Nullable
    private static Activity activityOf(@NonNull Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
