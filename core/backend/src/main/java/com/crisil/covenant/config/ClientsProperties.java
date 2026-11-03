package com.crisil.covenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "covenant.clients")
public class ClientsProperties {

    private Endpoint creditProposal = new Endpoint();
    private Endpoint attributes = new Endpoint();
    private Endpoint dataApi = new Endpoint();
    private Endpoint workflow = new Endpoint();
    private Endpoint ml = new Endpoint();
    private Endpoint notification = new Endpoint();
    private Endpoint fileUpload = new Endpoint();
    private Endpoint aiSuggestion = new Endpoint();
    /** R1: Document Processing Service (C1 recommendations). */
    private Endpoint dps = new Endpoint();

    public Endpoint getCreditProposal() {
        return creditProposal;
    }

    public void setCreditProposal(Endpoint creditProposal) {
        this.creditProposal = creditProposal;
    }

    public Endpoint getAttributes() {
        return attributes;
    }

    public void setAttributes(Endpoint attributes) {
        this.attributes = attributes;
    }

    public Endpoint getDataApi() {
        return dataApi;
    }

    public void setDataApi(Endpoint dataApi) {
        this.dataApi = dataApi;
    }

    public Endpoint getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Endpoint workflow) {
        this.workflow = workflow;
    }

    public Endpoint getMl() {
        return ml;
    }

    public void setMl(Endpoint ml) {
        this.ml = ml;
    }

    public Endpoint getNotification() {
        return notification;
    }

    public void setNotification(Endpoint notification) {
        this.notification = notification;
    }

    public Endpoint getFileUpload() {
        return fileUpload;
    }

    public void setFileUpload(Endpoint fileUpload) {
        this.fileUpload = fileUpload;
    }

    public Endpoint getAiSuggestion() {
        return aiSuggestion;
    }

    public void setAiSuggestion(Endpoint aiSuggestion) {
        this.aiSuggestion = aiSuggestion;
    }

    public Endpoint getDps() {
        return dps;
    }

    public void setDps(Endpoint dps) {
        this.dps = dps;
    }

    public static class Endpoint {
        private String baseUrl;
        /** Optional fixed bearer token for service-to-service calls (tests, short-lived setups). */
        private String serviceToken;
        /** OAuth 2.0 client credentials: Core fetches and refreshes its own token (production). */
        private ClientCredentials oauth = new ClientCredentials();

        public ClientCredentials getOauth() {
            return oauth;
        }

        public void setOauth(ClientCredentials oauth) {
            this.oauth = oauth;
        }

        public String getServiceToken() {
            return serviceToken;
        }

        public void setServiceToken(String serviceToken) {
            this.serviceToken = serviceToken;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /**
     * Client-credentials settings, e.g. Entra ID: token URI
     * {@code https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token} and scope
     * {@code api://<api-app>/.default}. Configured when the token URI is set.
     */
    public static class ClientCredentials {
        private String tokenUri;
        private String clientId;
        private String clientSecret;
        private String scope;

        public boolean isConfigured() {
            return tokenUri != null && !tokenUri.isBlank();
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }
}
