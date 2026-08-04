package ru.abs7.videooffer.bitrix;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.bitrix")
public record BitrixProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        Boolean reuseTalkProxy,
        ProxySettings proxy) {

    public int connectTimeoutSecondsOrDefault() {
        return positiveOrDefault(connectTimeoutSeconds, 15);
    }

    public int readTimeoutSecondsOrDefault() {
        return positiveOrDefault(readTimeoutSeconds, 45);
    }

    /**
     * By default Bitrix REST/OAuth traffic reuses the already configured
     * Kontur.Talk proxy when an explicit Bitrix proxy is not configured.
     */
    public boolean reuseTalkProxyOrDefault() {
        return reuseTalkProxy == null || reuseTalkProxy;
    }

    public ProxySettings proxyOrDefault() {
        return proxy == null
                ? new ProxySettings(false, null, null, null, null)
                : proxy;
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    public record ProxySettings(
            Boolean enabled,
            String host,
            Integer port,
            String username,
            String password) {

        public boolean enabledOrDefault() {
            return Boolean.TRUE.equals(enabled);
        }

        public boolean hostConfigured() {
            return host != null && !host.isBlank();
        }

        public boolean portConfigured() {
            return port != null && port > 0 && port <= 65_535;
        }

        public boolean usernameConfigured() {
            return username != null && !username.isBlank();
        }

        public boolean passwordConfigured() {
            return password != null && !password.isBlank();
        }

        public boolean authenticationConfigured() {
            return usernameConfigured() && passwordConfigured();
        }
    }
}
