// Generated by view binder compiler. Do not edit!
package org.pear.pairdrop_tp.databinding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;
import java.lang.NullPointerException;
import java.lang.Override;
import org.pear.pairdrop_tp.R;

public final class PairingExplanationTextNoWifiBinding implements ViewBinding {
  @NonNull
  private final TextView rootView;

  private PairingExplanationTextNoWifiBinding(@NonNull TextView rootView) {
    this.rootView = rootView;
  }

  @Override
  @NonNull
  public TextView getRoot() {
    return rootView;
  }

  @NonNull
  public static PairingExplanationTextNoWifiBinding inflate(@NonNull LayoutInflater inflater) {
    return inflate(inflater, null, false);
  }

  @NonNull
  public static PairingExplanationTextNoWifiBinding inflate(@NonNull LayoutInflater inflater,
      @Nullable ViewGroup parent, boolean attachToParent) {
    View root = inflater.inflate(R.layout.pairing_explanation_text_no_wifi, parent, false);
    if (attachToParent) {
      parent.addView(root);
    }
    return bind(root);
  }

  @NonNull
  public static PairingExplanationTextNoWifiBinding bind(@NonNull View rootView) {
    if (rootView == null) {
      throw new NullPointerException("rootView");
    }

    return new PairingExplanationTextNoWifiBinding((TextView) rootView);
  }
}
