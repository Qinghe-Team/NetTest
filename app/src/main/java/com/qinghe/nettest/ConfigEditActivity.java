package com.qinghe.nettest;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;

public class ConfigEditActivity extends AppCompatActivity {
    private EditText etName, etUpDelay, etUpJitter, etUpBandwidth, etUpPacketLoss;
    private EditText etUpContinuousLossPass, etUpContinuousLossDrop;
    private EditText etDownDelay, etDownJitter, etDownBandwidth, etDownPacketLoss;
    private EditText etDownContinuousLossPass, etDownContinuousLossDrop;
    private RadioGroup rgProtocol;
    private int configId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config_edit);

        etName = findViewById(R.id.et_name);
        etUpDelay = findViewById(R.id.et_up_delay);
        etUpJitter = findViewById(R.id.et_up_jitter);
        etUpBandwidth = findViewById(R.id.et_up_bandwidth);
        etUpPacketLoss = findViewById(R.id.et_up_packet_loss);
        etUpContinuousLossPass = findViewById(R.id.et_up_continuous_loss_pass);
        etUpContinuousLossDrop = findViewById(R.id.et_up_continuous_loss_drop);
        etDownDelay = findViewById(R.id.et_down_delay);
        etDownJitter = findViewById(R.id.et_down_jitter);
        etDownBandwidth = findViewById(R.id.et_down_bandwidth);
        etDownPacketLoss = findViewById(R.id.et_down_packet_loss);
        etDownContinuousLossPass = findViewById(R.id.et_down_continuous_loss_pass);
        etDownContinuousLossDrop = findViewById(R.id.et_down_continuous_loss_drop);
        rgProtocol = findViewById(R.id.rg_protocol);
        Button btnSave = findViewById(R.id.btn_save);

        configId = getIntent().getIntExtra("config_id", -1);
        if (configId != -1) {
            setTitle(R.string.title_config_edit);
            loadConfig();
        } else {
            setTitle(R.string.title_config_add);
        }

        btnSave.setOnClickListener(v -> saveConfig());
    }

    private void loadConfig() {
        new Thread(() -> {
            NetworkConfig config = AppDatabase.getInstance(this).networkConfigDao().getById(configId);
            if (config != null) {
                runOnUiThread(() -> {
                    etName.setText(config.getName());
                    etUpDelay.setText(String.valueOf(config.getUpDelay()));
                    etUpJitter.setText(String.valueOf(config.getUpJitter()));
                    etUpBandwidth.setText(String.valueOf(config.getUpBandwidth()));
                    etUpPacketLoss.setText(String.valueOf(config.getUpPacketLoss()));
                    etUpContinuousLossPass.setText(String.valueOf(config.getUpContinuousLossPassTime()));
                    etUpContinuousLossDrop.setText(String.valueOf(config.getUpContinuousLossDropTime()));
                    etDownDelay.setText(String.valueOf(config.getDownDelay()));
                    etDownJitter.setText(String.valueOf(config.getDownJitter()));
                    etDownBandwidth.setText(String.valueOf(config.getDownBandwidth()));
                    etDownPacketLoss.setText(String.valueOf(config.getDownPacketLoss()));
                    etDownContinuousLossPass.setText(String.valueOf(config.getDownContinuousLossPassTime()));
                    etDownContinuousLossDrop.setText(String.valueOf(config.getDownContinuousLossDropTime()));
                    int protocol = config.getProtocol();
                    if (protocol == 1) rgProtocol.check(R.id.rb_tcp);
                    else if (protocol == 2) rgProtocol.check(R.id.rb_udp);
                    else rgProtocol.check(R.id.rb_all);
                });
            }
        }).start();
    }

    private void saveConfig() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入配置名称", Toast.LENGTH_SHORT).show();
            return;
        }

        NetworkConfig config = new NetworkConfig();
        if (configId != -1) config.setId(configId);
        config.setName(name);
        config.setUpDelay(parseIntSafe(etUpDelay));
        config.setUpJitter(parseIntSafe(etUpJitter));
        config.setUpBandwidth(parseIntSafe(etUpBandwidth));
        config.setUpPacketLoss(parseIntSafe(etUpPacketLoss));
        config.setUpContinuousLossPassTime(parseIntSafe(etUpContinuousLossPass));
        config.setUpContinuousLossDropTime(parseIntSafe(etUpContinuousLossDrop));
        config.setDownDelay(parseIntSafe(etDownDelay));
        config.setDownJitter(parseIntSafe(etDownJitter));
        config.setDownBandwidth(parseIntSafe(etDownBandwidth));
        config.setDownPacketLoss(parseIntSafe(etDownPacketLoss));
        config.setDownContinuousLossPassTime(parseIntSafe(etDownContinuousLossPass));
        config.setDownContinuousLossDropTime(parseIntSafe(etDownContinuousLossDrop));

        int checkedId = rgProtocol.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_tcp) config.setProtocol(1);
        else if (checkedId == R.id.rb_udp) config.setProtocol(2);
        else config.setProtocol(3);

        new Thread(() -> {
            if (configId != -1) {
                AppDatabase.getInstance(this).networkConfigDao().update(config);
            } else {
                AppDatabase.getInstance(this).networkConfigDao().insert(config);
            }
            runOnUiThread(() -> {
                Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                finish();
            });
        }).start();
    }

    private int parseIntSafe(EditText et) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
