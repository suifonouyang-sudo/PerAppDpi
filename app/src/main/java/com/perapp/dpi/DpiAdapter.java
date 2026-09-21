package com.perapp.dpi;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 应用列表适配器：图标按需加载 + 缓存，已设置密度的行用蓝色徽标标出 */
public class DpiAdapter extends BaseAdapter {

    private final Context ctx;
    private final LayoutInflater inf;
    private final LruCache<String, Drawable> icons = new LruCache<>(120);
    private final List<AppList.Item> data = new ArrayList<>();

    public DpiAdapter(Context c) {
        this.ctx = c;
        this.inf = LayoutInflater.from(c);
    }

    public void setData(List<AppList.Item> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public AppList.Item getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = inf.inflate(R.layout.item_app, parent, false);
            Holder h = new Holder();
            h.icon = v.findViewById(R.id.ivIcon);
            h.name = v.findViewById(R.id.tvName);
            h.pkg = v.findViewById(R.id.tvPkg);
            h.dpi = v.findViewById(R.id.tvDpi);
            v.setTag(h);
        }
        Holder h = (Holder) v.getTag();
        AppList.Item it = data.get(position);

        h.name.setText(it.label);
        h.pkg.setText(it.pkg + (it.system ? " · 系统" : ""));

        Drawable d = icons.get(it.pkg);
        if (d == null) {
            try {
                d = ctx.getPackageManager().getApplicationIcon(it.pkg);
                icons.put(it.pkg, d);
            } catch (Throwable ignored) {
            }
        }
        h.icon.setImageDrawable(d);

        if (it.dpi > 0) {
            h.dpi.setText(it.dpi + " dpi");
            h.dpi.setTextColor(ctx.getColor(R.color.primary));
            h.dpi.setBackgroundResource(R.drawable.badge_bg);
            h.dpi.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            h.dpi.setText("跟随系统");
            h.dpi.setTextColor(ctx.getColor(R.color.text_dim));
            h.dpi.setBackgroundResource(0);
            h.dpi.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        return v;
    }

    private static class Holder {
        ImageView icon;
        TextView name;
        TextView pkg;
        TextView dpi;
    }
}
