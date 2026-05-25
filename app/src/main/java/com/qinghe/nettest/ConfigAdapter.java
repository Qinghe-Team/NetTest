package com.qinghe.nettest;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.qinghe.nettest.model.NetworkConfig;
import java.util.List;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {
    private final List<NetworkConfig> configs;
    private final OnConfigActionListener listener;
    private int selectedPosition = -1;

    public interface OnConfigActionListener {
        void onEdit(NetworkConfig config);
        void onDelete(NetworkConfig config);
        void onSelect(NetworkConfig config);
    }

    public ConfigAdapter(List<NetworkConfig> configs, OnConfigActionListener listener) {
        this.configs = configs;
        this.listener = listener;
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public NetworkConfig getSelectedConfig() {
        if (selectedPosition >= 0 && selectedPosition < configs.size()) {
            return configs.get(selectedPosition);
        }
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_config, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        NetworkConfig config = configs.get(position);
        holder.tvName.setText(config.getName());
        holder.btnEdit.setOnClickListener(v -> listener.onEdit(config));
        holder.btnDelete.setOnClickListener(v -> listener.onDelete(config));

        // Highlight selected item
        if (position == selectedPosition) {
            holder.itemView.setBackgroundColor(0x220000FF);
        } else {
            holder.itemView.setBackgroundColor(0x00000000);
        }

        holder.itemView.setOnClickListener(v -> {
            int oldSelected = selectedPosition;
            selectedPosition = holder.getAdapterPosition();
            if (oldSelected != -1) notifyItemChanged(oldSelected);
            notifyItemChanged(selectedPosition);
            listener.onSelect(config);
        });
    }

    @Override
    public int getItemCount() {
        return configs.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        ImageButton btnEdit, btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_config_name);
            btnEdit = itemView.findViewById(R.id.btn_edit);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
