package org.pear.pairdrop.Plugins.ConnectivityReportPlugin;

public class SubscriptionState {
    final int subId;
    int signalStrength = 0;
    String networkType = "Unknown";

    public SubscriptionState(int subId) {
        this.subId = subId;
    }
}