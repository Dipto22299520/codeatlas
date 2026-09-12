package com.codeatlas.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeatlas")
public class CodeAtlasProperties {

    /** demo or enterprise (README section 1). */
    private String profile = "demo";
    private String platformOwner = "";
    private String refreshCadence = "on-demand";
    private List<String> sourceRoots = new ArrayList<>();
    private Model model = new Model();
    private Agent agent = new Agent();

    public boolean isEnterprise() {
        return "enterprise".equalsIgnoreCase(profile);
    }

    public static class Model {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private List<String> localHosts = new ArrayList<>();

        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank();
        }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public List<String> getLocalHosts() { return localHosts; }
        public void setLocalHosts(List<String> localHosts) { this.localHosts = localHosts; }
    }

    public static class Agent {
        private int maxToolCalls = 8;
        private int maxModelCalls = 2;
        private long deadlineMs = 15000;

        public int getMaxToolCalls() { return maxToolCalls; }
        public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
        public int getMaxModelCalls() { return maxModelCalls; }
        public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
        public long getDeadlineMs() { return deadlineMs; }
        public void setDeadlineMs(long deadlineMs) { this.deadlineMs = deadlineMs; }
    }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public String getPlatformOwner() { return platformOwner; }
    public void setPlatformOwner(String platformOwner) { this.platformOwner = platformOwner; }
    public String getRefreshCadence() { return refreshCadence; }
    public void setRefreshCadence(String refreshCadence) { this.refreshCadence = refreshCadence; }
    public List<String> getSourceRoots() { return sourceRoots; }
    public void setSourceRoots(List<String> sourceRoots) { this.sourceRoots = sourceRoots; }
    public Model getModel() { return model; }
    public void setModel(Model model) { this.model = model; }
    public Agent getAgent() { return agent; }
    public void setAgent(Agent agent) { this.agent = agent; }
}
