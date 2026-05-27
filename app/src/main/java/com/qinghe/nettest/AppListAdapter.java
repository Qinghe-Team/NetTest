package com.qinghe.nettest;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {
    private final List<ApplicationInfo> apps;
    private final PackageManager pm;
    private final OnAppSelectedListener listener;

    public interface OnAppSelectedListener {
        void onAppSelected(ApplicationInfo app);
    }

    public AppListAdapter(List<ApplicationInfo> apps, PackageManager pm, OnAppSelectedListener listener) {
        this.apps = apps;
        this.pm = pm;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ApplicationInfo app = apps.get(position);
        holder.tvName.setText(app.loadLabel(pm));
        holder.tvPackage.setText(app.packageName);
        holder.ivIcon.setImageDrawable(app.loadIcon(pm));
        holder.itemView.setOnClickListener(v -> listener.onAppSelected(app));
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvName, tvPackage;

        ViewHolder(View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_app_icon);
            tvName = itemView.findViewById(R.id.tv_app_name);
            tvPackage = itemView.findViewById(R.id.tv_package_name);
        }
    }
}
