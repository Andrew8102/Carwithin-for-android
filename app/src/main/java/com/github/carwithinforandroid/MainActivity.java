package com.github.carwithinforandroid;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.carwithinforandroid.core.CastEngine;

/** 骨架界面：仅用于确认 App 可安装、可启动，并能看到当前引擎状态。 */
public class MainActivity extends Activity {

    private final CastEngine engine = new CastEngine();
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(16f);
        root.addView(statusView);

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setOnClickListener(v -> render());
        root.addView(refresh);

        setContentView(root);
        render();
    }

    private void render() {
        statusView.setText("状态：" + engine.status()
                + "\n\n项目骨架，尚未接入 CarLife / ICCOA 与极核投屏。\n"
                + "详见 README.md 与 docs/。");
    }
}
