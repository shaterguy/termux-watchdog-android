package com.shaterguy.termuxwatchdog;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native presentation only. Monitoring, power and authorization keep their dev12 contracts. */
final class WatchdogUi {
    final Activity activity;
    final LinearLayout root;
    final LinearLayout[] pages = new LinearLayout[3];
    private final ScrollView[] scrolls = new ScrollView[3];
    private final Button[] navigation = new Button[3];
    final boolean wideContent;
    final int background, surface, raised, ink, secondary, accent, line;
    private final boolean dark;
    private int selected;

    WatchdogUi(Activity activity, int initialPage) {
        this.activity = activity;
        Configuration config = activity.getResources().getConfiguration();
        dark = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background = Color.parseColor(dark ? "#141617" : "#F5F6F6");
        surface = Color.parseColor(dark ? "#1C1F20" : "#FFFFFF");
        raised = Color.parseColor(dark ? "#282C2D" : "#E9EEEE");
        ink = Color.parseColor(dark ? "#EDF1F0" : "#172321");
        secondary = Color.parseColor(dark ? "#AFB9B6" : "#53635F");
        accent = Color.parseColor(dark ? "#8BD9C4" : "#096759");
        line = Color.parseColor(dark ? "#35403C" : "#DCE4E1");
        boolean rail = config.screenWidthDp >= 600 && config.fontScale < 1.5f;
        wideContent = config.screenWidthDp >= 840 && config.fontScale < 1.35f;

        root = column();
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                int flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : flags, flags);
            }
        }
        LinearLayout header = column();
        header.setPadding(dp(24), dp(20), dp(24), dp(16));
        TextView name = text("Termux Watchdog", 26, ink, true);
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        header.addView(name, wrap());
        header.addView(text("서버 감시와 자동복구  ·  " + BuildConfig.VERSION_NAME, 13, secondary, false), wrap());
        root.addView(header, wrap());

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(rail ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = new LinearLayout(activity);
        nav.setOrientation(rail ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        nav.setBackgroundColor(surface);
        FrameLayout content = new FrameLayout(activity);
        if (rail) {
            body.addView(nav, new LinearLayout.LayoutParams(dp(164), -1));
            body.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
        } else {
            body.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
            body.addView(nav, wrap());
        }
        for (int i = 0; i < 3; i++) {
            ScrollView scroll = new ScrollView(activity);
            scroll.setId(0x7100 + i);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);
            scroll.setSaveEnabled(true);
            scroll.setPadding(dp(rail ? 24 : 16), dp(8), dp(rail ? 24 : 16), dp(24));
            pages[i] = column();
            scroll.addView(pages[i], new ScrollView.LayoutParams(-1, -2));
            content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            scrolls[i] = scroll;
            final int page = i;
            Button button = new Button(activity);
            button.setAllCaps(false);
            button.setText(new String[]{"상태", "연결", "기록"}[i]);
            button.setTextSize(14);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinHeight(dp(56));
            button.setPadding(dp(12), dp(8), dp(12), dp(8));
            button.setGravity(Gravity.CENTER);
            button.setCompoundDrawablePadding(dp(4));
            button.setOnClickListener(view -> select(page));
            LinearLayout.LayoutParams np = rail ? wrap() : new LinearLayout.LayoutParams(0, -2, 1);
            if (rail) np.bottomMargin = dp(8);
            nav.addView(button, np);
            navigation[i] = button;
        }
        select(initialPage);
    }

    void select(int index) {
        selected = Math.max(0, Math.min(2, index));
        for (int i = 0; i < 3; i++) {
            boolean active = selected == i;
            scrolls[i].setVisibility(active ? View.VISIBLE : View.GONE);
            Button button = navigation[i];
            button.setSelected(active);
            button.setContentDescription(new String[]{"상태", "연결", "기록"}[i] + (active ? ", 선택됨" : ""));
            button.setTextColor(active ? accent : secondary);
            button.setBackground(ripple(active ? raised : surface, 10));
            button.setCompoundDrawablesWithIntrinsicBounds(null, new NavIcon(i, active ? accent : secondary), null, null);
        }
    }

    int selectedPage() { return selected; }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    TextView text(String label, float size, int color, boolean medium) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    LinearLayout card(LinearLayout parent, String title, String summary) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(shape(surface, 16, true));
        LinearLayout.LayoutParams params = wrap();
        params.bottomMargin = dp(16);
        parent.addView(card, params);
        TextView heading = text(title, 18, ink, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        card.addView(heading, wrap());
        if (summary != null && !summary.isEmpty()) {
            TextView sub = text(summary, 14, secondary, false);
            LinearLayout.LayoutParams sp = wrap(); sp.topMargin = dp(4);
            card.addView(sub, sp);
        }
        return card;
    }

    TextView value(LinearLayout parent, String label) {
        LinearLayout block = column();
        LinearLayout.LayoutParams bp = wrap(); bp.topMargin = dp(16);
        parent.addView(block, bp);
        block.addView(text(label, 13, secondary, false), wrap());
        TextView value = text("상태 확인 중", 17, ink, false);
        value.setTextIsSelectable(true);
        LinearLayout.LayoutParams vp = wrap(); vp.topMargin = dp(4);
        block.addView(value, vp);
        return value;
    }

    Button button(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinHeight(dp(52));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        int foreground = primary ? (dark ? Color.parseColor("#102A24") : Color.WHITE) : ink;
        button.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{secondary, foreground}));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(raised, 10, false));
        states.addState(new int[]{}, shape(primary ? accent : raised, 10, false));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x30FFFFFF : 0x19000000), states, shape(Color.WHITE, 10, false)));
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams bp = wrap(); bp.topMargin = dp(12);
        parent.addView(button, bp);
        return button;
    }

    void note(LinearLayout parent, String label) {
        TextView note = text(label, 14, secondary, false);
        LinearLayout.LayoutParams np = wrap(); np.topMargin = dp(12);
        parent.addView(note, np);
    }

    void status(TextView view, String label, boolean good, boolean attention) {
        view.setText(label);
        view.setTextColor(good ? accent : attention ? Color.parseColor(dark ? "#F1C383" : "#85500E") : ink);
    }

    private GradientDrawable shape(int color, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border) drawable.setStroke(dp(1), line);
        return drawable;
    }

    private Drawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x20FFFFFF : 0x14000000),
                shape(color, radius, false), shape(Color.WHITE, radius, false));
    }

    int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }

    /** Three consistent line icons, with labels retained for accessibility. */
    private final class NavIcon extends Drawable {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        NavIcon(int kind, int color) {
            this.kind = kind;
            paint.setColor(color); paint.setStrokeWidth(1.7f);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override public int getIntrinsicWidth() { return dp(20); }
        @Override public int getIntrinsicHeight() { return dp(20); }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int save = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            if (kind == 0) {
                Path p = new Path();
                p.moveTo(3,12); p.lineTo(7,12); p.lineTo(10,5);
                p.lineTo(14,19); p.lineTo(17,12); p.lineTo(21,12);
                canvas.drawPath(p,paint);
            } else if (kind == 1) {
                canvas.drawRoundRect(4,4,20,20,4,4,paint);
                canvas.drawLine(8,9,16,9,paint); canvas.drawLine(8,15,16,15,paint);
                canvas.drawCircle(10,9,2,paint); canvas.drawCircle(14,15,2,paint);
            } else {
                canvas.drawRoundRect(5,3,19,21,2,2,paint);
                canvas.drawLine(9,8,15,8,paint); canvas.drawLine(9,12,15,12,paint);
                canvas.drawLine(9,16,13,16,paint);
            }
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
