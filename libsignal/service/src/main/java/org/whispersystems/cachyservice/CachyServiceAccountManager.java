package org.whispersystems.cachyservice;

import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Operations;

public class CachyServiceAccountManager extends SignalServiceAccountManager {
    public CachyServiceAccountManager(SignalServiceConfiguration configuration, java.util.UUID uuid, String e164, String password, String signalAgent, boolean automaticNetworkRetry) {
        super(configuration, uuid, e164, password, signalAgent, automaticNetworkRetry);
    }

    public CachyServiceAccountManager(SignalServiceConfiguration configuration, CredentialsProvider credentialsProvider, String signalAgent, GroupsV2Operations groupsV2Operations, boolean automaticNetworkRetry) {
        super(configuration, credentialsProvider, signalAgent, groupsV2Operations, automaticNetworkRetry);
    }

    public void callChild(){
        org.whispersystems.libsignal.logging.Log.d("CachyServiceAccountManager","issChild");
    }
}
