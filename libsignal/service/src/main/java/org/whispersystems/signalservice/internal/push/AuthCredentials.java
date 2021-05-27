package org.whispersystems.signalservice.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

import okhttp3.Credentials;

public class AuthCredentials {

  public String getUsername() {
    return username;
  }

  @JsonProperty
  private String username;

  @JsonProperty
  private String password;

  public String asBasic() {
    return Credentials.basic(username, password);
  }
}
