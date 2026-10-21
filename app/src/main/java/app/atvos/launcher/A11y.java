package app.atvos.launcher;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives accessibility services a tree of virtual items for the home screen view. The items are app tiles, the clock
 * pill, Control Center tiles, menu options and the Play button. It also tells the services which item has the focus of
 * the remote.
 *
 * {@link #sync} returns immediately when no accessibility service is on.
 */
final class A11y extends AccessibilityNodeProvider {

    /**
     * One focusable item. The bounds are in design units (1920 wide). {@code clickable}: Select does an action.
     * {@code menu}: a long press opens a menu of options (the long-press menu of an app).
     */
    record Item(int id, String label, String state, String hint, RectF bounds, boolean focused, boolean clickable,
                boolean menu) {
        Item(int id, String label, String state, String hint, RectF bounds, boolean focused) {
            this(id, label, state, hint, bounds, focused, true, false);
        }

        Item(int id, String label, String state, String hint, RectF bounds, boolean focused, boolean clickable) {
            this(id, label, state, hint, bounds, focused, clickable, false);
        }
    }

    /** The data that the home screen gives to this class. */
    interface Source {
        /**
         * Adds the items on the screen to {@code out}, in the order for screen readers. Only one item has the focus.
         */
        void items(List<Item> out);
        /** Moves the focus of the remote to the item. Returns false if the item cannot have the focus. */
        boolean focus(int id);
        /** Moves the focus to the item and selects it. */
        boolean click(int id);
        /** Moves the focus to the item and opens its menu, as a long press of Select does. */
        default boolean longClick(int id) {
            return false;
        }
        /** Returns the number of pixels in one design unit. */
        float scale();
    }

    private final View host;
    private final Source source;
    private final AccessibilityManager manager;
    private final int[] location = new int[2];
    private final AccessibilityNodeInfo.AccessibilityAction options; // the long click of an item with a menu

    // The items in the last events. Only changes cause new events.
    private int focusedId = View.NO_ID, a11yFocusedId = View.NO_ID;
    private String focusedText = "", layout = "";
    // The bounds of the focused item in the last event, and a flag to check the item again after the next draw.
    private final RectF focusedBounds = new RectF();
    private boolean recheck;

    // The items from the last call to Source.items, kept until the next draw or sync().
    private final List<Item> snapshot = new ArrayList<>();
    private boolean fresh;

    A11y(View host, Source source) {
        this.host = host;
        this.source = source;
        this.manager = host.getContext().getSystemService(AccessibilityManager.class);
        this.options = new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
                host.getContext().getString(R.string.a11y_options));
    }

    /**
     * Sends events for the changes since the last call. A change is a different set of items, the focus on a different
     * item, or new text or state on the focused item.
     */
    void sync() {
        if (manager == null || !manager.isEnabled()) return;
        fresh = false;
        List<Item> items = items();
        StringBuilder ids = new StringBuilder();
        Item focused = null;
        boolean a11yFocusShown = false;
        for (Item it : items) {
            // The labels are part of the key because two Settings pages can have the same ids.
            ids.append(it.id).append(':').append(it.label).append(',');
            if (it.focused) focused = it;
            if (it.id == a11yFocusedId) a11yFocusShown = true;
        }
        // Clears the accessibility focus when its item is not on the screen.
        if (!a11yFocusShown) a11yFocusedId = View.NO_ID;
        // The focused item has no bounds before the next draw. drawn() sends the events after that draw.
        if (focused != null && focused.bounds.isEmpty()) {
            recheck = true;
            return;
        }
        if (!ids.toString().equals(layout)) {
            layout = ids.toString();
            focusedId = View.NO_ID; // a new set of items: announce the focus again
            send(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null,
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        }
        if (focused == null) {
            focusedId = View.NO_ID;
            return;
        }
        String text = focused.label + '|' + focused.state + '|' + focused.hint;
        if (focused.id != focusedId) {
            focusedId = focused.id;
            focusedText = text;
            focusedBounds.set(focused.bounds);
            recheck = true;
            send(focused.id, AccessibilityEvent.TYPE_VIEW_FOCUSED, focused, 0);
        } else if (!text.equals(focusedText)) {
            focusedText = text;
            send(focused.id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, focused,
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT | AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION);
        }
    }

    @Override
    public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
        if (id == HOST_VIEW_ID) {
            AccessibilityNodeInfo info = new AccessibilityNodeInfo(host);
            host.onInitializeAccessibilityNodeInfo(info);
            info.setFocusable(false); // the virtual items take the focus, not the unlabelled view
            fresh = false; // lists the items again
            for (Item it : items()) info.addChild(host, it.id);
            return info;
        }
        Item it = find(id);
        if (it == null) return null;
        AccessibilityNodeInfo n = new AccessibilityNodeInfo(host, id);
        n.setPackageName(host.getContext().getPackageName());
        n.setClassName(it.clickable ? Button.class.getName() : TextView.class.getName());
        n.setParent(host);
        n.setText(it.label);
        n.setStateDescription(it.state);
        n.setHintText(it.hint);
        Rect screen = screenBounds(it.bounds);
        n.setBoundsInScreen(screen);
        View root = host.getRootView();
        n.setVisibleToUser(screen.width() > 0 && screen.height() > 0 && screen.right > 0 && screen.left < root.getWidth()
                && screen.bottom > 0 && screen.top < root.getHeight());
        n.setEnabled(true);
        n.setFocusable(true);
        n.setFocused(it.focused);
        n.setClickable(it.clickable);
        n.setAccessibilityFocused(id == a11yFocusedId);
        if (it.clickable) n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        if (it.menu) { // Switch Access and TalkBack offer it as an action: "Options"
            n.setLongClickable(true);
            n.addAction(options);
        }
        n.addAction(it.focused ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_FOCUS
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS);
        n.addAction(id == a11yFocusedId ? AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS);
        return n;
    }

    @Override
    public AccessibilityNodeInfo findFocus(int focus) {
        if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
            return a11yFocusedId != View.NO_ID ? createAccessibilityNodeInfo(a11yFocusedId) : null;
        }
        for (Item it : items()) if (it.focused) return createAccessibilityNodeInfo(it.id);
        return null;
    }

    @Override
    public boolean performAction(int id, int action, Bundle args) {
        if (id == HOST_VIEW_ID) return host.performAccessibilityAction(action, args);
        switch (action) {
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                if (a11yFocusedId == id) return false;
                if (a11yFocusedId != View.NO_ID) {
                    send(a11yFocusedId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, find(a11yFocusedId), 0);
                }
                a11yFocusedId = id;
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, find(id), 0);
                return true;
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                if (a11yFocusedId != id) return false;
                a11yFocusedId = View.NO_ID;
                send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, find(id), 0);
                return true;
            case AccessibilityNodeInfo.ACTION_FOCUS:
                return source.focus(id);
            case AccessibilityNodeInfo.ACTION_CLICK:
                return source.click(id);
            case AccessibilityNodeInfo.ACTION_LONG_CLICK:
                return source.longClick(id);
            default:
                return false;
        }
    }

    /** Lists the items again at the next request. Items can move during a draw. */
    void invalidate() {
        fresh = false;
    }

    /**
     * Sends the events that wait for bounds after a change. If the focused item moved, it sends the focus event again.
     * It runs once for each change.
     */
    void drawn() {
        if (!recheck || manager == null || !manager.isEnabled()) return;
        recheck = false;
        host.post(() -> {
            fresh = false;
            int was = focusedId;
            sync();
            if (focusedId != was || focusedId == View.NO_ID) return;
            Item it = find(focusedId);
            // Sends a new event only if the item moved, not if it only became larger with the focus.
            if (it == null || Math.abs(it.bounds.left - focusedBounds.left) + Math.abs(it.bounds.top - focusedBounds.top) < 24f) return;
            focusedBounds.set(it.bounds);
            send(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        });
    }

    private List<Item> items() {
        if (!fresh) {
            snapshot.clear();
            source.items(snapshot);
            fresh = true;
        }
        return snapshot;
    }

    private Item find(int id) {
        for (Item it : items()) if (it.id == id) return it;
        return null;
    }

    private Rect screenBounds(RectF design) {
        host.getLocationOnScreen(location);
        float k = source.scale();
        return new Rect(Math.round(location[0] + design.left * k), Math.round(location[1] + design.top * k),
                Math.round(location[0] + design.right * k), Math.round(location[1] + design.bottom * k));
    }

    /** Sends an event for one virtual item, or for the host view if {@code id} is {@link View#NO_ID}. */
    private void send(int id, int type, Item it, int changes) {
        ViewParent parent = host.getParent();
        if (parent == null) return;
        AccessibilityEvent e = new AccessibilityEvent(type);
        e.setPackageName(host.getContext().getPackageName());
        if (id == View.NO_ID) {
            e.setSource(host);
            e.setClassName(host.getClass().getName());
        } else {
            e.setSource(host, id);
            e.setClassName(it == null || it.clickable ? Button.class.getName() : TextView.class.getName());
        }
        if (it != null) {
            e.getText().add(it.label);
            if (it.state != null) e.getText().add(it.state);
            e.setContentDescription(null);
        }
        if (changes != 0) e.setContentChangeTypes(changes);
        parent.requestSendAccessibilityEvent(host, e);
    }
}
