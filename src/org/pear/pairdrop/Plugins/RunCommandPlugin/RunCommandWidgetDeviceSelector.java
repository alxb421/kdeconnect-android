package org.pear.pairdrop.Plugins.RunCommandPlugin;

import android.content.Intent;
import android.os.Bundle;
import android.view.Window;

import androidx.appcompat.app.AppCompatActivity;

import org.pear.pairdrop.BackgroundService;
import org.pear.pairdrop.Device;
import org.pear.pairdrop.UserInterface.List.ListAdapter;
import org.pear.pairdrop.UserInterface.ThemeUtil;
import org.pear.pairdrop_tp.databinding.WidgetRemoteCommandPluginDialogBinding;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class RunCommandWidgetDeviceSelector extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        ThemeUtil.setUserPreferredTheme(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        final WidgetRemoteCommandPluginDialogBinding binding =
                WidgetRemoteCommandPluginDialogBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        BackgroundService.RunCommand(this, service -> runOnUiThread(() -> {
            final List<CommandEntry> deviceItems = service.getDevices().values().stream()
                    .filter(Device::isPaired).filter(Device::isReachable)
                    .map(device -> new CommandEntry(device.getName(), null, device.getDeviceId()))
                    .sorted(Comparator.comparing(CommandEntry::getName))
                    .collect(Collectors.toList());

            ListAdapter adapter = new ListAdapter(RunCommandWidgetDeviceSelector.this, deviceItems);

            binding.runCommandsDeviceList.setAdapter(adapter);
            binding.runCommandsDeviceList.setOnItemClickListener((adapterView, viewContent, i, l) -> {
                CommandEntry entry = deviceItems.get(i);
                RunCommandWidget.setCurrentDevice(entry.getKey());

                Intent updateWidget = new Intent(RunCommandWidgetDeviceSelector.this, RunCommandWidget.class);
                RunCommandWidgetDeviceSelector.this.sendBroadcast(updateWidget);

                finish();
            });
        }));
    }
}