package com.yicli.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
