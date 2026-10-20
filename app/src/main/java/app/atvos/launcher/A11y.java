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
 * TalkBack for the home screen. Everything is drawn on one View, so to accessibility services it
 * would be a single blank node. This gives them a tree of virtual items instead (app tiles, the
 * clock pill, Control Center tiles, menu options, the Play button) and tells them which one has
 * focus as the remote moves it, so TalkBack reads each item as it's reached, as on any TV app.
 * <p>
 * Costs nothing while no accessibility service is on: {@link #sync} returns at once.
 */
final class A11y extends AccessibilityNodeProvider {

    /**
     * One focusable thing on screen. Bounds are in 1920-wide design units. {@code clickable}: select
     * does something (a row that only shows words, or a fact, doesn't).
     */
    record Item(int id, String label, String state, String hint, RectF bounds, boolean focused, boolean clickable) {
        Item(int id, String label, String state, String hint, RectF bounds, boolean focused) {
            this(id, label, state, hint, bounds, focused, true);
        }
    }

    /** What the home screen tells this class. */
    interface Source {
        /** The items on screen now, in reading order; exactly one is focused when anything is. */
        void items(List<Item> out);
        /** Moves the remote's focus to the item (TalkBack asking); false if it can't go there. */
        boolean focus(int id);
        /** Focuses the item and selects it, as the remote's select would. */
        boolean click(int id);
        /** Pixels per design unit. */
        float scale();
    }

    private final View host;
    private final Source source;
    private final AccessibilityManager manager;
    private final int[] location = new int[2];

    // What was last announced, to tell TalkBack only about changes.
    private int focusedId = View.NO_ID, a11yFocusedId = View.NO_ID;
    private String focusedText = "", layout = "";
    // Where the focused item was when TalkBack was told of it, and whether to look again once the
    // screen has drawn: what just opened is only laid out when first drawn.
    private final RectF focusedBounds = new RectF();
    private boolean recheck;

    // The items as last listed, kept until the screen draws again or changes (sync()). TalkBack
    // asks for the nodes one by one, and listing every item again for each was O(n²).
    private final List<Item> snapshot = new ArrayList<>();
    private boolean fresh;

    A11y(View host, Source source) {
        this.host = host;
        this.source = source;
        this.manager = host.getContext().getSystemService(AccessibilityManager.class);
    }

    /**
     * Tells accessibility services what changed since last time: a different set of items (the
     * Control Center opened, say), focus moving to another item, or the focused item's text or
     * state changing (Do Not Disturb turned on). Call after anything that may have done so.
     */
    void sync() {
        if (manager == null || !manager.isEnabled()) return;
        fresh = false;
        List<Item> items = items();
        StringBuilder ids = new StringBuilder();
        Item focused = null;
        boolean a11yFocusShown = false;
        for (Item it : items) {
            // Labels too: another page of Settings can have the same ids (rows 0 to 3) and still be new.
            ids.append(it.id).append(':').append(it.label).append(',');
            if (it.focused) focused = it;
            if (it.id == a11yFocusedId) a11yFocusShown = true;
        }
        // TalkBack's item went (a menu opened over it, say): don't let a later item with its id
        // claim TalkBack's focus.
        if (!a11yFocusShown) a11yFocusedId = View.NO_ID;
        if (!ids.toString().equals(layout)) {
            layout = ids.toString();
            focusedId = View.NO_ID; // a new screen: say where focus is on it, even at the same id
            send(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null,
                    AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        }
        if (focused == null) {
            focusedId = View.NO_ID;
            return;
        }
        if (focused.bounds.isEmpty()) { // not drawn yet: tell TalkBack once it has somewhere to point
            recheck = true;
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
            fresh = false; // a new look at the whole screen
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
                && screen.bottom > 0 && screen.top < root.getHeight()); // off the row's ends too
        n.setEnabled(true);
        n.setFocusable(true);
        n.setFocused(it.focused);
        n.setClickable(it.clickable);
        n.setAccessibilityFocused(id == a11yFocusedId);
        if (it.clickable) n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
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
            default:
                return false;
        }
    }

    /** The screen drew: things may have moved, so list the items afresh when next asked. */
    void invalidate() {
        fresh = false;
    }

    /**
     * The screen has drawn after a change: what opened is laid out now, so a focus held back for
     * want of bounds is told, and if the focused item turned out somewhere else than TalkBack was
     * told, it's told again. Once per change, not per frame.
     */
    void drawn() {
        if (!recheck || manager == null || !manager.isEnabled()) return;
        recheck = false;
        host.post(() -> {
            fresh = false;
            int was = focusedId;
            sync();
            if (focusedId != was || focusedId == View.NO_ID) return; // told just now, or nothing focused
            Item it = find(focusedId);
            // Somewhere else, not just grown a little with focus (a menu now beside its tile, say)
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

    /** An event from one virtual item (or the host, for {@link View#NO_ID}). */
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
